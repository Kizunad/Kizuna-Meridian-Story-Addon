package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.human.fabric.YunxiWorkReceipts;
import dev.kizuna.meridian.story.fabric.YunxiGameTest.ObservedCow;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 《寻隙剑谱》三式：选边的定距横移、钉住原目标与原位的迟刺、先退后撩且后路受阻时取消。 */
public final class XunxiGameTest {
    private static final int TIMEOUT = 60;
    private static final int STEP_TICK = 4;
    private static final int THRUST_HIT = 10;
    private static final int LIFT_HIT = 9;
    private static final int MID_WARMUP = 5;
    private static final int FLOOR_FROM = 0;
    private static final int FLOOR_TO = 6;
    private static final int LEG_MUSCLES = 6;
    private static final int QUARTER_TURN = 90;
    private static final int VOLUME_FORMS = 3;
    private static final double GROUND_LOSS = 1 - 0.546;
    private static final double SIDE_REACH = 1;
    private static final double BACK_REACH = 0.8;
    private static final double LEDGE_REACH = 0.7;
    private static final double LEG_WATTS = 100.0 / LEG_MUSCLES;
    private static final double WORK_SECONDS = 0.20;
    private static final double THRUST_PRICE = 0.7;
    private static final double LIFT_PRICE = 0.9;
    private static final double TARGET = 1.6;
    private static final double CLOSE = 1.2;
    private static final double SMALL_DRIFT = 0.5;
    private static final double LARGE_DRIFT = 0.7;
    private static final double AWAY = 6;
    private static final double EPSILON = 0.0001;
    private static final float IRON_THRUST = 6.3f;
    private static final float IRON_LIFT = 5.1f;
    private static final BlockPos FEET = new BlockPos(3, 2, 3);
    private static final Identifier SCROLL = Identifier.parse("meridian_story:xunxi_full_scroll");

    private enum Input { NONE, RIGHT }

    private enum Ground { OPEN, WALL_LEFT, WALL_BOTH, LEDGE_LEFT }

