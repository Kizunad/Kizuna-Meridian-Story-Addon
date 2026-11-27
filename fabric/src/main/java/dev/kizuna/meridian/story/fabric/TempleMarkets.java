package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.ParcelEscrow;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** 每维单独登记庙址。第一版的原点在西北角，入口朝南，前庭紧接南阶。 */
final class TempleMarkets extends SavedData {
    enum Phase { CLOSED, OPEN, RETIRING }
    enum Stall {
        RELICS(-5, "seller"), PARCELS(5, "buyer");
        final int offset;
        final String role;

        Stall(int offset, String role) {
            this.offset = offset;
            this.role = role;
        }
    }

    record Layout(BlockPos origin, BlockPos end, String entrance, int version) {
        private static final BlockPos EXTENT = new BlockPos(12, 6, 12);
        static final Codec<Layout> CODEC = RecordCodecBuilder.create(b -> b.group(
                BlockPos.CODEC.fieldOf("origin").forGetter(Layout::origin),
                BlockPos.CODEC.fieldOf("end").forGetter(Layout::end),
                Codec.STRING.fieldOf("entrance").forGetter(Layout::entrance),
                Codec.INT.fieldOf("template_version").forGetter(Layout::version)
        ).apply(b, Layout::new));

        static Layout fromAltar(BlockPos altar) {
            var base = altar.subtract(FateSites.ALTAR);
            return new Layout(base, base.offset(EXTENT), "south", 1);
        }
    }

    record Site(Layout layout, long dawn, Phase phase, List<UUID> banned, List<Long> dispersed) {
        private static final Codec<Site> CODEC = RecordCodecBuilder.create(b -> b.group(
                Layout.CODEC.fieldOf("temple").forGetter(Site::layout),
                Codec.LONG.fieldOf("dawn").forGetter(Site::dawn),
                Codec.STRING.xmap(Phase::valueOf, Phase::name).fieldOf("phase").forGetter(Site::phase),
                UUIDUtil.CODEC.listOf().fieldOf("refused").forGetter(Site::banned),
                Codec.LONG.listOf().fieldOf("dispersed").forGetter(Site::dispersed)
        ).apply(b, Site::new));

        BlockPos altar() {
            return layout.origin().offset(FateSites.ALTAR);
        }

        BlockPos yard() {
            return altar().below().south(YARD_DISTANCE);
        }

        BlockPos stall(Stall stall) {
            return yard().east(stall.offset);
        }

        UUID merchant(Stall stall) {
            return id(altar().asLong() + "/" + stall.role);
        }

        UUID visit() {
            return id(altar().asLong() + "/" + dawn);
        }

        Mob actor(ServerLevel level, Stall stall) {
            if (level.getEntity(merchant(stall)) instanceof Mob mob && mob.entityTags().contains(TAG)) {
                return mob;
            }
            return null;
        }

        Site retiring(Optional<UUID> striker) {
            Set<UUID> refused = new HashSet<>(banned);
            striker.ifPresent(refused::add);
            var nights = new java.util.ArrayList<>(dispersed);
            if (striker.isPresent()) {
                nights.add(dawn);
            }
            return new Site(layout, dawn, Phase.RETIRING, List.copyOf(refused), List.copyOf(nights));
        }
    }

