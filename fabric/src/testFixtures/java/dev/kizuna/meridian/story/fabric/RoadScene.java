package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 跳过等待与长距离行路，场次申请、实体、收场、聊天和交物均走正式入口。 */
record RoadScene(ServerPlayer player) {
    static final int WALK = 3;
    private static final int CHUNK_RADIUS = 3;

    CrisisRecord record() {
        return CrisisEncounters.current(player).orElseThrow();
    }

    LineState state() {
        return new StoryBodies().read(player).map(StoryProgress::read)
                .map(p -> p.getLinesOrDefault(LoneStarRoad.LINE, LineState.getDefaultInstance())).orElseThrow();
    }

    void readyRoad() {
        if (!state().getEnding().isEmpty()) {
            readyRoad(LoneStarRoad.Track.FATE);
        } else {
            readyRoad(LoneStarRoad.Track.FOOTMAN);
        }
    }

    void readyRoad(LoneStarRoad.Track track) {
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var progress = StoryProgress.read(body);
        var crisis = record();
        var store = new StoryStore();
        store.ready(player, PhysiqueScene.LONE);
        var view = store.read(player).orElseThrow();
        var previous = progress.getRoad();
        if (track == LoneStarRoad.Track.FOOTMAN) {
            previous = progress.getFootmanRoad();
        }
        long number = Math.max(previous.getDraws(), crisis.getRoadRequest()) + 1;
        while (!LoneStarRoad.selected(player, number, track, view)) {
            number++;
        }
        var road = previous.toBuilder()
                .setLastSettlement(Math.max(crisis.getRoadSettledAt(), crisis.getSettledAt()))
                .setWalkingTicks(LoneStarRoad.DRAW - LoneStarRoad.SECOND)
                .clearCurrentSegment()
                .clearSegments()
                .clearSegmentTicks()
                .setDraws(number - 1)
                .clearWaiting()
                .build();
        var prepared = progress.toBuilder();
        if (track == LoneStarRoad.Track.FOOTMAN) {
            prepared.setFootmanRoad(road);
        } else {
            prepared.setRoad(road);
        }
        if (!bodies.write(player, body, prepared.build())) {
            throw new IllegalStateException("未能预置最后一段脚程");
        }
        StoryRaidFixture.roadClock(player, crisis.getOnlineTicks() + LoneStarRoad.REST);
    }

    void ground(BlockPos column) {
        var level = player.level();
        // 无客户端的 GameTest 玩家要显式加载来人搜索圈，并随移动更新区块跟踪。
        // 每个方向的区块覆盖来人落点半径。
        int chunkX = net.minecraft.core.SectionPos.blockToSectionCoord(column.getX());
        int chunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(column.getZ());
        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
        var feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        player.setPos(Vec3.atBottomCenterOf(feet));
        level.getChunkSource().move(player);
        player.setOnGround(true /* onGround */);
        player.setGameMode(GameType.SURVIVAL);
    }

    void walk() {
        player.setPos(player.position().add(WALK, 0, 0));
        player.awardStat(Stats.WALK_ONE_CM, WALK * LoneStarRoad.CM);
        player.setOnGround(true /* onGround */);
    }

    Mob leader() {
        return (Mob) player.level().getEntity(UUID.fromString(record().getPending().getLeader()));
    }

    void win() {
        leader().die(player.damageSources().playerAttack(player));
    }

    void openMeridian() {
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "meridian opening complete " + player.getScoreboardName() + " left_lung_meridian");
    }

    void camp() {
        var pos = player.blockPosition().east();
        var level = player.level();
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.CAMPFIRE.defaultBlockState());
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
        UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }

    Villager visitor() {
        return RoadFootman.visitor(player);
    }

    boolean delivered(String id) {
        var claim = state().getClaimsMap().get(id);
        return claim != null && !claim.getAwaitingDelivery() && !claim.getSpent()
                && new StoryLetters(player.getStringUUID(), LoneStarRoad.LINE, id, claim).carried(player) == 1;
    }
}
