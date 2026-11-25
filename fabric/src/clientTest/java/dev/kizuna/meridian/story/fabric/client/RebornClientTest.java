package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.origin.fabric.client.OriginScreen;
import dev.kizuna.meridian.story.fabric.RebornScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;

public final class RebornClientTest implements ClientModInitializer {
    private static final int TIMEOUT = 1800;
    private static final int WORLD_READY = 80;
    private static final int SETTLE = 10;
    private static final int CARD_FRAMES = 3;
    private static final double PAGE_HEIGHT = 0.25;
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private enum Stage { START, ORIGIN, CARD, SCHEDULE, NOTE, NOTE_VIEW, WIN, FALL, PAGE, PAGE_VIEW, OPEN, DIALOGUE,
        HOVER, HAND_IN, CONFIRMED, BELL, CHECK_BOOK, ARCHIVE, WITNESS, PUBLISH, ENDING, TAIL_VIEW, LEAVE, DONE }

    private final ChatChoices choices = new ChatChoices();
    private Stage stage = Stage.START;
    private RebornScene scene;
    private int ticks;
    private int steady;
    private int cardFrames;
    private boolean capturing;

    @Override
    public void onInitializeClient() {
        if ("reborn".equals(System.getProperty("meridian.story.scene"))) {
            choices.listen();
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
                if (screen instanceof OriginScreen) {
                    cardFrames = 0;
                    ScreenEvents.afterExtract(screen).register((drawn, graphics, x, y, delta) -> cardFrames++);
                }
            });
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE || capturing) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("超时：" + stage);
            }
            if (client.player == null || client.getSingleplayerServer() == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            if (stage.ordinal() < Stage.WIN.ordinal()) {
                originAndOmen(client);
            } else if (stage.ordinal() < Stage.BELL.ordinal()) {
                recoverPage(client);
            } else {
                testify(client);
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private void originAndOmen(Minecraft client) {
        switch (stage) {
            case START -> start(client);
            case ORIGIN -> {
                if (onServer(client, () -> scene.watching("roll"))) {
                    client.player.connection.sendCommand("meridian body origin");
                    stage = Stage.CARD;
                }
            }
            case CARD -> {
                // 软件渲染时一帧可能跨多个客户端刻；等出身卡实际绘制后才能读取上一帧。
                if (client.gui.screen() instanceof OriginScreen && cardFrames >= CARD_FRAMES) {
                    capture(client, "00-origin", Stage.SCHEDULE);
                }
            }
            case SCHEDULE -> {
                client.gui.setScreen(null);
                act(client, () -> scene.watching("roll"), scene::schedule, Stage.NOTE);
            }
            case NOTE -> take(client, RebornScene.NOTE, Stage.NOTE_VIEW);
            case NOTE_VIEW -> inspect(client, RebornScene.NOTE, "01-omen-note", Stage.WIN);
            default -> { }
        }
    }

    private void recoverPage(Minecraft client) {
        switch (stage) {
            case WIN -> {
                client.gui.setScreen(null);
                client.gui.hud.getChat().clearMessages(false /* clearSentMessages */);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, scene.fallSite().add(0, PAGE_HEIGHT, 0));
                act(client, () -> scene.watching("roll"), scene::win, Stage.FALL);
            }
            case FALL -> {
                if (onServer(client, () -> scene.dropped(RebornScene.FRAGMENT))) {
                    capture(client, "02-leader-fall", Stage.PAGE);
                }
            }
            case PAGE -> take(client, RebornScene.FRAGMENT, Stage.PAGE_VIEW);
            case PAGE_VIEW -> inspect(client, RebornScene.FRAGMENT, "03-torn-page", Stage.OPEN);
            case OPEN -> {
                client.gui.setScreen(null);
                act(client, () -> scene.watching("seal"), scene::speak, Stage.DIALOGUE);
            }
            case DIALOGUE -> {
                if (choices.locate(client)) {
                    var eyes = client.getSingleplayerServer().submit(scene::witnessEyes).join();
                    client.player.lookAt(EntityAnchorArgument.Anchor.EYES, eyes);
                    capture(client, "04-hand-in", Stage.HOVER);
                }
            }
            case HOVER -> {
                if (choices.locate(client)) {
                    choices.hover(client);
                    capture(client, "04a-hover-cost", Stage.HAND_IN);
                }
            }
            case HAND_IN -> confirm(client, Stage.CONFIRMED);
            case CONFIRMED -> {
                var paper = Component.translatable(RebornScene.FRAGMENT);
                var reply = Component.translatable("chat.meridian_story.taken", paper).withStyle(ChatFormatting.GRAY);
                if (choices.received(reply) && onServer(client, () -> scene.delivered("impression"))) {
                    capture(client, "04b-confirmed", Stage.BELL);
                }
            }
            default -> { }
        }
    }

    private void start(Minecraft client) {
        if (client.gui.screen() != null || client.player.tickCount < WORLD_READY) {
            return;
        }
        var id = client.player.getUUID();
        if (onServer(client, () -> {
            var player = client.getSingleplayerServer().getPlayerList().getPlayer(id);
            if (player == null || CrisisEncounters.current(player).isEmpty()) {
                return false;
            }
            scene = new RebornScene(player);
            return true;
        })) {
            stage = Stage.ORIGIN;
        }
    }

    private void testify(Minecraft client) {
        switch (stage) {
            case BELL -> act(client, () -> scene.delivered("impression"), scene::placeBell, Stage.CHECK_BOOK);
            case CHECK_BOOK -> act(client, scene::claimBell, scene::speak, Stage.ARCHIVE);
            case ARCHIVE -> confirm(client, Stage.WITNESS);
            case WITNESS -> act(client, () -> scene.delivered("testimony"), scene::speak, Stage.PUBLISH);
            case PUBLISH -> confirm(client, Stage.ENDING);
            case ENDING -> {
                if (onServer(client, () -> scene.delivered("public_letter"))) {
                    stage = Stage.TAIL_VIEW;
                }
            }
            case TAIL_VIEW -> inspect(client, RebornScene.TAIL, "05-closing-letter", Stage.LEAVE);
            case LEAVE -> {
                if (client.gui.screen() != null) {
                    client.gui.setScreen(null);
                } else if (++steady >= SETTLE) {
                    finish(client, null);
                }
            }
            default -> { }
        }
    }

    private void act(Minecraft client, BooleanSupplier ready, Runnable action, Stage next) {
        if (onServer(client, () -> {
            if (!ready.getAsBoolean()) {
                return false;
            }
            action.run();
            return true;
        })) {
            stage = next;
        }
    }

    private void take(Minecraft client, String item, Stage next) {
        if (onServer(client, () -> scene.pickUp(item))) {
            stage = next;
        }
    }

    private static boolean onServer(Minecraft client, BooleanSupplier read) {
        return client.getSingleplayerServer().submit(read::getAsBoolean).join();
    }

    private void confirm(Minecraft client, Stage next) {
        if (choices.locate(client)) {
            choices.click(client);
            stage = next;
        }
    }

    private void inspect(Minecraft client, String item, String shot, Stage next) {
        if (!(client.gui.screen() instanceof StoryInventory)) {
            client.gui.setScreen(new StoryInventory(client.player));
        }
        if (client.gui.screen() instanceof StoryInventory inventory && inventory.hover(client, item)) {
            capture(client, shot, next);
        }
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../reborn-" + name + ".png"));
                client.execute(() -> {
                    capturing = false;
                    stage = next;
                });
            } catch (Exception failed) {
                client.execute(() -> finish(client, failed.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (error == null) + "\nerror=" + error + "\n");
        } catch (java.io.IOException invalid) {
            throw new java.io.UncheckedIOException(invalid);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
