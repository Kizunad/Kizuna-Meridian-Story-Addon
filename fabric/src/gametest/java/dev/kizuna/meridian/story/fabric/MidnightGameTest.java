package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.MidnightScene.MarketOutcome;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Stall;
import dev.kizuna.meridian.undead.fabric.NightVisits;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.stats.Stats;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public final class MidnightGameTest {
    private static final BlockPos REGION = new BlockPos(7856, 0, 7856);
    private static final int SPACING = 256;
    private static final int TIMEOUT = 600;
    private static final int AWAY = 16;
    private static final int SECOND = 20;
    private static final String VISITOR_ID_PREFIX = "meridian_undead:night_visit/";

    private record Run(GameTestHelper test, StoryPlayer owner, MidnightScene scene) {
        void expect(boolean passed, String message) {
            test.assertTrue(passed, message);
        }

        long count(ItemStack target) {
            var player = owner.player();
            return CurrencyService.get(player.level().getServer()).player(player).contents().stream()
                    .filter(stack -> stack.is(target.getItem())).mapToLong(ItemStack::getCount).sum();
        }
    }

    private static void run(GameTestHelper test, int lane, MarketOutcome outcome, Consumer<Run> exercise) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY).thenWaitUntil(() ->
                test.assertTrue(CrisisEncounters.current(owner.player()).isPresent(), "等待身体"))
                .thenExecute(() -> {
                    long before = test.getLevel().getOverworldClockTime();
                    try (owner) {
                        var scene = new MidnightScene(owner.player(), REGION.east(lane * SPACING));
                        scene.midnight(outcome);
                        scene.invite();
                        exercise.accept(new Run(test, owner, scene));
                    } catch (RuntimeException failure) {
                        test.fail(failure.toString());
                    } finally {
                        test.setTime(before);
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight")
    public void trueParcelOpensAwayAfterReplacement(GameTestHelper test) {
        run(test, 0, MarketOutcome.SILVER, r -> redeem(r, MarketOutcome.SILVER));
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight")
    public void falseParcelOpensAwayAfterRetirement(GameTestHelper test) {
        run(test, 1, MarketOutcome.PAPER, r -> redeem(r, MarketOutcome.PAPER));
    }

    private static void redeem(Run r, MarketOutcome outcome) {
        var s = r.scene();
        var p = r.owner().player();
        s.approach(Stall.PARCELS);
        var silver = VenueScene.coins("silver_fragment", 1);
        long before = r.count(silver);
        r.expect(s.click(MarketOrders.SELL, s.site.dawn()) && s.record().getSold(), "收货摊未签发订单");
        r.expect(r.count(silver) == before, "封银成了当夜可花的钱");
        r.expect(!s.record().getOpened(), "未晨验就结清");
        s.click(MarketOrders.OPEN, 0);
        r.expect(!s.record().getOpened(), "当夜拆开了银包");
        var bag = p.getInventory();
        int slot = bag.findSlotMatchingItem(bag.getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.RABBIT_HIDE)).findFirst().orElseThrow());
        var old = bag.removeItemNoUpdate(slot);
        s.click(MarketOrders.REISSUE, 0);
        r.expect(s.record().getSerial() == 1, "未挂失补发新包");
        bag.add(old);
        s.clock(s.site.dawn());
        TempleMarkets.get(p.level()).tick(p.level());
        s.move(s.site.yard().south(AWAY));
        s.tick();
        s.click(MarketOrders.OPEN, 0);
        r.expect(!s.record().getOpened(), "旧签仍可核销");
        s.click(MarketOrders.OPEN, 1);
        r.expect(s.record().getClosed() && s.record().getOpened(), "异地拆封未结原订单");
        s.click(MarketOrders.OPEN, 1);
        if (outcome == MarketOutcome.SILVER) {
            r.expect(r.count(silver) == before + MarketOrders.SILVER, "真包没有恰付一次原银");
        } else {
            r.expect(r.count(silver) == before && r.count(new ItemStack(Items.PAPER)) == 1, "假包兑现有误");
        }
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight")
    public void attackClosesBothStallsAndRefusesOnlyStriker(GameTestHelper test) {
        var other = StoryPlayer.join(test, UUID.randomUUID());
        run(test, 2, MarketOutcome.SILVER, r -> {
            var s = r.scene();
            var p = r.owner().player();
            var level = p.level();
            var markets = TempleMarkets.get(level);
            var site = s.site;
            try (other) {
                var witness = other.player();
                witness.setGameMode(GameType.SURVIVAL);
                witness.setPos(p.position());
                s.store.ready(witness, "");
                var view = s.store.read(witness).orElseThrow();
                StoryChat.market.invite(witness, view, site);
                var witnessKey = MidnightMarket.key(witness, site);
                r.expect(s.store.read(witness).orElseThrow().progress().containsMarketVisits(witnessKey),
                        "同一双摊未接待第二位客人");
                s.approach(Stall.RELICS);
                var seller = site.actor(level, Stall.RELICS);
                AttackEntityCallback.EVENT.invoker().interact(p, level, InteractionHand.MAIN_HAND, seller, null);
                var closed = markets.site(s.altar);
                r.expect(closed.phase() == TempleMarkets.Phase.CLOSED
                                && closed.banned().equals(java.util.List.of(p.getUUID())),
                        "没有只封动手者");
                r.expect(site.actor(level, Stall.PARCELS) == null && site.actor(level, Stall.RELICS) == null,
                        "散市仍有演员");
                s.clock(site.dawn() + MidnightMarket.MIDNIGHT);
                StoryChat.market.tick(witness, s.store.read(witness).orElseThrow());
                var next = markets.open(witness, closed);
                r.expect(next != null, "次夜没有重开双摊");
                StoryChat.market.invite(witness, s.store.read(witness).orElseThrow(), next);
                StoryChat.market.invite(p, s.store.read(p).orElseThrow(), next);
                var invited = s.store.read(witness).orElseThrow().progress();
                r.expect(invited.containsMarketVisits(MidnightMarket.key(witness, next))
                        && !s.store.read(p).orElseThrow().progress().containsMarketVisits(MidnightMarket.key(p, next)),
                        "次夜拒市误伤旁人或放行动手者");
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight")
    public void dawnExpiresPriceAndKeepsEditedStall(GameTestHelper test) {
        run(test, 3, MarketOutcome.SILVER, r -> {
            var s = r.scene();
            var p = r.owner().player();
            s.approach(Stall.PARCELS);
            var counter = s.site.stall(Stall.PARCELS).north();
            p.level().setBlockAndUpdate(counter, Blocks.GOLD_BLOCK.defaultBlockState());
            s.clock(s.site.dawn());
            p.getInventory().add(VenueScene.coins("copper_cash", MarketOrders.RELIC_PRICE));
            s.approach(Stall.RELICS);
            r.expect(!s.click(MarketOrders.BUY, s.site.dawn()) && !s.record().getBought(), "黎明仍按旧报价卖货");
            s.approach(Stall.PARCELS);
            r.expect(!s.click(MarketOrders.SELL, s.site.dawn()) && !s.record().getSold(), "黎明仍按旧报价收货");
            TempleMarkets.get(p.level()).tick(p.level());
            r.expect(p.level().getBlockState(counter).is(Blocks.GOLD_BLOCK), "撤摊拆了玩家改建");
            r.expect(!TempleMarkets.room(p, s.site.layout().origin()), "被占前庭仍判为双摊可用");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight")
    public void cursedRelicFollowsItsWalkingOwnerAndRebinds(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        var p = owner.player();
        var prepared = new MidnightScene[1];
        var carried = new ItemStack[1];
        var sequence = PhysiqueGameTest.choose(test, owner, PhysiqueScene.ORDINARY);
        sequence.thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(p).isPresent(), "等待身体"))
                .thenExecute(() -> {
            var scene = new MidnightScene(p, REGION.east(SPACING * 4));
            prepared[0] = scene;
            scene.midnight(MarketOutcome.CURSED);
            scene.invite();
            scene.approach(Stall.RELICS);
            p.getInventory().add(VenueScene.coins("copper_cash", MarketOrders.RELIC_PRICE));
            scene.click(MarketOrders.BUY, scene.site.dawn());
            test.assertTrue(scene.record().getBought() && scene.record().getCursed(), "未交本签旧货");
            var bag = p.getInventory();
            int slot = bag.findSlotMatchingItem(MarketOrders.relic(scene.site));
            carried[0] = bag.removeItemNoUpdate(slot);
            scene.move(scene.site.yard().south(AWAY));
            scene.tick();
        }).thenIdle(SECOND).thenExecute(() -> {
            var s = prepared[0];
            step(s, p);
            test.assertTrue(s.record().getPursuit().isEmpty(), "空手夜行招了亡灵");
            p.getInventory().add(carried[0]);
        }).thenIdle(SECOND).thenExecute(() -> {
            var s = prepared[0];
            step(s, p);
            test.assertTrue(s.record().getPursuit().equals("PRESENT"), "实际携诅货夜行未见原版亡灵");
            reloadVisitor(test, s, owner);
            owner.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:midnight_walk")
    public void walkingIntoLegacyCourtyardUsesOriginalTemple(GameTestHelper test) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var p = owner.player();
        var prepared = new MidnightScene[1];
        long before = test.getLevel().getOverworldClockTime();
        test.startSequence().thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(p).isPresent(), "等未选出身身体"))
                .thenExecute(() -> {
                    var s = new MidnightScene(p, REGION.west(SPACING), MidnightScene.Source.LEGACY);
                    prepared[0] = s;
                    s.midnight(MarketOutcome.SILVER);
                    var level = p.level();
                    var marker = s.site.layout().origin();
                    level.setBlockAndUpdate(marker, Blocks.GOLD_BLOCK.defaultBlockState());
                    s.tick();
                    var site = TempleMarkets.get(level).site(s.altar);
                    test.assertTrue(site != null && site.phase() == TempleMarkets.Phase.CLOSED, "旧香案未登记或原地开场");
                }).thenIdle(SECOND).thenExecute(() -> {
                    try (owner) {
                        var s = prepared[0];
                        s.move(p.blockPosition().east(1));
                        p.awardStat(Stats.WALK_ONE_CM, LoneStarRoad.CM);
                        s.tick();
                        s.site = TempleMarkets.get(p.level()).site(s.altar);
                        test.assertTrue(s.site.phase() == TempleMarkets.Phase.OPEN && !s.record().getClosed(),
                                "未选出身徒步进入前庭未获本夜邀请");
                        test.assertTrue(p.level().getBlockState(s.site.layout().origin()).is(Blocks.GOLD_BLOCK),
                                "重放荒庙覆盖了原址改建");
                    } finally {
                        test.setTime(before);
                    }
                }).thenSucceed();
    }

    private static void step(MidnightScene s, net.minecraft.server.level.ServerPlayer p) {
        s.clock(s.site.dawn() - MidnightMarket.DAY + MidnightMarket.MIDNIGHT);
        s.move(p.blockPosition().east(1));
        p.awardStat(Stats.WALK_ONE_CM, LoneStarRoad.CM);
        s.tick();
    }

    private static void reloadVisitor(GameTestHelper test, MidnightScene s, StoryPlayer owner) {
        var player = owner.player();
        var level = player.level();
        var ticket = new MarketOrders(player, MidnightMarket.key(player, s.site), s.record()).ticket();
        var visitorId = UUID.nameUUIDFromBytes(
                (VISITOR_ID_PREFIX + ticket).getBytes(StandardCharsets.UTF_8));
        var visitor = level.getEntity(visitorId);
        test.assertTrue(visitor instanceof Zombie, "按夜访签未找到原版亡灵");
        if (!(visitor instanceof Zombie zombie)) {
            return;
        }
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        zombie.saveWithoutId(output);
        zombie.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        s.tick();
        test.assertTrue(s.record().getPursuit().equals("SUSPENDED"), "卸载被剧情记作胜逃败");
        var loaded = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.LOAD);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        test.assertTrue(level.addFreshEntity(loaded) && loaded.getUUID().equals(zombie.getUUID()), "未重载原实体");
        s.tick();
        test.assertTrue(NightVisits.status(player, ticket) == NightVisits.Status.PRESENT, "原订单未续夜访");
        loaded.kill(level);
        s.tick();
        test.assertTrue(s.record().getPursuit().equals("WON"), "剧情未记原签战果");
    }
}
