package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.CrisisRaiders;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.CrisisArrival;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Claim;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.FateJourney;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.server.StoryLine;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class FateScenes {
    static final Identifier ACTION = Identifier.parse("meridian_story:fate");
    static final String PREFIX = "story.meridian_story.fate.";
    static final String COMPANION = "meridian_story.fate_visitor.";
    private static final Identifier COPPER = Identifier.parse("meridian_currency:copper_cash");
    private static final Identifier ESCORT_BANNER = Identifier.withDefaultNamespace("red_banner");
    private static final int PRICE = 8;
    private static final int RANGE = 6;
    private static final int APPROACH = 14;
    private static final int LIFETIME = 600;
    private static final long GRIEF = 7 * CrisisRules.DAY_TICKS;
    private static final double WALK_SPEED = 0.75;
    private static final int MIN_PACES = 1;
    private final StoryStore store;
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Map<UUID, UUID> opened = new HashMap<>();
    private StoryLine definition;

    record Offer(String nonce, long revision, long until, String actor, List<String> choices) { }

    FateScenes(StoryStore store) {
        this.store = store;
    }

    void definitions(StoryLine line) {
        definition = line;
        // 重载使旧按钮失效，已见过的演员在本次在线期间仍只开场一次。
        offers.clear();
    }

    void leave(ServerPlayer player) {
        offers.remove(player.getUUID());
        opened.remove(player.getUUID());
    }

    void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Villager escort && escort.level() instanceof ServerLevel level) {
                escort.entityTags().stream().filter(tag -> tag.startsWith(COMPANION)).findFirst()
                        .ifPresent(tag -> FateDeaths.get(level)
                                .died(tag.substring(COMPANION.length()), escort.getUUID()));
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0 || !(entity.level() instanceof ServerLevel level)) {
                return;
            }
            for (var owner : level.players()) {
                var view = store.read(owner);
                if (view.isPresent() && eligible(view.get()) && chapter(view.get()).equals("walking")
                        && view.get().crisis().getPending().getParty().equals(FateRoad.AMBUSH)) {
                    var fate = view.get().progress().getFate();
                    if (!fate.getBlood() && (entity == owner || entity.getStringUUID().equals(fate.getActor())
                            || view.get().crisis().getPending().getRaidersList().contains(entity.getStringUUID()))) {
                        update(owner, view.get(), data -> data.setBlood(true /* blood */));
                    }
                }
            }
        });
        UseEntityCallback.EVENT.register((actor, world, hand, target, hit) -> {
            if (actor instanceof ServerPlayer owner && hand == InteractionHand.MAIN_HAND
                    && owner.isShiftKeyDown() && interact(owner, target)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((actor, world, hand, target, hit) -> {
            if (actor instanceof ServerPlayer player && hand == InteractionHand.MAIN_HAND
                    && target instanceof Mob mob) {
                store.read(player).filter(this::eligible).filter(view -> personal(player, view, mob))
                        .ifPresent(view -> attack(player, view, mob));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((reader, level, hand) -> {
            if (reader instanceof ServerPlayer player && use(player, player.getItemInHand(hand))) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private boolean eligible(StoryStore.View view) {
        return definition != null && definition.eligible(view.facts().origin())
                && view.progress().containsLines(FateRoad.LINE)
                && FateRoad.state(view.progress()).getEnding().isEmpty();
    }

    private static String chapter(StoryStore.View view) {
        return FateRoad.state(view.progress()).getChapter();
    }

    private static boolean personal(ServerPlayer player, StoryStore.View view, Mob actor) {
        return actor.isAlive() && player.isAlive() && !player.isSpectator() && player.level() == actor.level()
                && player.level().dimension() == Level.OVERWORLD && player.distanceTo(actor) <= RANGE
                && actor.getStringUUID().equals(view.progress().getFate().getActor());
    }

    static Mob actor(ServerPlayer owner, FateJourney fate) {
        String id = fate.getActor();
        if (!id.isEmpty() && owner.level().getEntity(UUID.fromString(id)) instanceof Mob mob) {
            return mob;
        }
        return null;
    }

    private void attack(ServerPlayer player, StoryStore.View view, Mob mob) {
        if (chapter(view).equals("reading")) {
            if (advance(player, view, "strike", data -> data.clearActor())) {
                narrate(player, "strike.after");
                mob.removeTag(COMPANION + player.getStringUUID());
                mob.setNoAi(false /* noAi */);
            }
        } else if (chapter(view).equals("temple") && !view.crisis().hasPending()) {
            CrisisEncounters.challenge(player, FateRoad.MASTER, mob);
        }
    }

    void tick(ServerPlayer owner, StoryStore.View view) {
        if (!eligible(view) || owner.level().dimension() != Level.OVERWORLD
                || !owner.isAlive() || owner.isSpectator()) {
            return;
        }
        var fate = view.progress().getFate();
        switch (chapter(view)) {
            case "reading", "escort" -> opening(owner, view);
            case "walking" -> accompany(owner, view);
            case "paid_hand" -> {
                var pending = view.crisis().getPending();
                if (pending.getParty().equals(FateRoad.HIRED) && pending.getArrived()
                        && !fate.getHired().equals(pending.getLeader())) {
                    update(owner, view, data -> data
                            .setHired(pending.getLeader())
                            .setRequest(pending.getRoadRequest()));
                }
            }
            case "search" -> approachEvidence(owner, view, "slip");
            case "temple" -> temple(owner, view);
            case "victory" -> approachEvidence(owner, view, "notebook");
            case "directions", "unread", "decision" -> recover(owner, view);
            default -> { }
        }
    }

    private void opening(ServerPlayer owner, StoryStore.View view) {
        var fate = view.progress().getFate();
        var deaths = FateDeaths.get(owner.level());
        String dead = deaths.receipt(owner.getStringUUID());
        if (!dead.isEmpty() && dead.equals(fate.getActor())
                && update(owner, view, FateJourney.Builder::clearActor)) {
            deaths.acknowledge(owner.getStringUUID(), dead);
            return;
        }
        if (fate.getActor().isEmpty() && !view.crisis().hasPending()) {
            meet(owner, view);
            return;
        }
        var guest = actor(owner, fate);
        if (guest != null && !opened(owner, guest) && personal(owner, view, guest) && owner.hasLineOfSight(guest)) {
            opened.put(owner.getUUID(), guest.getUUID());
            String prompt = chapter(view).equals("reading") ? "reading" : "escort.scene";
            narrate(owner, prompt);
            if (prompt.equals("reading")) {
                // 算命人听见脚步，把烤到一半的手收进袖里。
                StoryPoses.sleeves(guest);
            }
            present(owner, view, guest.getStringUUID());
        }
    }

    private boolean opened(ServerPlayer owner, Mob guest) {
        return guest.getUUID().equals(opened.get(owner.getUUID()));
    }

    private void meet(ServerPlayer player, StoryStore.View view) {
        var gate = FateSites.gate(player);
        if (gate.isEmpty()) {
            return;
        }
        var level = player.level();
        boolean reading = chapter(view).equals("reading");
        var around = Optional.of(player.blockPosition().relative(player.getDirection(), RANGE));
        if (reading) {
            if (CrisisArrival.nightfall(level.getOverworldClockTime())) {
                return;
            }
            around = FateSites.fire(player);
        }
        var spot = around.flatMap(pos -> FateSites.feet(level, pos));
        if (spot.isEmpty()) {
            return;
        }
        String name = reading ? "seer" : "escort";
        var guest = HumanoidVisitors.arrive(level, spot.get(), UUID.randomUUID(),
                Component.translatable(PREFIX + name));
        if (guest.isEmpty()) {
            return;
        }
        var actor = guest.get();
        actor.addTag(COMPANION + player.getStringUUID());
        if (reading) {
            // 算命人坐在火边烤手。
            around.ifPresent(fire -> StoryPoses.seat(level, actor, fire));
        }
        if (!reading) {
            actor.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(BuiltInRegistries.ITEM.getValue(ESCORT_BANNER)));
        }
        if (!update(player, view, data -> data
                .setActor(actor.getStringUUID())
                .setGate(gate.get().asLong()))) {
            actor.discard();
        }
    }

    private void accompany(ServerPlayer owner, StoryStore.View view) {
        var fate = view.progress().getFate();
        var deaths = FateDeaths.get(owner.level());
        String dead = deaths.receipt(owner.getStringUUID());
        if (!dead.isEmpty() && dead.equals(fate.getActor())) {
            if (advance(owner, view, "death", data -> data
                    .setDeceased(dead)
                    .clearActor()
                    .setGrievingUntil(view.facts().ticks() + GRIEF))) {
                deaths.acknowledge(owner.getStringUUID(), dead);
                narrate(owner, "death.after");
            }
            return;
        }
        var escort = actor(owner, fate);
        // 缺席也可能只是区块卸载。直到同 UUID 的角色回来才继续护送。
        if (escort == null || !escort.isAlive()) {
            return;
        }
        var gate = BlockPos.of(fate.getGate());
        if (owner.blockPosition().closerThan(gate, RANGE) && escort.blockPosition().closerThan(gate, RANGE)
                && !view.crisis().hasPending()) {
            if (advance(owner, view, "gate", FateJourney.Builder::clearActor)) {
                escort.discard();
                String line = "quiet";
                if (fate.getBlood()) {
                    line = "blood";
                } else if (fate.getAmbushed()) {
                    line = "safe";
                }
                narrate(owner, "gate." + line);
            }
            return;
        }
        if (escort.distanceTo(owner) <= APPROACH) {
            escort.getNavigation().moveTo(owner, WALK_SPEED);
        }
        var pending = view.crisis().getPending();
        if (pending.getParty().equals(FateRoad.AMBUSH) && pending.getArrived()
                && owner.level().getEntity(UUID.fromString(pending.getLeader())) instanceof Mob enemy
                && CrisisRaiders.threatenEscort(owner, pending.getRoadRequest(), enemy, escort)
                && !fate.getAmbushed()) {
            if (update(owner, view, data -> data.setAmbushed(true /* ambushed */))) {
                narrate(owner, "ambush");
            }
        }
    }

    private void approachEvidence(ServerPlayer owner, StoryStore.View view, String reward) {
        var claim = FateRoad.state(view.progress()).getClaimsMap().get(reward);
        if (claim != null && nearFall(owner, claim) && !sent(owner, view)) {
            present(owner, view, "");
        }
    }

    private static boolean nearFall(ServerPlayer player, Claim claim) {
        return player.level().dimension().identifier().toString().equals(claim.getDimension())
                && player.distanceToSqr(claim.getX(), claim.getY(), claim.getZ()) < RANGE * RANGE;
    }

    private void temple(ServerPlayer player, StoryStore.View view) {
        var fate = view.progress().getFate();
        if (!fate.hasTemple() || view.crisis().hasPending()) {
            return;
        }
        var site = BlockPos.of(fate.getTemple());
        if (!player.blockPosition().closerThan(site, APPROACH)) {
            return;
        }
        var state = FateRoad.state(view.progress());
        if (fate.getRequest() == state.getRevision() && !fate.getActor().isEmpty()) {
            return;
        }
        var spot = FateSites.feet(player.level(), site);
        if (spot.isEmpty()) {
            return;
        }
        var guest = HumanoidVisitors.swordsman(player.level(), spot.get(), Component.translatable(PREFIX + "master"));
        if (!update(player, view, data -> data
                .setActor(guest.getStringUUID())
                .setRequest(state.getRevision()))) {
            guest.discard();
            return;
        }
        narrate(player, "arrival");
        if (state.containsOutcomes("temple")) {
            narrate(player, "return");
        }
        store.read(player).ifPresent(current -> present(player, current, guest.getStringUUID()));
    }

    private boolean interact(ServerPlayer owner, Entity entity) {
        var sample = store.read(owner);
        if (sample.isEmpty() || !eligible(sample.get()) || !(entity instanceof Mob mob)
                || !personal(owner, sample.get(), mob)) {
            return false;
        }
        var view = sample.get();
        String chapter = chapter(view);
        if ((!chapter.equals("reading") && !chapter.equals("escort")) || opened(owner, mob)) {
            present(owner, view, mob.getStringUUID());
        }
        return true;
    }

    private void recover(ServerPlayer player, StoryStore.View view) {
        String reward = "notebook";
        if (chapter(view).equals("directions")) {
            reward = "slip";
        }
        var letter = letter(player, view, reward);
        if (letter.isPresent() && letter.get().carried(player) == 0 && nearFall(player, letter.get().claim())
                && !sent(player, view)) {
            offer(player, view, "", "recover", List.of("recover"));
        }
    }

    private static Optional<StoryLetters> letter(ServerPlayer player, StoryStore.View view, String id) {
        return Optional.ofNullable(FateRoad.state(view.progress()).getClaimsMap().get(id))
                .map(claim -> new StoryLetters(player.getStringUUID(), FateRoad.LINE, id, claim));
    }

    private boolean use(ServerPlayer player, ItemStack stack) {
        var current = store.read(player);
        if (current.isEmpty() || !eligible(current.get())) {
            return false;
        }
        var view = current.get();
        if (letter(player, view, "notebook").filter(book -> book.matches(stack)).isPresent()) {
            if (chapter(view).equals("unread") && !advance(player, view, "read", data -> {})) {
                return false;
            }
            store.read(player).filter(v -> chapter(v).equals("decision")).ifPresent(v -> present(player, v, ""));
            return true;
        }
        if (letter(player, view, "slip").filter(slip -> slip.matches(stack)).isPresent()) {
            directions(player, view);
            if (chapter(view).equals("directions")) {
                present(player, view, "");
            }
            return true;
        }
        return false;
    }

    private static void directions(ServerPlayer owner, StoryStore.View view) {
        var pos = BlockPos.of(view.progress().getFate().getTemple());
        var origin = owner.blockPosition();
        int deltaX = pos.getX() - origin.getX();
        int deltaZ = pos.getZ() - origin.getZ();
        double distance = Math.sqrt((double) deltaX * deltaX + (double) deltaZ * deltaZ);
        int paces = Math.max(MIN_PACES, (int) Math.round(distance));
        String direction = direction(deltaX, deltaZ);
        owner.sendSystemMessage(Component.translatable(PREFIX + "direction",
                Component.translatable(PREFIX + "direction." + direction), paces));
    }

    private static String direction(int deltaX, int deltaZ) {
        if (deltaX == 0 && deltaZ < 0) {
            return "north";
        }
        if (deltaX > 0 && deltaZ < 0) {
            return "northeast";
        }
        if (deltaX > 0 && deltaZ == 0) {
            return "east";
        }
        if (deltaX > 0) {
            return "southeast";
        }
        if (deltaX == 0 && deltaZ > 0) {
            return "south";
        }
        if (deltaX < 0 && deltaZ > 0) {
            return "southwest";
        }
        if (deltaX < 0 && deltaZ == 0) {
            return "west";
        }
        return "northwest";
    }

    private boolean sent(ServerPlayer owner, StoryStore.View view) {
        var offer = offers.get(owner.getUUID());
        return offer != null && offer.revision() == FateRoad.state(view.progress()).getRevision()
                && owner.level().getGameTime() < offer.until();
    }

    private void present(ServerPlayer owner, StoryStore.View view, String actor) {
        boolean repeat = sent(owner, view);
        switch (chapter(view)) {
            case "reading" -> offer(owner, view, actor, "", List.of("pay", "leave"));
            case "escort" -> offer(owner, view, actor, "", List.of("accompany"));
            case "walking" -> offer(owner, view, actor, "", List.of("dismiss"));
            case "search" -> offer(owner, view, "", repeat ? "" : "hired", List.of("search", "mark"));
            case "directions" -> offer(owner, view, "", "", List.of("follow"));
            case "temple" -> offer(owner, view, actor, repeat ? "" : "temple", List.of("retreat"));
            case "victory" -> offer(owner, view, "", repeat ? "" : "victory", List.of("take"));
            case "decision" -> offer(owner, view, "", repeat ? "" : "read.after",
                    List.of("drawn", "stilled", "later"));
            default -> { }
        }
    }

    private void offer(ServerPlayer owner, StoryStore.View view, String actor, String prompt, List<String> choices) {
        var invitation = new Offer(UUID.randomUUID().toString(), FateRoad.state(view.progress()).getRevision(),
                owner.level().getGameTime() + LIFETIME, actor, choices);
        offers.put(owner.getUUID(), invitation);
        if (!prompt.isEmpty()) {
            narrate(owner, prompt);
        }
        var row = Component.empty();
        for (int index = 0; index < choices.size(); index++) {
            String key = PREFIX + choices.get(index);
            var selection = new StoryChat.Selection(invitation.nonce(), invitation.revision(), index);
            row.append(StoryChat.button(key, Component.translatable(key + ".hint"), ChatFormatting.GOLD,
                    selection, ACTION)).append("  ");
        }
        owner.sendSystemMessage(row);
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        var offer = offers.get(player.getUUID());
        if (offer == null || !offer.nonce().equals(selection.nonce())) {
            return Optional.empty();
        }
        offers.remove(player.getUUID());
        var current = store.read(player);
        if (current.isEmpty() || !eligible(current.get()) || !player.isAlive() || player.isSpectator()
                || player.level().dimension() != Level.OVERWORLD || player.level().getGameTime() >= offer.until()
                || selection.revision() != offer.revision() || selection.option() < 0
                || selection.option() >= offer.choices().size()) {
            return Optional.empty();
        }
        var view = current.get();
        var mob = actor(player, view.progress().getFate());
        if (FateRoad.state(view.progress()).getRevision() != offer.revision() || !offer.actor().isEmpty()
                && (mob == null || !mob.getStringUUID().equals(offer.actor()) || !personal(player, view, mob))) {
            return Optional.empty();
        }
        String action = offer.choices().get(selection.option());
        String reply = act(player, view, mob, action);
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable(PREFIX + reply));
    }

    private String act(ServerPlayer player, StoryStore.View view, Mob mob, String action) {
        switch (action) {
            case "pay", "leave" -> { return answerReading(player, view, mob, action); }
            case "accompany" -> {
                if (advance(player, view, action, data -> {})) {
                    mob.setNoAi(false /* noAi */);
                    return "accompany.after";
                }
            }
            case "dismiss" -> { return dismiss(player, view, mob); }
            case "search", "take", "recover" -> { return collect(player, view, action); }
            case "follow" -> {
                if (holding(player, view, "slip") && advance(player, view, action, data -> data.clearRequest())) {
                    return "follow.after";
                }
            }
            case "drawn", "stilled", "later" -> {
                if (holding(player, view, "notebook") && (action.equals("later")
                        || advance(player, view, action, data -> {}))) {
                    return action + ".after";
                }
            }
            case "mark" -> {
                if (letter(player, view, "slip").filter(l -> nearFall(player, l.claim())).isPresent()) {
                    return "mark.after";
                }
            }
            case "retreat" -> {
                if (view.crisis().hasPending()) {
                    return "";
                }
                return "retreat.after";
            }
            default -> { }
        }
        return "";
    }

    private String answerReading(ServerPlayer player, StoryStore.View view, Mob mob, String action) {
        var cash = BuiltInRegistries.ITEM.getValue(COPPER);
        var inventory = player.getInventory();
        boolean paying = action.equals("pay");
        if (paying && inventory.countItem(cash) < PRICE) {
            return "poor";
        }
        if (!advance(player, view, action, FateJourney.Builder::clearActor)) {
            return "";
        }
        if (paying) {
            int needed = PRICE;
            for (int slot = 0; needed > 0 && slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(cash)) {
                    needed -= inventory.removeItem(slot, needed).getCount();
                }
            }
        }
        mob.discard();
        return action + ".after";
    }

    private String dismiss(ServerPlayer player, StoryStore.View view, Mob mob) {
        var exit = FateSites.feet(player.level(), mob.blockPosition().relative(player.getDirection(), RANGE));
        if (view.crisis().hasPending() || exit.isEmpty()
                || !FateSites.directlyWalkable(player.level(), mob.blockPosition(), exit.get())) {
            return "";
        }
        if (!advance(player, view, "dismiss", FateJourney.Builder::clearActor)) {
            return "";
        }
        mob.discard();
        return "dismiss.after";
    }

    private static boolean holding(ServerPlayer player, StoryStore.View view, String reward) {
        return letter(player, view, reward).filter(l -> l.matches(player.getMainHandItem())
                || l.matches(player.getOffhandItem())).isPresent();
    }

    private String collect(ServerPlayer player, StoryStore.View view, String action) {
        String reward = "notebook";
        if (action.equals("search") || chapter(view).equals("directions")) {
            reward = "slip";
        }
        var source = letter(player, view, reward);
        if (source.isEmpty() || !nearFall(player, source.get().claim())) {
            return "";
        }
        if (action.equals("recover")) {
            var gift = source.get();
            if (!gift.claim().getAwaitingDelivery() && gift.carried(player) == 0
                    && player.getInventory().add(gift.letter(1))) {
                return "recover.after";
            }
            return "";
        }
        if (action.equals("search") && !view.progress().getFate().hasTemple()) {
            var site = FateSites.temple(player);
            if (site.isEmpty() || !setTemple(player, view, site.get())) {
                return "no_site";
            }
            view = store.read(player).orElse(view);
        }
        if (!advance(player, view, action, data -> {})) {
            return "";
        }
        store.read(player).ifPresent(v -> StoryLetters.deliverPending(player, store, v.progress()));
        return action + ".after";
    }

    private boolean setTemple(ServerPlayer player, StoryStore.View view, BlockPos site) {
        var before = FateRoad.state(view.progress());
        var slip = before.getClaimsOrThrow("slip").toBuilder()
                .setLoreX(site.getX())
                .setLoreY(site.getY())
                .setLoreZ(site.getZ())
                .build();
        var nextState = before.toBuilder()
                .putClaims("slip", slip)
                .build();
        return store.change(player, current -> {
            if (!view.progress().getFate().equals(current.progress().getFate())
                    || !before.equals(FateRoad.state(current.progress()))) {
                return current.progress();
            }
            var fate = current.progress().getFate().toBuilder()
                    .setTemple(site.asLong())
                    .build();
            return current.progress().toBuilder()
                    .putLines(FateRoad.LINE, nextState)
                    .setFate(fate)
                    .build();
        });
    }

    private boolean advance(ServerPlayer owner, StoryStore.View view, String action,
                            Consumer<FateJourney.Builder> edit) {
        var before = FateRoad.state(view.progress());
        var trigger = definition.chapters().get(before.getChapter()).on().stream()
                .filter(t -> t.kind() == StoryLine.Kind.SCENE && action.equals(t.id())).findFirst();
        if (trigger.isEmpty()) {
            return false;
        }
        var branch = trigger.get().branches().get("accepted");
        return store.change(owner, current -> {
            var progress = current.progress();
            if (!eligible(current) || !before.equals(FateRoad.state(progress))) {
                return progress;
            }
            var next = StoryProgress.advance(before, action, branch, current.facts().ticks()).toBuilder()
                    .putOutcomes(before.getChapter(), action);
            if (action.equals("search") || action.equals("take")) {
                String reward = action.equals("search") ? "slip" : "notebook";
                var gift = before.getClaimsOrThrow(reward).toBuilder().setAwaitingDelivery(true /* awaiting */).build();
                next.putClaims(reward, gift);
            }
            if (action.equals("gate")) {
                int serial = Math.max(1, current.crisis().getRoadCount() + current.crisis().getSettledCount());
                var cord = Claim.newBuilder()
                        .setEncounter(serial)
                        .setChapter("walking")
                        .setDimension(owner.level().dimension().identifier().toString())
                        .setX(owner.getX())
                        .setY(owner.getY())
                        .setZ(owner.getZ())
                        .setAwaitingDelivery(true /* awaiting */)
                        .build();
                next.putClaims("cord", cord);
            }
            var fate = progress.getFate().toBuilder();
            edit.accept(fate);
            return progress.toBuilder().putLines(FateRoad.LINE, next.build()).setFate(fate.build()).build();
        });
    }

    private boolean update(ServerPlayer player, StoryStore.View before, Consumer<FateJourney.Builder> edit) {
        return store.change(player, current -> {
            var progress = current.progress();
            if (!before.progress().getFate().equals(progress.getFate())
                    || !FateRoad.state(before.progress()).equals(FateRoad.state(progress))) {
                return progress;
            }
            var fate = progress.getFate().toBuilder();
            edit.accept(fate);
            return progress.toBuilder().setFate(fate.build()).build();
        });
    }

    private static void narrate(ServerPlayer owner, String key) {
        StoryChat.narrate(owner, PREFIX + key);
    }
}
