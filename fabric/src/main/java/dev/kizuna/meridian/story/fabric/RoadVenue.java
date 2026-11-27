package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.shapes.CollisionContext;

final class RoadVenue extends SavedData {
    static final BlockPos CRATE = new BlockPos(0, 1, 0);
    static final BlockPos BANK = new BlockPos(-5, 0, 2);
    static final BlockPos FORK = new BlockPos(7, 0, 3);
    static final BlockPos TABLE = new BlockPos(6, 0, 0);
    static final BlockPos POST = new BlockPos(-5, 1, 4);
    static final int PLATFORM = 4;
    static final int APPROACH = 8;
    private static final int CART_X = 8;
    private static final int CART_Z = 5;
    private static final int RING_X = 9;
    private static final int RING_Z = 8;
    private static final int CLEARANCE = 3;
    private static final int REUSE_RADIUS = 14;
    private static final String VACANT = "";

    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level instanceof ServerLevel server && get(server).reserved(hit.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
                !(level instanceof ServerLevel server) || !get(server).reserved(pos));
    }

    private boolean reserved(BlockPos pos) {
        String cart = claims.get(key(pos.subtract(CRATE), EscortCart.KIND));
        String ring = claims.get(key(pos.subtract(TABLE), VillageRing.KIND));
        return cart != null && !cart.isEmpty() || ring != null && !ring.isEmpty();
    }
    private static final Codec<RoadVenue> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(RoadVenue::new, venue -> venue.claims);
    private static final SavedDataType<RoadVenue> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_story:road_venues"), () -> new RoadVenue(Map.of()), CODEC, null);
    private final Map<String, String> claims;

    private RoadVenue(Map<String, String> claims) {
        this.claims = new HashMap<>(claims);
    }

    static RoadVenue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static List<StructureBlockInfo> cells(ServerLevel level, String kind) {
        var template = level.getStructureManager().get(Identifier.fromNamespaceAndPath("meridian_story", kind))
                .orElseThrow();
        return ((GraveTemplateAccessor) template).meridianStory$palettes().getFirst().blocks();
    }

    private static BlockPos corner(BlockPos center, String kind) {
        if (kind.equals(VillageRing.KIND)) {
            return center.offset(-RING_X, 0, -RING_Z);
        }
        return center.offset(-CART_X, 0, -CART_Z);
    }

    Optional<BlockPos> nearbyRing(BlockPos approach) {
        String prefix = VillageRing.KIND + "/";
        return claims.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .map(e -> BlockPos.of(Long.parseLong(e.getKey().substring(prefix.length()))))
                .filter(pos -> pos.distSqr(approach) <= REUSE_RADIUS * REUSE_RADIUS)
                .min(java.util.Comparator.comparingDouble(approach::distSqr));
    }

    boolean available(ServerLevel level, BlockPos center, String kind) {
        var start = corner(center, kind);
        String owner = claims.get(key(center, kind));
        if (owner != null) {
            return owner.isEmpty() && kind.equals(VillageRing.KIND) && cells(level, kind).stream().allMatch(cell -> {
                var pos = start.offset(cell.pos());
                return level.isLoaded(pos) && level.getBlockState(pos).equals(cell.state());
            });
        }
        var end = center.offset(center.getX() - start.getX(), 0, center.getZ() - start.getZ());
        for (var floor : BlockPos.betweenClosed(start, end)) {
            if (!level.isLoaded(floor) || !level.getBlockState(floor.below()).isSolid()) {
                return false;
            }
            for (int y = 0; y < CLEARANCE; y++) {
                var pos = floor.above(y);
                var state = level.getBlockState(pos);
                if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) {
                    return false;
                }
            }
        }
        return cells(level, kind).stream().allMatch(cell -> {
            var pos = start.offset(cell.pos());
            var state = level.getBlockState(pos);
            return state.canBeReplaced() && state.getFluidState().isEmpty()
                    && level.isUnobstructed(cell.state(), pos, CollisionContext.empty());
        });
    }

    boolean place(ServerLevel level, BlockPos center, String kind, String owner) {
        if (!available(level, center, kind)) {
            return false;
        }
        var key = key(center, kind);
        if (!claims.containsKey(key)) {
            var start = corner(center, kind);
            for (var cell : cells(level, kind)) {
                level.setBlock(start.offset(cell.pos()), cell.state(), Block.UPDATE_CLIENTS);
            }
        }
        claims.put(key, owner);
        setDirty();
        return true;
    }

    void release(ServerLevel level, BlockPos center, String kind, String owner) {
        var key = key(center, kind);
        if (!claims.remove(key, owner)) {
            return;
        }
        if (kind.equals(VillageRing.KIND)) {
            claims.put(key, VACANT);
        } else {
            var start = corner(center, kind);
            for (var cell : cells(level, kind)) {
                var pos = start.offset(cell.pos());
                if (level.getBlockState(pos).equals(cell.state())
                        && !(level.getBlockEntity(pos) instanceof Container box && !box.isEmpty())) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        setDirty();
    }

    private static String key(BlockPos center, String kind) {
        return kind + "/" + center.asLong();
    }
}
