package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.meridian.story.fabric.DialogueScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class DialogueClientTest implements ClientModInitializer {
    private static final int TIMEOUT = 1800;
    private static final int SETTLE = 12;
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private final ChatChoices choices = new ChatChoices();
    private int ticks;
    private int opened;
    private UUID actor;
    private Stage stage = Stage.OPEN;
    private enum Stage { OPEN, APPROACH, FIRST, CAPTURING, CLOSE, SECOND, VERIFY, DONE }

    @Override
    public void onInitializeClient() {
        if ("dialogue".equals(System.getProperty("meridian.story.scene"))) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        ticks++;
        if (stage == Stage.DONE) {
            return;
        }
        if (ticks > TIMEOUT) {
            finish(client, "等待 " + stage + " 超时");
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.gui.overlay() != null) {
            return;
        }
        try {
            UUID owner = client.player.getUUID();
            advance(client, server, owner);
        } catch (RuntimeException failed) {
            finish(client, failed.toString());
        }
    }

    private void advance(Minecraft client, MinecraftServer server, UUID owner) {
        switch (stage) {
            case OPEN -> open(server, owner);
            case APPROACH -> {
                if (++opened >= SETTLE) {
                    server.submit(() -> DialogueScene.reopen(player(server, owner), actor)).join();
                    opened = 0;
                    stage = Stage.FIRST;
                }
            }
            case FIRST -> capture(client);
            case CLOSE -> reopen(client, server, owner);
            case SECOND -> {
                if (choices.locate(client)) {
                    choices.click(client);
                    stage = Stage.VERIFY;
                }
            }
            case VERIFY -> {
                var reply = Component.translatable("chat.meridian_story.agreed").withStyle(ChatFormatting.GRAY);
                if (choices.received(reply) && server.submit(() -> DialogueScene.done(player(server, owner))).join()) {
                    finish(client, null);
                }
            }
            default -> { }
        }
    }

    private static ServerPlayer player(MinecraftServer server, UUID owner) {
        return server.getPlayerList().getPlayer(owner);
    }

    private void open(MinecraftServer server, UUID owner) {
        actor = server.submit(() -> DialogueScene.open(server, player(server, owner))).join();
        if (actor != null) {
            stage = Stage.APPROACH;
        }
    }

    private void capture(Minecraft client) {
        if (!choices.locate(client) || ++opened < SETTLE) {
            return;
        }
        stage = Stage.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path shot = Path.of("../story-dialogue-" + System.getProperty("meridian.story.shots") + ".png");
                image.writeToFile(shot);
                stage = Stage.CLOSE;
            } catch (Exception failed) {
                finish(client, failed.toString());
            }
        });
    }

    private void reopen(Minecraft client, MinecraftServer server, UUID owner) {
        client.gui.screen().onClose();
        server.submit(() -> {
            var player = player(server, owner);
            if (DialogueScene.done(player)) {
                throw new IllegalStateException("关窗竟然结章");
            }
            DialogueScene.reopen(player, actor);
        }).join();
        stage = Stage.SECOND;
    }

    private void finish(Minecraft client, String failure) {
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nerror=" + failure + "\n");
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
