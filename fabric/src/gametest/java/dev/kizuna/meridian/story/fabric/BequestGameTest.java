package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.BequestGoods.Gift;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

public final class BequestGameTest {
    private static final int TIMEOUT = 600;
    private static final BlockPos REGION = new BlockPos(14000, 0, 15000);
    private static final int SPACING = 192;
    private static final int BAD = 99;
    private static final int SITE_LANE = 0;
    private static final int ACCEPT_LANE = 1;
    private static final int NOTES_LANE = 2;
    private static final int RETURN_LANE = 3;
    private static final int LOOT_LANE = 4;
    private static final int STRUCK_LANE = 5;
    private static final int EMPTY_LANE = 6;
    private static final int OPENED_LANE = 7;
    private static final int WALK_LANE = 8;
    private static final int SLOPE_LANE = 9;
    private static final int SEARCH_RADIUS = 20;
    private static final String ENV = "meridian_story_test:bequest";

    private record Run(GameTestHelper test, BequestScene s) {
        void expect(boolean ok, String message) {
            test.assertTrue(ok, message);
        }

        RoadsideCase open(int roll) {
            expect(s.place(), "山洞没放好");
            expect(s.meet(), "有村口与石榻却没有开场");
            if (s.current().getRoll() != roll) {
                s.roll(roll);
            }
            return s.current();
        }
    }

