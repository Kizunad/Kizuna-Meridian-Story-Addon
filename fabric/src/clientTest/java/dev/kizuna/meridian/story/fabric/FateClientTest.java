package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class FateClientTest implements ClientModInitializer {
    private static final int AWAY = 256;
    private static final int CAMERA = 5;
    private static final int SETTLE = 24;
    private static final int LIMIT = 6500;
    private static final int PRICE = 8;
    private final ChatChoices choices = new ChatChoices(FateScenes.ACTION);
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;
    private int subject;
    private BlockPos gate;

    private enum Stage {
        START, READING, READING_SHOT, PAY, ESCORT, JOIN, ARM_AMBUSH, AMBUSH, AMBUSH_SHOT, GATE, ARM_HIRED,
        HIRED, FIND, SEARCH, SLIP, FOLLOW, TEMPLE, TEMPLE_SHOT, FIGHT, FIND_BOOK, TAKE, READ, DECISION_SHOT,
        SETTLE_CHOICE, CHECK, CAPTURE, DONE
    }

    @Override
    public void onInitializeClient() {
        if ("lone_star_fate".equals(System.getProperty("meridian.story.scene"))) {
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
                throw new IllegalStateException("命格取景超时 " + stage);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            switch (stage) {
                case READING_SHOT -> chatShot(client, "reading", Stage.PAY);
                case AMBUSH_SHOT -> capture(client, "ambush", Stage.GATE);
                case TEMPLE_SHOT -> capture(client, "temple", Stage.FIGHT);
                case DECISION_SHOT -> chatShot(client, "notebook", Stage.SETTLE_CHOICE);
                case PAY -> click(client, Stage.ESCORT);
                case JOIN -> click(client, Stage.ARM_AMBUSH);
                case SEARCH -> click(client, Stage.SLIP);
                case FOLLOW -> click(client, Stage.TEMPLE);
                case TAKE -> click(client, Stage.READ);
                case SETTLE_CHOICE -> click(client, Stage.CHECK);
                default -> {
                    var id = client.player.getUUID();
                    stage = server.submit(() -> advance(server.getPlayerList().getPlayer(id))).join();
                    client.options.keyUp.setDown(stage == Stage.AMBUSH || stage == Stage.HIRED);
                    if (stage == Stage.DONE) {
                        finish(client, "");
                    }
                }
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private void chatShot(Minecraft client, String name, Stage next) {
        if (choices.locate(client)) {
            capture(client, name, next);
        }
    }

    private void click(Minecraft client, Stage next) {
        if (choices.locate(client)) {
            choices.click(client);
            client.gui.setScreen(null);
            stage = next;
        }
    }

    private Stage advance(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return stage;
        }
        var scene = new FateScene(player);
        var road = new RoadScene(player);
        return switch (stage) {
            case START -> {
                var server = player.level().getServer();
                var commands = server.getCommands();
                var source = server.createCommandSourceStack();
                commands.performPrefixedCommand(source, "difficulty easy");
                commands.performPrefixedCommand(source, "weather clear");
                commands.performPrefixedCommand(source, "meridian debug origin set " + player.getScoreboardName()
                        + " " + PhysiqueScene.LONE);
                gate = scene.prepare(player.blockPosition().east(AWAY));
                player.teleportTo(player.getX(), player.getY(), player.getZ());
                var cash = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_currency:copper_cash"));
                player.getInventory().add(new ItemStack(cash, PRICE));
                yield Stage.READING;
            }
            case READING, ESCORT -> meet(scene);
            case ARM_AMBUSH, ARM_HIRED -> arm(scene, road);
            case AMBUSH, HIRED -> road(scene, road);
            case GATE -> {
                road.win();
                var pos = Vec3.atBottomCenterOf(gate.south());
                scene.actor().setPos(pos);
                player.teleportTo(pos.x, pos.y, pos.z);
                yield Stage.ARM_HIRED;
            }
            case FIND -> {
                if (scene.chapter().equals("search")) {
                    visitEvidence(scene, "slip");
                    yield Stage.SEARCH;
                }
                yield stage;
            }
            case SLIP -> {
                if (scene.chapter().equals("directions")) {
                    scene.read("slip");
                    yield Stage.FOLLOW;
                }
                yield stage;
            }
            case TEMPLE -> temple(scene);
            case FIGHT -> {
                scene.strike(scene.actor());
                if (!road.record().getPending().getParty().equals(FateRoad.MASTER)) {
                    throw new IllegalStateException("未实际与邪修交手");
                }
                road.win();
                yield Stage.FIND_BOOK;
            }
            case FIND_BOOK -> {
                if (scene.chapter().equals("victory")) {
                    visitEvidence(scene, "notebook");
                    yield Stage.TAKE;
                }
                yield stage;
            }
            case READ -> {
                if (scene.chapter().equals("unread")) {
                    scene.read("notebook");
                    subject = 0;
                    yield Stage.DECISION_SHOT;
                }
                yield stage;
            }
            case CHECK -> {
                if (FateRoad.grade(scene.progress()) == FateRoad.Grade.DRAWN) {
                    yield Stage.DONE;
                }
                yield stage;
            }
            default -> stage;
        };
    }

    private Stage meet(FateScene scene) {
        String wanted = stage == Stage.READING ? "reading" : "escort";
        if (!scene.registered(gate) || !scene.chapter().equals(wanted)) {
            return stage;
        }
        var actor = scene.actor();
        if (actor == null) {
            return stage;
        }
        var player = scene.player();
        player.teleportTo(actor.getX(), actor.getY(), actor.getZ() + CAMERA - 1);
        player.lookAt(Anchor.EYES, actor.getEyePosition());
        subject = actor.getId();
        if (stage == Stage.READING) {
            return Stage.READING_SHOT;
        }
        return Stage.JOIN;
    }

    private Stage arm(FateScene scene, RoadScene road) {
        boolean escort = stage == Stage.ARM_AMBUSH;
        if (!scene.chapter().equals(escort ? "walking" : "paid_hand")) {
            return stage;
        }
        var player = scene.player();
        player.lookAt(Anchor.EYES, player.getEyePosition().add(0, 0, CAMERA));
        road.readyRoad(LoneStarRoad.Track.FATE);
        return escort ? Stage.AMBUSH : Stage.HIRED;
    }

    private Stage road(FateScene scene, RoadScene road) {
        var player = scene.player();
        var pending = road.record().getPending();
        if (!pending.getArrived()) {
            return stage;
        }
        if (stage == Stage.HIRED) {
            if (!pending.getParty().equals(FateRoad.HIRED)) {
                throw new IllegalStateException("命帖来错了人");
            }
            road.win();
            return Stage.FIND;
        }
        var escort = scene.actor();
        var enemy = road.leader();
        if (escort == null || enemy.getTarget() != escort || !scene.progress().getFate().getAmbushed()) {
            return stage;
        }
        for (String id : pending.getRaidersList()) {
            if (player.level().getEntity(UUID.fromString(id)) instanceof Mob mob) {
                mob.setNoAi(true /* noAi */);
            }
        }
        escort.setNoAi(true /* noAi */);
        enemy.setPos(escort.position().add(2, 0, -1));
        player.teleportTo(escort.getX(), escort.getY(), escort.getZ() + CAMERA);
        player.lookAt(Anchor.EYES, escort.getEyePosition().add(1, 0, 0));
        subject = escort.getId();
        return Stage.AMBUSH_SHOT;
    }

    private static void visitEvidence(FateScene scene, String reward) {
        var claim = FateRoad.state(scene.progress()).getClaimsOrThrow(reward);
        scene.player().teleportTo(claim.getX(), claim.getY(), claim.getZ());
    }

    private Stage temple(FateScene scene) {
        if (!scene.chapter().equals("temple")) {
            return stage;
        }
        var player = scene.player();
        var site = BlockPos.of(scene.progress().getFate().getTemple());
        var pos = Vec3.atBottomCenterOf(site.south(CAMERA));
        player.teleportTo(pos.x, pos.y, pos.z);
        var actor = scene.actor();
        if (actor == null) {
            return stage;
        }
        subject = actor.getId();
        player.lookAt(Anchor.EYES, actor.getEyePosition());
        return Stage.TEMPLE_SHOT;
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (subject != 0) {
            var actor = client.level.getEntity(subject);
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
        if (next == Stage.FIGHT) {
            choices.clear();
        }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../fate-" + name + ".png"));
                client.execute(() -> stage = next);
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
