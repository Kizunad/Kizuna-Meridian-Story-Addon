package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.MidnightScene.MarketOutcome;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Stall;
import dev.kizuna.meridian.inventory.MobInventories;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.MarketVisit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

public final class MidnightRestartGameTest {
    private static final int TIMEOUT = 600;
    private static final int AWAY = 32;
    private static final int SPACING = 512;
    private static final BlockPos FIELD = new BlockPos(7056, 0, 7056);

    @GameTest(maxTicks = TIMEOUT)
    public void sealedSilverSurvivesServerRestart(GameTestHelper test) throws IOException {
        verify(test, MarketOutcome.SILVER);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void paperAndMerchantRefundSurviveServerRestart(GameTestHelper test) throws IOException {
        verify(test, MarketOutcome.PAPER);
    }

    private static void verify(GameTestHelper test, MarketOutcome outcome) throws IOException {
        Path note = Path.of("midnight-" + outcome.name() + ".txt");
        if ("save".equals(System.getProperty("meridian.story.restart"))) {
            var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
            PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                    test.assertTrue(CrisisEncounters.current(owner.player()).isPresent(), "等待身体")).thenExecute(() -> {
                try (owner) {
                    var scene = new MidnightScene(owner.player(), FIELD.east(outcome.ordinal() * SPACING));
                    scene.midnight(outcome);
                    scene.invite();
                    scene.approach(Stall.PARCELS);
                    var stock = scene.site.actor(test.getLevel(), Stall.PARCELS);
                    int before = fragments(stock);
                    scene.click(MarketOrders.SELL, scene.site.dawn());
                    test.assertTrue(scene.record().getSold(), "停服前未成交");
                    Files.write(note, List.of(owner.player().getStringUUID(),
                            MidnightMarket.key(owner.player(), scene.site), Integer.toString(before),
                            Long.toString(test.getLevel().getGameTime())));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).thenSucceed();
            return;
        }
        var saved = Files.readAllLines(note);
        var owner = new StoryPlayer[1];
        test.startSequence().thenWaitUntil(() -> test.assertTrue(
                test.getLevel().getGameTime() > Long.parseLong(saved.getLast()), "等待原服时钟"))
                .thenExecute(() -> owner[0] = StoryPlayer.join(test, UUID.fromString(saved.getFirst())))
                .thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(owner[0].player()).isPresent(), "等身体"))
                .thenExecute(() -> {
                    var p = owner[0].player();
                    new RoadScene(p).ground(BlockPos.of(visit(p, saved.get(1)).getAltar()));
                }).thenWaitUntil(() -> {
                    var p = owner[0].player();
                    var record = visit(p, saved.get(1));
                    var markets = TempleMarkets.get(p.level());
                    test.setTime(record.getDawn());
                    markets.tick(p.level());
                    test.assertTrue(markets.site(BlockPos.of(record.getAltar())).phase() == TempleMarkets.Phase.CLOSED,
                            "等待原址区块载入并撤摊");
                }).thenExecute(() -> {
                    try (var guest = owner[0]) {
                        restore(test, guest, saved.get(1), outcome);
                        verifyMerchant(test, guest, saved, outcome);
                    }
                }).thenSucceed();
    }

    private static MarketVisit visit(ServerPlayer player, String key) {
        var store = new StoryStore();
        store.ready(player, "");
        return store.read(player).orElseThrow().progress().getMarketVisitsOrThrow(key);
    }

    private static int fragments(Mob mob) {
        var fragment = VenueScene.coins("silver_fragment", 1).getItem();
        return MobInventories.open(mob).snapshot().stream().filter(s -> s.is(fragment))
                .mapToInt(ItemStack::getCount).sum();
    }

    private static void verifyMerchant(GameTestHelper test, StoryPlayer owner, List<String> saved,
                                       MarketOutcome outcome) {
        var player = owner.player();
        var visit = visit(player, saved.get(1));
        var markets = TempleMarkets.get(player.level());
        var site = markets.site(BlockPos.of(visit.getAltar()));
        test.assertTrue(site.phase() == TempleMarkets.Phase.CLOSED, "重开后原摊未撤");
        test.setTime(visit.getDawn() + MidnightMarket.MIDNIGHT);
        var reopened = markets.open(player, site);
        test.assertTrue(reopened != null, "原摊主未能次夜再来");
        var buyer = reopened.actor(player.level(), Stall.PARCELS);
        int before = Integer.parseInt(saved.get(2));
        int expected = before;
        if (outcome == MarketOutcome.SILVER) {
            expected = before - MarketOrders.SILVER;
        }
        test.assertTrue(fragments(buyer) == expected && MobInventories.open(buyer).snapshot().stream()
                .anyMatch(stack -> stack.is(Items.AMETHYST_SHARD)), "收货人重返后账货不符");
    }

    private static void restore(GameTestHelper test, StoryPlayer owner, String key, MarketOutcome outcome) {
        var p = owner.player();
        p.setGameMode(GameType.SURVIVAL);
        var store = new StoryStore();
        store.ready(p, "");
        var visit = store.read(p).orElseThrow().progress().getMarketVisitsOrThrow(key);
        test.assertTrue(visit.getSold() && !visit.getOpened(), "重启丢失原订单");
        var markets = TempleMarkets.get(p.level());
        var site = markets.site(BlockPos.of(visit.getAltar()));
        test.assertTrue(site != null && site.dawn() == visit.getDawn(), "庙址或夜次未恢复");
        var origin = site.yard();
        test.setTime(visit.getDawn());
        markets.tick(p.level());
        var far = origin.east(AWAY);
        p.teleportTo(far.getX(), far.getY(), far.getZ());
        StoryChat.market.choose(p, new StoryChat.Selection(key, visit.getSerial(), MarketOrders.OPEN));
        var after = store.read(p).orElseThrow().progress().getMarketVisitsOrThrow(key);
        test.assertTrue(after.getOpened() && after.getPaper() == (outcome == MarketOutcome.PAPER), "异地晨验错单");
        var item = VenueScene.coins("silver_fragment", 1).getItem();
        if (outcome == MarketOutcome.PAPER) {
            item = Items.PAPER;
        }
        test.assertTrue(p.getInventory().countItem(item) > 0, "缺晨验原物");
    }
}
