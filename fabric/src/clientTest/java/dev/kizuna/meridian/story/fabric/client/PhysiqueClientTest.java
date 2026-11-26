package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.OpeningHover;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.status.StatusArrangement;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.fabric.client.OriginScreen;
import dev.kizuna.meridian.origin.fabric.client.StoryOriginCards;
import dev.kizuna.meridian.story.fabric.PhysiqueScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

public final class PhysiqueClientTest implements ClientModInitializer {
    private enum Stage { CANDIDATES, CHOOSE, OPEN_ORDINARY, READ, LONE, LONE_CARD, UNLOCK, MERIDIANS, OPEN,
        HOLD, INJURE, ACTIVE, HEAL, RESTED, CLOSE, DONE }
    private static final int TIMEOUT = 2200;
    private static final int FRAMES = 5;
    private static final int SETTLE = 12;
    private static final int FRAME_HALF = 12;
    private static final double LOW = 0.33;
    private static final String EFFECT = "meridian_story:gritted_counter";
    private static final String OPENING = "meridian_ui:meridian_operation";
    private static final String MERIDIANS = "meridian_ui:meridians";
    private Stage stage = Stage.CANDIDATES;
    private int ticks;
    private int frames;
    private int steady;
    private boolean capturing;
    private UiWindowManager.WindowKey window;