    static final String TAG = "meridian_story.midnight_trader";
    private static final float FACE_NORTH = 180.0F;
    private static final int REUSE = 160;
    private static final int YARD_DISTANCE = 13;
    private static final int HALF_WIDTH = 8;
    private static final int HALF_DEPTH = 2;
    private static final int HEIGHT = 5;
    // 庙址版本确定模板的方向、尺寸与香案偏移；以后生成的同版结构沿用此登记。
    private static final SavedDataType<TempleMarkets> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_story:temple_markets_v1"), () -> new TempleMarkets(List.of()),
            Site.CODEC.listOf().xmap(TempleMarkets::new, data -> List.copyOf(data.sites.values())), null);
    private final Map<BlockPos, Site> sites = new HashMap<>();
    private final Set<BlockPos> active = new HashSet<>();

    private TempleMarkets(List<Site> saved) {
        saved.forEach(this::index);
    }

    static TempleMarkets get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("meridian_story:midnight/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private void index(Site site) {
        sites.put(site.altar(), site);
        if (site.phase() == Phase.CLOSED) {
            active.remove(site.altar());
        } else {
            active.add(site.altar());
        }
    }

    private void save(Site site) {
        index(site);
        setDirty();
    }

    Site site(BlockPos altar) {
        return sites.get(altar);
    }

    void register(BlockPos altar) {
        if (!sites.containsKey(altar)) {
            save(new Site(Layout.fromAltar(altar), Long.MIN_VALUE, Phase.CLOSED, List.of(), List.of()));
        }
    }

    void adopt(ServerLevel level, BlockPos altar) {
        if (!sites.containsKey(altar) && intact(level, altar)) {
            register(altar);
        }
    }

    static boolean intact(ServerLevel level, BlockPos altar) {
        return level.isLoaded(altar) && level.getBlockState(altar.north().above()).is(Blocks.LANTERN)
                && level.getBlockState(altar.north()).is(Blocks.DARK_OAK_SLAB);
    }

    Optional<BlockPos> nearby(BlockPos pos) {
        return sites.keySet().stream().filter(altar -> altar.closerThan(pos, REUSE))
                .min(Comparator.comparingDouble(pos::distSqr));
    }

    static boolean room(ServerPlayer player, BlockPos base) {
        var level = player.level();
        var center = base.offset(FateSites.ALTAR).below().south(YARD_DISTANCE);
        var start = center.offset(-HALF_WIDTH, 0, -HALF_DEPTH - 1);
        var end = center.offset(HALF_WIDTH, 0, HALF_DEPTH);
        for (var floor : BlockPos.betweenClosed(start, end)) {
            if (!level.isLoaded(floor) || !level.getWorldBorder().isWithinBounds(floor)
                    || level.getServer().isUnderSpawnProtection(level, floor, player)
                    || !FateSites.naturalGround(level.getBlockState(floor.below()))
                    || !level.getFluidState(floor.below()).isEmpty()
                    || !level.structureManager().getAllStructuresAt(floor).isEmpty()) {
                return false;
            }
            for (int y = 0; y < HEIGHT; y++) {
                if (!level.getBlockState(floor.above(y)).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    Site open(ServerPlayer player, Site old) {
        var level = player.level();
        long clock = level.getOverworldClockTime();
        long dawn = ParcelEscrow.nextDawn(clock);
        if (!MidnightMarket.trading(clock, dawn) || !old.equals(sites.get(old.altar()))) {
            return null;
        }
        if (old.phase() == Phase.OPEN && old.dawn() == dawn) {
            return old;
        }
        if (old.phase() != Phase.CLOSED || old.dawn() >= dawn || !intact(level, old.altar())
                || !room(player, old.layout().origin())) {
            return null;
        }
        var site = new Site(old.layout(), dawn, Phase.OPEN, old.banned(), old.dispersed());
        if (!MarketStall.place(level, site.stall(Stall.RELICS), MarketStall.Light.WHITE)) {
            return null;
        }
        if (!MarketStall.place(level, site.stall(Stall.PARCELS), MarketStall.Light.WHITE)) {
            MarketStall.remove(level, site.stall(Stall.RELICS), MarketStall.Light.WHITE);
            return null;
        }
        save(site);
        for (var stall : Stall.values()) {
            if (!arrive(level, site, stall)) {
                close(level, site, Optional.empty());
                return null;
            }
        }
        return site;
    }

    private static boolean arrive(ServerLevel level, Site site, Stall stall) {
        var form = new HumanoidVisitors.Guest<>(EntityTypes.WANDERING_TRADER, HumanoidTier.TRUE_QI);
        var guest = HumanoidVisitors.arrive(level, site.stall(stall), site.merchant(stall),
                Component.translatable(MidnightMarket.KEY + stall.role), form);
        if (guest.isEmpty()) {
            return false;
        }
        var actor = guest.get();
        actor.addTag(TAG);
        actor.setYRot(FACE_NORTH);
        actor.setYHeadRot(FACE_NORTH);
        try {
            CurrencyService.get(level.getServer()).parcels().arrive(actor, site.visit());
            if (stall == Stall.RELICS) {
                return MarketOrders.stock(actor, site);
            }
            return true;
        } catch (TradeRejected rejected) {
            return false;
        }
    }

    void tick(ServerLevel level) {
        long clock = level.getOverworldClockTime();
        for (var altar : Set.copyOf(active)) {
            var site = sites.get(altar);
            if (site.phase() == Phase.RETIRING || !MidnightMarket.trading(clock, site.dawn())) {
                close(level, site, Optional.empty());
            }
        }
    }

    void close(ServerLevel level, Site site, Optional<UUID> striker) {
        if (site.phase() == Phase.OPEN) {
            save(site.retiring(striker));
        }
        for (var stall : Stall.values()) {
            if (!level.isPositionEntityTicking(site.stall(stall)) && site.actor(level, stall) == null) {
                return;
            }
        }
        for (var stall : Stall.values()) {
            var actor = site.actor(level, stall);
            if (actor != null) {
                try {
                    CurrencyService.get(level.getServer()).parcels().depart(actor, site.visit());
                } catch (TradeRejected rejected) {
                    return;
                }
                HumanoidVisitors.depart(level, actor.getUUID());
            }
            MarketStall.remove(level, site.stall(stall), MarketStall.Light.WHITE);
        }
        var retiring = sites.get(site.altar());
        save(new Site(site.layout(), site.dawn(), Phase.CLOSED, retiring.banned(), retiring.dispersed()));
    }
}
