package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

final class MountainEncounters {
    static final long DAY = CrisisRules.DAY_TICKS;
    static final int REACH = 6;
    static final int AWAY = 32;
    static final String OPEN = "open";
    static final String PREFIX = "story.meridian_story.";
    private final StoryStore store;
    private final Set<Mob> loaded = new HashSet<>();
    private static final String OWNER = "meridian_story.mountain_owner.";
    final FathersDebt debt;
    final HerbHarvest herb;

    MountainEncounters(StoryStore store) {
        this.store = store;
        debt = new FathersDebt(this);
        herb = new HerbHarvest(this);
    }

    void register() {
        herb.register();
        registerActors();
        registerCombat();
    }

    private void registerActors() {
        ServerEntityEvents.ENTITY_LOAD.register((actor, level) -> {
            if (actor instanceof Mob mob && mob.entityTags().contains(GUEST)) {
                loaded.add(mob);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> loaded.clear());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % LoneStarRoad.SECOND == 0) {
                loaded.removeIf(this::reconcile);
            }
        });
        UseEntityCallback.EVENT.register((user, level, hand, target, hit) -> {
            if (user instanceof ServerPlayer player && target instanceof Mob mob && mob.entityTags().contains(GUEST)) {
                current(player).filter(c -> player.isShiftKeyDown() && belongs(c, mob) && near(player, mob))
                        .ifPresent(c -> offer(player, c));
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private void registerCombat() {
        AttackEntityCallback.EVENT.register((user, level, hand, target, hit) -> {
            if (user instanceof ServerPlayer player && target instanceof Mob mob && mob.entityTags().contains(GUEST)
                    && near(player, mob)) {
                strike(player, mob);
            }
            return InteractionResult.PASS;
        });
        VanillaDamageFabric.registerDamageObserver(hit -> {
            if (hit.attacker() instanceof ServerPlayer player && hit.target() instanceof Mob mob
                    && mob.entityTags().contains(GUEST)) {
                var direct = hit.source().getDirectEntity();
                if (direct instanceof Projectile projectile && projectile.getOwner() == player) {
                    strike(player, mob);
                }
                if (hit.result().health().getDamageResolution().getLossesCount() > 0 && !hit.bodyDead()) {
                    current(player).filter(c -> c.getActor().equals(mob.getStringUUID()))
                            .ifPresent(c -> debt.hurt(player, c, mob, hit.result().body()));
                }
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                ambushDeath(player, source.getEntity());
                current(player).ifPresent(c -> leave(player, c));
            } else if (entity instanceof Mob mob && mob.entityTags().contains(GUEST)) {
                for (var owner : mob.level().getServer().getPlayerList().getPlayers()) {
                    current(owner).filter(c -> belongs(c, mob)).ifPresent(c -> {
                        if (c.getKind().equals(FathersDebt.KIND)) {
                            debt.died(owner, c, source.getEntity());
                        } else {
                            herb.died(owner, c, mob);
                        }
                    });
                }
            }
        });
    }

    static final String GUEST = "meridian_story.mountain_guest";

    static void mark(ServerPlayer player, Mob guest) {
        guest.addTag(GUEST);
        guest.addTag(OWNER + player.getStringUUID());
    }

    private boolean reconcile(Mob mob) {
        if (mob.isRemoved()) {
            return true;
        }
        var id = mob.entityTags().stream().filter(t -> t.startsWith(OWNER)).findFirst();
        if (id.isEmpty()) {
            return true;
        }
        var owner = UUID.fromString(id.get().substring(OWNER.length()));
        var player = mob.level().getServer().getPlayerList().getPlayer(owner);
        if (player == null) {
            return false;
        }
        return store.read(player).map(view -> {
            if (!belongs(view.progress().getRoadside().getCurrent(), mob)) {
                mob.removeTag(GUEST);
                HumanoidVisitors.withdraw(mob, owner);
            }
            return true;
        }).orElse(false);
    }

    private void ambushDeath(ServerPlayer player, net.minecraft.world.entity.Entity killer) {
        if (killer instanceof Mob mob) {
            store.read(player).stream().flatMap(v -> v.progress().getRoadside().getMountainHistoryMap()
                    .values().stream()).filter(c -> c.getFollowupLeader().equals(mob.getStringUUID()))
                    .filter(c -> c.getReturnParty().equals(HerbHarvest.AMBUSH)).findFirst()
                    .ifPresent(c -> herb.rob(player, c, mob));
        }
    }

    private void strike(ServerPlayer player, Mob mob) {
        current(player).filter(c -> belongs(c, mob)).ifPresent(c -> {
            if (c.getKind().equals(FathersDebt.KIND)) {
                debt.strike(player, c, mob);
            } else {
                herb.strike(player, c, mob);
            }
        });
    }

    private static boolean belongs(RoadsideCase c, Mob mob) {
        String id = mob.getStringUUID();
        return c.getActor().equals(id) || c.getBuyer().equals(id) || c.getGuardian().equals(id);
    }

    Optional<RoadsideCase> current(ServerPlayer player) {
        return store.read(player).map(v -> v.progress().getRoadside().getCurrent())
                .filter(c -> c.getKind().equals(FathersDebt.KIND) || c.getKind().equals(HerbHarvest.KIND));
    }

    static Optional<Mob> actor(ServerPlayer player, String id) {
        if (!id.isEmpty() && player.level().getServer().overworld().getEntity(UUID.fromString(id)) instanceof Mob mob
                && mob.isAlive()) {
            return Optional.of(mob);
        }
        return Optional.empty();
    }

    static boolean near(ServerPlayer player, Mob mob) {
        return player.isAlive() && !player.isSpectator() && player.level() == mob.level()
                && player.distanceTo(mob) <= REACH;
    }

    static RoadsideCase.Builder scene(ServerPlayer player, Roadside road, String kind, BlockPos site) {
        long number = road.getCases() + 1;
        return RoadsideCase.newBuilder()
                .setNumber(number)
                .setActor(UUID.randomUUID().toString())
                .setKind(kind)
                .setSite(site.asLong())
                .setRoll(RoadsideRules.roll(FallenCultivator.seed(player), number))
                .setStage(OPEN);
    }

    boolean encounter(ServerPlayer player, StoryStore.View view, long tile) {
        var road = view.progress().getRoadside();
        var father = debt.father(player, view);
        var trail = father.flatMap(dead -> FathersDebt.trail(player));
        var ledge = HerbLedge.find(player, view);
        if (trail.isEmpty() && ledge.isEmpty()) {
            return false;
        }
        var drawn = FallenCultivator.remember(road, tile);
        if (!RoadsideRules.opens(FallenCultivator.seed(player), tile)) {
            save(player, road, drawn.build());
            return true;
        }
        if (trail.isPresent()) {
            return debt.open(player, drawn.build(), trail.get(), father.orElseThrow());
        }
        return herb.open(player, drawn.build(), ledge.orElseThrow());
    }

    boolean open(ServerPlayer player, Roadside drawn, RoadsideCase c) {
        return store.change(player, view -> {
            var road = view.progress().getRoadside();
            if (road.hasCurrent() || road.getCases() != drawn.getCases()) {
                return view.progress();
            }
            var next = drawn.toBuilder()
                    .setCurrent(c)
                    .setCases(c.getNumber())
                    .clearLastEnding()
                    .build();
            return view.progress().toBuilder().setRoadside(next).build();
        });
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (c.getKind().equals(FathersDebt.KIND)) {
            debt.offer(player, c);
        } else {
            herb.offer(player, c);
        }
    }

    static Component button(RoadsideCase c, String key, int index, Component cost) {
        var click = new StoryChat.Selection(c.getActor(), c.getNumber(), index);
        return StoryChat.button(key, cost, ChatFormatting.GOLD, click);
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection click) {
        return current(player).filter(c -> valid(c, click))
                .flatMap(c -> {
                    if (c.getKind().equals(FathersDebt.KIND)) {
                        return debt.choose(player, c, click.option());
                    }
                    return herb.choose(player, c, click.option());
                });
    }

    private static boolean valid(RoadsideCase c, StoryChat.Selection click) {
        long revision = c.getNumber();
        if (c.getKind().equals(HerbHarvest.KIND) && click.option() == HerbHarvest.SELL) {
            revision = HerbHarvest.saleRevision(c, c.getQuotedUnits());
        }
        return c.getActor().equals(click.nonce()) && revision == click.revision();
    }

    boolean tick(ServerPlayer player, StoryStore.View view, RoadsideCase c) {
        if (!c.getKind().equals(FathersDebt.KIND) && !c.getKind().equals(HerbHarvest.KIND)) {
            return false;
        }
        if (player.level().dimension() != Level.OVERWORLD || !player.isAlive()
                || player.blockPosition().distSqr(BlockPos.of(c.getSite())) > AWAY * AWAY) {
            leave(player, c);
        } else if (c.getKind().equals(FathersDebt.KIND)) {
            debt.tick(player, c);
        } else {
            herb.tick(player, c);
        }
        return true;
    }

    private void leave(ServerPlayer player, RoadsideCase c) {
        if (c.getKind().equals(FathersDebt.KIND)) {
            debt.separated(player, c);
        } else {
            herb.leave(player, c);
        }
    }

    boolean update(ServerPlayer player, RoadsideCase before, RoadsideCase after) {
        return store.change(player, view -> {
            var road = view.progress().getRoadside();
            if (!road.getCurrent().equals(before)) {
                return view.progress();
            }
            var next = road.toBuilder().setCurrent(after).build();
            return view.progress().toBuilder().setRoadside(next).build();
        });
    }

    void end(ServerPlayer player, RoadsideCase c, String ending, String party, long delay) {
        var found = store.read(player);
        if (found.isEmpty()) {
            return;
        }
        var view = found.get();
        var road = view.progress().getRoadside();
        if (!road.getCurrent().getActor().equals(c.getActor())) {
            return;
        }
        long now = view.facts().ticks();
        var closed = c.toBuilder()
                .setEnding(ending)
                .setReturnParty(party)
                .setReturnAt(now + delay)
                .build();
        long rest = HerbHarvest.REST;
        if (c.getKind().equals(FathersDebt.KIND)) {
            rest = FathersDebt.REST;
        }
        var next = road.toBuilder()
                .clearCurrent()
                .setLastEnding(ending)
                .putMountainHistory(c.getActor(), closed)
                .putRestByKind(c.getKind(), now + rest)
                .build();
        if (save(player, road, next)) {
            release(player, c);
            narrate(player, c, ending);
        }
    }

    private static void release(ServerPlayer player, RoadsideCase c) {
        for (String id : java.util.List.of(c.getActor(), c.getBuyer(), c.getGuardian())) {
            actor(player, id).ifPresent(mob -> {
                mob.removeTag(GUEST);
                mob.removeTag(OWNER + player.getStringUUID());
                mob.setPose(net.minecraft.world.entity.Pose.STANDING);
                HumanoidVisitors.withdraw(mob, player.getUUID());
            });
        }
    }

    static void narrate(ServerPlayer player, RoadsideCase c, String ending) {
        StoryChat.narrate(player, PREFIX + c.getKind() + "." + ending);
    }

    static void fight(ServerPlayer player, Mob actor) {
        actor.setNoAi(false /* noAi */);
        actor.setTarget(player);
        actor.getNavigation().moveTo(player, 1);
    }

    boolean revisit(ServerPlayer player, StoryStore.View view) {
        if (player.level().dimension() != Level.OVERWORLD || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        var road = view.progress().getRoadside();
        return road.getMountainHistoryMap().values().stream()
                .filter(c -> !c.getReturnParty().isEmpty() && c.getReturnAt() <= view.facts().ticks())
                .min(java.util.Comparator.comparing((RoadsideCase c) -> c.getTicket() == 0)
                        .thenComparingLong(RoadsideCase::getReturnAt)
                        .thenComparingLong(RoadsideCase::getNumber))
                .map(c -> follow(player, view, c)).orElse(false);
    }

    private boolean follow(ServerPlayer player, StoryStore.View view, RoadsideCase c) {
        var crisis = view.crisis();
        String key = "meridian_story:mountain/" + c.getActor();
        String party = c.getReturnParty();
        if (c.getTicket() == 0) {
            if (crisis.hasPending()) {
                return true;
            }
            long ticket = Math.max(crisis.getRoadRequest(), view.progress().getRoad().getDraws()) + 1;
            var watch = RaidWatch.newBuilder()
                    .setRoad(true /* road */)
                    .setParty(party)
                    .build();
            if (CrisisEncounters.requestRoad(player, party, ticket)) {
                CrisisEncounters.watch(player, key, watch);
                history(player, view, c.toBuilder().setTicket(ticket).build());
            }
            return true;
        }
        var pending = crisis.getPending();
        if (pending.getRoadRequest() == c.getTicket() && pending.getArrived() && !c.getShouted()) {
            actor(player, pending.getLeader()).ifPresent(leader -> arrived(player, view, c, leader));
        } else if (crisis.containsReceipts(key)) {
            var receipt = crisis.getReceiptsOrThrow(key);
            var result = c.toBuilder()
                    .clearReturnParty()
                    .setFollowupOutcome(receipt.getOutcome());
            if (c.getKind().equals(HerbHarvest.KIND)) {
                result.setCarriedFlowers(HerbHarvest.carried(player, c));
            }
            if (history(player, view, result.build())) {
                CrisisEncounters.acknowledge(player, key, receipt);
                StoryChat.narrate(player, PREFIX + "mountain." + receipt.getOutcome());
            }
        }
        return true;
    }

    private void arrived(ServerPlayer player, StoryStore.View view, RoadsideCase c, Mob leader) {
        var next = c.toBuilder()
                .setShouted(true /* shouted */)
                .setFollowupLeader(leader.getStringUUID())
                .build();
        if (history(player, view, next)) {
            String party = c.getReturnParty();
            String line = party.substring(party.indexOf(':') + 1);
            leader.addTag("meridian_story.case." + c.getActor());
            leader.setCustomName(Component.translatable(PREFIX + line + ".name"));
            if (c.getKind().equals(FathersDebt.KIND)) {
                leader.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
            narrate(player, c, line);
        }
    }

    private boolean history(ServerPlayer player, StoryStore.View view, RoadsideCase c) {
        var road = view.progress().getRoadside();
        var next = road.toBuilder().putMountainHistory(c.getActor(), c).build();
        return save(player, road, next);
    }

    private boolean save(ServerPlayer player, Roadside before, Roadside after) {
        return store.change(player, v -> {
            if (v.progress().getRoadside().equals(before)) {
                return v.progress().toBuilder().setRoadside(after).build();
            }
            return v.progress();
        });
    }
}
