package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.fabric.MeridianCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

final class GraveSite extends SavedData {
    static final Identifier TEMPLATE = Identifier.parse("meridian_story:sword_grave");
    static final BlockPos STONE = new BlockPos(12, 1, 10);
    static final BlockPos GUEST = new BlockPos(12, 1, 15);
    private static final int WIDTH = 25;
    private static final int DEPTH = 23;
    private static final int HEIGHT = 8;
    private static final int FIRST_RADIUS = 32;
    private static final int LAST_RADIUS = 128;
    private static final int SITES = 80;
    private static final int OUTER_RADIUS = 192;
    private static final int BATCH = 128;
    private static final int VERSION = 1;
    private static final int RETRY_DELAY = 200;
    private static final Codec<GraveSite> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            BlockPos.CODEC.optionalFieldOf("north_west").forGetter(site -> site.base),
            Codec.BOOL.fieldOf("complete").forGetter(site -> site.complete),
            Codec.intRange(VERSION, VERSION).fieldOf("template_version").forGetter(site -> VERSION),
            UUIDUtil.CODEC.optionalFieldOf("host").forGetter(site -> site.host),
            UUIDUtil.CODEC.optionalFieldOf("actor").forGetter(site -> site.actor)
    ).apply(builder, GraveSite::new));
    static final SavedDataType<GraveSite> TYPE = new SavedDataType<>(TEMPLATE, GraveSite::new, CODEC, null);
    private Optional<BlockPos> base;
    private boolean complete;
    private Optional<UUID> host;
    private Optional<UUID> actor;
    private int cursor;
    private int attempt;
    private long nextTick;
    private List<StructureBlockInfo> blocks;

    GraveSite() {
        this(Optional.empty(), false /* complete */, VERSION, Optional.empty(), Optional.empty());
    }

    private GraveSite(Optional<BlockPos> base, boolean complete, int version,
                      Optional<UUID> host, Optional<UUID> actor) {
        this.host = host;
        this.actor = actor;
        this.base = base;
        this.complete = complete;
    }

    static GraveSite get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<BlockPos> anchor() {
        return base.filter(ignored -> complete).map(pos -> pos.offset(STONE));
    }

    BlockPos guest() {
        return base.orElseThrow().offset(GUEST);
    }

    boolean receive(ServerLevel level, UUID owner, UUID visit) {
        // 重新打开世界时，离线玩家占用的席位也可释放，尚未结算的签仍随本人保存。
        if (host.isPresent() && level.getServer().getPlayerList().getPlayer(host.get()) == null) {
            release(level, host.get());
        }
        if (host.isPresent()) {
            return host.get().equals(owner) && actor.filter(visit::equals).isPresent();
        }
        host = Optional.of(owner);
        actor = Optional.of(visit);
        setDirty();
        return true;
    }

    void release(ServerLevel level, UUID owner) {
        if (host.filter(owner::equals).isEmpty()) {
            return;
        }
        level.getChunkAt(guest());
        actor.ifPresent(visit -> HumanoidVisitors.depart(level, visit));
        host = Optional.empty();
        actor = Optional.empty();
        setDirty();
    }

    void advance(ServerLevel level) {
        long tick = level.getGameTime();
        if (complete || tick < nextTick) {
            return;
        }
        nextTick = tick + 1;
        if (base.isEmpty()) {
            int sequence = attempt++ % (SITES + SITES);
            if (!reserve(level, candidate(level, sequence))) {
                if (sequence == SITES + SITES - 1) {
                    nextTick = tick + RETRY_DELAY;
                }
            }
            return;
        }
        var origin = base.orElseThrow();
        var cells = template(level);
        int end = Math.min(cursor + BATCH, cells.size());
        for (; cursor < end; cursor++) {
            var cell = cells.get(cursor);
            var pos = origin.offset(cell.pos());
            var state = level.getBlockState(pos);
            if (cell.state().equals(state)) {
                continue;
            }
            if (!replaceable(level, pos, cell.pos().getY(), state)) {
                nextTick = tick + RETRY_DELAY;
                return;
            }
            level.setBlock(pos, cell.state(), Block.UPDATE_CLIENTS);
        }
        if (cursor == cells.size()) {
            complete = true;
            setDirty();
        }
    }

    private List<StructureBlockInfo> template(ServerLevel level) {
        if (blocks == null) {
            var structure = level.getStructureManager().get(TEMPLATE).orElseThrow();
            var palettes = ((GraveTemplateAccessor) structure).meridianStory$palettes();
            blocks = palettes.getFirst().blocks().stream()
                    .sorted(Comparator.comparingInt(cell -> cell.pos().getY()))
                    .toList();
        }
        return blocks;
    }

    private static BlockPos candidate(ServerLevel level, int sequence) {
        var spawn = level.getRespawnData().globalPos().pos();
        var random = RandomSource.create(level.getSeed() ^ Integer.toUnsignedLong(sequence) * 0x9E3779B9L);
        int lo = FIRST_RADIUS;
        int hi = LAST_RADIUS;
        if (sequence >= SITES) {
            lo = LAST_RADIUS;
            hi = OUTER_RADIUS;
        }
        double angle = random.nextDouble() * Math.TAU;
        int radius = random.nextIntBetweenInclusive(lo + 1, hi - 1);
        int x = spawn.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * radius);
        // 高度图在区块未载入时只返回世界底；只加载本次候选的区块再验地形。
        var chunk = level.getChunkAt(new BlockPos(x, spawn.getY(), z));
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        return new BlockPos(x - STONE.getX(), y - 1, z - STONE.getZ());
    }

    static void registerCommand() {
        MeridianCommands.register(MeridianCommands.Branch.DEBUG, "grave", MeridianCommands.GAMEMASTER,
                (node, registry) -> node.then(Commands.literal("reserve")
                        .then(Commands.argument("north_west", BlockPosArgument.blockPos())
                                .executes(context -> reserveAt(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "north_west"))))));
    }

    private static int reserveAt(CommandSourceStack source, BlockPos base) {
        var level = source.getLevel();
        if (level != level.getServer().overworld() || !get(level).reserve(level, base)) {
            source.sendFailure(Component.literal("冢址已定或地面不宜"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已预留冢址"), false /* broadcastToOps */);
        return 1;
    }

    boolean reserve(ServerLevel level, BlockPos origin) {
        // 先查整块场地的支撑、净空与已有结构，再独占坐标。续建只补仍可替换的格子。
        if (base.isPresent() || !level.getWorldBorder().isWithinBounds(origin)
                || !level.getWorldBorder().isWithinBounds(origin.offset(WIDTH - 1, HEIGHT, DEPTH - 1))
                || level.isOutsideBuildHeight(origin.below()) || level.isOutsideBuildHeight(origin.above(HEIGHT))) {
            return false;
        }
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                var floor = origin.offset(x, 0, z);
                if (!natural(level.getBlockState(floor.below())) || !level.structureManager()
                        .getAllStructuresAt(floor).isEmpty() || !openColumn(level, floor)) {
                    return false;
                }
            }
        }
        base = Optional.of(origin.immutable());
        setDirty();
        return true;
    }

    private static boolean openColumn(ServerLevel level, BlockPos floor) {
        for (int y = 0; y < HEIGHT; y++) {
            var pos = floor.above(y);
            if (!replaceable(level, pos, y, level.getBlockState(pos))) {
                return false;
            }
        }
        return true;
    }

    private static boolean replaceable(ServerLevel level, BlockPos pos, int y, BlockState state) {
        if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.isAir() || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || y == 0 && natural(state);
    }

    private static boolean natural(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
    }
}
