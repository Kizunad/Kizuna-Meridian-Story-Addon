package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

public final class StoryRestartGameTest {
    private static final int TIMEOUT = 500;
    private static final Path PLAYER = Path.of("story-restart-player.txt");

    @GameTest(maxTicks = TIMEOUT)
    public void receiptCrossesServerRestart(GameTestHelper test) throws IOException {
        if ("save".equals(System.getProperty("meridian.story.restart"))) {
            save(test);
            return;
        }
        var saved = Files.readAllLines(PLAYER);
        UUID id = UUID.fromString(saved.getFirst());
        long savedWorldTick = Long.parseLong(saved.getLast());
        // GameTestServer 会重新起算世界时钟；让它自然走过上次退出时刻，再让宿主接入原身体。
        var owner = new StoryPlayer[1];
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(test.getLevel().getGameTime() > savedWorldTick, "等待世界钟续上"))
                .thenExecute(() -> owner[0] = StoryPlayer.join(test, id))
                .thenWaitUntil(() -> test.assertTrue(owner[0].state().getChapter().equals("witness"), "重开后没续胜场"))
                .thenExecute(() -> {
                    var state = owner[0].state();
                    var claim = state.getClaimsOrThrow("register");
                    test.assertTrue(claim.getEncounter() == 1 && state.getLastEncounter() == 1 && !claim.getSpent(),
                            "重启后场次或补领凭据不符");
                    var crisis = CrisisEncounters.current(owner[0].player()).orElseThrow();
                    test.assertTrue(crisis.getSettledCount() == 1 && crisis.getReceiptsCount() == 0,
                            "重开后没有确认已经提交的同场回执");
                    owner[0].close();
                }).thenSucceed();
    }

    private static void save(GameTestHelper test) {
        UUID id = StoryPlayer.offered(test);
        var owner = StoryPlayer.join(test, id);
        var leader = test.spawn(EntityTypes.PILLAGER, 1, 1, 1);
        StoryGameTest.chosen(test, owner).thenExecute(() -> {
            StoryRaidFixture.arrive(owner.player(), leader, StoryPlayer.ORIGIN + "#0");
            leader.die(owner.player().damageSources().playerAttack(owner.player()));
            var record = CrisisEncounters.current(owner.player()).orElseThrow();
            test.assertTrue(record.getReceiptsCount() == 1 && owner.state().getClaimsCount() == 0,
                    "必须在剧情确认以前存盘");
            owner.close();
            try {
                Files.write(PLAYER, java.util.List.of(id.toString(), Long.toString(test.getLevel().getGameTime())));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }).thenSucceed();
    }
}
