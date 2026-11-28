package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class BequestClientTest implements ClientModInitializer {
    private enum Stage { START, MEET, OPENING, ACCEPT, WALK, DELIVER, ENDING, CAPTURING, DONE }
    private static final int LIMIT = 3600;
    private static final int SETTLE = 35;
    private static final int CAMERA = 4;
    private static final int OFFSET = 256;
    private static final int PORTRAIT_DISTANCE = 3;
    private final ChatChoices choices = new ChatChoices();
    private BequestScene scene;
    private String caseId;
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;

    @Override
    public void onInitializeClient() {
        if ("bequest".equals(System.getProperty("meridian.story.scene"))) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.CAPTURING || stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > LIMIT) {
                throw new IllegalStateException("传功取景超时 " + stage);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            var id = client.player.getUUID();
            switch (stage) {
                case START -> {
                    client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
                    stage = server.submit(() -> prepare(server.getPlayerList().getPlayer(id))).join();
                }
                case MEET -> stage = server.submit(this::meet).join();
                case OPENING -> {
                    if (choices.offered()) {
                        capture(client, "bequest-elder", Stage.ACCEPT);
                    }
                }
                case ACCEPT, DELIVER -> {
                    if (stage == Stage.DELIVER && (!client.levelRenderer.hasRenderedAllSections()
                            || ++steady < SETTLE)) {
                        return;
                    }
                    if (choices.locate(client)) {
                        choices.click(client);
                        client.gui.setScreen(null);
                        stage = stage == Stage.ACCEPT ? Stage.WALK : Stage.ENDING;
                    }
                }
                case WALK -> stage = server.submit(this::walk).join();
                case ENDING -> {
                    if (choices.received(LastRequest.text("delivered").copy().withStyle(ChatFormatting.GRAY))) {
                        capture(client, "bequest-disciple", Stage.DONE);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private Stage prepare(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return Stage.START;
        }
        scene = new BequestScene(player, player.blockPosition().east(OFFSET));
        if (!scene.place()) {
            throw new IllegalStateException("山洞落地失败");
        }
        return Stage.MEET;
    }

    private Stage meet() {
        if (!scene.gate() || !scene.meet()) {
            return Stage.MEET;
        }
        var c = scene.current();
        if (!LastRequest.eligible(scene.player, c)) {
            throw new IllegalStateException("取景老人或学生没有受功资格");
        }
        caseId = c.getActor();
        var bedside = scene.site.at(MountainRetreats.ELDER);
        scene.move(bedside.north(CAMERA).east());
        frame(bedside);
        return Stage.OPENING;
    }

    private Stage walk() {
        var road = scene.view().progress().getRoadside();
        var paid = road.getMountainHistoryOrDefault(caseId, null);
        if (paid == null) {
            return Stage.WALK;
        }
        var gate = BlockPos.of(paid.getBequest().getGate());
        scene.move(gate.south(PORTRAIT_DISTANCE).west());
        frame(gate);
        var disciple = MountainEncounters.actor(scene.player, paid.getDisciple()).orElseThrow();
        scene.events.bequest.promise.atDisciple(scene.player, disciple);
        return Stage.DELIVER;
    }

    private void frame(BlockPos pos) {
        scene.player.lookAt(Anchor.EYES, Vec3.atCenterOf(pos.above()));
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (!client.levelRenderer.isSectionCompiledAndVisible(scene.player.blockPosition())
                || !client.levelRenderer.hasRenderedAllSections() || ++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../" + name + ".png"));
                client.execute(() -> {
                    stage = next;
                    if (next == Stage.DONE) {
                        finish(client, "");
                    }
                });
            } catch (IOException failure) {
                client.execute(() -> finish(client, failure.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        stage = Stage.DONE;
        try {
            Files.writeString(Path.of("../clienttest-results.txt"), "success=" + error.isEmpty() + "\nerror=" + error);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
