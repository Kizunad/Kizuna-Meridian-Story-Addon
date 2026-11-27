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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class MarketRoadClientTest implements ClientModInitializer {
    private static final int LIMIT = 3600;
    private static final int SETTLE = 35;
    private static final float FACE_NORTH = 180.0F;
    private static final int ROLL = 30;
    private static final int CAMERA = 4;
    private final ChatChoices choices = new ChatChoices();
    private MarketRoadScene scene;
    private String kind;
    private int ticks;
    private int steady;
    private int actorId;
    private Stage stage = Stage.START;

    private enum Stage { START, WALK, OPENING, CLICK, ENDING, CAPTURE, DONE }

    @Override
    public void onInitializeClient() {
        kind = System.getProperty("meridian.story.scene", "");
        if (kind.equals(ManualSeller.KIND) || kind.equals(FranticCultivator.KIND)) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.CAPTURE || stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > LIMIT) {
                throw new IllegalStateException("取景超时 " + stage);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            if (stage == Stage.WALK && !client.levelRenderer.isSectionCompiledAndVisible(
                    client.player.blockPosition().below())) {
                return;
            }
            if (stage == Stage.START || stage == Stage.WALK) {
                if (stage == Stage.WALK && actorId == 0 && steady == 0) {
                    client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
                    steady++;
                }
                var id = client.player.getUUID();
                stage = server.submit(() -> advance(server.getPlayerList().getPlayer(id))).join();
            } else if (stage == Stage.OPENING && choices.locate(client)) {
                capture(client, "opening", Stage.CLICK);
            } else if (stage == Stage.CLICK) {
                choices.click(client);
                client.gui.setScreen(null);
                stage = Stage.ENDING;
            } else if (stage == Stage.ENDING) {
                if (choices.received(ending())) {
                    capture(client, "ending", Stage.DONE);
                }
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private Component ending() {
        String key;
        if (kind.equals(FranticCultivator.KIND)) {
            key = FranticCultivator.KEY + "feed.good";
        } else {
            key = ManualSeller.KEY + "buy.bad";
        }
        return Component.translatable(key).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    private Stage advance(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return stage;
        }
        if (stage == Stage.START) {
            scene = new MarketRoadScene(player, kind);
            scene.prepare(player.blockPosition(), ROLL);
            scene.fund();
            return Stage.WALK;
        }
        scene.walk();
        var current = scene.road().getCurrent();
        if (current.getKind().equals(kind) && current.getStage().equals(VillageEncounters.OPEN)) {
            var actor = VillageEncounters.actor(player, current.getActor()).orElseThrow();
            actorId = actor.getId();
            actor.setCustomNameVisible(true /* visible */);
            actor.setYRot(FACE_NORTH);
            actor.setYBodyRot(FACE_NORTH);
            actor.setYHeadRot(FACE_NORTH);
            player.teleportTo(actor.getX(), actor.getY(), actor.getZ() - CAMERA);
            player.lookAt(Anchor.EYES, actor.getEyePosition());
            steady = 0;
            return Stage.OPENING;
        }
        return Stage.WALK;
    }

    private void capture(Minecraft client, String name, Stage next) {
        var actor = client.level.getEntity(actorId);
        var renderer = client.levelRenderer;
        if (actor == null || !renderer.isSectionCompiledAndVisible(actor.blockPosition().below())) {
            steady = 0;
            return;
        }
        var counter = actor.blockPosition().north();
        if (kind.equals(ManualSeller.KIND) && (!client.level.getBlockState(counter)
                .is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS)
                || !renderer.isSectionCompiledAndVisible(counter) || !renderer.hasRenderedAllSections())) {
            steady = 0;
            return;
        }
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURE;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../" + kind + "-" + name + ".png"));
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
