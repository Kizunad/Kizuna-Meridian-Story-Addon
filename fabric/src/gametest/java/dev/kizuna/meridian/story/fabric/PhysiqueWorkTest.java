package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.human.fabric.StoryMotionFixture;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

public final class PhysiqueWorkTest {
    private static final double ENDURANCE = 1.20;
    private record Charge(double joules, double fatigue) { }

    @GameTest
    public void paidMovementsSaveLimbFatigueAndLeaveBreathingAlone(GameTestHelper test) {
        // 从实际选出身的玩家测步态、付费跃步和手持铁剑的完整挥击入口。
        var lone = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.LONE));
        var plain = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.ORDINARY));
        PhysiqueGameTest.choose(test, lone, PhysiqueScene.LONE)
                .thenExecute(() -> plain.chooseOrigin(PhysiqueScene.ORDINARY))
                .thenWaitUntil(() -> PhysiqueGameTest.selected(test, plain, PhysiqueScene.ORDINARY))
                .thenExecute(() -> {
                    var normal = body(plain);
                    var solitary = body(lone);
                    var diaphragm = new Anatomy.TissueId("diaphragm");
                    var load = new TissueCapabilities.Exertion(diaphragm, 0, 1, 1);
                    var plainBreath = charge(normal, TissueCapabilities.exert(normal, load));
                    var loneBreath = charge(solitary, TissueCapabilities.exert(solitary, load));
                    PhysiqueGameTest.near(test, loneBreath.fatigue(), plainBreath.fatigue(), "膈肌同功疲劳被改变");
                    for (var pace : StoryMotionFixture.Pace.values()) {
                        compare(test, charge(normal, StoryMotionFixture.move(normal, pace)),
                                charge(solitary, StoryMotionFixture.move(solitary, pace)), pace.name());
                    }
                    compare(test, leap(test, plain), leap(test, lone), "付费跃步");
                    compare(test, strike(plain), strike(lone), "铁剑挥击");
                    plain.close();
                    lone.close();
                }).thenSucceed();
    }

    private static Charge leap(GameTestHelper test, StoryPlayer actor) {
        var before = body(actor);
        var registered = TechniqueStatusBenefits.get("meridian_basic_techniques:light_body");
        var paid = TechniqueFabric.service().submitLeap(actor.player().getUUID(), registered.technique(),
                registered.benefit().getLeap(), UUID.randomUUID().toString());
        test.assertTrue(paid.accepted(), "跃步未实际付费 " + paid.rejection());
        double work = paid.efforts().stream().mapToDouble(effort -> effort.getWatts() * effort.getSeconds()).sum();
        var bill = charge(before, body(actor));
        PhysiqueGameTest.near(test, bill.joules(), work, "跃步组织实扣与账单焦耳");
        return bill;
    }

    private static Charge strike(StoryPlayer actor) {
        actor.player().setGameMode(GameType.SURVIVAL);
        actor.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        var before = body(actor);
        VanillaStrikeContext.swing(actor.player(), InteractionHand.MAIN_HAND);
        return charge(before, body(actor));
    }

    private static BodySnapshot body(StoryPlayer actor) {
        return BodyProtobuf.fromMessage(new PhysiqueScene(actor.player()).body());
    }

    private static Charge charge(BodySnapshot before, BodySnapshot after) {
        // 按每块组织实际疲劳变化和用前可用耐力，反算该块肌肉所付的焦耳。
        double work = 0;
        double fatigue = 0;
        for (int i = 0; i < before.tissueStates().size(); i++) {
            var state = before.tissueStates().get(i);
            double delta = after.tissueStates().get(i).fatigue() - state.fatigue();
            if (delta <= 0) {
                continue;
            }
            var tissue = before.structure().tissues().stream().filter(it -> it.id().equals(state.id()))
                    .findFirst().orElseThrow();
            double remaining = tissue.fatigueCapacity() - state.fatigue();
            work += delta / remaining * TissueCapabilities.available(before, state.id()).enduranceJoules();
            fatigue += delta;
        }
        return new Charge(work, fatigue);
    }

    private static void compare(GameTestHelper test, Charge normal, Charge lone, String reason) {
        test.assertTrue(normal.joules() > 0 && lone.joules() > 0, reason + "没有真实收支");
        PhysiqueGameTest.near(test, lone.joules(), normal.joules(), reason + "同功");
        PhysiqueGameTest.near(test, lone.fatigue(), normal.fatigue() / ENDURANCE, reason + "同功疲劳");
        org.slf4j.LoggerFactory.getLogger("meridian_story_test").info("{} 普通 {} / 孤星 {}", reason, normal, lone);
    }
}
