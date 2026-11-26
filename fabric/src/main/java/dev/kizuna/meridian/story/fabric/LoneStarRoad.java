package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadTravel;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class LoneStarRoad {
    static final String LINE = "meridian_story:lone_star";
    static final String SCOUT = "meridian_story:road_scout";
    static final String PURSUIT = "meridian_story:road_pursuit";
    static final int SECOND = 20;
    static final int DRAW = 6 * 60 * SECOND;
    static final long REST = 2 * CrisisRules.DAY_TICKS;
    private static final int RADIUS = 64;
    private static final int TILE = 16;
    private static final int TILE_LIMIT = 4 * SECOND;
    private static final int MEMORY = 512;
    private static final int MAX_STEP = 12;
    static final int CM = 100;
    private static final int ROUNDING_CM = 2;
    private static final int CHANCE = 40;
    private static final int PERCENT = 100;
    private static final long DRAW_SALT = 0x9E3779B97F4A7C15L;
    private final Map<UUID, Step> previous = new HashMap<>();
    private final StoryStore store;

    LoneStarRoad(StoryStore store) {
        this.store = store;
    }

    void registerCamp() {
        // 玩家亲自认下的营火、原版出生点和床分别划定安全范围。
        UseBlockCallback.EVENT.register((actor, level, hand, hit) -> {
            if (actor instanceof ServerPlayer player && hand == InteractionHand.MAIN_HAND
                    && level.dimension() == Level.OVERWORLD && level.getBlockState(hit.getBlockPos())
                    .is(BlockTags.CAMPFIRES)) {
                camp(player, hit.getBlockPos());
            }
            return InteractionResult.PASS;
        });
    }

    private void camp(ServerPlayer player, BlockPos pos) {
        boolean changed = store.change(player, view -> {
            if (!StoryOrigins.roadTrouble(view.facts().origin())) {
                return view.progress();
            }
            var camp = view.progress().getRoad().toBuilder().setCamp(pos.asLong()).build();
            return view.progress().toBuilder().setRoad(camp).build();
        });
        if (changed) {
            StoryChat.narrate(player, "story.meridian_story.road.camp");
        }
    }

    void leave(ServerPlayer player) {
        previous.remove(player.getUUID());
        RoadFootman.leave(player);
    }

    record Step(Vec3 feet, long cm, long tick) {
        static Step sample(ServerPlayer player) {
            var stats = player.getStats();
            long cm = (long) stats.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM))
                    + stats.getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM))
                    + stats.getValue(Stats.CUSTOM.get(Stats.CROUCH_ONE_CM));
            return new Step(player.position(), cm, player.level().getGameTime());
        }

        boolean followed(Step old) {
            if (old == null || tick - old.tick != SECOND) {
                return false;
            }
            double distance = feet.subtract(old.feet).horizontalDistance();
            long walked = cm - old.cm;
            return distance >= 1 && distance <= MAX_STEP && walked >= distance * CM - ROUNDING_CM;
        }
    }

    void tick(ServerPlayer player, StoryStore.View view) {
        // 重登、换维或乘骑后第一点只作基线，避免把位置跳变算成行路。
        if (!StoryOrigins.roadTrouble(view.facts().origin()) || player.level().dimension() != Level.OVERWORLD
                || !player.isAlive() || player.isSpectator()) {
            if (previous.containsKey(player.getUUID()) || view.progress().hasRoad()) {
                leave(player);
            }
            return;
        }
        RoadFootman.tick(player, view);
        var road = view.progress().getRoad();
        long last = Math.max(view.crisis().getRoadSettledAt(), view.crisis().getSettledAt());
        if (last != road.getLastSettlement()) {
            var reset = road.toBuilder()
                    .setLastSettlement(last)
                    .clearWalkingTicks()
                    .clearWaiting()
                    .clearCurrentSegment()
                    .clearSegmentTicks()
                    .build();
            save(player, road, reset);
            previous.remove(player.getUUID());
            return;
        }
        if (player.isCreative() || !player.onGround() || player.isPassenger() || player.isInWater()
                || player.getAbilities().flying || view.crisis().hasPending() || view.facts().ticks() - last < REST
                || !outside(player, road)) {
            previous.remove(player.getUUID());
            return;
        }
        var step = Step.sample(player);
        var old = previous.put(player.getUUID(), step);
        if (step.followed(old)) {
            walk(player, view, road);
        }
    }

    static boolean outside(ServerPlayer player, RoadTravel road) {
        var level = player.level();
        var spawn = level.getRespawnData();
        var feet = player.blockPosition();
        if (spawn.dimension() == level.dimension() && near(feet, spawn.pos())) {
            return false;
        }
        var bed = player.getRespawnConfig();
        if (bed != null && bed.respawnData().dimension() == level.dimension() && near(feet, bed.respawnData().pos())) {
            return false;
        }
        if (!road.hasCamp()) {
            return true;
        }
        var camp = BlockPos.of(road.getCamp());
        return !near(feet, camp) || !level.getBlockState(camp).is(BlockTags.CAMPFIRES);
    }

    private static boolean near(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dz = (double) a.getZ() - b.getZ();
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    private void walk(ServerPlayer player, StoryStore.View view, RoadTravel before) {
        long tile = BlockPos.asLong(Math.floorDiv(player.getBlockX(), TILE), 0,
                Math.floorDiv(player.getBlockZ(), TILE));
        var advanced = credit(before, tile);
        if (advanced.getWalkingTicks() == before.getWalkingTicks()) {
            if (!advanced.equals(before)) {
                save(player, before, advanced);
            }
            return;
        }
        RoadTravel next;
        if (!before.getWaiting() && advanced.getWalkingTicks() >= DRAW) {
            // 通用路上事件也会占用轻场签号，新签总排在两边已用过的号之后。
            long draw = Math.max(before.getDraws(), view.crisis().getRoadRequest()) + 1;
            next = advanced.toBuilder()
                    .setDraws(draw)
                    .setWaiting(selected(player, draw))
                    .clearWalkingTicks()
                    .build();
        } else {
            next = advanced;
        }
        if (!save(player, before, next) || !next.getWaiting()) {
            return;
        }
        if (view.crisis().getRoadRequest() == next.getDraws()
                || CrisisEncounters.requestRoad(player, party(player, view), next.getDraws())) {
            var accepted = next.toBuilder()
                    .clearWaiting()
                    .clearWalkingTicks()
                    .build();
            save(player, next, accepted);
        }
    }

    static boolean selected(ServerPlayer player, long draw) {
        var id = player.getUUID();
        long seed = player.level().getSeed() ^ id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        return RandomSource.create(seed + draw * DRAW_SALT).nextInt(PERCENT) < CHANCE;
    }

    static RoadTravel credit(RoadTravel road, long tile) {
        // 一个新网格只记首次连续穿行的脚程。再进同格不续算。
        var next = road.toBuilder();
        int used;
        if (road.hasCurrentSegment() && road.getCurrentSegment() == tile) {
            used = road.getSegmentTicks();
        } else {
            if (road.getSegmentsList().contains(tile)) {
                return next.clearCurrentSegment().clearSegmentTicks().build();
            }
            if (road.getSegmentsCount() >= MEMORY) {
                var history = road.getSegmentsList();
                next.clearSegments();
                next.addAllSegments(history.subList(1, history.size()));
            }
            next.addSegments(tile);
            next.setCurrentSegment(tile);
            used = 0;
        }
        if (used >= TILE_LIMIT) {
            return road;
        }
        return next.setSegmentTicks(used + SECOND)
                .setWalkingTicks(road.getWalkingTicks() + SECOND)
                .build();
    }

    private boolean save(ServerPlayer player, RoadTravel before, RoadTravel next) {
        return store.change(player, view -> {
            var progress = view.progress();
            if (!progress.getRoad().equals(before)) {
                return progress;
            }
            return progress.toBuilder().setRoad(next).build();
        });
    }

    static String party(ServerPlayer player, StoryStore.View view) {
        var state = view.progress().getLinesMap().get(LINE);
        if (state == null || !state.getEnding().isEmpty() || view.facts().opened() == 0) {
            return SCOUT;
        }
        String proof = switch (state.getChapter()) {
            case "night_escort" -> "escort_stub";
            case "night_solo" -> "road_token";
            default -> "";
        };
        var claim = state.getClaimsMap().get(proof);
        if (claim != null && !claim.getSpent()
                && new StoryLetters(player.getStringUUID(), LINE, proof, claim).carried(player) > 0) {
            return PURSUIT;
        }
        return SCOUT;
    }
}
