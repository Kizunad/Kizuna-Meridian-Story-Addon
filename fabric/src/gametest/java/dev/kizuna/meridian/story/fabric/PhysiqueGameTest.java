package dev.kizuna.meridian.story.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.human.server.HumanPhysique;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginCard;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PhysiqueGameTest {
    private static final int TIMEOUT = 750;
    private static final int REJOIN = 6;
    private static final int FARMER_STANDING = 20;
    private static final int TICKS_PER_SECOND = 20;
    private static final int EIGHT_SECONDS = 8 * TICKS_PER_SECOND;
    private static final int NINETY_SECONDS = 90 * TICKS_PER_SECOND;
    private static final int OBSERVE = 25;
    private static final int SHIELD_TICKS = 500;
    private static final int SHIELD_LEVEL = 4;
    private static final int FOOD_WITHOUT_REGENERATION = 10;
    private static final double JUST_ABOVE = 0.351;
    private static final double TRIGGER = 0.35;
    private static final double RECOVERED = 0.50;
    private static final double EPSILON = 0.000001;
    private static final double ATTACK_SPEED = 1.10;
    private static final double BURST = 1.25;
    private static final String COMMON = "meridian_story_test:roadside";
    private static final String ARM = "right_biceps_brachii";

    @GameTest(maxTicks = TIMEOUT)
    public void ordinaryKeepsBaselineAndSharesTraderAcrossRelog(GameTestHelper test) {
        var id = StoryPlayer.offered(test, PhysiqueScene.ORDINARY, PhysiqueScene.FARMER);
        var first = StoryPlayer.join(test, id);
        var farmer = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.FARMER));
        var villager = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        var trader = test.spawn(EntityTypes.WANDERING_TRADER, 1, 1, 1);
        var next = new StoryPlayer[1];
        var sequence = test.startSequence().thenWaitUntil(() -> {
            test.assertTrue(!cards(first).isEmpty() && !cards(farmer).isEmpty(), "等待两位玩家的真实候选");
        }).thenExecute(() -> {
            var offer = cards(first).getFirst();
            test.assertTrue(card(offer, PhysiqueScene.ORDINARY).getIcon().equals("minecraft:bread")
                    && card(offer, PhysiqueScene.FARMER).getIcon().equals("minecraft:wheat"), "面包和小麦未并列");
            first.chooseOrigin(PhysiqueScene.ORDINARY);
            farmer.chooseOrigin(PhysiqueScene.FARMER);
        }).thenWaitUntil(() -> {
            selected(test, first, PhysiqueScene.ORDINARY);
            selected(test, farmer, PhysiqueScene.FARMER);
            test.assertTrue(first.state(COMMON).getChapter().equals("meeting"), "平常出身未接通用线");
        });
        sequence.thenExecute(() -> {
            test.assertTrue(villager.getPlayerReputation(first.player()) == 0, "平常出身不应得到村民声望");
            test.assertTrue(villager.getPlayerReputation(farmer.player()) == FARMER_STANDING, "农户声望没有加二十");
            var origin = Origins.chosen(first.player()).orElseThrow();
            test.assertTrue(origin.talents().isEmpty() && origin.threats().isEmpty(), "平常出身多了天赋或麻烦");
            for (var actor : List.of(first, farmer)) {
                actor.player().setPos(trader.position());
                StoryGameTest.use(actor, trader);
                actor.choose(actor.offers().getLast());
                test.assertTrue(actor.state(COMMON).getEnding().equals("met"), "两种出身未走到同一通用事件结局");
            }
            first.close();
            farmer.close();
        }).thenExecuteAfter(REJOIN, () -> next[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> selected(test, next[0], PhysiqueScene.ORDINARY))
                .thenExecute(() -> verifyOrdinary(test, next[0])).thenSucceed();
    }

    private static void verifyOrdinary(GameTestHelper test, StoryPlayer actor) {
        // 重新连入后经玩家指令复看空卡，再读此人的同一具身体。
        var player = actor.player();
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                "meridian body origin");
        var readOnly = cards(actor).getLast();
        var retained = card(readOnly, PhysiqueScene.ORDINARY);
        test.assertTrue(readOnly.getChosen() && retained.getTalentLines() == 0
                && retained.getThreatLines() == 0, "重登复看的不是平常空卡");
        test.assertTrue(actor.state(COMMON).getEnding().equals("met"), "重登丢了通用事件结局");
        var body = new PhysiqueScene(player).body();
        test.assertTrue(!body.containsAddonProgress(HumanPhysique.KEY), "普通身体占用体质进度键");
        test.assertTrue(!HumanPhysique.read(body).getSlowOpening(), "平常出身开脉被延长");
        var arm = new Anatomy.TissueId(ARM);
        near(test, TissueCapabilities.enduranceMultiplier(BodyProtobuf.fromMessage(body), arm), 1, "平常出身肌肉基线");
        actor.close();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void woundedBodyIgnoresAbsorptionAndClearsAtHalf(GameTestHelper test) {
        var actor = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.LONE));
        var scene = new PhysiqueScene(actor.player());
        var player = actor.player();
        var speed = new double[1];
        choose(test, actor, PhysiqueScene.LONE).thenExecute(() -> {
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SHIELD_TICKS, SHIELD_LEVEL));
            scene.injureTo(JUST_ABOVE);
            speed[0] = player.getAttributeValue(Attributes.ATTACK_SPEED);
        }).thenExecuteAfter(OBSERVE, () -> {
            test.assertTrue(scene.effect() == null && player.getAbsorptionAmount() > 0, "阈值之上误触或未建黄心");
            scene.injureTo(TRIGGER);
        }).thenWaitUntil(() -> test.assertTrue(scene.effect() != null, "身体到了35%仍有黄心，应触发咬牙还手"))
                .thenExecute(() -> {
                    test.assertTrue(scene.effect().getAmplifier() == 1, "咬牙还手应为二级");
                    near(test, player.getAttributeValue(Attributes.ATTACK_SPEED), speed[0] * ATTACK_SPEED, "攻速");
                    compareBurst(test, scene);
                    scene.heal();
                    scene.injureTo(RECOVERED);
                }).thenWaitUntil(() -> test.assertTrue(
                        scene.effect() == null && scene.physique().getBurstUntilTick() == 0,
                        "身体回到50%应撤去增益"))
                .thenExecute(() -> {
                    near(test, player.getAttributeValue(Attributes.ATTACK_SPEED), speed[0], "恢复后攻速");
                    String assign = "meridian debug origin set " + player.getScoreboardName()
                            + " " + PhysiqueScene.ORDINARY;
                    var server = player.level().getServer();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), assign);
                    selected(test, actor, PhysiqueScene.ORDINARY);
                    var plain = scene.physique();
                    test.assertTrue(!plain.getEnduringLimbs() && !plain.getSlowOpening() && plain.getReadyAtTick() == 0,
                            "换回平常出身后仍残留旧体质");
                    actor.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void milkAndRelogPreserveCooldownAndRequireRecovery(GameTestHelper test) {
        var id = StoryPlayer.offered(test, PhysiqueScene.LONE);
        var first = StoryPlayer.join(test, id);
        first.player().getFoodData().setFoodLevel(FOOD_WITHOUT_REGENERATION);
        var scene = new PhysiqueScene(first.player());
        var next = new StoryPlayer[1];
        var times = new long[2];
        // 内存连接不在原版套接字列表里。补投连接 tick，推动真实在线统计和状态剩余刻。
        test.onEachTick(() -> {
            var joined = next[0];
            var online = joined == null ? first : joined;
            if (online.connection().isConnected()) {
                online.connection().tick();
            }
        });
        var sequence = choose(test, first, PhysiqueScene.LONE)
                .thenWaitUntil(() -> readyForPhysique(test, first))
                .thenExecute(() -> scene.injureTo(TRIGGER))
                .thenWaitUntil(() -> test.assertTrue(scene.effect() != null, "等待第一次爆发")).thenExecute(() -> {
                    var active = scene.physique();
                    times[0] = active.getReadyAtTick();
                    times[1] = active.getBurstUntilTick();
                    test.assertTrue(times[0] - times[1] == NINETY_SECONDS - EIGHT_SECONDS,
                            "冷却与效果没有从同一在线刻起算");
                    first.close();
                }).thenExecuteAfter(REJOIN, () -> next[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> selected(test, next[0], PhysiqueScene.LONE)).thenExecute(() -> {
                    var actor = next[0].player();
                    verifyRelogAndDrink(test, actor, times);
                }).thenExecuteAfter(OBSERVE, () -> {
                    var actor = next[0].player();
                    var restored = new PhysiqueScene(actor);
                    test.assertTrue(restored.effect() == null && restored.physique().getReadyAtTick() == times[0],
                            "喝奶刷新了爆发或冷却");
                    actor.awardStat(Stats.PLAY_TIME, NINETY_SECONDS);
                }).thenExecuteAfter(OBSERVE, () -> {
                    var restored = new PhysiqueScene(next[0].player());
                    test.assertTrue(restored.effect() == null, "未回过半血却持续重触");
                    restored.heal();
                }).thenExecuteAfter(OBSERVE, () -> new PhysiqueScene(next[0].player()).injureTo(TRIGGER))
                .thenWaitUntil(() -> test.assertTrue(new PhysiqueScene(next[0].player()).effect() != null,
                        "冷却过且回过半血后不能再触发"));
        verifyExpiry(test, next, sequence);
    }

    private static void verifyExpiry(GameTestHelper test, StoryPlayer[] next, GameTestSequence sequence) {
        // 再触发后先看第七秒仍在；到时虽回过半血，在新一轮冷却内仍不能再用。
        sequence.thenExecuteAfter(EIGHT_SECONDS - REJOIN, () ->
                test.assertTrue(new PhysiqueScene(next[0].player()).effect() != null, "八在线秒以前提前收势"))
                .thenExecuteAfter(OBSERVE, () -> {
                    var scene = new PhysiqueScene(next[0].player());
                    test.assertTrue(scene.effect() == null && scene.physique().getBurstUntilTick() == 0,
                            "满八在线秒效果仍在");
                    scene.heal();
                }).thenExecuteAfter(OBSERVE, () -> new PhysiqueScene(next[0].player()).injureTo(TRIGGER))
                .thenExecuteAfter(OBSERVE, () -> {
                    test.assertTrue(new PhysiqueScene(next[0].player()).effect() == null, "九十在线秒以内回过血就重触");
                    next[0].close();
                }).thenSucceed();
    }

    private static void verifyRelogAndDrink(GameTestHelper test, ServerPlayer actor, long[] times) {
        // 同一有效期跨过重登，随后喝奶须在返回物品栏以前收回功率。
        var restored = new PhysiqueScene(actor);
        test.assertTrue(restored.physique().getReadyAtTick() == times[0], "重登清了九十秒冷却");
        test.assertTrue(restored.effect() != null && restored.physique().getBurstUntilTick() == times[1],
                "重登没有续原来八秒的剩余时间");
        new ItemStack(Items.MILK_BUCKET).finishUsingItem(actor.level(), actor);
        test.assertTrue(restored.physique().getBurstUntilTick() == 0, "喝奶当刻双臂仍有爆发功率");
    }

    static GameTestSequence choose(GameTestHelper test, StoryPlayer actor, String id) {
        // 经真实选择包进入宿主，不跳过出身就绪事件。
        return test.startSequence().thenWaitUntil(() -> test.assertTrue(!cards(actor).isEmpty(), "等待正式候选"))
                .thenExecute(() -> actor.chooseOrigin(id)).thenWaitUntil(() -> selected(test, actor, id));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void deathRemovesBurstAndRespawnRestoresOnlyEndurance(GameTestHelper test) {
        var first = StoryPlayer.join(test, StoryPlayer.offered(test, PhysiqueScene.LONE));
        var scene = new PhysiqueScene(first.player());
        var replacement = new ServerPlayer[1];
        var expiry = new long[1];
        choose(test, first, PhysiqueScene.LONE).thenExecute(() -> scene.injureTo(TRIGGER))
                .thenWaitUntil(() -> test.assertTrue(scene.effect() != null, "等待重伤爆发")).thenExecute(() -> {
                    expiry[0] = scene.physique().getReadyAtTick();
                    var player = first.player();
                    player.kill(player.level());
                    test.assertTrue(!player.isAlive() && scene.effect() == null, "死亡留下旧状态");
                    var respawn = new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
                    player.connection.handleClientCommand(respawn);
                    replacement[0] = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
                }).thenExecuteAfter(OBSERVE, () -> {
                    var respawn = new PhysiqueScene(replacement[0]);
                    var traits = respawn.physique();
                    test.assertTrue(traits.getEnduringLimbs() && respawn.effect() == null
                            && traits.getBurstUntilTick() == 0, "复活未分清常态与短时能力");
                    test.assertTrue(traits.getReadyAtTick() == expiry[0], "死亡刷新了原来的冷却");
                    first.close();
                }).thenSucceed();
    }

    static void selected(GameTestHelper test, StoryPlayer actor, String id) {
        test.assertTrue(Origins.chosen(actor.player()).filter(value -> value.id().toString().equals(id)).isPresent(),
                "等待出身 " + id);
    }

    private static void readyForPhysique(GameTestHelper test, StoryPlayer actor) {
        selected(test, actor, PhysiqueScene.LONE);
        test.assertTrue(new PhysiqueScene(actor.player()).physique().getEnduringLimbs(), "等待孤星体质同步");
    }

    private static void compareBurst(GameTestHelper test, PhysiqueScene scene) {
        // 同一受伤快照去掉发力标记，避免把健康率降低误作增益丢失。
        var body = scene.body();
        var atRest = scene.physique().toBuilder().clearBurstUntilTick().build();
        var baseline = body.toBuilder()
                .putAddonProgress(HumanPhysique.KEY, com.google.protobuf.Any.pack(atRest))
                .build();
        var wounded = BodyProtobuf.fromMessage(body);
        var rested = BodyProtobuf.fromMessage(baseline);
        for (String name : List.of(ARM, "left_biceps_brachii", "left_gluteus_maximus", "diaphragm")) {
            var id = new Anatomy.TissueId(name);
            double actual = TissueCapabilities.available(wounded, id).burstWatts();
            double base = TissueCapabilities.available(rested, id).burstWatts();
            double factor = name.endsWith("biceps_brachii") ? BURST : 1;
            near(test, actual, base * factor, "低血爆发 " + name);
        }
    }

    static void near(GameTestHelper test, double actual, double expected, String reason) {
        test.assertTrue(Math.abs(actual - expected) < EPSILON, reason + " 实得 " + actual + " 预期 " + expected);
    }

    private static List<OriginOffer> cards(StoryPlayer actor) {
        return actor.packets().filter(packet -> packet.type().equals(OriginNetworking.OFFER.type()))
                .map(packet -> parse((ProtobufChannel.Payload) packet)).toList();
    }

    private static OriginOffer parse(ProtobufChannel.Payload packet) {
        try {
            return OriginOffer.parseFrom(packet.bytes());
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private static OriginCard card(OriginOffer offer, String id) {
        return offer.getCardsList().stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow();
    }
}
