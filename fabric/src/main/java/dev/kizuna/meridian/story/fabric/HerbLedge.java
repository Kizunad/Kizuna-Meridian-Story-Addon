package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

final class HerbLedge extends SavedData {
    static final int WALK = 5;
    static final int HALF = 3;
    private static final int MIN_RISE = 2;
    private static final int MIDDAY_END = 12_000;
    private static final Identifier TEMPLATE = Identifier.parse("meridian_story:herb_ledge");
    static final Codec<HerbLedge> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(HerbLedge::new, data -> data.owners);
    private static final SavedDataType<HerbLedge> TYPE = new SavedDataType<>(TEMPLATE,
            () -> new HerbLedge(Map.of()), CODEC, null);
    private final Map<String, String> owners;

    private HerbLedge(Map<String, String> owners) {
        this.owners = new HashMap<>(owners);
    }

    record Site(BlockPos foot, BlockPos flower, BlockPos partner, BlockPos buyer) { }

    static HerbLedge get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static String key(BlockPos pos) {
        return Long.toString(pos.asLong());
    }

    String owner(BlockPos pos) {
        return owners.get(key(pos));
    }

    static Optional<Site> find(ServerPlayer player, StoryStore.View view) {
        var level = player.level();
        if (Math.floorMod(level.getOverworldClockTime(), MountainEncounters.DAY) >= MIDDAY_END
                || view.facts().ticks() < view.progress().getRoadside().getRestByKindOrDefault(HerbHarvest.KIND, 0L)) {
            return Optional.empty();
        }
        return find(level, player.blockPosition(), player.getDirection());
    }

    static Optional<Site> find(ServerLevel level, BlockPos feet, Direction direction) {
        var foot = feet.relative(direction);
        var partner = foot.relative(direction.getClockWise());
        var buyer = foot.relative(direction.getCounterClockWise());
        if (!stand(level, foot) || !stand(level, partner) || !stand(level, buyer)) {
            return Optional.empty();
        }
        var step = foot;
        for (int walk = 0; walk < WALK; walk++) {
            var next = step.relative(direction);
            if (stand(level, next)) {
                step = next;
            } else if (stand(level, next.above())) {
                step = next.above();
            } else {
                return Optional.empty();
            }
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, step.getX(), step.getZ());
        if (step.getY() - foot.getY() < MIN_RISE || step.getY() < surface || get(level).owner(step) != null) {
            return Optional.empty();
        }
        return Optional.of(new Site(foot, step, partner, buyer));
    }

    private static boolean stand(ServerLevel level, BlockPos feet) {
        return level.isLoaded(feet) && level.getBlockState(feet.below()).isSolid()
                && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir();
    }

    boolean plant(ServerLevel level, BlockPos flower, String owner) {
        if (owners.containsKey(key(flower))) {
            return false;
        }
        var template = level.getStructureManager().get(TEMPLATE).orElseThrow();
        var cells = ((GraveTemplateAccessor) template).meridianStory$palettes().getFirst().blocks();
        var base = flower.offset(-HALF, -1, -HALF);
        for (var cell : cells) {
            var pos = base.offset(cell.pos());
            if (!level.isLoaded(pos)) {
                return false;
            }
            var block = level.getBlockState(pos);
            if (!block.isAir() && !block.is(BlockTags.DIRT) && !block.is(BlockTags.BASE_STONE_OVERWORLD)
                    && !block.is(Blocks.GRASS_BLOCK) && !block.is(Blocks.DIRT_PATH)) {
                return false;
            }
        }
        for (var cell : cells) {
            level.setBlock(base.offset(cell.pos()), cell.state(), Block.UPDATE_CLIENTS);
        }
        owners.put(key(flower), owner);
        setDirty();
        return true;
    }

    boolean harvest(ServerLevel level, BlockPos flower, String owner) {
        if (!owner.equals(owner(flower)) || !level.getBlockState(flower).is(Blocks.WITHER_ROSE)) {
            return false;
        }
        level.setBlock(flower, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    void retire(ServerLevel level, BlockPos flower, String owner) {
        if (owner.equals(owner(flower))) {
            harvest(level, flower, owner);
            owners.put(key(flower), "");
            setDirty();
        }
    }
}
