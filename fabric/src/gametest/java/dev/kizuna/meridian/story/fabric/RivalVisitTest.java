package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.server.StoryProgress;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/**
 * 陈恪经正式回冢入口到场：聊天约剑、服务端复核不满足时由他开口提示，满足后立架；离开空坪散场，当天不再来。
 * 调试指令请来的陈恪对没学两式的人只给提示；邀请只管那一次回冢。
 */
public final class RivalVisitTest {
    private static final int TIMEOUT = 2600;
    private static final String PREFIX = "story.meridian_story.rival.";
    private static final int NEAR = 2;
    private static final int OUT_OF_ARENA = 12;
    private static final int GUARD_READY = 12;
    private static final int STAY_ON = 60;
    private static final int DISTANT = 100;
    private static final int SETTLE = 40;

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_visit")
    public void swordsmanArrivesInvitesAndChecksAcceptance(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        var player = owner.player();
        returned(test, owner)
                .thenWaitUntil(() -> test.assertTrue(rival(owner) instanceof RivalSwordsman,
                        "陈恪未到场 " + diagnose(owner)))
                .thenWaitUntil(() -> test.assertTrue(said(owner, "invite"), "陈恪未约剑"))
                .thenExecute(() -> {
                    test.assertTrue(narrated(owner, "scene"), "到场没有旁白");
                    test.assertTrue(((RivalSwordsman) rival(owner)).getMainHandItem().is(Items.IRON_SWORD),
                            "陈恪手里没剑");
                    owner.choose(owner.offers().getLast(), 0);
                })
                .thenWaitUntil(() -> test.assertTrue(said(owner, "unarmed"), "空手应约没有提示"))
                .thenExecute(() -> {
                    test.assertFalse(RivalDuel.active(player.getUUID()), "空手应约却开了场");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    owner.choose(owner.offers().getLast(), 0);
                    test.assertTrue(RivalDuel.active(player.getUUID()), "持剑在空坪应约未开场");
                    test.assertTrue(narrated(owner, "stand"), "开场没有旁白");
                })
                .thenExecuteAfter(GUARD_READY, () -> {
                    test.assertTrue(rival(owner) instanceof RivalSwordsman, "成架前陈恪离场 " + diagnose(owner));
                    test.assertTrue(TechniqueFabric.service().activeGuard(rival(owner).getUUID()).isPresent(),
                            "陈恪未成架");
                    test.assertTrue(said(owner, "ready"), "成架后没说好了");
                    move(owner, GraveSite.get(player.level()).guest().east(OUT_OF_ARENA));
                })
                .thenWaitUntil(() -> test.assertTrue(rival(owner) == null, "离开空坪后陈恪未走"))
                .thenWaitUntil(() -> test.assertTrue(record(owner).hasRivalSetback(), "散场未记冷却"))
                .thenExecuteAfter(STAY_ON, () -> {
                    test.assertTrue(rival(owner) == null, "散场当天又来了");
                    test.assertFalse(record(owner).getRivalWon(), "散场被记成胜场");
                    owner.close();
                })
                .thenSucceed();
    }

