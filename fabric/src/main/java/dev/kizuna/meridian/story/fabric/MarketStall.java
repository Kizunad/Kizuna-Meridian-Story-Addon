package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

final class MarketStall {
    private static final Identifier TEMPLATE = Identifier.parse("meridian_story:market_stall");
    private static final int CENTER_X = 3;
    private static final int CENTER_Z = 2;

    private MarketStall() { }

    private static List<StructureBlockInfo> cells(ServerLevel level) {
        var template = level.getStructureManager().get(TEMPLATE).orElseThrow();
        return ((GraveTemplateAccessor) template).meridianStory$palettes().getFirst().blocks();
    }

    enum Light {
        LANTERN, WHITE;

        BlockState state(StructureBlockInfo cell) {
            var original = cell.state();
            if (this == WHITE && original.is(Blocks.LANTERN)) {
                return Blocks.SEA_LANTERN.defaultBlockState();
            }
            return original;
        }
    }

    static boolean place(ServerLevel level, BlockPos site) {
        return place(level, site, Light.LANTERN);
    }

    static boolean place(ServerLevel level, BlockPos site, Light light) {
        var corner = site.offset(-CENTER_X, 0, -CENTER_Z);
        var cells = cells(level);
        for (var cell : cells) {
            var pos = corner.offset(cell.pos());
            if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.atY(site.getY() - 1)).isSolid()
                    || !level.isUnobstructed(light.state(cell), pos, CollisionContext.empty())) {
                return false;
            }
        }
        for (var cell : cells) {
            level.setBlock(corner.offset(cell.pos()), light.state(cell), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    static void remove(ServerLevel level, BlockPos site) {
        remove(level, site, Light.LANTERN);
    }

    static void remove(ServerLevel level, BlockPos site, Light light) {
        var corner = site.offset(-CENTER_X, 0, -CENTER_Z);
        for (var cell : cells(level)) {
            var pos = corner.offset(cell.pos());
            if (level.getBlockState(pos).equals(light.state(cell))
                    && !(level.getBlockEntity(pos) instanceof Container container && !container.isEmpty())) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    static void overturn(ServerLevel level, BlockPos site) {
        var counter = site.north();
        var state = level.getBlockState(counter);
        if (state.is(Blocks.SPRUCE_PLANKS)) {
            level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, counter, Block.getId(state));
        }
    }
}
