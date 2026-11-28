package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.story.fabric.BequestGoods.Gift;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class BequestPromise {
    private final StoryStore store;
    private final java.util.Set<UUID> saving = new java.util.HashSet<>();

    BequestPromise(StoryStore store) {
        this.store = store;
    }

    void register() {
        UseBlockCallback.EVENT.register((user, level, hand, hit) -> {
            if (user instanceof ServerPlayer player && level.dimension() == Level.OVERWORLD) {
                store.read(player).ifPresent(v -> v.progress().getRoadside().getMountainHistoryMap().values().stream()
                        .filter(c -> owed(c) && atTable(player, c, hit.getBlockPos()))
                        .filter(c -> BequestGoods.slot(player, Gift.JADE.stack(c)) < 0)
                        .forEach(c -> button(player, c, LastRequest.REPLACE, "replace")));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((user, level, hand) -> {
            if (user instanceof ServerPlayer player) {
                var held = player.getItemInHand(hand);
                store.read(player).ifPresent(v -> v.progress().getRoadside().getMountainHistoryMap().values().stream()
                        .filter(c -> c.getEnding().equals("notes_given") && !c.getBequest().getStudied()
                                && ItemStack.isSameItemSameComponents(held, Gift.NOTES.stack(c)))
                        .forEach(c -> button(player, c, LastRequest.STUDY, "study")));
            }
            return InteractionResult.PASS;
        });
    }

    static boolean owed(RoadsideCase c) {
        return c.hasBequest() && c.getBequest().getDue() > 0 && !c.getBequest().getDelivered();
    }

    static boolean overdue(Progress progress, long now) {
        return progress.getRoadside().getMountainHistoryMap().values().stream()
                .anyMatch(c -> owed(c) && c.getBequest().getDue() <= now);
    }

    void tick(ServerPlayer player, StoryStore.View view) {
        long now = view.facts().ticks();
        for (var c : view.progress().getRoadside().getMountainHistoryMap().values()) {
            if (c.getBequest().getDue() > 0) {
                retirePaid(player, c);
            }
            if (owed(c) && c.getBequest().getDue() <= now && !c.getBequest().getOverdue()) {
                var terms = c.getBequest().toBuilder().setOverdue(true /* overdue */).build();
                if (save(player, c, c.toBuilder().setBequest(terms).build())) {
                    StoryChat.narrate(player, LastRequest.KEY + "overdue");
                }
            } else if (c.getEnding().equals("notes_left") && !c.getBequest().getWitnessedNote()
                    && now >= c.getReturnAt() && !view.crisis().hasPending()
                    && !view.progress().getRoadside().hasCurrent()) {
                returnToCave(player, c);
            }
        }
    }

    void retirePaid(ServerPlayer player, RoadsideCase c) {
        var server = player.level().getServer();
        var site = MountainRetreats.get(server.overworld()).site(c.getSite());
        var id = UUID.fromString(c.getActor());
        if (site == null || !site.caseId().equals(c.getActor()) || !saving.add(id)) {
            return;
        }
        // 写入含欠约的身体快照后才能让老人永久退场；失败或掉线留给原案重试。
        dev.kizuna.meridian.human.fabric.HumanInheritance.receipt(player, id).whenCompleteAsync((paid, error) -> {
            saving.remove(id);
            if (error == null && paid.isPresent()) {
                LastRequest.retire(player, c);
            }
        }, server);
    }

    private void returnToCave(ServerPlayer player, RoadsideCase c) {
        var level = player.level();
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        var retreats = MountainRetreats.get(level);
        var site = retreats.site(c.getSite());
        if (site == null || !site.walkable(level)) {
            return;
        }
        var bedside = site.at(MountainRetreats.ELDER);
        if (!bedside.closerThan(player.blockPosition(), MountainEncounters.REACH)
                || !(level.getEntity(UUID.fromString(c.getGiftItem())) instanceof ItemEntity note)
                || !note.blockPosition().closerThan(bedside, MountainEncounters.REACH)) {
            return;
        }
        var disciple = MountainEncounters.actor(player, c.getDisciple());
        if (disciple.isEmpty()) {
            return;
        }
        var actor = disciple.get();
        actor.setPos(Vec3.atBottomCenterOf(bedside));
        if (!MountainEncounters.near(player, actor) || !actor.hasLineOfSight(player)) {
            return;
        }
        var terms = c.getBequest().toBuilder().setWitnessedNote(true /* witnessedNote */).build();
        var witness = c.toBuilder()
                .setBequest(terms)
                .setReturnParty(LastRequest.ACCUSER)
                .build();
        if (save(player, c, witness)) {
            StoryChat.narrate(player, LastRequest.KEY + "accuse");
            HumanoidVisitors.depart(level, actor.getUUID());
            note.discard();
            retreats.release(c.getSite(), c.getActor());
        }
    }

    private static void button(ServerPlayer player, RoadsideCase c, int index, String label) {
        var selection = new StoryChat.Selection(c.getActor(), c.getBequest().getSerial(), index);
        var hint = LastRequest.text(label + ".cost");
        var button = StoryChat.button(LastRequest.KEY + label, hint, net.minecraft.ChatFormatting.GOLD, selection);
        player.sendSystemMessage(Component.empty().append(button));
    }

    private static boolean atTable(ServerPlayer player, RoadsideCase c, BlockPos hit) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return false;
        }
        var site = MountainRetreats.get(player.level()).site(c.getSite());
        return site != null && site.at(MountainRetreats.TABLE).equals(hit)
                && hit.closerThan(player.blockPosition(), MountainEncounters.REACH) && site.walkable(player.level());
    }

    void atDisciple(ServerPlayer player, Mob actor) {
        store.read(player).ifPresent(v -> v.progress().getRoadside().getMountainHistoryMap().values().stream()
                .filter(c -> owed(c) && c.getDisciple().equals(actor.getStringUUID()) && atGate(player, c).isPresent())
                .filter(c -> BequestGoods.slot(player, Gift.JADE.stack(c)) >= 0)
                .forEach(c -> button(player, c, LastRequest.DELIVER, "deliver")));
    }

    private static Optional<Mob> atGate(ServerPlayer player, RoadsideCase c) {
        var gate = BlockPos.of(c.getBequest().getGate());
        return MountainEncounters.actor(player, c.getDisciple()).filter(actor -> MountainEncounters.near(player, actor)
                && actor.blockPosition().closerThan(gate, MountainEncounters.REACH));
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection click) {
        var c = store.read(player).map(v -> v.progress().getRoadside().getMountainHistoryMap().get(click.nonce()));
        if (c.isEmpty() || !c.get().hasBequest() || !player.isAlive() || player.isSpectator()
                || player.isCreative() || click.revision() != c.get().getBequest().getSerial()) {
            return Optional.empty();
        }
        var current = c.get();
        return switch (click.option()) {
            case LastRequest.DELIVER -> deliver(player, current);
            case LastRequest.REPLACE -> replace(player, current);
            case LastRequest.STUDY -> study(player, current);
            default -> Optional.empty();
        };
    }

    private Optional<Component> deliver(ServerPlayer player, RoadsideCase c) {
        var disciple = atGate(player, c);
        var jade = Gift.JADE.stack(c);
        int slot = BequestGoods.slot(player, jade);
        if (!owed(c) || disciple.isEmpty() || slot < 0) {
            return Optional.empty();
        }
        var bag = MobInventories.open(disciple.get());
        if (!bag.previewInsertion(jade).remainder().isEmpty()) {
            return Optional.empty();
        }
        var terms = c.getBequest().toBuilder()
                .setDelivered(true /* delivered */)
                .clearOverdue()
                .build();
        if (save(player, c, c.toBuilder().setBequest(terms).build())) {
            bag.insert(player.getInventory().removeItem(slot, 1));
            HumanoidVisitors.withdraw(disciple.get(), player.getUUID());
            return Optional.of(LastRequest.text("delivered"));
        }
        return Optional.empty();
    }

    private Optional<Component> replace(ServerPlayer player, RoadsideCase c) {
        var level = player.level();
        var site = MountainRetreats.get(level).site(c.getSite());
        int slot = player.getInventory().getFreeSlot();
        if (!owed(c) || site == null || !atTable(player, c, site.at(MountainRetreats.TABLE)) || slot < 0
                || BequestGoods.slot(player, Gift.JADE.stack(c)) >= 0) {
            return Optional.empty();
        }
        var terms = c.getBequest().toBuilder().setSerial(Math.incrementExact(c.getBequest().getSerial())).build();
        var next = c.toBuilder().setBequest(terms).build();
        if (save(player, c, next)) {
            player.getInventory().setItem(slot, Gift.JADE.stack(next));
            return Optional.of(LastRequest.text("replaced"));
        }
        return Optional.empty();
    }

    private Optional<Component> study(ServerPlayer player, RoadsideCase c) {
        if (!c.getEnding().equals("notes_given") || c.getBequest().getStudied()
                || BequestGoods.slot(player, Gift.NOTES.stack(c)) < 0) {
            return Optional.empty();
        }
        var service = TechniqueFabric.service();
        try {
            var known = service.known(player.getUUID());
            if (known.stream().noneMatch(k -> BequestGoods.FORM.equals(k.technique().getId()))) {
                service.learn(player.getUUID(), BequestGoods.FORM);
            }
        } catch (TechniqueService.LearningDeniedException refused) {
            return Optional.of(LastRequest.text("unready"));
        }
        var terms = c.getBequest().toBuilder().setStudied(true /* studied */).build();
        if (save(player, c, c.toBuilder().setBequest(terms).build())) {
            return Optional.of(LastRequest.text("learned"));
        }
        return Optional.empty();
    }

    private boolean save(ServerPlayer player, RoadsideCase before, RoadsideCase after) {
        return store.change(player, v -> {
            var road = v.progress().getRoadside();
            if (!before.equals(road.getMountainHistoryOrDefault(before.getActor(), null))) {
                return v.progress();
            }
            var next = road.toBuilder().putMountainHistory(before.getActor(), after).build();
            return v.progress().toBuilder().setRoadside(next).build();
        });
    }
}
