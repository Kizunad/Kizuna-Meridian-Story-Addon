package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiWorkReceipts;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class YunxiGameTest {
    private static final String PREFIX = "meridian_story:";
    private static final Identifier SCROLL = Identifier.parse(PREFIX + "yunxi_upper_scroll");
    private static final BlockPos START = new BlockPos(3, 2, 3);
    private static final double WORK_SECONDS = 0.20;
    private static final int MUSCLES = 3;
    private static final int AFTER_STRIKE = 8;
    private static final double SIDE = 0.57;
    private static final double NEAR = 1.25;
    private static final double FAR = 2.55;
    private static final double OUTSIDE = 1.40;
    private static final double NEAR_FORWARD = 0.48;
    private static final double DISTANT_SIDE = 0.95;
    private static final double SECOND_FORWARD = 2.85;
    private static final double THIRD_NEAR = 1.72;
    private static final int IRON_SWORD_DAMAGE = 6;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int FACE_ATTACKER = 180;

    private enum Form {
        THRUST("wind_thrust", 20, 0.85), LIFT("moon_lift", 25, 0.70);
        final String id;
        final double watts;
        final float dose;

        Form(String path, double watts, double multiplier) {
            this.id = PREFIX + path;
            this.watts = watts;
            this.dose = (float) (IRON_SWORD_DAMAGE * multiplier);
        }
    }

    private enum Encounter { FRONT, PAIR, MISS }

    @GameTest
    public void yunxiThrustMain(GameTestHelper test) {
        strike(test, Form.THRUST, InteractionHand.MAIN_HAND, Encounter.FRONT);
    }

    @GameTest
    public void yunxiThrustOff(GameTestHelper test) {
        strike(test, Form.THRUST, InteractionHand.OFF_HAND, Encounter.FRONT);
    }

    @GameTest
    public void yunxiLiftMain(GameTestHelper test) {
        strike(test, Form.LIFT, InteractionHand.MAIN_HAND, Encounter.PAIR);
    }

    @GameTest
    public void yunxiLiftOff(GameTestHelper test) {
        strike(test, Form.LIFT, InteractionHand.OFF_HAND, Encounter.PAIR);
    }

    @GameTest
    public void yunxiLiftMissPaysOnce(GameTestHelper test) {
        strike(test, Form.LIFT, InteractionHand.MAIN_HAND, Encounter.MISS);
    }

    @GameTest
    public void yunxiThrustMissPaysOnce(GameTestHelper test) {
        strike(test, Form.THRUST, InteractionHand.OFF_HAND, Encounter.MISS);
    }

    @GameTest
    public void yunxiLiftMeetsShield(GameTestHelper test) {
        var origin = Vec3.atBottomCenterOf(test.absolutePos(START));
        var defender = target(test, origin.add(0, 0, NEAR), 1, "持盾者").mob();
        var off = InteractionHand.OFF_HAND;
        defender.setItemInHand(off, new ItemStack(Items.SHIELD));
        defender.setYRot(FACE_ATTACKER);
        defender.setYBodyRot(FACE_ATTACKER);
        defender.setYHeadRot(FACE_ATTACKER);
        defender.startUsingItem(off);
        test.runAfterDelay(SHIELD_READY_TICKS, () -> {
            test.assertTrue(defender.isBlocking(), "盾尚未举稳");
            var actor = StoryPlayer.join(test, UUID.randomUUID());
            var player = actor.player();
            try {
                prepare(test, player);
                player.setItemInHand(off, new ItemStack(Items.IRON_SWORD));
                cast(test, player, Form.LIFT);
                test.runAfterDelay(AFTER_STRIKE, () -> {
                    try (actor) {
                        test.assertTrue(defender.hits == 1 && defender.damage == Form.LIFT.dose, "盾前没有一撩");
                        test.assertTrue(defender.getHealth() == defender.getMaxHealth(), "正面盾未承接伤害");
                        test.assertTrue(defender.isBlocking(), "轻撩击散了正面盾");
                        test.succeed();
                    }
                });
            } catch (Throwable failure) {
                actor.close();
                throw failure;
            }
        });
    }

    @GameTest
    public void yunxiVolumeInCreative(GameTestHelper test) {
        var item = TechniqueScrollItems.scrolls().get(SCROLL);
        var group = TechniqueScrollItems.group();
        var level = test.getLevel();
        var parameters = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(),
                false /* hasPermissions */, level.registryAccess());
        group.buildContents(parameters);
        test.assertTrue(group.getDisplayItems().stream().anyMatch(stack -> stack.is(item)), "创造栏缺上卷");
        var volume = item.scroll().volume();
        test.assertTrue(volume.getScrollDropsCount() == 0 && volume.getTechniquesCount() == 2,
                "卷目或生存掉落错误");
        test.succeed();
    }

    private static void strike(GameTestHelper test, Form form, InteractionHand hand, Encounter encounter) {
        // 从登录的生存玩家读卷起步；有效拍走世界调度和正式受伤入口。
        var actor = StoryPlayer.join(test, UUID.randomUUID());
        var player = actor.player();
        try {
            prepare(test, player);
            player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
            var targets = targets(test, player, encounter);
            int previous = YunxiWorkReceipts.read(player).size();
            YunxiMortal.requireMortal(player);
            cast(test, player, form);
            YunxiMortal.requireMortal(player);
            checkWork(test, player, form, hand, previous);
            test.runAfterDelay(AFTER_STRIKE, () -> {
                try (actor) {
                    String suffix = hand == InteractionHand.MAIN_HAND ? "_main_hand" : "_off_hand";
                    targets.forEach(target -> target.check(test, form, suffix));
                    checkWork(test, player, form, hand, previous);
                    test.succeed();
                }
            });
        } catch (Throwable failure) {
            actor.close();
            throw failure;
        }
    }

    private static void prepare(GameTestHelper test, ServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        player.setNoGravity(true /* noGravity */);
        var origin = Vec3.atBottomCenterOf(test.absolutePos(START));
        player.snapTo(origin.x, origin.y, origin.z, 0, 0);
        YunxiMortal.emptyQi(player);
        var scroll = new ItemStack(TechniqueScrollItems.scrolls().get(SCROLL));
        var main = InteractionHand.MAIN_HAND;
        player.setItemInHand(main, scroll);
        var result = player.gameMode.useItem(player, player.level(), scroll, main);
        test.assertTrue(result instanceof InteractionResult.Success && scroll.isEmpty(), "凡人读卷未消耗一卷");
        var known = TechniqueFabric.service().known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId()).collect(Collectors.toSet());
        test.assertTrue(known.containsAll(Set.of(Form.THRUST.id, Form.LIFT.id)), "读卷未学齐刺撩");
    }

    private static void cast(GameTestHelper test, ServerPlayer player, Form form) {
        var reference = TechniqueReference.newBuilder()
                .setId(form.id)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        var result = TechniqueFabric.service().cast(player.getUUID(), request);
        test.assertTrue(result.hasBinding(), "持剑起手失败 " + result);
    }

    private static void checkWork(GameTestHelper test, ServerPlayer player, Form form, InteractionHand hand,
                                  int previous) {
        var receipts = YunxiWorkReceipts.read(player);
        int count = receipts.size() - previous;
        test.assertTrue(count == 1, "每次施放只应有一笔做功，实际笔数 " + count);
        var paid = receipts.getLast().getExecuteActivity();
        var efforts = paid.getEffortsList();
        var mainArm = player.getMainArm();
        HumanoidArm arm;
        if (hand == InteractionHand.OFF_HAND) {
            arm = mainArm.getOpposite();
        } else {
            arm = mainArm;
        }
        String side = arm.getSerializedName() + "_";
        var expected = Set.of(side + "biceps_brachii", side + "triceps_brachii", side + "brachioradialis");
        var actual = efforts.stream().map(effort -> effort.getTissueId()).collect(Collectors.toSet());
        test.assertTrue(efforts.size() == MUSCLES && actual.equals(expected), "扣费肌肉错误 " + actual);
        boolean exact = efforts.stream().allMatch(effort ->
                effort.getWatts() == form.watts && effort.getSeconds() == WORK_SECONDS);
        test.assertTrue(paid.getAccountsCount() == 0 && exact, "实付剂量错误 " + efforts);
    }

    private record Target(ObservedCow mob, int expected, String name) {
        void check(GameTestHelper test, Form form, String suffix) {
            test.assertTrue(mob.hits == expected, name + " 预期受击 " + expected + " 实际 " + mob.hits);
            if (expected > 0) {
                test.assertTrue((form.id + suffix).equals(mob.damageType), name + " 伤害手别错误 " + mob.damageType);
                test.assertTrue(mob.damage == form.dose, name + " 铁剑伤害倍率错误 " + mob.damage);
                test.assertTrue(mob.getHealth() < mob.getMaxHealth(), name + " 命中后未受伤");
            }
        }
    }

    private static final class ObservedCow extends Cow {
        private int hits;
        private String damageType;
        private float damage;

        ObservedCow(ServerLevel level) {
            super(EntityTypes.COW, level);
        }

        @Override
        public boolean isPushable() {
            // 固定夹具站位，排除目标间碰撞挤出扇形的影响。
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            // 只记录剑式进入原版受击入口的次数，实际生命损耗仍由宿主结算。
            var key = source.typeHolder().unwrapKey().orElseThrow().identifier();
            if (key.getNamespace().equals("meridian_story")) {
                hits++;
                damageType = key.toString();
                damage = amount;
            }
            return super.hurtServer(level, source, amount);
        }
    }

    private static List<Target> targets(GameTestHelper test, ServerPlayer player, Encounter encounter) {
        var origin = player.position();
        if (encounter == Encounter.FRONT) {
            return List.of(target(test, origin.add(0, 0, FAR), 1, "前方目标"),
                    target(test, origin.add(0, 0, SECOND_FORWARD), 0, "直刺后方第二人"),
                    target(test, origin.add(SIDE, 0, NEAR), 0, "窄扇外"));
        }
        if (encounter == Encounter.PAIR) {
            return List.of(target(test, origin.add(-SIDE, 0, NEAR), 1, "并排左"),
                    target(test, origin.add(SIDE, 0, NEAR), 1, "并排右"),
                    target(test, origin.add(0, 0, THIRD_NEAR), 0, "近扇内第三人"),
                    target(test, origin.add(OUTSIDE, 0, NEAR_FORWARD), 0, "宽扇外"));
        }
        return List.of(target(test, origin.add(DISTANT_SIDE, 0, FAR), 0, "远处侧敌"),
                target(test, origin.add(OUTSIDE, 0, NEAR_FORWARD), 0, "近处扇外"));
    }

    private static Target target(GameTestHelper test, Vec3 position, int expected, String name) {
        var level = test.getLevel();
        var mob = new ObservedCow(level);
        mob.setNoAi(true /* noAi */);
        mob.setNoGravity(true /* noGravity */);
        mob.setPos(position.x, position.y, position.z);
        test.assertTrue(level.addFreshEntity(mob), "目标未生成 " + name);
        return new Target(mob, expected, name);
    }
}