    @Override
    public void onInitializeClient() {
        if ("physique".equals(System.getProperty("meridian.story.scene"))) {
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
                frames = 0;
                ScreenEvents.afterExtract(screen).register((drawn, graphics, x, y, delta) -> frames++);
            });
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (capturing || stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("客户端等待超时 " + stage);
            }
            if (client.player == null || client.getSingleplayerServer() == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            if (stage.ordinal() <= Stage.LONE_CARD.ordinal()) {
                origin(client);
            } else {
                physique(client);
            }
        } catch (RuntimeException invalid) {
            finish(client, invalid.toString());
        }
    }

    private void origin(Minecraft client) {
        switch (stage) {
            case CANDIDATES -> card(client, "01-candidates", Stage.CHOOSE);
            case CHOOSE -> {
                StoryOriginCards.chooseOrdinary((OriginScreen) client.gui.screen());
                stage = Stage.OPEN_ORDINARY;
            }
            case OPEN_ORDINARY -> openOrdinary(client);
            case READ -> card(client, "02-ordinary", Stage.LONE);
            case LONE -> {
                client.gui.setScreen(null);
                server(client, player -> {
                    var chosen = Origins.chosen(player).orElseThrow();
                    if (!chosen.id().toString().equals(PhysiqueScene.ORDINARY)) {
                        throw new IllegalStateException("服务端没有选中平常出身");
                    }
                    String name = player.getScoreboardName();
                    command(player, "meridian debug origin set " + name + " " + PhysiqueScene.LONE);
                });
                client.player.connection.sendCommand("meridian body origin");
                stage = Stage.LONE_CARD;
            }
            case LONE_CARD -> card(client, "03-lone-star", Stage.UNLOCK);
            default -> { }
        }
    }

    private void openOrdinary(Minecraft client) {
        // 等选择包在服务端落盘后再发复看指令，保证拿到只读卡。
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        boolean chosen = server.submit(() -> Origins.chosen(server.getPlayerList().getPlayer(id))
                .filter(origin -> origin.id().toString().equals(PhysiqueScene.ORDINARY)).isPresent()).join();
        if (chosen) {
            client.gui.setScreen(null);
            client.player.connection.sendCommand("meridian body origin");
            stage = Stage.READ;
        }
    }

    private void card(Minecraft client, String name, Stage next) {
        if (!(client.gui.screen() instanceof OriginScreen screen) || frames < FRAMES) {
            return;
        }
        boolean ready = switch (stage) {
            case CANDIDATES -> StoryOriginCards.ordinaryAndLoneStar(screen);
            case READ -> StoryOriginCards.chosen(screen, PhysiqueScene.ORDINARY);
            case LONE_CARD -> StoryOriginCards.chosen(screen, PhysiqueScene.LONE);
            default -> false;
        };
        if (ready) {
            capture(client, name, next);
        }
    }

    private void physique(Minecraft client) {
        switch (stage) {
            case UNLOCK -> prepareOpening(client);
            case MERIDIANS -> meridians();
            case OPEN -> open();
            case HOLD -> {
                var diagram = OpeningHover.diagram(window);
                if (diagram != null) {
                    cursor(client, diagram.x() + diagram.width() / 2, diagram.y() + diagram.height() / 2);
                    capture(client, "04-opening", Stage.INJURE);
                }
            }
            case INJURE -> {
                InventoryUiClient.runtime().workspace().windows().close(window);
                client.gui.setScreen(new InventoryScreen(client.player));
                server(client, player -> new PhysiqueScene(player).injureTo(LOW));
                stage = Stage.ACTIVE;
            }
            case ACTIVE -> active(client);
            case HEAL -> {
                server(client, player -> new PhysiqueScene(player).heal());
                stage = Stage.RESTED;
            }
            case RESTED -> {
                if (!hasIcon() && frames >= FRAMES) {
                    capture(client, "06-recovered", Stage.CLOSE);
                }
            }
            case CLOSE -> {
                if (client.gui.screen() != null) {
                    client.gui.setScreen(null);
                } else if (++steady >= SETTLE) {
                    finish(client, null);
                }
            }
            default -> { }
        }
    }

    private void prepareOpening(Minecraft client) {
        // 调试指令解锁修炼并补足内腔，后面的开脉操作卡来自宿主订阅。
        client.gui.setScreen(null);
        var name = client.player.getScoreboardName();
        server(client, player -> {
            command(player, "execute as " + name + " run meridian progression unlock");
            command(player, "execute as " + name + " run meridian qi fill");
        });
        stage = Stage.MERIDIANS;
    }

    private void meridians() {
        // 经脉总览开窗后才会订阅内观；等宿主送达操作目录，再打开对应目标。
        var runtime = InventoryUiClient.runtime();
        runtime.uiVersion(UiVersion.CLASSIC);
        if (runtime.workspace().canOpen(MERIDIANS)) {
            runtime.openWorkspace();
            runtime.open(MERIDIANS, null);
            stage = Stage.OPEN;
        }
    }

    private void open() {
        var runtime = InventoryUiClient.runtime();
        if (runtime.workspace().canOpen(OPENING)) {
            window = runtime.open(OPENING, null).key();
            stage = Stage.HOLD;
        }
    }

    private void active(Minecraft client) {
        var layout = StatusIcons.lastLayout();
        for (int i = 0; i < layout.slots().size(); i++) {
            var slot = layout.slots().get(i);
            if (slot instanceof StatusArrangement.Single single && single.effect().id().equals(EFFECT)) {
                var cell = layout.cells().get(i);
                cursor(client, cell.left() + FRAME_HALF, cell.top() + FRAME_HALF);
                capture(client, "05-gritted-counter", Stage.HEAL);
                return;
            }
        }
    }

    private static boolean hasIcon() {
        return StatusIcons.lastLayout().slots().stream().anyMatch(slot ->
                slot instanceof StatusArrangement.Single single && single.effect().id().equals(EFFECT));
    }

    private static void cursor(Minecraft client, double x, double y) {
        long handle = client.getWindow().handle();
        double scale = client.getWindow().getGuiScale();
        var callback = GLFW.glfwSetCursorPosCallback(handle, null);
        GLFW.glfwSetCursorPosCallback(handle, callback);
        if (callback == null) {
            GLFW.glfwSetCursorPos(handle, x * scale, y * scale);
        } else {
            callback.invoke(handle, x * scale, y * scale);
        }
    }

    private static void server(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        server.submit(() -> action.accept(server.getPlayerList().getPlayer(id))).join();
    }

    private static void command(ServerPlayer player, String command) {
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../physique-" + name + ".png"));
                client.execute(() -> {
                    stage = next;
                    capturing = false;
                });
            } catch (Exception failure) {
                client.execute(() -> finish(client, failure.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        stage = Stage.DONE;
        try {
            Files.writeString(Path.of("../clienttest-results.txt"), "success=" + (error == null) + "\nerror=" + error);
        } catch (java.io.IOException invalid) {
            throw new java.io.UncheckedIOException(invalid);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
