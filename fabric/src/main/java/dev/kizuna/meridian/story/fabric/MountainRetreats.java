package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

final class MountainRetreats extends SavedData {
    static final Identifier TEMPLATE = Identifier.parse("meridian_story:mountain_retreat");
    static final BlockPos MOUTH = new BlockPos(7, 1, 0);
    static final BlockPos ELDER = new BlockPos(6, 1, 12);
    static final BlockPos TABLE = new BlockPos(5, 1, 12);
    private static final BlockPos BACK = new BlockPos(7, 3, 19);
    private static final int REUSE = 96;
    private static final int PROBES = 12;
    private static final int SEARCH = 20;
    private static final int VERSION = 1;

    record Site(BlockPos mouth, Rotation direction, int version, String caseId) {
        static final Codec<Site> CODEC = RecordCodecBuilder.create(b -> b.group(
                BlockPos.CODEC.fieldOf("mouth").forGetter(Site::mouth),
                Codec.STRING.xmap(Rotation::valueOf, Rotation::name).fieldOf("rotation").forGetter(Site::direction),
                Codec.INT.fieldOf("template_version").forGetter(Site::version),
                Codec.STRING.fieldOf("case").forGetter(Site::caseId)
        ).apply(b, Site::new));

        BlockPos at(BlockPos local) {
            return mouth.offset(local.subtract(MOUTH).rotate(direction));
        }

        boolean walkable(ServerLevel level) {
            if (version != VERSION) {
                return false;
            }
            for (int depth = MOUTH.getZ(); depth <= ELDER.getZ(); depth++) {
                if (!stands(level, at(MOUTH.south(depth)))) {
                    return false;
                }
            }
            return level.isLoaded(at(TABLE))
                    && level.getBlockState(at(TABLE)).is(Blocks.CHISELED_STONE_BRICKS)
                    && stands(level, at(MOUTH.north()))
                    && stands(level, at(ELDER));
        }
    }

    private static final Codec<MountainRetreats> CODEC = RecordCodecBuilder.create(b -> b.group(
            Site.CODEC.listOf().fieldOf("sites").forGetter(data -> List.copyOf(data.byMouth.values())),
            UUIDUtil.CODEC.listOf().optionalFieldOf("departures", List.of())
                    .forGetter(data -> List.copyOf(data.departures))
    ).apply(b, MountainRetreats::new));
    private static final SavedDataType<MountainRetreats> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_story:mountain_retreats"), () -> new MountainRetreats(List.of(), List.of()),
            CODEC, null);
    private final Map<BlockPos, Site> byMouth = new HashMap<>();
    private final Set<UUID> departures;

    private MountainRetreats(List<Site> sites, List<UUID> departures) {
        sites.forEach(site -> byMouth.put(site.mouth(), site));
        this.departures = new HashSet<>(departures);
    }

    void depart(ServerLevel level, UUID actor) {
        if (level.getEntity(actor) != null) {
            HumanoidVisitors.depart(level, actor);
            if (departures.remove(actor)) {
                setDirty();
            }
        } else if (departures.add(actor)) {
            setDirty();
        }
    }

    void loaded(ServerLevel level, UUID actor) {
        if (departures.contains(actor)) {
            level.getServer().execute(() -> depart(level, actor));
        }
    }

    static MountainRetreats get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<Site> nearby(BlockPos feet) {
        return byMouth.values().stream().filter(site -> site.mouth().closerThan(feet, REUSE))
                .min(Comparator.comparingDouble(site -> feet.distSqr(site.mouth())));
    }

    Site site(long mouth) {
        return byMouth.get(BlockPos.of(mouth));
    }

    void assign(Site site, String owner) {
        byMouth.put(site.mouth(), new Site(site.mouth(), site.direction(), site.version(), owner));
        setDirty();
    }

    void release(long mouth, String owner) {
        var site = site(mouth);
        if (site != null && site.caseId().equals(owner)) {
            assign(site, "");
        }
    }

    void search(ServerPlayer player) {
        var level = player.level();
        for (int probe = 0; probe < PROBES; probe++) {
            double angle = Math.TAU * probe / PROBES;
            var column = player.blockPosition().offset((int) (SEARCH * Math.cos(angle)), 0,
                    (int) (SEARCH * Math.sin(angle)));
            if (!level.isLoaded(column)) {
                continue;
            }
            var feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            for (var direction : Rotation.values()) {
                var site = new Site(feet, direction, VERSION, "");
                if (place(player, site)) {
                    return;
                }
            }
        }
    }

    boolean place(ServerPlayer player, Site site) {
        var level = player.level();
        var back = site.at(BACK);
        if (nearby(site.mouth()).isPresent() || !level.isLoaded(back)
                || !FateSites.naturalGround(level.getBlockState(back)) || !stands(level, site.at(MOUTH.north()))) {
            return false;
        }
        var template = level.getStructureManager().get(TEMPLATE).orElseThrow();
        var cells = ((GraveTemplateAccessor) template).meridianStory$palettes().getFirst().blocks();
        if (cells.stream().anyMatch(cell -> !safe(player, site.at(cell.pos()), cell.pos().getY()))) {
            return false;
        }
        for (var cell : cells) {
            level.setBlock(site.at(cell.pos()), cell.state().rotate(site.direction()), Block.UPDATE_CLIENTS);
        }
        assign(site, "");
        return true;
    }

    private static boolean safe(ServerPlayer player, BlockPos pos, int layer) {
        var level = player.level();
        if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || level.getServer().isUnderSpawnProtection(level, pos, player)
                || !level.structureManager().getAllStructuresAt(pos).isEmpty() || level.getBlockEntity(pos) != null
                || !level.getFluidState(pos).isEmpty()) {
            return false;
        }
        var state = level.getBlockState(pos);
        if (layer == 0) {
            return FateSites.naturalGround(state) && level.getBlockState(pos.below()).isSolid();
        }
        return state.isAir() || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || FateSites.naturalGround(state);
    }

    private static final int PATH_BUDGET = 768;
    private static final int STEP_HEIGHT = 1;

    static boolean reachable(ServerLevel level, BlockPos start, BlockPos goal) {
        var seen = new java.util.HashSet<BlockPos>();
        var queue = new java.util.PriorityQueue<BlockPos>(Comparator.comparingDouble(goal::distSqr));
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < PATH_BUDGET) {
            var pos = queue.remove();
            if (pos.equals(goal)) {
                return true;
            }
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var adjacent = pos.relative(direction);
                for (int dy = -STEP_HEIGHT; dy <= STEP_HEIGHT; dy++) {
                    var next = adjacent.above(dy);
                    if (stands(level, next) && (dy <= 0 || level.getBlockState(pos.above(2)).isAir())
                            && (dy >= 0 || level.getBlockState(adjacent.above()).isAir()) && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }
        return false;
    }

    static boolean stands(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos.below()).isSolid()
                && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }
}
