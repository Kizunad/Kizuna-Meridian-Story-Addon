package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class MountainClientTest implements ClientModInitializer {
    private enum Stage { START, ARRIVE, OPENING, CLICK, HARVEST, ENDING, CAPTURE, DONE }
    private static final int LIMIT = 3600;
    private static final int SETTLE = 24;
    private static final int AWAY = 96;
    private static final int CAMERA = 3;
    private static final double DECIDING_BLOW = 10_000;
    private final ChatChoices choices = new ChatChoices();
    private final String kind = System.getProperty("meridian.story.scene", "");
    private Stage stage = Stage.START;
    private MountainScene scene;
    private VenueScene venue;
    private RoadsideCase round;
    private int ticks;
    private int steady;
    private int actorId;
    private BlockPos landmark;

    @Override
    public void onInitializeClient() {
        if (kind.equals(FathersDebt.KIND) || kind.equals(HerbHarvest.KIND) || venueScene()) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private boolean venueScene() {
        return kind.equals(EscortCart.KIND) || kind.equals(VillageRing.KIND);
    }

    private void tick(Minecraft client) {
        if (stage == Stage.DONE || stage == Stage.CAPTURE) {
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
            var id = client.player.getUUID();
            switch (stage) {
                case START -> stage = server.submit(() -> prepare(server.getPlayerList().getPlayer(id))).join();
                case ARRIVE -> {
                    if (client.levelRenderer.isSectionCompiledAndVisible(landmark)) {
                        client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
                        stage = server.submit(() -> open(server.getPlayerList().getPlayer(id))).join();
                    }
                }
                case OPENING -> {
                    if (choices.locate(client)) {
                        capture(client, "opening", Stage.CLICK);
                    }
                }
                case CLICK -> {
                    choices.click(client);
                    client.gui.setScreen(null);
                    stage = Stage.HARVEST;
                }
                case HARVEST -> stage = server.submit(() -> finishScene(server.getPlayerList().getPlayer(id))).join();
                case ENDING -> capture(client, "ending", Stage.DONE);
                default -> { }
            }
        } catch (RuntimeException failed) {
            finish(client, failed.toString());
        }
    }

    private Stage prepare(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return Stage.START;
        }
        var server = player.level().getServer();
        String name = player.getScoreboardName();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "meridian debug origin set " + name + " meridian_story:ordinary");
        if (venueScene()) {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            venue = new VenueScene(player, player.blockPosition().east(AWAY), VenueScene.GOOD);
            landmark = venue.center.below();
            if (kind.equals(VillageRing.KIND)) {
                player.getInventory().add(VenueScene.coins("silver_fragment", 1));
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            return Stage.ARRIVE;
        }
        var terrain = MountainScene.Terrain.CLIFF;
        if (kind.equals(FathersDebt.KIND)) {
            terrain = MountainScene.Terrain.TRAIL;
        }
        scene = new MountainScene(player, player.blockPosition().east(AWAY), terrain, MountainScene.Lot.GOOD);
        landmark = scene.feet.below();
        dress(player, scene.feet);
        if (kind.equals(FathersDebt.KIND)) {
            scene.father().die(player.damageSources().playerAttack(player));
            player.getInventory().add(MountainScene.silver(1));
        }
        scene.age();
        return Stage.ARRIVE;
    }

    private Stage open(ServerPlayer player) {
        if (venueScene()) {
            round = venue.open(kind);
            if (kind.equals(EscortCart.KIND)) {
                venue.move(venue.center.offset(RoadVenue.BANK));
                landmark = venue.center.offset(RoadVenue.CRATE);
            } else {
                venue.move(venue.center.offset(RoadVenue.TABLE).south(CAMERA));
                landmark = venue.center;
            }
            player.lookAt(Anchor.EYES, Vec3.atCenterOf(landmark.above()));
            return Stage.OPENING;
        }
        scene.move(scene.feet);
        if (!scene.enter() || !scene.current().getKind().equals(kind)) {
            throw new IllegalStateException("取景地没有开场 " + kind);
        }
        var c = scene.current();
        var actor = scene.actor(c.getActor());
        actorId = actor.getId();
        landmark = actor.blockPosition().below();
        int facing = 180;
        actor.setYRot(facing);
        actor.setYBodyRot(facing);
        actor.setYHeadRot(facing);
        actor.setCustomNameVisible(true /* visible */);
        if (kind.equals(HerbHarvest.KIND)) {
            landmark = BlockPos.of(c.getPlant());
            scene.move(scene.feet.north(CAMERA));
            player.lookAt(Anchor.EYES, Vec3.atCenterOf(BlockPos.of(c.getPlant())));
        } else {
            player.lookAt(Anchor.EYES, actor.getEyePosition());
        }
        return Stage.OPENING;
    }

    private static void dress(ServerPlayer player, BlockPos feet) {
        var level = player.level();
        for (var trunk : java.util.List.of(feet.west(CAMERA + 1).south(CAMERA),
                feet.east(CAMERA + 1).south(CAMERA + CAMERA))) {
            for (int y = 0; y <= CAMERA; y++) {
                level.setBlockAndUpdate(trunk.above(y), Blocks.SPRUCE_LOG.defaultBlockState());
            }
            for (var leaf : BlockPos.betweenClosed(trunk.offset(-1, CAMERA, -1), trunk.offset(1, CAMERA + 1, 1))) {
                level.setBlockAndUpdate(leaf, Blocks.SPRUCE_LEAVES.defaultBlockState());
            }
        }
    }

    private Stage finishScene(ServerPlayer player) {
        if (venueScene()) {
            return finishVenue(player);
        }
        if (scene.current().getStage().equals("shared")) {
            var c = scene.current();
            scene.pick(c);
            player.lookAt(Anchor.EYES, scene.actor(c.getActor()).getEyePosition());
        }
        if (scene.road().getLastEnding().equals("paid_good") || scene.road().getLastEnding().equals("shared_good")) {
            return Stage.ENDING;
        }
        return Stage.HARVEST;
    }

    private Stage finishVenue(ServerPlayer player) {
        var c = venue.current();
        if (c.getStage().equals(EscortCart.WATCH)) {
            venue.retreat(c, "chief", "bandit");
        } else if (c.getStage().equals("exhibition")) {
            var winner = venue.role(c, "white");
            var loser = venue.role(c, "second");
            var hit = new VanillaDamageFabric.DirectDamage(loser,
                    loser.damageSources().mobAttack(winner), "head", DECIDING_BLOW);
            VanillaDamageFabric.damageDirect(hit).orElseThrow();
        }
        if (!venue.current().getActor().isEmpty()) {
            return Stage.HARVEST;
        }
        var ending = venue.history(round).getEnding();
        if (!ending.equals("salvage") && !ending.equals("bet_good")) {
            throw new IllegalStateException("取景结局错误 " + ending);
        }
        landmark = venue.center.below();
        return Stage.ENDING;
    }

    private void capture(Minecraft client, String name, Stage next) {
        var renderer = client.levelRenderer;
        boolean waitingForActor = !venueScene() && client.level.getEntity(actorId) == null;
        if (waitingForActor || !renderer.isSectionCompiledAndVisible(landmark)) {
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
            } catch (IOException failed) {
                client.execute(() -> finish(client, failed.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        stage = Stage.DONE;
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
