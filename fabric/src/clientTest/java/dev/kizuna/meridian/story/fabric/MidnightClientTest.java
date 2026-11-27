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
import net.minecraft.world.phys.Vec3;

public final class MidnightClientTest implements ClientModInitializer {
    private enum Stage { START, OPENING, SELL, DAWN, OPEN, SILVER, CAPTURING, DONE }
    private static final int LIMIT = 3600;
    private static final int SETTLE = 35;
    private static final int CAMERA = 11;
    private static final int OFFSET = 256;
    private final ChatChoices choices = new ChatChoices(MidnightMarket.ACTION);
    private MidnightScene scene;
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;

    @Override
    public void onInitializeClient() {
        if ("midnight".equals(System.getProperty("meridian.story.scene"))) {
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
                throw new IllegalStateException("鬼市取景超时 " + stage);
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
                case OPENING -> {
                    if (choices.offered()) {
                        capture(client, "midnight-market", Stage.SELL);
                    }
                }
                case SELL, OPEN -> {
                    if (choices.locate(client)) {
                        if (stage == Stage.SELL) {
                            server.executeBlocking(() -> scene.approach(TempleMarkets.Stall.PARCELS));
                        }
                        choices.click(client);
                        client.gui.setScreen(null);
                        stage = stage == Stage.SELL ? Stage.DAWN : Stage.SILVER;
                    }
                }
                case DAWN -> {
                    if (choices.received(MarketOrders.text("sold").copy().withStyle(ChatFormatting.GRAY))) {
                        server.executeBlocking(() -> sunrise(server.getPlayerList().getPlayer(id)));
                        stage = Stage.OPEN;
                    }
                }
                case SILVER -> {
                    if (choices.received(MarketOrders.text("silver").copy().withStyle(ChatFormatting.GRAY))) {
                        capture(client, "midnight-dawn", Stage.DONE);
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
        scene = new MidnightScene(player, player.blockPosition().east(OFFSET));
        scene.midnight(MidnightScene.MarketOutcome.SILVER);
        scene.invite();
        frame(player);
        return Stage.OPENING;
    }

    private void frame(ServerPlayer player) {
        var yard = scene.site.yard();
        scene.move(yard.south(CAMERA));
        player.lookAt(Anchor.EYES, Vec3.atCenterOf(yard.above(2)));
    }

    private void sunrise(ServerPlayer player) {
        scene.clock(scene.site.dawn());
        TempleMarkets.get(player.level()).tick(player.level());
        frame(player);
        scene.tick();
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (!client.levelRenderer.isSectionCompiledAndVisible(scene.site.yard())
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