    @GameTest(maxTicks = TIMEOUT)
    public void sideStepDefaultsLeftAndPaysLegsOnly(GameTestHelper test) {
        sideStep(test, Input.NONE, Ground.OPEN, SIDE_REACH);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sideStepFollowsRightInput(GameTestHelper test) {
        sideStep(test, Input.RIGHT, Ground.OPEN, -SIDE_REACH);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sideStepWithoutInputTakesOpenSide(GameTestHelper test) {
        sideStep(test, Input.NONE, Ground.WALL_LEFT, -SIDE_REACH);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sideStepStaysWhenBothSidesWalled(GameTestHelper test) {
        sideStep(test, Input.NONE, Ground.WALL_BOTH, 0);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sideStepStopsShortOfLedge(GameTestHelper test) {
        sideStep(test, Input.NONE, Ground.LEDGE_LEFT, LEDGE_REACH);
    }

    private static void sideStep(GameTestHelper test, Input input, Ground ground, double leftward) {
        // 凡人零真元持剑施放；第四刻按能站住的距离给一次横向初速度，面朝南时左手是正东。
        floor(test);
        shape(test, ground);
        var actor = mortal(test);
        var player = actor.player();
        int previous = YunxiWorkReceipts.read(player).size();
        accepted(test, request(player, SIDE_STEP, input));
        checkLegWork(test, player, previous);
        test.startSequence()
                .thenExecuteAfter(STEP_TICK - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(player.getDeltaMovement().horizontalDistance() < EPSILON, "第三刻提前横移");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        Vec3 velocity = player.getDeltaMovement();
                        near(test, velocity.x, leftward * GROUND_LOSS, "横移初速度");
                        near(test, velocity.z, 0, "横移不应带前后分量");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void heldThrustHitsPinnedTargetThatStays(GameTestHelper test) {
        heldThrust(test, SMALL_DRIFT, 1);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void heldThrustMissesPinnedTargetThatMoved(GameTestHelper test) {
        heldThrust(test, LARGE_DRIFT, 0);
    }

    private static void heldThrust(GameTestHelper test, double drift, int expected) {
        // 起手钉住身前的人；他沿剑路挪开一段后仍在扇区和距离内，只有离原位太远才刺空。
        floor(test);
        var actor = swordsman(test);
        var player = actor.player();
        var target = enemy(test, player, TARGET);
        double before = lumen(player);
        accepted(test, request(player, HELD_THRUST, Input.NONE));
        near(test, before - lumen(player), THRUST_PRICE, "顿剑起手真元");
        test.startSequence()
                .thenExecuteAfter(MID_WARMUP, () -> {
                    TechniqueFabric.service().tick();
                    target.setPos(target.position().add(0, 0, drift));
                })
                .thenExecuteAfter(THRUST_HIT - MID_WARMUP - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 0, "第九刻提前出剑");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == expected, "原目标受击次数错误 " + target.hits);
                        test.assertTrue(expected == 0 || target.damage == IRON_THRUST, "顿剑倍率错误 " + target.damage);
                        test.assertTrue(player.getMainHandItem().getDamageValue() == 1, "末击未磨剑一点");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void heldThrustIgnoresNewcomerAtPinnedSpot(GameTestHelper test) {
        // 钉住的甲走远，乙站到原位：这一剑只认甲，乙不中。
        floor(test);
        var actor = swordsman(test);
        var player = actor.player();
        var first = enemy(test, player, TARGET);
        accepted(test, request(player, HELD_THRUST, Input.NONE));
        ObservedCow[] newcomer = new ObservedCow[1];
        test.startSequence()
                .thenExecuteAfter(MID_WARMUP, () -> {
                    TechniqueFabric.service().tick();
                    first.setPos(first.position().add(AWAY, 0, 0));
                    newcomer[0] = enemy(test, player, TARGET);
                })
                .thenExecuteAfter(THRUST_HIT - MID_WARMUP + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(first.hits == 0 && newcomer[0].hits == 0, "顿剑打了补位的人或走远的人");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void heldThrustKeepsFacingFromStart(GameTestHelper test) {
        // 起手后施剑者转开一个直角，原目标没动：剑沿起手的朝向刺出，照样命中。
        floor(test);
        var actor = swordsman(test);
        var player = actor.player();
        var target = enemy(test, player, TARGET);
        accepted(test, request(player, HELD_THRUST, Input.NONE));
        test.startSequence()
                .thenExecuteAfter(MID_WARMUP, () -> {
                    TechniqueFabric.service().tick();
                    player.setYRot(QUARTER_TURN);
                    player.setYHeadRot(QUARTER_TURN);
                })
                .thenExecuteAfter(THRUST_HIT - MID_WARMUP + 1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == 1, "转身后剑没有沿起手朝向刺出");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void backLiftRetreatsThenStrikes(GameTestHelper test) {
        backLift(test, Ground.OPEN, BACK_REACH, 1);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void backLiftBlockedBehindCancelsStrike(GameTestHelper test) {
        backLift(test, Ground.WALL_BOTH, 0, 0);
    }

    private static void backLift(GameTestHelper test, Ground ground, double retreat, int expected) {
        // 第四刻向后给一次定距初速度，第九刻出剑；背后是墙时一步也退不了，余拍取消、剑不磨。
        floor(test);
        if (ground == Ground.WALL_BOTH) {
            wall(test, FEET.north());
        }
        var actor = swordsman(test);
        var player = actor.player();
        var target = enemy(test, player, CLOSE);
        double before = lumen(player);
        accepted(test, request(player, BACK_LIFT, Input.NONE));
        near(test, before - lumen(player), LIFT_PRICE, "撤步回撩起手真元");
        test.startSequence()
                .thenExecuteAfter(STEP_TICK, () -> {
                    TechniqueFabric.service().tick();
                    Vec3 velocity = player.getDeltaMovement();
                    near(test, velocity.z, -retreat * GROUND_LOSS, "后撤初速度");
                    near(test, velocity.x, 0, "后撤不应带左右分量");
                })
                .thenExecuteAfter(LIFT_HIT - STEP_TICK - 1, () -> {
                    TechniqueFabric.service().tick();
                    test.assertTrue(target.hits == 0, "第八刻提前出剑");
                })
                .thenExecuteAfter(1, () -> {
                    try (actor) {
                        TechniqueFabric.service().tick();
                        test.assertTrue(target.hits == expected, "回撩受击次数错误 " + target.hits);
                        test.assertTrue(expected == 0 || target.damage == IRON_LIFT, "回撩倍率错误 " + target.damage);
                        test.assertTrue(player.getMainHandItem().getDamageValue() == expected, "磨剑点数与出剑不符");
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void mortalIsTaughtOnlySideStepAndScrollNeedsAllThree(GameTestHelper test) {
        // 全卷没有生存掉落。读卷是整卷一起学：凡人读不成、卷不消耗；按式口授时凡人只学得会第一式。
        var item = TechniqueScrollItems.scrolls().get(SCROLL);
        var volume = item.scroll().volume();
        test.assertTrue(volume.getScrollDropsCount() == 0 && volume.getTechniquesCount() == VOLUME_FORMS,
                "卷目或生存掉落错误");
        try (var actor = mortal(test)) {
            var player = actor.player();
            var all = Set.of(SIDE_STEP, HELD_THRUST, BACK_LIFT);
            var scroll = read(player);
            test.assertTrue(!scroll.isEmpty(), "凡人读卷消耗了卷轴");
            var taught = known(player);
            test.assertTrue(taught.contains(SIDE_STEP), "凡人学不会错步避锋");
            test.assertTrue(!taught.contains(HELD_THRUST) && !taught.contains(BACK_LIFT), "凡人学到了真炁两式");
            for (String form : Set.of(HELD_THRUST, BACK_LIFT)) {
                try {
                    TechniqueFabric.service().learn(player.getUUID(), form);
                } catch (RuntimeException refused) {
                    // 境界不到的口授由功法库拒绝，下面按已学列表核对。
                }
            }
            test.assertTrue(!known(player).contains(HELD_THRUST) && !known(player).contains(BACK_LIFT),
                    "凡人经口授学到了真炁两式");
            YunxiPracticeBody.practitioner(player);
            var second = read(player);
            test.assertTrue(second.isEmpty() && known(player).containsAll(all), "达标后读卷未学齐三式");
            test.succeed();
        }
    }

    private static ItemStack read(ServerPlayer player) {
        var scroll = new ItemStack(TechniqueScrollItems.scrolls().get(SCROLL));
        player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
        player.gameMode.useItem(player, player.level(), scroll, InteractionHand.MAIN_HAND);
        return scroll;
    }

    private static StoryPlayer mortal(GameTestHelper test) {
        // 读过上卷的凡人，经口授学了纯体力的第一式。
        var actor = StoryPlayer.join(test, UUID.randomUUID());
        var player = actor.player();
        YunxiGameTest.prepare(test, player);
        TechniqueFabric.service().learn(player.getUUID(), SIDE_STEP);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        return actor;
    }

    private static StoryPlayer swordsman(GameTestHelper test) {
        var actor = mortal(test);
        var player = actor.player();
        YunxiPracticeBody.practitioner(player);
        TechniqueFabric.service().learn(player.getUUID(), HELD_THRUST);
        TechniqueFabric.service().learn(player.getUUID(), BACK_LIFT);
        return actor;
    }

    private static Set<String> known(ServerPlayer player) {
        return TechniqueFabric.service().known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .collect(Collectors.toSet());
    }

    private static CastOutcome request(ServerPlayer player, String form, Input input) {
        // 输入是世界水平向量；面朝南时向右是正西。
        var reference = TechniqueReference.newBuilder()
                .setId(form)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString());
        if (input == Input.RIGHT) {
            var west = HorizontalDirection.newBuilder()
                    .setX(-1)
                    .build();
            request.setDirection(west);
        }
        return TechniqueFabric.service().cast(player.getUUID(), request.build());
    }

    private static void accepted(GameTestHelper test, CastOutcome outcome) {
        test.assertTrue(outcome.hasBinding(), "施放未受理 " + outcome);
    }

    private static void checkLegWork(GameTestHelper test, ServerPlayer player, int previous) {
        var receipts = YunxiWorkReceipts.read(player);
        test.assertTrue(receipts.size() - previous == 1, "错步应只有一笔做功");
        var paid = receipts.getLast().getExecuteActivity();
        boolean exact = paid.getEffortsList().stream().allMatch(effort ->
                Math.abs(effort.getWatts() - LEG_WATTS) < EPSILON && effort.getSeconds() == WORK_SECONDS);
        test.assertTrue(paid.getEffortsCount() == LEG_MUSCLES && exact, "腿部做功剂量错误 " + paid.getEffortsList());
        test.assertTrue(paid.getAccountsCount() == 0, "错步动用了真元");
    }

    private static void floor(GameTestHelper test) {
        // 定距位移要查落脚，夹具自己铺一层地面。
        for (int x = FLOOR_FROM; x <= FLOOR_TO; x++) {
            for (int z = FLOOR_FROM; z <= FLOOR_TO; z++) {
                test.setBlock(new BlockPos(x, FEET.getY() - 1, z), Blocks.STONE);
            }
        }
    }

    private static void shape(GameTestHelper test, Ground ground) {
        // 面朝南站在中间：东边是左手，西边是右手。
        if (ground == Ground.WALL_LEFT || ground == Ground.WALL_BOTH) {
            wall(test, FEET.east());
        }
        if (ground == Ground.WALL_BOTH) {
            wall(test, FEET.west());
        }
        if (ground == Ground.LEDGE_LEFT) {
            for (int x = FEET.getX() + 1; x <= FLOOR_TO; x++) {
                for (int z = FLOOR_FROM; z <= FLOOR_TO; z++) {
                    test.setBlock(new BlockPos(x, FEET.getY() - 1, z), Blocks.AIR);
                }
            }
        }
    }

    private static void wall(GameTestHelper test, BlockPos feet) {
        test.setBlock(feet, Blocks.STONE);
        test.setBlock(feet.above(), Blocks.STONE);
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

    private static double lumen(ServerPlayer player) {
        var body = TechniqueFabric.service().observe(player.getUUID()).orElseThrow().body();
        return body.meridianStates().stream().flatMap(m -> m.lumenQi().entries().stream())
                .mapToDouble(Qi::amount).sum();
    }

    private static void near(GameTestHelper test, double actual, double expected, String name) {
        test.assertTrue(Math.abs(actual - expected) < EPSILON, name + " 实际 " + actual + " 应为 " + expected);
    }
}
