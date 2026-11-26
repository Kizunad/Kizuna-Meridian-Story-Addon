package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.story.fabric.YunxiGameTest.ObservedCow;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService.Stagger;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 下卷旋影回剑与连珠两剑：背向扇区、两拍各自查敌、整式只认一人、末击磨剑和一次付费。 */
public final class YunxiLowerTest {
    private static final int TIMEOUT = 60;
    private static final int RETURN_HIT = 7;
    private static final int FIRST_BEAT = 6;
    private static final int SECOND_BEAT = 13;
    private static final int BETWEEN_BEATS = 9;
    private static final double REACH = 1.6;
    private static final double AWAY = 6;
    private static final double RETURN_PRICE = 0.8;
    private static final double TWIN_PRICE = 1.3;
    private static final double EPSILON = 0.0001;
    private static final float IRON_RETURN = 6.0f;
    private static final float IRON_BEAT = 4.2f;
    private static final float DIAMOND_BEAT = 4.9f;
    private static final long STAGGER_MILLIS = 500;
    private static final int WARMUP_HIT = 3;
    private static final BlockPos WALL_FEET = new BlockPos(3, 2, 2);

    @GameTest(maxTicks = TIMEOUT)
    public void returnStrikesRearNotFrontMain(GameTestHelper test) {
        returnStrike(test, InteractionHand.MAIN_HAND);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void returnStrikesRearNotFrontOff(GameTestHelper test) {
        returnStrike(test, InteractionHand.OFF_HAND);
    }

    private static void returnStrike(GameTestHelper test, InteractionHand hand) {
        // 前后各站一人；第七刻只查背后一次，玩家朝向不变。
        var actor = swordsman(test, hand);
        var player = actor.player();
        var front = enemy(test, player, REACH);
        var rear = enemy(test, player, -REACH);
        float yaw = player.getYRot();
        double before = lumen(player);
        accepted(test, player, RETURN);
        near(test, before - lumen(player), RETURN_PRICE, "回剑起手真元");
        test.startSequence()
                .thenExecuteAfter(RETURN_HIT - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(rear.hits == 0, "第六刻提前回剑");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        String suffix = hand == InteractionHand.MAIN_HAND ? "_main_hand" : "_off_hand";
                        test.assertTrue(rear.hits == 1 && rear.damage == IRON_RETURN, "背后一人未中一剑 " + rear.hits);
                        test.assertTrue((RETURN + suffix).equals(rear.damageType), "回剑手别错误 " + rear.damageType);
                        test.assertTrue(front.hits == 0, "回剑打到了身前");
                        test.assertTrue(player.getYRot() == yaw, "回剑改了玩家朝向");
                        test.assertTrue(player.getItemInHand(hand).getDamageValue() == 1, "回剑末击未磨剑一点");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void returnCannotReachThroughWall(GameTestHelper test) {
        // 背后的人隔着两格高的墙，看不见就打不到；挥空照样付费磨剑。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        test.setBlock(WALL_FEET, Blocks.STONE);
        test.setBlock(WALL_FEET.above(), Blocks.STONE);
        var rear = enemy(test, player, -REACH);
        double before = lumen(player);
        accepted(test, player, RETURN);
        near(test, before - lumen(player), RETURN_PRICE, "挥空真元");
        test.runAfterDelay(RETURN_HIT + 1, () -> {
            try (actor) {
                TechniqueFabric.service().tick();
                test.assertTrue(rear.hits == 0, "回剑穿墙命中");
                test.assertTrue(player.getMainHandItem().getDamageValue() == 1, "挥空未磨剑");
                test.succeed();
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinHitsStayingTargetTwiceAndPaysOnce(GameTestHelper test) {
        var actor = swordsman(test, InteractionHand.OFF_HAND);
        var player = actor.player();
        var target = enemy(test, player, REACH);
        double before = lumen(player);
        accepted(test, player, TWIN);
        near(test, before - lumen(player), TWIN_PRICE, "两拍合付真元");
        double paid = lumen(player);
        float[] afterFirst = new float[1];
        test.startSequence()
                .thenExecuteAfter(FIRST_BEAT - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 0, "首拍提前");
                })
                .thenExecuteAfter(1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 1 && target.damage == IRON_BEAT, "第六刻未中首拍");
                    test.assertTrue(player.getOffhandItem().getDamageValue() == 0, "首拍就磨了剑");
                    afterFirst[0] = target.getHealth();
                    test.assertTrue(afterFirst[0] < target.getMaxHealth(), "首拍没有掉血");
                })
                .thenExecuteAfter(SECOND_BEAT - FIRST_BEAT - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 1, "末拍提前");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 2 && target.damage == IRON_BEAT, "第十三刻未中末拍");
                        test.assertTrue(target.getHealth() < afterFirst[0], "末拍被受击间隔吞掉，没有再掉血");
                        test.assertTrue((TWIN + "_off_hand").equals(target.damageType), "连珠手别错误");
                        test.assertTrue(player.getOffhandItem().getDamageValue() == 1, "两拍应只磨剑一点");
                        test.assertTrue(lumen(player) <= paid, "真元异常回升");
                        test.assertTrue(paid - lumen(player) < TWIN_PRICE, "末拍又付了一次真元");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinSecondBeatMissesDepartedTargetAndIgnoresNewcomer(GameTestHelper test) {
        // 甲中首拍后走开，乙补到原位：整式只认一人，末拍挥空，剑照磨。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var first = enemy(test, player, REACH);
        accepted(test, player, TWIN);
        ObservedCow[] newcomer = new ObservedCow[1];
        test.startSequence()
                .thenExecuteAfter(BETWEEN_BEATS, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(first.hits == 1, "首拍未中甲");
                    first.setPos(first.position().add(AWAY, 0, 0));
                    newcomer[0] = enemy(test, player, REACH);
                })
                .thenExecuteAfter(SECOND_BEAT - BETWEEN_BEATS + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(first.hits == 1, "走开的甲仍中末拍");
                        test.assertTrue(newcomer[0].hits == 0, "末拍打了补位的乙");
                        test.assertTrue(player.getMainHandItem().getDamageValue() == 1, "末拍挥空未磨剑");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinSecondBeatHitsLateArrival(GameTestHelper test) {
        // 首拍面前没人，末拍前才有人站进来，仍中一拍。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        accepted(test, player, TWIN);
        ObservedCow[] late = new ObservedCow[1];
        test.startSequence()
                .thenExecuteAfter(BETWEEN_BEATS, () -> {
                    TechniqueFabric.service().tick();
                    late[0] = enemy(test, player, REACH);
                })
                .thenExecuteAfter(SECOND_BEAT - BETWEEN_BEATS + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(late[0].hits == 1 && late[0].damage == IRON_BEAT, "后到的人未中末拍");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinStaggeredInWarmupCancelsBothBeats(GameTestHelper test) {
        // 起手段挨打会打断整式：两拍都不出，剑也不磨。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var target = enemy(test, player, REACH);
        accepted(test, player, TWIN);
        test.startSequence()
                .thenExecuteAfter(WARMUP_HIT, () -> {
                    TechniqueFabric.service().tick();
                    var stagger = new Stagger(STAGGER_MILLIS, 1, StaggerKind.HIT);
                    TechniqueFabric.service().stagger(player.getUUID(), stagger);
                })
                .thenExecuteAfter(SECOND_BEAT - WARMUP_HIT + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 0, "起手被打断后仍出了剑 " + target.hits);
                        test.assertTrue(player.getMainHandItem().getDamageValue() == 0, "整式取消却磨了剑");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinLeavingBetweenBeatsCancelsSecond(GameTestHelper test) {
        // 两拍之间施剑者离场，待发的末拍随施放一起清掉。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var target = enemy(test, player, REACH);
        accepted(test, player, TWIN);
        test.startSequence()
                .thenExecuteAfter(BETWEEN_BEATS, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 1, "首拍未中");
                    TechniqueFabric.service().cancelOnUnload(player.getUUID());
                })
                .thenExecuteAfter(SECOND_BEAT - BETWEEN_BEATS + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 1, "离场后仍出了末拍");
                        test.assertTrue(player.getMainHandItem().getDamageValue() == 0, "余拍取消却磨了剑");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void twinSecondBeatUsesSwordSwappedBetweenBeats(GameTestHelper test) {
        // 起手固定的是手；两拍间换成钻石剑，末拍按新剑算剂量，末击磨的也是新剑。
        var actor = swordsman(test, InteractionHand.MAIN_HAND);
        var player = actor.player();
        var target = enemy(test, player, REACH);
        accepted(test, player, TWIN);
        test.startSequence()
                .thenExecuteAfter(BETWEEN_BEATS, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 1 && target.damage == IRON_BEAT, "首拍未按铁剑结算");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                })
                .thenExecuteAfter(SECOND_BEAT - BETWEEN_BEATS + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 2 && target.damage == DIAMOND_BEAT, "末拍未按新剑结算");
                        test.assertTrue(player.getMainHandItem().getDamageValue() == 1, "末击未磨新剑");
                    }
                }).thenSucceed();
    }

    static StoryPlayer swordsman(GameTestHelper test, InteractionHand hand) {
        // 读上卷的生存玩家，再经人体入口升到真炁、开两手一足并学齐下卷。
        var actor = StoryPlayer.join(test, UUID.randomUUID());
        var player = actor.player();
        YunxiGameTest.prepare(test, player);
        YunxiPracticeBody.practitioner(player);
        var service = TechniqueFabric.service();
        service.learn(player.getUUID(), CLEAVE);
        service.learn(player.getUUID(), RETURN);
        service.learn(player.getUUID(), TWIN);
        player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
        return actor;
    }

    private static ObservedCow enemy(GameTestHelper test, ServerPlayer player, double forward) {
        var cow = new ObservedCow(test.getLevel());
        Vec3 position = player.position().add(0, 0, forward);
        cow.setPos(position.x, position.y, position.z);
        test.assertTrue(test.getLevel().addFreshEntity(cow), "试剑目标未生成");
        cow.setNoAi(true /* noAi */);
        cow.setNoGravity(true /* noGravity */);
        return cow;
    }

    static double lumen(ServerPlayer player) {
        var body = TechniqueFabric.service().observe(player.getUUID()).orElseThrow().body();
        return body.meridianStates().stream().flatMap(m -> m.lumenQi().entries().stream())
                .mapToDouble(Qi::amount).sum();
    }

    static void accepted(GameTestHelper test, ServerPlayer player, String form) {
        var result = cast(player, form);
        test.assertTrue(result.hasBinding(), "施放未受理 " + result);
    }

    private static void near(GameTestHelper test, double actual, double expected, String name) {
        test.assertTrue(Math.abs(actual - expected) < EPSILON, name + " 实际 " + actual + " 应为 " + expected);
    }
}
