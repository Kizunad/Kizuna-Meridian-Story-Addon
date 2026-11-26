package dev.kizuna.meridian.ui.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.fabric.client.CombatSlotAccess;
import dev.kizuna.meridian.story.fabric.YunxiMortal;
import dev.kizuna.meridian.story.fabric.client.YunxiTimeline;
import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

/** 通过正式读卷和装槽入口拍摄未学谱页、卷面、已学谱页与战斗槽。 */
public final class YunxiClientTest implements ClientModInitializer {
    private static final String BOOK = "meridian_story:yunxi";
    private static final String VOLUME = BOOK + "_upper";
    private static final Identifier SCROLL = Identifier.parse(VOLUME + "_scroll");
    private static final List<String> FORMS = List.of("meridian_story:wind_thrust",
            "meridian_story:moon_lift", "meridian_story:flash_parry");
    private static final int SETTLE = 12;
    private static final int TIMEOUT = 1800;
    private enum Stage {
        OPEN, MISSING, GIVE, ITEM, READ, PAGE, EQUIP, LEARNED, STANCE, HUD, CAST, VERIFY,
        CAPTURE, CAMERA, TIMELINE, DONE
    }
    private Stage stage = Stage.OPEN;
    private YunxiTimeline timeline;
    private int ticks;
    private int steady;
    private int slot;

    @Override
    public void onInitializeClient() {
        if ("yunxi".equals(System.getProperty("meridian.story.scene"))) {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("截图等待超时 " + stage);
            }
            if (client.player == null || client.getSingleplayerServer() == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            advance(client);
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private void advance(Minecraft client) {
        switch (stage) {
            case OPEN -> {
                var volume = CodexMoments.catalog().volume(VOLUME).orElseThrow();
                if (CodexProgress.current().state(volume) == CodexProgress.State.MISSING) {
                    TechniqueCodex.OPEN.invoker().open();
                    stage = Stage.MISSING;
                }
            }
            case MISSING -> codex(client, CodexProgress.State.MISSING, "missing", Stage.GIVE);
            case GIVE -> give(client);
            case ITEM -> inventory(client);
            case READ -> read(client);
            case PAGE -> {
                var screen = client.gui.screen();
                if (screen != null && screen.getClass().getSimpleName().equals("TechniqueScrollScreen")) {
                    capture(client, "scroll", Stage.EQUIP);
                }
            }
            case EQUIP -> equip(client);
            case LEARNED -> codex(client, CodexProgress.State.LEARNED, "learned", Stage.STANCE);
            case STANCE -> {
                client.gui.setScreen(null);
                tap(GLFW.GLFW_KEY_R);
                stage = Stage.HUD;
            }
            case HUD -> {
                client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
                capture(client, "slots", Stage.CAST);
            }
            case CAST -> {
                server(client, YunxiMortal::emptyQi);
                tap(GLFW.GLFW_KEY_1 + slot);
                stage = Stage.VERIFY;
            }
            case VERIFY -> verifyCast(client);
            case CAMERA -> {
                if (++steady >= SETTLE) {
                    steady = 0;
                    timeline = new YunxiTimeline(client);
                    stage = Stage.TIMELINE;
                }
            }
            case TIMELINE -> {
                if (timeline.tick(client)) {
                    finish(client, "");
                }
            }
            default -> { }
        }
    }

    private void inventory(Minecraft client) {
        if (client.player.getMainHandItem().is(TechniqueScrollItems.scrolls().get(SCROLL))) {
            if (!(client.gui.screen() instanceof InventoryScreen)) {
                client.gui.setScreen(new InventoryScreen(client.player));
            }
            capture(client, "item", Stage.READ);
        }
    }

    private void give(Minecraft client) {
        client.gui.setScreen(null);
        server(client, player -> {
            player.setGameMode(GameType.SURVIVAL);
            var scroll = TechniqueScrollItems.scrolls().get(SCROLL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(scroll));
        });
        stage = Stage.ITEM;
    }

    private void codex(Minecraft client, CodexProgress.State expected, String file, Stage next) {
        // 选书只操作正式书页；状态由服务器已同步的上卷知识决定。
        if (!(client.gui.screen() instanceof CodexScreen screen)) {
            return;
        }
        var panel = screen.panel();
        panel.select(BOOK);
        var volume = CodexMoments.catalog().volume(VOLUME).orElseThrow();
        if (CodexProgress.current().state(volume) == expected) {
            capture(client, file, next);
        }
    }

    private void read(Minecraft client) {
        client.gui.setScreen(null);
        server(client, player -> {
            var hand = InteractionHand.MAIN_HAND;
            var scroll = player.getItemInHand(hand);
            YunxiMortal.emptyQi(player);
            player.gameMode.useItem(player, player.level(), scroll, hand);
            if (!scroll.isEmpty()) {
                throw new IllegalStateException("生存读卷没有消耗上卷");
            }
            player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
        });
        stage = Stage.PAGE;
    }

    private static void tap(int key) {
        KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key));
    }

    private static void server(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        server.submit(() -> action.accept(server.getPlayerList().getPlayer(id))).join();
    }

    private void verifyCast(Minecraft client) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        String form = FORMS.get(slot);
        boolean used = server.submit(() -> {
            var body = TechniqueFabric.service().observe(id).orElseThrow().body();
            var progress = TechniqueState.read(body);
            return progress.containsCooldowns(form) && !progress.hasActiveCast();
        }).join();
        if (used) {
            slot++;
            if (slot == FORMS.size()) {
                tap(GLFW.GLFW_KEY_R);
                stage = Stage.CAMERA;
            } else {
                stage = Stage.CAST;
            }
        }
    }

    private void equip(Minecraft client) {
        if (!CodexProgress.current().known().keySet().containsAll(FORMS)) {
            return;
        }
        server(client, YunxiMortal::emptyQi);
        for (int index = 0; index < FORMS.size(); index++) {
            var result = CombatSlotAccess.set(index + 1, FORMS.get(index));
            if (result.status() != CombatSlotAccess.Status.SAVED) {
                throw new IllegalStateException("装槽失败 " + result);
            }
        }
        var slots = CombatSlotAccess.snapshot().slots();
        if (!slots.subList(0, FORMS.size()).equals(FORMS)) {
            throw new IllegalStateException("装槽回读错误 " + slots);
        }
        TechniqueCodex.OPEN.invoker().open();
        stage = Stage.LEARNED;
    }

    private void capture(Minecraft client, String file, Stage next) {
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURE;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../yunxi-" + file + ".png"));
                client.execute(() -> stage = next);
            } catch (IOException error) {
                client.execute(() -> finish(client, error.toString()));
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
