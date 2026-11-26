package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.StoryInventory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;

import static dev.kizuna.meridian.story.fabric.YunxiScrolls.*;

public final class YunxiInheritanceClient implements ClientModInitializer {
    private static final int TIMEOUT = 1800;
    private static final int SETTLE = 16;
    private enum Stage { START, INVENTORY, UPPER_ITEM, LOWER_ITEM, STUDY, PAGE, CAPTURE, DONE }
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;

    @Override
    public void onInitializeClient() {
        if ("yunxi-inheritance".equals(System.getProperty("meridian.story.scene"))) {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE || stage == Stage.CAPTURE) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("等待开局剑谱超时 " + stage);
            }
            if (client.player == null || client.getSingleplayerServer() == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            switch (stage) {
                case START -> {
                    if (server(client, YunxiInheritanceClient::start)) {
                        client.gui.setScreen(null);
                        stage = Stage.INVENTORY;
                    }
                }
                case INVENTORY -> {
                    if (openInventory(client)) {
                        capture(client, "starting-inventory", Stage.UPPER_ITEM);
                    }
                }
                case UPPER_ITEM -> inventory(client, UPPER, Stage.LOWER_ITEM);
                case LOWER_ITEM -> inventory(client, LOWER, Stage.STUDY);
                case STUDY -> study(client);
                case PAGE -> {
                    var screen = client.gui.screen();
                    if (screen != null && screen.getClass().getSimpleName()
                            .equals("TechniqueScrollScreen") && server(client, player ->
                                    known(player).contains(YunxiForms.CLEAVE) && count(player, LOWER) == 0)) {
                        capture(client, "lower-page", Stage.DONE);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private static boolean start(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return false;
        }
        player.setGameMode(GameType.SURVIVAL);
        origin(player, GraveVisits.ORIGIN);
        return true;
    }

    private static boolean openInventory(Minecraft client) {
        if (!server(client, player -> count(player, UPPER) == 1 && count(player, LOWER) == 1
                && !record(player).getFirstSwept())) {
            return false;
        }
        if (!(client.gui.screen() instanceof StoryInventory)) {
            client.gui.setScreen(new StoryInventory(client.player));
            return false;
        }
        return true;
    }

    private void inventory(Minecraft client, String volume, Stage next) {
        String key = "item." + volume.replace(':', '.') + "_scroll";
        if (client.gui.screen() instanceof StoryInventory view && view.hover(client, key)) {
            capture(client, volume.substring(volume.indexOf(':') + 1), next);
        }
    }

    private void study(Minecraft client) {
        client.gui.setScreen(null);
        server(client, player -> {
            YunxiPracticeBody.practitioner(player);
            return true;
        });
        var inventory = client.player.getInventory();
        var lower = scroll(LOWER).getItem();
        int slot = inventory.findSlotMatchingItem(lower.getDefaultInstance());
        if (!Inventory.isHotbarSlot(slot)) {
            throw new IllegalStateException("开局下卷没有进入快捷栏 " + slot);
        }
        inventory.setSelectedSlot(slot);
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        stage = Stage.PAGE;
    }

    private static <T> T server(Minecraft client, Function<ServerPlayer, T> work) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        return server.submit(() -> work.apply(server.getPlayerList().getPlayer(id))).join();
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURE;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../yunxi-p2-" + name + ".png"));
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
        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        client.stop();
    }
}
