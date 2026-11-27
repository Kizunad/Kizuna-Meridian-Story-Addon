package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

final class FateSites {
    static final Identifier TEMPLE = Identifier.parse("meridian_story:deserted_temple");
    static final BlockPos ALTAR = new BlockPos(6, 1, 3);
    private static final int GATE_RANGE = 64;
    private static final int MIN_WALK = 14;
    private static final int MAX_WALK = 42;
    private static final int SEARCH = 4;
    private static final int TEMPLE_DISTANCE = 42;
    private static final int HALF_TEMPLE = 6;
    private static final int CANDIDATES = 12;

    private FateSites() { }

    static Optional<BlockPos> gate(ServerPlayer player) {
        var level = player.level();
        var origin = player.blockPosition();
        return level.getPoiManager().findClosest(type -> type.is(PoiTypes.MEETING),
                p -> level.isLoaded(p) && level.isVillage(p) && !origin.closerThan(p, MIN_WALK)
                        && origin.closerThan(p, MAX_WALK), origin, GATE_RANGE, PoiManager.Occupancy.ANY);
    }

    static Optional<BlockPos> feet(ServerLevel level, BlockPos around) {
        return BlockPos.findClosestMatch(around, SEARCH, SEARCH,
                p -> level.isLoaded(p) && level.getBlockState(p.below()).isSolid()
                        && !level.getBlockState(p.below()).is(BlockTags.CAMPFIRES)
                        && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir());
    }

    static Optional<BlockPos> fire(ServerPlayer player) {
        var level = player.level();
        return BlockPos.findClosestMatch(player.blockPosition(), SEARCH * 2, SEARCH,
                pos -> level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.CAMPFIRES));
    }

    static boolean directlyWalkable(ServerLevel level, BlockPos from, BlockPos target) {
        if (from.getY() != target.getY()) {
            return false;
        }
        int stepX = Integer.signum(target.getX() - from.getX());
        int stepZ = Integer.signum(target.getZ() - from.getZ());
        if (stepX != 0 && stepZ != 0) {
            return false;
        }
        var cursor = from;
        while (!cursor.equals(target)) {
            cursor = cursor.offset(stepX, 0, stepZ);
            if (!level.isLoaded(cursor) || !level.getBlockState(cursor).isAir()
                    || !level.getBlockState(cursor.above()).isAir()) {
                return false;
            }
        }
        return true;
    }

    static Optional<BlockPos> temple(ServerPlayer player) {
        var level = player.level();
        // 先核整座庙的净空与地基；复用同一模板，落地完成才签发命帖。
        var template = level.getStructureManager().get(TEMPLE).orElseThrow();
        var cells = ((GraveTemplateAccessor) template).meridianStory$palettes().getFirst().blocks();
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = Math.TAU * i / CANDIDATES;
            var column = player.blockPosition().offset((int) (Math.cos(angle) * TEMPLE_DISTANCE), 0,
                    (int) (Math.sin(angle) * TEMPLE_DISTANCE));
            if (!level.isLoaded(column)) {
                continue;
            }
            var base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
                    .offset(-HALF_TEMPLE, 0, -HALF_TEMPLE);
            for (var cell : cells) {
                level.getChunkAt(base.offset(cell.pos()));
            }
            if (cells.stream().anyMatch(cell -> !clear(level, base.offset(cell.pos()), cell.pos().getY()))) {
                continue;
            }
            for (var cell : cells) {
                level.setBlock(base.offset(cell.pos()), cell.state(), Block.UPDATE_ALL);
            }
            return Optional.of(base.offset(ALTAR));
        }
        return Optional.empty();
    }

    private static boolean clear(ServerLevel level, BlockPos pos, int layer) {
        if (!level.isLoaded(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.structureManager().getAllStructuresAt(pos).isEmpty() || level.getBlockEntity(pos) != null) {
            return false;
        }
        var state = level.getBlockState(pos);
        if (!state.isAir() && !state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.TALL_GRASS) && !state.is(Blocks.FERN)) {
            return false;
        }
        if (layer > 0) {
            return true;
        }
        var ground = level.getBlockState(pos.below());
        return ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.BASE_STONE_OVERWORLD);
    }
}