    private static void run(GameTestHelper test, int lane, Consumer<Run> check) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var player = owner.player();
        var scene = new BequestScene[1];
        test.startSequence().thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(player).isPresent(), "等身体"))
                .thenExecute(() -> scene[0] = new BequestScene(player, REGION.east(lane * SPACING)))
                .thenWaitUntil(() -> test.assertTrue(scene[0].gate(), "等村口登记"))
                .thenExecute(() -> {
                    try (owner) {
                        check.accept(new Run(test, scene[0]));
                    } catch (RuntimeException error) {
                        test.fail(error.toString());
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void registeredCaveRequiresOpenPassageAndVillage(GameTestHelper test) {
        run(test, SITE_LANE, r -> {
            var s = r.s;
            var level = s.player.level();
            var plug = s.site.mouth().south();
            level.setBlockAndUpdate(plug, Blocks.GOLD_BLOCK.defaultBlockState());
            r.expect(!s.place() && level.getBlockState(plug).is(Blocks.GOLD_BLOCK), "覆盖了改建");
            level.setBlockAndUpdate(plug, Blocks.AIR.defaultBlockState());
            r.expect(s.place() && s.site.walkable(level), "洞口与石榻不通");
            level.setBlockAndUpdate(plug, Blocks.GOLD_BLOCK.defaultBlockState());
            r.expect(!s.meet(), "堵洞仍开场");
            level.setBlockAndUpdate(plug, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(s.bell, Blocks.AIR.defaultBlockState());

            r.expect(!s.meet(), "无村口仍开场");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void oneTransferReplacementAndLateDelivery(GameTestHelper test) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var player = owner.player();
        var scene = new BequestScene[1];
        var original = new RoadsideCase[1];
        var opened = new long[1];
        test.startSequence().thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(player).isPresent(), "等身体"))
                .thenExecute(() -> scene[0] = new BequestScene(player, REGION.east(ACCEPT_LANE * SPACING)))
                .thenWaitUntil(() -> test.assertTrue(scene[0].gate(), "等村口登记"))
                .thenExecute(() -> {
                    var s = scene[0];
                    var r = new Run(test, s);
                    var c = r.open(BAD);
                    original[0] = c;
                    opened[0] = s.view().facts().opened();
                    r.expect(LastRequest.eligible(player, c), "老人或受方缺实资");
                    r.expect(s.choose(c, LastRequest.ACCEPT), "拒绝受功");
                    r.expect(!s.choose(c, LastRequest.ACCEPT), "回执前重复受理");
                }).thenWaitUntil(() -> test.assertTrue(scene[0].view().progress().getRoadside()
                        .containsMountainHistory(original[0].getActor()), "等原号传功与交佩"))
                .thenExecute(() -> {
                    try (owner) {
                        var s = scene[0];
                        var r = new Run(test, s);
                        var paid = s.history(original[0].getActor());
                        r.expect(s.view().facts().opened() == opened[0] + 1 && paid.getBequest().getCreditedQi() > 0
                                && !paid.getBequest().getFundingDigest().isEmpty(), "非一次实收");
                        r.expect(paid.getReturnParty().equals(LastRequest.ENEMY) && !paid.getShouted(), "追兵提前认功");
                        checkDelivery(r, paid);
                    }
                }).thenSucceed();
    }

    private static void checkDelivery(Run r, RoadsideCase paid) {
        var s = r.s;
        var player = s.player;
        var inventory = player.getInventory();
        var old = Gift.JADE.stack(paid);
        int first = BequestGoods.slot(player, old);
        r.expect(first >= 0 && !s.choose(paid, LastRequest.DELIVER), "人在山洞就交了佩");
        inventory.removeItem(first, 1);
        r.expect(s.choose(paid, LastRequest.REPLACE), "原洞不能补佩");
        var reissued = s.history(paid.getActor());
        var replaced = Gift.JADE.stack(reissued);
        int slot = BequestGoods.slot(player, replaced);
        r.expect(slot >= 0 && reissued.getBequest().getSerial() > paid.getBequest().getSerial(), "补佩没换号");
        inventory.setItem(slot, old);
        s.move(BlockPos.of(paid.getBequest().getGate()).east());
        r.expect(!s.choose(reissued, LastRequest.DELIVER), "旧佩被核销");
        inventory.setItem(slot, replaced.copy());
        s.advance(paid.getBequest().getDue());
        r.expect(s.history(paid.getActor()).getBequest().getOverdue(), "第五日未欠约");
        var graves = new GraveVisits(s.store, new RivalVisit(s.store));
        graves.reconcileMood(player, s.view(), GraveMood.Mood.AT_PEACE);
        r.expect(player.hasEffect(GraveMood.EFFECT), "单独欠约未见低落");
        r.expect(s.choose(reissued, LastRequest.DELIVER), "迟交失败");
        graves.reconcileMood(player, s.view(), GraveMood.Mood.HOMESICK);
        r.expect(player.hasEffect(GraveMood.EFFECT), "迟交同时解除了离冢低落");
        graves.reconcileMood(player, s.view(), GraveMood.Mood.AT_PEACE);
        r.expect(!player.hasEffect(GraveMood.EFFECT), "守墓与欠约均解后仍低落");
        r.expect(!s.choose(reissued, LastRequest.DELIVER), "重复交佩");
        r.expect(BequestGoods.slot(player, replaced) < 0, "交佩后玩家仍有原件");
        r.expect(!BequestPromise.overdue(s.view().progress(), s.view().facts().ticks()), "交佩未解欠约");
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void declineOnlyGivesSignedNotes(GameTestHelper test) {
        run(test, NOTES_LANE, r -> {
            var s = r.s;
            var c = r.open(0);
            r.expect(s.choose(c, LastRequest.DECLINE), "无法推辞");
            var closed = s.history(c.getActor());
            r.expect(BequestGoods.slot(s.player, Gift.NOTES.stack(c)) >= 0 && !BequestPromise.owed(closed)
                    && closed.getBequest().getCreditedQi() == 0, "手札和传功兼得");
            r.expect(!s.choose(c, LastRequest.ACCEPT), "推辞后又受功");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void discipleOnlyAccusesAtBedsideAfterThreeDays(GameTestHelper test) {
        run(test, RETURN_LANE, r -> {
            var s = r.s;
            var c = r.open(BAD);
            r.expect(s.choose(c, LastRequest.DECLINE), "无法推辞");
            var left = s.history(c.getActor());
            r.expect(BequestGoods.slot(s.player, Gift.NOTES.stack(c)) < 0, "坏签拿到了手札");
            s.advance(left.getReturnAt() - 1);
            r.expect(!s.history(c.getActor()).getBequest().getWitnessedNote(), "弟子提前归咎");
            s.move(s.bell);
            s.advance(left.getReturnAt());
            r.expect(!s.history(c.getActor()).getBequest().getWitnessedNote(), "在村口就归咎");
            s.move(s.site.at(MountainRetreats.ELDER).east());
            s.events.bequest.promise.tick(s.player, s.view());
            r.expect(s.history(c.getActor()).getReturnParty().equals(LastRequest.ACCUSER), "弟子重访未归咎");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void strikingTakesOnlyActualBundle(GameTestHelper test) {
        run(test, LOOT_LANE, r -> strike(r, 0, "looted"));
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void elderPalmWoundsChestAndKeepsBundle(GameTestHelper test) {
        run(test, STRUCK_LANE, r -> strike(r, BAD, "struck"));
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void emptyDonorStillOffersRefusal(GameTestHelper test) {
        run(test, EMPTY_LANE, r -> {
            var s = r.s;
            var c = r.open(0);
            var elder = LastRequest.elder(s.player, c).orElseThrow();
            var server = s.player.level().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "meridian qi set " + elder.getStringUUID() + " 0");
            r.expect(!LastRequest.eligible(s.player, c) && !s.choose(c, LastRequest.ACCEPT), "无资仍可受功");
            r.expect(s.choose(c, LastRequest.DECLINE), "无资不能推辞");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void fullyOpenedStudentCanStillStrike(GameTestHelper test) {
        run(test, OPENED_LANE, r -> {
            var s = r.s;
            var server = s.player.level().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "meridian opening complete " + s.player.getScoreboardName() + " all");
            var c = r.open(0);
            r.expect(!LastRequest.eligible(s.player, c) && !s.choose(c, LastRequest.ACCEPT), "全开仍可受功");
            attack(r, c, "looted");
        });
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void unchosenWalkerMeetsElderOnlyAfterMoving(GameTestHelper test) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var player = owner.player();
        var scene = new BequestScene[1];
        var store = new StoryStore();
        store.ready(player, "");
        var tracker = new FallenCultivator(store);
        test.startSequence().thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(player).isPresent(), "等身体"))
                .thenExecute(() -> scene[0] = new BequestScene(player, REGION.east(WALK_LANE * SPACING)))
                .thenWaitUntil(() -> test.assertTrue(scene[0].gate(), "等村口登记"))
                .thenExecute(() -> {
                    var s = scene[0];
                    test.assertTrue(s.place(), "不能登记山洞");
                    s.advance(LoneStarRoad.REST);
                    s.move(s.site.at(MountainRetreats.ELDER).east());
                    s.clock();
                    tracker.tick(player, s.view());
                    test.assertTrue(!s.current().hasBequest(), "原地开场");
                }).thenIdle(LoneStarRoad.SECOND).thenExecute(() -> {
                    try (owner) {
                        var s = scene[0];
                        s.clock();
                        s.move(s.site.at(MountainRetreats.ELDER).east(2));
                        player.awardStat(net.minecraft.stats.Stats.WALK_ONE_CM, LoneStarRoad.CM);
                        tracker.tick(player, s.view());
                        test.assertTrue(s.current().hasBequest(), "未选出身徒步入洞未见老人");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = ENV)
    public void nearbySlopeRegistersAllFourEntrances(GameTestHelper test) {
        var owner = StoryPlayer.join(test, UUID.randomUUID());
        var player = owner.player();
        test.startSequence().thenWaitUntil(() -> test.assertTrue(CrisisEncounters.current(player).isPresent(), "等身体"))
                .thenExecute(() -> {
                    try (owner) {
                        var level = player.level();
                        var retreats = MountainRetreats.get(level);
                        for (var facing : net.minecraft.world.level.block.Rotation.values()) {
                            var column = REGION.east((SLOPE_LANE + facing.ordinal()) * SPACING);
                            var s = new BequestScene(player, column, facing);
                            s.move(s.site.mouth().west(SEARCH_RADIUS));
                            retreats.search(player);
                            var registered = retreats.site(s.site.mouth().asLong());
                            test.assertTrue(registered != null && registered.direction() == facing
                                    && registered.walkable(level), "坡脚未登记可走的洞室 " + facing);
                        }
                    }
                }).thenSucceed();
    }

    private static void strike(Run r, int roll, String ending) {
        attack(r, r.open(roll), ending);
    }

    private static void attack(Run r, RoadsideCase c, String ending) {
        var s = r.s;
        var mob = LastRequest.elder(s.player, c).orElseThrow();
        AttackEntityCallback.EVENT.invoker().interact(s.player, s.player.level(), InteractionHand.MAIN_HAND, mob, null);
        var closed = s.history(c.getActor());
        r.expect(closed.getEnding().equals(ending) && closed.getBequest().getCreditedQi() == 0, "夺包串支");
        r.expect((BequestGoods.slot(s.player, Gift.BUNDLE.stack(c)) >= 0) == ending.equals("looted"), "行李归属错误");
    }
}
