package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FranticCultivator {
    static final String KIND = "frantic";
    static final String KEY = VillageEncounters.PREFIX + KIND + ".";
    static final int REST_DAYS = 4;
    static final int RESTRAINED_GOOD = 60;
    static final int THANKFUL = 55;
    static final int CALMED = 65;
    static final int FEED = 0;
    static final int HOLD_TICKS = 60;
    static final String DOWN = "down";
    private static final String ENEMY = "meridian_story:disciple_grudge";
    private static final String PREPARE = "arriving";
    private static final String WOUND_PART = "left_forearm";
    private static final double WOUND_HP = 1.0;
    private static final int RESIDENT_RANGE = 12;
    private static final int LANE_WIDTH = 4;
    private static final int RESIDENT_AREA = RESIDENT_RANGE * RESIDENT_RANGE;
    private static final int PATH_LENGTH = 6;
    private static final int WITNESS_RANGE = 16;
    private static final int GOODWILL = 10;
    private static final int WARNING_TICKS = 120;
    private static final double SAFE_RADIUS_SQUARED = 4.0;
    private final VillageEncounters events;

    FranticCultivator(VillageEncounters events) {
        this.events = events;
    }

    static boolean gate(ServerLevel level, BlockPos site) {
        if (!level.canSeeSky(site)) {
            return false;
        }
        var ground = site.below();
        return BlockPos.betweenClosedStream(ground.offset(-LANE_WIDTH, 0, -LANE_WIDTH),
                        ground.offset(LANE_WIDTH, 0, LANE_WIDTH))
                .anyMatch(pos -> level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.DIRT_PATH));
    }

    boolean open(ServerPlayer player, Roadside.Builder road, BlockPos site) {
        var level = player.level();
        if (!level.isVillage(site)) {
            return false;
        }
        var nearby = level.getEntitiesOfClass(Villager.class, new AABB(site).inflate(RESIDENT_RANGE),
                mob -> mob.isAlive() && !mob.entityTags().contains("meridian_humanoid.visitor"));
        for (var resident : nearby) {
            var refuge = resident.blockPosition().relative(player.getDirection(), PATH_LENGTH);
            if (!level.isLoaded(refuge) || !level.getBlockState(refuge.below()).isSolid()) {
                continue;
            }
            if (clearLane(player, resident.blockPosition())) {
                return arrive(player, road, site, resident, refuge);
            }
        }
        return false;
    }

    private static boolean clearLane(ServerPlayer player, BlockPos from) {
        var level = player.level();
        for (int step = 1; step <= PATH_LENGTH; step++) {
            var pos = from.relative(player.getDirection(), step);
            if (!level.isLoaded(pos) || !level.getBlockState(pos.below()).isSolid()
                    || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getFluidState(pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean arrive(ServerPlayer player, Roadside.Builder road, BlockPos site, Villager resident,
                           BlockPos refuge) {
        var c = VillageEncounters.start(player, road, site, KIND)
                .setStage(PREPARE)
                .setDisciple(UUID.randomUUID().toString())
                .setResident(resident.getStringUUID())
                .setRefuge(refuge.asLong())
                .build();
        var actor = VillageEncounters.arrive(player, site, c.getActor(), KEY + "actor", EntityTypes.WANDERING_TRADER);
        if (actor.isEmpty()) {
            return false;
        }
        var disciple = VillageEncounters.arrive(player, site.relative(player.getDirection().getClockWise()),
                c.getDisciple(), KEY + "disciple", EntityTypes.WANDERING_TRADER);
        if (disciple.isEmpty() || !events.open(player, road, c)) {
            actor.get().discard();
            disciple.ifPresent(Mob::discard);
            return false;
        }
        return true;
    }

    void tick(ServerPlayer player, RoadsideCase c, Mob actor) {
        long now = player.level().getGameTime();
        if (c.getStage().equals(PREPARE)) {
            wound(player, c, actor);
            return;
        }
        if (c.getStage().equals(DOWN)) {
            if (now - c.getLastStrike() >= HOLD_TICKS) {
                spared(player, c, actor);
            }
            return;
        }
        var resident = resident(player, c);
        if (resident.isEmpty()) {
            events.end(player, c, "gone", "", "");
        } else if (now - c.getArrivedAt() >= WARNING_TICKS || c.getBrokeFree()) {
            var villager = resident.get();
            var refuge = BlockPos.of(c.getRefuge());
            if (villager.blockPosition().distSqr(refuge) <= 1) {
                events.end(player, c, "protected", KEY + "safe", "");
            } else {
                escape(villager, refuge);
                HumanoidVisitors.pursue(player.level(), actor, villager);
            }
        }
    }

    private void wound(ServerPlayer player, RoadsideCase c, Mob actor) {
        if (player.level().getGameTime() - c.getArrivedAt() < FallenCultivator.SETTLE) {
            return;
        }
        var hit = new VanillaDamageFabric.DirectDamage(actor, actor.damageSources().generic(), WOUND_PART, WOUND_HP);
        var result = VanillaDamageFabric.damageDirect(hit);
        if (result.isEmpty() || BitterHealing.wound(result.get().body(), WOUND_PART) <= 0) {
            events.end(player, c, "gone", "", "");
            return;
        }
        var ready = c.toBuilder()
                .setWounded(true)
                .setStage(VillageEncounters.OPEN)
                .setArrivedAt(player.level().getGameTime())
                .build();
        if (events.update(player, c, ready)) {
            offer(player, ready);
        }
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (!c.getStage().equals(VillageEncounters.OPEN)) {
            return;
        }
        StoryChat.narrate(player, KEY + "opening");
        var row = Component.translatable(KEY + "hold").append("  ").append(Component.translatable(KEY + "kill"));
        player.sendSystemMessage(row);
        if (!c.getBrokeFree() && dose(player).isPresent()) {
            var feed = VillageEncounters.button(c, KEY + "feed", FEED, Component.translatable(KEY + "cost"));
            player.sendSystemMessage(Component.empty().append(feed));
        }
    }

    private static Optional<ItemStack> dose(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(Items.POTION) && BrewItemStacks.isBrew(stack)
                    && BrewItemStacks.kind(stack) == BrewKind.BITTER_YELLOW) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    Optional<Component> choose(ServerPlayer player, RoadsideCase c, int option) {
        if (option != FEED || !c.getStage().equals(VillageEncounters.OPEN) || c.getBrokeFree()
                || !c.getDose().isEmpty()) {
            return Optional.empty();
        }
        var helper = VillageEncounters.actor(player, c.getDisciple())
                .filter(actor -> VillageEncounters.near(player, actor));
        var actor = VillageEncounters.actor(player, c.getActor());
        var stack = dose(player);
        if (helper.isEmpty() || actor.isEmpty() || helper.get().distanceTo(actor.get()) > VillageEncounters.REACH
                || stack.isEmpty()) {
            return Optional.empty();
        }
        return feed(player, c, actor.get(), stack.get());
    }

    private Optional<Component> feed(ServerPlayer player, RoadsideCase c, Mob patient, ItemStack bottle) {
        var before = VanillaDamageFabric.inspectBody(patient);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        var body = before.get();
        if (body.getPartStatesList().stream().noneMatch(part ->
                BitterHealing.isLimb(part.getId()) && BitterHealing.wound(body, part.getId()) > 0)) {
            return Optional.empty();
        }
        var prior = BrewBodyProgress.read(body).getDosesList();
        BrewingRuntime.consume(player.level(), patient, bottle);
        var applied = VanillaDamageFabric.inspectBody(patient).stream()
                .flatMap(after -> BrewBodyProgress.read(after).getDosesList().stream())
                .filter(d -> d.getKind().equals(BrewKind.BITTER_YELLOW.name()))
                .filter(d -> d.getPartIdsList().stream().anyMatch(BitterHealing::isLimb) && !prior.contains(d))
                .findFirst();
        if (applied.isEmpty()) {
            return Optional.empty();
        }
        var receipt = applied.get();
        var dosed = c.toBuilder()
                .setHealed(true)
                .setDose(receipt.getQuality() + "/" + receipt.getStartedMillis())
                .build();
        if (events.update(player, c, dosed)) {
            bottle.shrink(1);
            var glass = new ItemStack(Items.GLASS_BOTTLE);
            if (!player.getInventory().add(glass)) {
                player.drop(glass, false /* random velocity */);
            }
            if (c.getRoll() < CALMED) {
                HumanoidVisitors.restrain(patient);
                events.end(player, dosed, "calmed", KEY + "feed.good", "");
            } else {
                relapse(player, dosed, "feed.bad");
            }
        }
        return Optional.of(Component.empty());
    }

    void strike(ServerPlayer player, RoadsideCase c, Mob actor) {
        if (c.getWounded()) {
            events.update(player, c, c.toBuilder().setLastStrike(actor.level().getGameTime()).build());
        }
    }

    void hurt(VanillaDamageFabric.DamageEvent hit) {
        if (hit.result().health().getDamageResolution().getLossesCount() == 0) {
            return;
        }
        if (hit.target() instanceof Villager victim && hit.attacker() instanceof Mob attacker) {
            VillageEncounters.ownerOf(attacker).ifPresent(player -> events.current(player)
                    .filter(c -> c.getKind().equals(KIND) && c.getActor().equals(attacker.getStringUUID()))
                    .filter(c -> victim.blockPosition().distSqr(BlockPos.of(c.getSite())) <= RESIDENT_AREA)
                    .ifPresent(c -> events.end(player, c.toBuilder().setVictim(victim.getStringUUID()).build(),
                            "resident_hurt", KEY + "injured", "")));
        } else if (hit.target() instanceof Mob actor && !hit.bodyDead()
                && hit.attacker() instanceof ServerPlayer player
                && VillageEncounters.ownerOf(actor).orElse(null) == player) {
            events.current(player).filter(c -> c.getKind().equals(KIND) && c.getActor().equals(actor.getStringUUID())
                    && c.getWounded() && deliberate(player, c, hit.source())).ifPresent(c -> {
                        var down = c.toBuilder().setStage(DOWN).setLastStrike(actor.level().getGameTime()).build();
                        if (events.update(player, c, down)) {
                            HumanoidVisitors.restrain(actor);
                        }
                    });
        }
    }

    private static boolean deliberate(ServerPlayer player, RoadsideCase c, DamageSource source) {
        if (source.getEntity() != player) {
            return false;
        }
        return source.getDirectEntity() instanceof Projectile
                || source.getDirectEntity() == player && c.getLastStrike() == player.level().getGameTime();
    }

    private void spared(ServerPlayer player, RoadsideCase c, Mob actor) {
        if (c.getBrokeFree()) {
            var resident = resident(player, c);
            if (resident.isEmpty()) {
                events.end(player, c, "gone", "", "");
            } else {
                var villager = resident.get();
                var refuge = BlockPos.of(c.getRefuge());
                escape(villager, refuge);
                if (villager.position().distanceToSqr(Vec3.atBottomCenterOf(refuge)) <= SAFE_RADIUS_SQUARED) {
                    events.end(player, c, "protected", KEY + "safe", "");
                }
            }
        } else if (c.getRoll() < RESTRAINED_GOOD) {
            var bag = MobInventories.open(actor);
            for (int slot = 0; slot < MobInventory.SIZE; slot++) {
                var offered = bag.peek(slot);
                if (!offered.isEmpty()) {
                    var item = BuiltInRegistries.ITEM.getKey(offered.getItem());
                    var spared = c.toBuilder().setGiftItem(item.toString()).build();
                    if (!events.end(player, spared, "spared", "", "")) {
                        return;
                    }
                    var gift = bag.extract(slot, 1);
                    if (!player.getInventory().add(gift)) {
                        player.drop(gift, false /* random velocity */);
                    }
                    StoryChat.narrate(player, KEY + "hold.good");
                    return;
                }
            }
            events.end(player, c, "spared_empty", "", "");
        } else {
            relapse(player, c, "hold.bad");
        }
    }

    private static void escape(Villager villager, BlockPos refuge) {
        var target = new WalkTarget(refuge, 1.0F, 1);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, target);
        var destination = Vec3.atBottomCenterOf(refuge);
        villager.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0);
    }

    private void relapse(ServerPlayer player, RoadsideCase c, String line) {
        var next = c.toBuilder()
                .setStage(VillageEncounters.OPEN)
                .setBrokeFree(true)
                .build();
        if (events.update(player, c, next)) {
            StoryChat.narrate(player, KEY + line);
        }
    }

    void death(ServerPlayer player, RoadsideCase c, Mob actor, DamageSource source) {
        if (!deliberate(player, c, source)) {
            events.end(player, c, "interrupted", "story.meridian_story.fallen.gone", "");
            return;
        }
        boolean witnessed = VillageEncounters.actor(player, c.getDisciple())
                .filter(mob -> mob.distanceTo(actor) <= WITNESS_RANGE && mob.hasLineOfSight(actor)
                        && mob.hasLineOfSight(player)).isPresent();
        var killed = c.toBuilder().setKilledByOwner(true).setWitnessed(witnessed).build();
        if (c.getRoll() >= THANKFUL && witnessed) {
            events.end(player, killed, "disciple_grudge", KEY + "kill.bad", ENEMY);
            return;
        }
        var resident = resident(player, c).filter(v -> v.distanceTo(actor) <= WITNESS_RANGE);
        if (resident.isPresent()) {
            resident.get().getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, GOODWILL);
            events.end(player, killed, "thanked", KEY + "kill.good", "");
        } else {
            events.end(player, killed, "killed", "", "");
        }
    }

    private static Optional<Villager> resident(ServerPlayer player, RoadsideCase c) {
        return VillageEncounters.actor(player, c.getResident())
                .filter(Villager.class::isInstance).map(Villager.class::cast);
    }
}