    /** 冢址全世界只有一处，与上一条同批跑会互相占空坪，单独一批。 */
    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:rival_summon")
    public void summonedSwordsmanRefusesUnlearnedKeeper(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test, GraveVisits.ORIGIN));
        var player = owner.player();
        var stale = new AtomicReference<StoryPlayer.Invitation>();
        var sent = new AtomicInteger();
        summoned(test, owner)
                .thenWaitUntil(() -> test.assertTrue(said(owner, "invite"), "召来的陈恪未约剑 " + diagnose(owner)))
                .thenExecute(() -> owner.choose(owner.offers().getLast(), 0))
                .thenWaitUntil(() -> test.assertTrue(said(owner, "unlearned"), "没学两式应约没有提示"))
                .thenExecute(() -> {
                    test.assertFalse(RivalDuel.active(player.getUUID()), "没学两式却开了场");
                    test.assertTrue(rival(owner) instanceof RivalSwordsman, "提示后陈恪走了");
                    stale.set(owner.offers().getLast());
                    sent.set(owner.offers().size());
                    // 学齐两式、记下首次祭扫再走远：下一次回冢他按正式条件来。
                    YunxiPracticeBody.practitioner(player);
                    var service = TechniqueFabric.service();
                    service.learn(player.getUUID(), LIFT);
                    service.learn(player.getUUID(), TWIN);
                    sweep(test, owner);
                    move(owner, GraveSite.get(player.level()).guest().east(DISTANT));
                })
                .thenWaitUntil(() -> test.assertTrue(rival(owner) == null, "走远后陈恪未走"))
                .thenExecute(() -> approach(owner))
                .thenWaitUntil(() -> test.assertTrue(rival(owner) instanceof RivalSwordsman
                        && owner.offers().size() > sent.get(), "学齐两式回冢后陈恪未重新约剑"))
                .thenExecute(() -> {
                    owner.choose(stale.get(), 0);
                    test.assertFalse(RivalDuel.active(player.getUUID()), "上一次回冢的邀请仍然开了场");
                    owner.choose(owner.offers().getLast(), 0);
                    test.assertTrue(RivalDuel.active(player.getUUID()), "这次回冢的邀请未开场");
                    owner.close();
                })
                .thenSucceed();
    }

    private static GameTestSequence summoned(GameTestHelper test, StoryPlayer owner) {
        // 没学剑的守墓人持剑，经调试指令请他，再站到冢前。
        var player = owner.player();
        return settled(test, owner).thenExecute(() -> {
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            var server = player.level().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "meridian debug rival summon " + player.getScoreboardName());
            approach(owner);
        });
    }

    private static GameTestSequence returned(GameTestHelper test, StoryPlayer owner) {
        // 学齐两式、记下首次祭扫，空手先走远再回到冢前。
        var player = owner.player();
        return settled(test, owner)
                .thenExecute(() -> {
                    YunxiGameTest.prepare(test, player);
                    YunxiPracticeBody.practitioner(player);
                    TechniqueFabric.service().learn(player.getUUID(), TWIN);
                    sweep(test, owner);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    move(owner, GraveSite.get(player.level()).guest().east(DISTANT));
                })
                .thenExecuteAfter(SETTLE, () -> approach(owner));
    }

    static GameTestSequence settled(GameTestHelper test, StoryPlayer owner) {
        // 正式出身入口定下守墓人，等冢址和遗物。
        var player = owner.player();
        return test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(owner.packets()
                        .anyMatch(packet -> packet.type().equals(OriginNetworking.OFFER.type())), "等待出身卡"))
                .thenExecute(() -> owner.chooseOrigin(GraveVisits.ORIGIN))
                .thenWaitUntil(() -> test.assertTrue(GraveSite.get(player.level()).anchor().isPresent(),
                        "等待祖冢登记完成"))
                .thenWaitUntil(() -> test.assertTrue(record(owner).getDelivered() == GraveItems.GIFTS.size(),
                        "等待遗物交付"));
    }

    static void approach(StoryPlayer owner) {
        // 假玩家挪位不带区块票：冢址区块只靠一次性载入，到期卸载后陈恪随之离场，回来时又按初到招呼。
        // 测试世界里把冢址区块常驻，先同步载入再走回去。
        var level = (ServerLevel) owner.player().level();
        var guest = GraveSite.get(level).guest();
        var chunk = ChunkPos.containing(guest);
        level.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        level.getChunkAt(guest);
        move(owner, guest.east(NEAR));
    }

    static String diagnose(StoryPlayer owner) {
        var player = owner.player();
        var level = player.level();
        var guest = GraveSite.get(level).guest();
        var grave = record(owner);
        return "player=" + player.blockPosition() + " guest=" + guest + " chunk=" + level.hasChunkAt(guest)
                + " floor=" + level.getBlockState(guest.below()) + " feet=" + level.getBlockState(guest)
                + " swept=" + grave.getFirstSwept() + " ready=" + RivalVisit.ready(player) + " lot=" + grave.getLot()
                + " setback=" + grave.hasRivalSetback() + " won=" + grave.getRivalWon()
                + " lastVigil=" + grave.getLastVigil();
    }

    static void sweep(GameTestHelper test, StoryPlayer owner) {
        // 直接写首次祭扫，下一次回冢就该见到他。
        var player = owner.player();
        var body = new PhysiqueScene(player).body();
        var progress = StoryProgress.read(body);
        var swept = progress.getGrave().toBuilder().setFirstSwept(true /* firstSwept */).build();
        var next = progress.toBuilder().setGrave(swept).build();
        test.assertTrue(new StoryBodies().write(player, body, next), "首次祭扫未写入");
    }

    static Entity rival(StoryPlayer owner) {
        var player = owner.player();
        return player.level().getEntity(RivalVisit.guest(player.getUUID()));
    }

    static boolean said(StoryPlayer owner, String line) {
        var expected = Component.translatable(PREFIX + line);
        return owner.packets().filter(BubblePayload.class::isInstance).map(BubblePayload.class::cast)
                .anyMatch(bubble -> expected.equals(bubble.line()));
    }

    static boolean narrated(StoryPlayer owner, String line) {
        return owner.chat().stream().anyMatch(message -> message.getContents() instanceof TranslatableContents text
                && text.getKey().equals(PREFIX + line));
    }

    static GraveKeeper record(StoryPlayer owner) {
        return StoryProgress.read(new PhysiqueScene(owner.player()).body()).getGrave();
    }

    static void move(StoryPlayer owner, BlockPos pos) {
        owner.player().setPos(Vec3.atBottomCenterOf(pos));
    }
}
