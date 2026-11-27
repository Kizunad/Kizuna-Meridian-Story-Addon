package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.world.entity.Mob;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

public final class RoadClientTest implements ClientModInitializer {
    private static final int AWAY = 160;
    private static final int CAMERA = 7;
    private static final int GROUP_SPACING = 2;
    private static final int SETTLE = 30;
    private static final int LIMIT = 4500;
    private final ChatChoices choices = new ChatChoices();
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;
    private int leaderId;

    private enum Stage { START, SCOUT, FIRST, LODGE, CHAT, CLICK, DEPART, PURSUIT, SIDE, LAST, CAPTURE, DONE }

    @Override
    public void onInitializeClient() {
        if (java.util.Set.of("lonestar", "footman").contains(System.getProperty("meridian.story.scene", ""))) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE || stage == Stage.CAPTURE) {
            return;
        }
        try {
            if (++ticks > LIMIT) {
                throw new IllegalStateException("孤星取景超时 " + stage);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            switch (stage) {
                case FIRST -> capture(client, "scout", Stage.LODGE);
                case LAST -> capture(client, "pursuit", Stage.DONE);
                case CHAT -> {
                    if (choices.locate(client)) {
                        capture(client, "lodging", Stage.CLICK);
                    }
                }
                case CLICK -> {
                    choices.click(client);
                    client.gui.setScreen(null);
                    stage = Stage.DEPART;
                }
                default -> {
                    var id = client.player.getUUID();
                    stage = server.submit(() -> advance(server.getPlayerList().getPlayer(id))).join();
                    client.options.keyUp.setDown(stage == Stage.SCOUT || stage == Stage.PURSUIT);
                    if (stage == Stage.FIRST || stage == Stage.LAST) {
                        client.gui.hud.getChat().clearMessages(false /* clearSentMessages */);
                    }
                }
            }
        } catch (RuntimeException failed) {
            finish(client, failed.toString());
        }
    }

    private Stage advance(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return stage;
        }
        var scene = new RoadScene(player);
        return switch (stage) {
            case START -> {
                var server = player.level().getServer();
                var commands = server.getCommands();
                var source = server.createCommandSourceStack();
                commands.performPrefixedCommand(source, "difficulty easy");
                commands.performPrefixedCommand(source, "time set day");
                commands.performPrefixedCommand(source, "weather clear");
                commands.performPrefixedCommand(source, "meridian debug origin set " + player.getScoreboardName()
                        + " " + PhysiqueScene.ORDINARY);
                player.setGameMode(GameType.SURVIVAL);
                depart(scene);
                yield Stage.SCOUT;
            }
            case SCOUT, PURSUIT -> approach(scene);
            case LODGE -> lodging(scene);
            case SIDE -> pursuitFrame(scene);
            case DEPART -> {
                if (!scene.delivered("escort_stub")) {
                    yield Stage.DEPART;
                }
                scene.openMeridian();
                depart(scene);
                yield Stage.PURSUIT;
            }
            default -> stage;
        };
    }

    private void depart(RoadScene scene) {
        var player = scene.player();
        var level = player.level();
        var column = player.blockPosition().east(AWAY);
        level.getChunkAt(column);
        var ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        player.teleportTo(ground.getX(), ground.getY(), ground.getZ());
        scene.readyRoad();
    }

    private Stage approach(RoadScene scene) {
        var pending = scene.record().getPending();
        if (!pending.getArrived()) {
            return stage;
        }
        String expected = stage == Stage.SCOUT ? LoneStarRoad.SCOUT : LoneStarRoad.PURSUIT;
        if (!pending.getParty().equals(expected) || pending.getRoadRequest() <= 0 || pending.getNoteGiven()) {
            throw new IllegalStateException("出场队伍或纸条错误 " + pending.getParty());
        }
        // 来人已正式落地；固定在原地取景，等客户端收到实体与地形后再拍。
        for (String id : pending.getRaidersList()) {
            if (scene.player().level().getEntity(UUID.fromString(id)) instanceof Mob actor) {
                actor.getNavigation().stop();
                actor.setNoAi(true /* noAi */);
            }
        }
        var leader = scene.leader();
        leaderId = leader.getId();
        leader.setCustomNameVisible(true /* visible */);
        frame(scene.player(), leader, CAMERA);
        if (stage == Stage.SCOUT) {
            return Stage.FIRST;
        }
        return Stage.SIDE;
    }

    private Stage pursuitFrame(RoadScene scene) {
        var player = scene.player();
        var guest = scene.visitor();
        if (guest == null || !guest.blockPosition().closerThan(player.blockPosition(), CAMERA)) {
            return Stage.SIDE;
        }
        var leader = scene.leader();
        // 从脚夫侧面取景，让两名正式到场的追兵在画面中各占一个位置。
        for (String id : scene.record().getPending().getRaidersList()) {
            var actor = player.level().getEntity(UUID.fromString(id));
            if (actor instanceof Mob follower && follower != leader) {
                follower.teleportTo(leader.getX(), leader.getY(), leader.getZ() - GROUP_SPACING);
            }
        }
        player.teleportTo(leader.getX() + CAMERA, leader.getY(), leader.getZ());
        player.lookAt(Anchor.EYES, leader.getEyePosition());
        return Stage.LAST;
    }

    private Stage lodging(RoadScene scene) {
        if (scene.record().hasPending()) {
            scene.win();
            scene.camp();
        }
        var guest = scene.visitor();
        if (guest == null || !scene.delivered("waybill")) {
            return Stage.LODGE;
        }
        frame(scene.player(), guest, RoadScene.WALK);
        DialogueScene.reopen(scene.player(), guest.getUUID());
        return Stage.CHAT;
    }

    private static void frame(ServerPlayer player, LivingEntity actor, int distance) {
        player.teleportTo(actor.getX(), actor.getY(), actor.getZ() + distance);
        player.lookAt(Anchor.EYES, actor.getEyePosition());
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (stage == Stage.FIRST || stage == Stage.LAST) {
            var actor = client.level.getEntity(leaderId);
            if (actor == null || !client.levelRenderer.isSectionCompiledAndVisible(actor.blockPosition().below())) {
                steady = 0;
                return;
            }
        }
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURE;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../lone-star-" + name + ".png"));
                client.execute(() -> {
                    stage = next;
                    if (next == Stage.DONE) {
                        finish(client, "");
                    }
                });
            } catch (IOException error) {
                client.execute(() -> finish(client, error.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        stage = Stage.DONE;
        client.options.keyUp.setDown(false /* pressed */);
        try {
            Files.writeString(Path.of("../clienttest-results.txt"), "success=" + error.isEmpty() + "\nerror=" + error);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
