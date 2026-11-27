package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.fabric.TempleMarkets.Site;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Stall;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.MarketVisit;
import dev.kizuna.meridian.story.server.RoadsideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

final class MidnightScene {
    private static final int TEMPLE_OFFSET = 42;
    private static final int MARGIN = 12;
    private static final int LIFT = 2;
    private final ServerPlayer player;
    final StoryStore store = new StoryStore();
    private final MidnightMarket tracker = new MidnightMarket(store);
    final BlockPos altar;
    Site site;

    enum Source { REGISTERED, LEGACY }
    private static final int HALF_TEMPLE = 6;

    MidnightScene(ServerPlayer player, BlockPos column) {
        this(player, column, Source.REGISTERED);
    }

    MidnightScene(ServerPlayer player, BlockPos column, Source source) {
        this.player = player;
        store.ready(player, "");
        var ground = new RoadScene(player);
        ground.ground(column);
        var level = player.level();
        var origin = player.blockPosition();
        var target = origin.east(TEMPLE_OFFSET).above(LIFT);
        for (var feet : BlockPos.betweenClosed(target.offset(-MARGIN, 0, -MARGIN),
                target.offset(MARGIN, 0, MARGIN * 2))) {
            level.getChunkAt(feet);
            level.setBlockAndUpdate(feet.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        if (source == Source.LEGACY) {
            var base = target.offset(-HALF_TEMPLE, 0, -HALF_TEMPLE);
            altar = legacy(base);
            site = new Site(TempleMarkets.Layout.fromAltar(altar), Long.MIN_VALUE,
                    TempleMarkets.Phase.CLOSED, java.util.List.of(), java.util.List.of());
        } else {
            altar = FateSites.placeTemple(player, base -> TempleMarkets.room(player, base))
                    .orElseThrow(() -> new IllegalStateException("测试前庭未能放庙 " + origin));
            site = TempleMarkets.get(level).site(altar);
        }
        move(site.yard());
        player.getInventory().add(new ItemStack(Items.AMETHYST_SHARD));
    }

    private BlockPos legacy(BlockPos base) {
        var level = player.level();
        var template = level.getStructureManager().get(FateSites.TEMPLE).orElseThrow();
        var accessor = (dev.kizuna.meridian.story.fabric.mixin.GraveTemplateAccessor) template;
        for (var cell : accessor.meridianStory$palettes().getFirst().blocks()) {
            level.setBlockAndUpdate(base.offset(cell.pos()), cell.state());
        }
        var old = base.offset(FateSites.ALTAR);
        store.change(player, view -> {
            var fate = view.progress().getFate().toBuilder().setTemple(old.asLong()).build();
            return view.progress().toBuilder().setFate(fate).build();
        });
        return old;
    }

    void move(BlockPos pos) {
        var feet = Vec3.atBottomCenterOf(pos);
        player.teleportTo(feet.x, feet.y, feet.z);
        player.level().getChunkSource().move(player);
        player.setOnGround(true /* onGround */);
    }

    void clock(long ticks) {
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set " + ticks);
    }

    void midnight(MarketOutcome outcome) {
        long dawn = MidnightMarket.DAY;
        while (!matches(dawn, outcome)) {
            dawn += MidnightMarket.DAY;
        }
        clock(dawn - MidnightMarket.DAY + MidnightMarket.MIDNIGHT);
    }

    enum MarketOutcome { SILVER, PAPER, CURSED }

    private boolean matches(long dawn, MarketOutcome outcome) {
        var night = new Site(site.layout(), dawn, TempleMarkets.Phase.OPEN, java.util.List.of(), java.util.List.of());
        var key = java.util.UUID.fromString(MidnightMarket.key(player, night));
        var seed = FallenCultivator.seed(player);
        if (outcome == MarketOutcome.CURSED) {
            return RoadsideRules.roll(seed, key.getMostSignificantBits()) >= MidnightMarket.TRUE_RELIC;
        }
        boolean paper = RoadsideRules.roll(seed, key.getLeastSignificantBits()) >= MidnightMarket.TRUE_SEAL;
        return paper == (outcome == MarketOutcome.PAPER);
    }

    void invite() {
        var markets = TempleMarkets.get(player.level());
        site = markets.open(player, markets.site(altar));
        if (site == null) {
            throw new IllegalStateException("庙前未能开市 " + altar);
        }
        StoryChat.market.invite(player, store.read(player).orElseThrow(), site);
    }

    void approach(Stall stall) {
        move(site.stall(stall).north(3));
    }

    MarketVisit record() {
        return store.read(player).orElseThrow().progress().getMarketVisitsOrThrow(MidnightMarket.key(player, site));
    }

    boolean click(int option, long revision) {
        var choice = new StoryChat.Selection(MidnightMarket.key(player, site), revision, option);
        return StoryChat.market.choose(player, choice).isPresent();
    }

    void tick() {
        tracker.tick(player, store.read(player).orElseThrow());
    }
}
