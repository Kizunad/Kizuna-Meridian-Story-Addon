package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

final class VillageEncounters {
    static final String PREFIX = "story.meridian_story.";
    static final String OPEN = "open";
    static final int REACH = 6;
    private static final int AWAY = 24;
    private static final int OWNER_CHECK = 20;
    private static final String OWNER = "meridian_story.encounter.owner.";
    private static final String WATCH = "meridian_story:roadside_followup";
    private static final int NIGHT = 13_000;
    private static final String STALL_SITE = "meridian_story.stall.site.";
    private final Set<Mob> arrivals = new HashSet<>();
    private final StoryStore store;
    final ManualSeller seller;
    final FranticCultivator frantic;

    VillageEncounters(StoryStore store) {
        this.store = store;
        seller = new ManualSeller(this);
        frantic = new FranticCultivator(this);
    }

    void register() {
        registerInteractions();
        registerDamage();
        registerLoad();
    }

    private void registerInteractions() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer owner && entity instanceof Mob actor && owner(actor).isPresent()
                    && near(owner, actor)) {
                current(owner).filter(c -> c.getActor().equals(actor.getStringUUID())).ifPresent(c -> {
                    if (c.getKind().equals(ManualSeller.KIND)) {
                        seller.wreck(owner, c, actor);
                    } else {
                        frantic.strike(owner, c, actor);
                    }
                });
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(entity instanceof Mob actor) || owner(actor).isEmpty()) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer visitor && visitor.isShiftKeyDown() && near(visitor, actor)) {
                current(visitor).filter(c -> belongs(c, actor)).ifPresent(c -> offer(visitor, c));
            }
            return InteractionResult.SUCCESS;
        });
    }

    private void registerDamage() {
        VanillaDamageFabric.registerDamageObserver(hit -> {
            if (hit.source().getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
                    && hit.attacker() instanceof ServerPlayer shooter && hit.target() instanceof Mob target
                    && owner(target).isPresent()) {
                current(shooter).filter(c -> c.getKind().equals(ManualSeller.KIND)
                        && c.getActor().equals(target.getStringUUID()))
                        .ifPresent(c -> seller.wreck(shooter, c, target));
            }
            frantic.hurt(hit);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Mob actor && actor.level() instanceof ServerLevel level) {
                owner(actor).map(id -> level.getServer().getPlayerList().getPlayer(id)).ifPresent(player ->
                        current(player).filter(c -> c.getActor().equals(actor.getStringUUID())).ifPresent(c -> {
                            if (c.getKind().equals(FranticCultivator.KIND)) {
                                frantic.death(player, c, actor, source);
                            } else {
                                seller.gone(player, c);
                            }
                        }));
            }
        });
    }

    private void registerLoad() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob actor && owner(actor).isPresent()) {
                arrivals.add(actor);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % OWNER_CHECK == 0) {
                arrivals.removeIf(actor -> reconcile(server, actor));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> arrivals.clear());
    }

    private static Optional<UUID> owner(Mob actor) {
        return actor.entityTags().stream().filter(tag -> tag.startsWith(OWNER)).findFirst()
                .map(tag -> UUID.fromString(tag.substring(OWNER.length())));
    }

    static Optional<ServerPlayer> ownerOf(Mob actor) {
        if (actor.level() instanceof ServerLevel level) {
            return owner(actor).map(id -> level.getServer().getPlayerList().getPlayer(id));
        }
        return Optional.empty();
    }

    private boolean reconcile(MinecraftServer server, Mob actor) {
        if (actor.isRemoved()) {
            return true;
        }
        var player = owner(actor).map(id -> server.getPlayerList().getPlayer(id));
        if (player.isEmpty()) {
            return false;
        }
        var view = store.read(player.get());
        if (view.isEmpty()) {
            return false;
        }
        if (!belongs(view.get().progress().getRoadside().getCurrent(), actor)) {
            actor.entityTags().stream().filter(tag -> tag.startsWith(STALL_SITE)).findFirst()
                    .map(tag -> BlockPos.of(Long.parseLong(tag.substring(STALL_SITE.length()))))
                    .ifPresent(site -> MarketStall.remove(server.overworld(), site));
            release(player.get(), actor);
        }
        return true;
    }

    private static boolean belongs(RoadsideCase c, Mob actor) {
        return c.getActor().equals(actor.getStringUUID()) || c.getDisciple().equals(actor.getStringUUID());
    }

    static boolean near(ServerPlayer player, Mob actor) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative() && actor.isAlive()
                && player.level() == actor.level() && player.distanceToSqr(actor) <= REACH * REACH;
    }

    static <T extends Mob> Optional<T> arrive(ServerPlayer player, BlockPos pos, String id, String key,
                                               EntityType<T> type) {
        var name = Component.translatable(key);
        var actor = HumanoidVisitors.arrive(player.level(), pos, UUID.fromString(id), name, type);
        actor.ifPresent(mob -> mob.addTag(OWNER + player.getStringUUID()));
        return actor;
    }

    static void markStall(Mob seller, BlockPos site) {
        seller.addTag(STALL_SITE + site.asLong());
    }

    static RoadsideCase.Builder start(ServerPlayer player, Roadside.Builder road, BlockPos site, String kind) {
        long number = road.getCases() + 1;
        return RoadsideCase.newBuilder()
                .setNumber(number)
                .setKind(kind)
                .setActor(UUID.randomUUID().toString())
                .setSite(site.asLong())
                .setRoll(RoadsideRules.roll(FallenCultivator.seed(player), number))
                .setStage(OPEN)
                .setArrivedAt(player.level().getGameTime());
    }

    boolean open(ServerPlayer player, Roadside.Builder road, RoadsideCase c) {
        var opened = road.clone()
                .setCases(c.getNumber())
                .setCurrent(c)
                .clearLastEnding()
                .build();
        return store.change(player, view -> {
            var progress = view.progress();
            var before = progress.getRoadside();
            if (before.hasCurrent() || before.getCases() + 1 != c.getNumber()) {
                return progress;
            }
            return progress.toBuilder().setRoadside(opened).build();
        });
    }

    Optional<RoadsideCase> current(ServerPlayer player) {
        return store.read(player).map(view -> view.progress().getRoadside().getCurrent())
                .filter(c -> c.getKind().equals(ManualSeller.KIND) || c.getKind().equals(FranticCultivator.KIND));
    }

    static Optional<Mob> actor(ServerPlayer player, String id) {
        if (!id.isEmpty() && player.level().getServer().overworld().getEntity(UUID.fromString(id)) instanceof Mob mob
                && mob.isAlive()) {
            return Optional.of(mob);
        }
        return Optional.empty();
    }

    void tick(ServerPlayer player, RoadsideCase c) {
        var site = BlockPos.of(c.getSite());
        var level = player.level().getServer().overworld();
        boolean left = !player.isAlive() || player.isSpectator() || player.isCreative()
                || player.level().dimension() != Level.OVERWORLD
                || player.blockPosition().distSqr(site) > AWAY * AWAY;
        if (!left && !level.isLoaded(site)) {
            return;
        }
        var actor = actor(player, c.getActor());
        if (c.getKind().equals(ManualSeller.KIND)) {
            if (!seller.recoverSale(player, c) && (left || actor.isEmpty())) {
                seller.gone(player, c);
            }
        } else if (actor.isEmpty() || left) {
            end(player, c, "gone", "story.meridian_story.fallen.gone", "");
        } else {
            frantic.tick(player, c, actor.get());
        }
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (c.getKind().equals(ManualSeller.KIND)) {
            seller.offer(player, c);
        } else {
            frantic.offer(player, c);
        }
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        var found = current(player).filter(c -> c.getActor().equals(selection.nonce())
                && c.getNumber() == selection.revision());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var c = found.get();
        if (c.getKind().equals(ManualSeller.KIND)) {
            return seller.choose(player, c, selection.option());
        }
        return frantic.choose(player, c, selection.option());
    }

    static Component button(RoadsideCase c, String key, int option, Component cost) {
        var selection = new StoryChat.Selection(c.getActor(), c.getNumber(), option);
        return StoryChat.button(key, cost, ChatFormatting.GOLD, selection);
    }

    boolean update(ServerPlayer player, RoadsideCase before, RoadsideCase next) {
        return store.change(player, view -> {
            var progress = view.progress();
            var road = progress.getRoadside();
            if (!road.getCurrent().equals(before)) {
                return progress;
            }
            var updated = road.toBuilder().setCurrent(next).build();
            return progress.toBuilder().setRoadside(updated).build();
        });
    }

    boolean end(ServerPlayer player, RoadsideCase c, String ending, String key, String party) {
        boolean closed = store.change(player, view -> {
            var progress = view.progress();
            var road = progress.getRoadside();
            if (!road.getCurrent().getActor().equals(c.getActor())) {
                return progress;
            }
            long days = ManualSeller.REST_DAYS;
            if (c.getKind().equals(FranticCultivator.KIND)) {
                days = FranticCultivator.REST_DAYS;
            }
            var settled = c.toBuilder().setEnding(ending).build();
            var next = road.toBuilder()
                    .clearCurrent()
                    .putLastCase(c.getKind(), settled)
                    .putRestByKind(c.getKind(), view.facts().ticks() + days * CrisisRules.DAY_TICKS)
                    .setLastEnding(ending);
            if (!party.isEmpty()) {
                next.setInquiryAt(view.facts().ticks() + 1);
                next.setInquiryParty(party);
            }
            return progress.toBuilder().setRoadside(next).build();
        });
        if (closed) {
            var level = player.level().getServer().overworld();
            var site = BlockPos.of(c.getSite());
            if (c.getKind().equals(ManualSeller.KIND) && level.isLoaded(site)) {
                MarketStall.remove(level, site);
            }
            actor(player, c.getActor()).ifPresent(mob -> release(player, mob));
            actor(player, c.getDisciple()).ifPresent(mob -> release(player, mob));
            StoryChat.narrate(player, key);
        }
        return closed;
    }

    private static void release(ServerPlayer player, Mob mob) {
        mob.removeTag(OWNER + player.getStringUUID());
        mob.setNoAi(false /* noAi */);
        mob.setTarget(null);
        mob.setPose(net.minecraft.world.entity.Pose.STANDING);
        if (mob instanceof net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader) {
            trader.setDespawnDelay(ManualSeller.DEPART);
        }
    }

    void followup(ServerPlayer player, StoryStore.View view) {
        var road = view.progress().getRoadside();
        String party = road.getInquiryParty();
        var crisis = view.crisis();
        long ticket = road.getInquiryTicket();
        if (ticket == 0) {
            requestFollowup(player, view, road);
            return;
        }
        var pending = crisis.getPending();
        if (pending.getRoadRequest() == ticket && pending.getArrived() && !road.getInquiryShown()) {
            showVisit(player, road, pending.getLeader());
            return;
        }
        var receipt = crisis.getReceiptsMap().get(WATCH);
        if (receipt != null && receipt.getPlayer().equals(player.getStringUUID()) && receipt.getParty().equals(party)) {
            String outcome = receipt.getOutcome();
            String kind = followupKind(party);
            var settled = road.getLastCaseOrThrow(kind).toBuilder().setFollowupOutcome(outcome).build();
            var next = road.toBuilder()
                    .clearInquiryTicket()
                    .clearInquiryParty()
                    .clearInquiryShown()
                    .putLastCase(kind, settled)
                    .setLastEnding(party + "/" + outcome)
                    .build();
            if (saveRoad(player, road, next)) {
                CrisisEncounters.acknowledge(player, WATCH, receipt);
                StoryChat.narrate(player, PREFIX + "followup." + outcome);
            }
        }
    }

    private void showVisit(ServerPlayer player, Roadside road, String leaderId) {
        String party = road.getInquiryParty();
        String kind = followupKind(party);
        var previous = road.getLastCaseOrThrow(kind);
        var arrived = previous.toBuilder().setFollowupLeader(leaderId).build();
        var shown = road.toBuilder()
                .putLastCase(kind, arrived)
                .setInquiryShown(true)
                .build();
        if (saveRoad(player, road, shown)) {
            if (kind.equals(FranticCultivator.KIND)) {
                actor(player, leaderId).ifPresent(leader ->
                        leader.addTag("meridian_story.disciple." + previous.getDisciple()));
            }
            StoryChat.narrate(player, followupLine(party));
        }
    }

    private void requestFollowup(ServerPlayer player, StoryStore.View view, Roadside road) {
        var crisis = view.crisis();
        String party = road.getInquiryParty();
        if (player.level().dimension() != Level.OVERWORLD || view.facts().ticks() < road.getInquiryAt()
                || party.equals(ManualSeller.ASSOCIATES)
                && Math.floorMod(player.level().getOverworldClockTime(), CrisisRules.DAY_TICKS) < NIGHT) {
            return;
        }
        long next = Math.max(crisis.getRoadRequest(), view.progress().getRoad().getDraws()) + 1;
        var watch = RaidWatch.newBuilder().setRoad(true).setParty(party).build();
        CrisisEncounters.watch(player, WATCH, watch);
        if (CrisisEncounters.requestRoad(player, party, next)) {
            saveRoad(player, road, road.toBuilder().clearInquiryAt().setInquiryTicket(next).build());
        }
    }

    private boolean saveRoad(ServerPlayer player, Roadside before, Roadside next) {
        return store.change(player, view -> {
            var progress = view.progress();
            if (!progress.getRoadside().equals(before)) {
                return progress;
            }
            return progress.toBuilder().setRoadside(next).build();
        });
    }

    private static String followupLine(String party) {
        if (party.equals(ManualSeller.ASSOCIATES)) {
            return ManualSeller.KEY + "wreck.bad";
        }
        return FranticCultivator.KEY + "return";
    }

    private static String followupKind(String party) {
        if (party.equals(ManualSeller.ASSOCIATES)) {
            return ManualSeller.KIND;
        }
        return FranticCultivator.KIND;
    }
}
