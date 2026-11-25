package dev.kizuna.meridian.story.fabric;

import java.util.List;
import dev.kizuna.meridian.story.fabric.StoryPlayer.Invitation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;

public final class ChatChoiceTest {
    private static final int TIMEOUT = 800;
    private static final int LIFETIME = 600;
    private static final int BEFORE_EXPIRY = LIFETIME - 1;
    private static final String EXPIRED = "screen.meridian_story.expired";

    @GameTest(maxTicks = TIMEOUT)
    public void onlyInvitedPlayerCanClickChatChoice(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test));
        var neighbor = StoryPlayer.join(test, StoryPlayer.offered(test));
        var actor = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        actor.setNoAi(true /* noAi */);
        var sequence = StoryGameTest.chosen(test, owner);
        sequence.thenExecute(neighbor::chooseOrigin)
                .thenWaitUntil(() -> test.assertTrue(neighbor.state().getChapter().equals("visitors"), "等待邻人接线"))
                .thenExecute(() -> {
                    for (var listener : List.of(owner, neighbor)) {
                        listener.player().setPos(actor.position());
                        StoryGameTest.use(listener, actor);
                    }
                    var invitation = owner.offers().getLast();
                    neighbor.choose(invitation);
                    test.assertTrue(neighbor.state().getEnding().isEmpty(), "旁人点别人的聊天按钮竟结了章");
                    lastReply(test, neighbor, EXPIRED);
                    owner.choose(invitation);
                    test.assertTrue(owner.state().getEnding().equals("declined"), "本人未能选择");
                    lastReply(test, owner, "chat.meridian_story.agreed");
                    owner.choose(invitation);
                    lastReply(test, owner, EXPIRED);
                    neighbor.choose(neighbor.offers().getLast());
                    test.assertTrue(neighbor.state().getEnding().equals("declined"), "别人的按钮耗掉了自己的邀请");
                    owner.close();
                    neighbor.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT, environment = "meridian_story_test:chat_expiry")
    public void retellingKeepsOriginalChatDeadline(GameTestHelper test) {
        var owner = StoryPlayer.join(test, StoryPlayer.offered(test));
        var actor = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
        actor.setNoAi(true /* noAi */);
        var first = new Invitation[1];
        StoryGameTest.chosen(test, owner).thenExecute(() -> {
            owner.player().setPos(actor.position());
            StoryGameTest.use(owner, actor);
            first[0] = owner.offers().getLast();
        }).thenExecuteAfter(BEFORE_EXPIRY, () -> {
            owner.player().setPos(actor.position());
            StoryGameTest.use(owner, actor);
            test.assertTrue(owner.offers().getLast().equals(first[0]), "重说时换了邀请");
            test.assertTrue(owner.offers().size() == 2, "重说没有补发同一行");
        }).thenExecuteAfter(1, () -> {
            owner.choose(first[0]);
            lastReply(test, owner, EXPIRED);
            test.assertTrue(owner.state().getEnding().isEmpty(), "重说延长了旧邀请");
            StoryGameTest.use(owner, actor);
            var fresh = owner.offers().getLast();
            owner.choose(fresh, fresh.buttons().size() - 1);
            lastReply(test, owner, "chat.meridian_story.later");
            test.assertTrue(owner.state().getEnding().isEmpty(), "改日再谈消耗了章节");
            owner.choose(fresh);
            lastReply(test, owner, EXPIRED);
            StoryGameTest.use(owner, actor);
            owner.choose(owner.offers().getLast());
            test.assertTrue(owner.state().getEnding().equals("declined"), "告辞后无法再谈");
            owner.close();
        }).thenSucceed();
    }

    private static void lastReply(GameTestHelper test, StoryPlayer player, String key) {
        var expected = Component.translatable(key).withStyle(ChatFormatting.GRAY);
        test.assertTrue(player.chat().getLast().equals(expected), "末行未收到灰字 " + key);
    }
}
