package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** 倒在路边的修士取景：开场三选项、裹伤后递卷、夺卷后门人在路口亮朱印。 */
public final class FallenClientTest implements ClientModInitializer {
    private static final int AWAY = 160;
    private static final int TILE = 16;
    private static final int LEAD = 2;
    private static final int HALF_WIDTH = 4;
    private static final int LENGTH = 40;
    private static final int HEADROOM = 4;
    private static final int CAMERA = 4;
    private static final int SETTLE = 30;
    private static final int LIMIT = 6000;
    private static final String KEY = "story.meridian_story.fallen.";
    private final ChatChoices choices = new ChatChoices();
    private Stage stage = Stage.START;
    private int ticks;
    private int steady;

    private enum Stage { START, WALK_GOOD, OPENING, CLICK, HANDOVER, WALK_BAD, STRIKE, SCHOOL, SEAL, CAPTURE, DONE }

    @Override
    public void onInitializeClient() {
        if ("fallen".equals(System.getProperty("meridian.story.scene"))) {
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
                throw new IllegalStateException("路边修士取景超时 " + stage);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            switch (stage) {
                case OPENING -> {
                    if (choices.locate(client)) {
                        capture(client, "opening", Stage.CLICK);
                    }
                }
                case CLICK -> {
                    choices.click(client);
                    client.gui.setScreen(null);
                    stage = Stage.HANDOVER;
                }
                case HANDOVER -> {
                    if (choices.received(said("rescue.good"))) {
                        var id = client.player.getUUID();
                        server.submit(() -> frameActor(server.getPlayerList().getPlayer(id))).join();
                        capture(client, "handover", Stage.WALK_BAD);
                    }
                }
                case SEAL -> {
                    if (choices.received(narration("school"))) {
                        capture(client, "seal", Stage.DONE);
                    }
                }
                default -> {
                    var id = client.player.getUUID();
                    stage = server.submit(() -> advance(server.getPlayerList().getPlayer(id))).join();
                    client.options.keyUp.setDown(stage == Stage.WALK_GOOD || stage == Stage.STRIKE);
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
        return switch (stage) {
            case START -> {
                var server = player.level().getServer();
                var commands = server.getCommands();
                var source = server.createCommandSourceStack();
                commands.performPrefixedCommand(source, "difficulty easy");
                commands.performPrefixedCommand(source, "time set noon");
                commands.performPrefixedCommand(source, "weather clear");
                commands.performPrefixedCommand(source, "meridian debug origin set " + player.getScoreboardName()
                        + " meridian_story:ordinary");
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
                road(player, true);
                yield Stage.WALK_GOOD;
            }
            case WALK_GOOD -> {
                if (!opened(player)) {
                    yield stage;
                }
                // 停步后站到伤者跟前，开场旁白与三个选项一起入画。
                frame(player, FallenCultivator.actor(player, roadside(player).getCurrent()).orElseThrow());
                yield Stage.OPENING;
            }
            case WALK_BAD -> {
                if (!roadside(player).getLastEnding().equals("rescued")) {
                    yield stage;
                }
                road(player, false);
                yield Stage.STRIKE;
            }
            case STRIKE -> {
                if (!opened(player)) {
                    yield stage;
                }
                var actor = FallenCultivator.actor(player, roadside(player).getCurrent()).orElseThrow();
                AttackEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                        actor, null);
                yield Stage.SCHOOL;
            }
            case SCHOOL -> school(player);
            default -> stage;
        };
    }

    private static boolean opened(ServerPlayer player) {
        var roadside = roadside(player);
        return roadside.hasCurrent() && roadside.getCurrent().getWounded();
    }

    private Stage school(ServerPlayer player) {
        var roadside = roadside(player);
        var record = CrisisEncounters.current(player).orElseThrow();
        if (roadside.getInquiryAt() > 0 && record.getOnlineTicks() < roadside.getInquiryAt()) {
            StoryRaidFixture.roadClock(player, roadside.getInquiryAt());
            return stage;
        }
        var pending = record.getPending();
        if (!pending.getArrived() || !pending.getParty().equals(FallenCultivator.SCHOOL)) {
            return stage;
        }
        // 门人正式落地后原地站定，从他正面取景。
        if (player.level().getEntity(UUID.fromString(pending.getLeader())) instanceof Mob leader) {
            leader.getNavigation().stop();
            leader.setNoAi(true /* noAi */);
            leader.setCustomNameVisible(true /* visible */);
            frame(player, leader);
            return Stage.SEAL;
        }
        return stage;
    }

    private void frameActor(ServerPlayer player) {
        player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(CAMERA * 2),
                mob -> mob.getCustomName() != null).stream().findFirst().ifPresent(actor -> {
                    actor.setCustomNameVisible(true /* visible */);
                    frame(player, actor);
                });
    }

    private static void road(ServerPlayer player, boolean good) {
        // 冷却与场次号照固定种子预置；路肩铺在玩家面朝的南边，一进下一格就开场。
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var progress = StoryProgress.read(body);
        long seed = FallenCultivator.seed(player);
        long number = progress.getRoadside().getCases() + 1;
        while (RoadsideRules.good(RoadsideRules.roll(seed, number)) != good) {
            number++;
        }
        var roadside = progress.getRoadside().toBuilder().setCases(number - 1).clearRestUntil().build();
        if (!bodies.write(player, body, progress.toBuilder().setRoadside(roadside).build())) {
            throw new IllegalStateException("未能预置路肩场次");
        }
        var record = CrisisEncounters.current(player).orElseThrow();
        StoryRaidFixture.roadClock(player, record.getOnlineTicks() + LoneStarRoad.REST);
        var level = player.level();
        var column = player.blockPosition().east(AWAY);
        while (true) {
            if (RoadsideRules.opens(seed, FallenCultivator.tile(column.south(TILE)))) {
                break;
            }
            column = column.south(TILE);
        }
        var target = column.south(TILE);
        var start = new BlockPos(target.getX() - Math.floorMod(target.getX(), TILE) + TILE / 2, 0,
                target.getZ() - Math.floorMod(target.getZ(), TILE) - LEAD);
        level.getChunkAt(start);
        var feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start).above(HEADROOM);
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -LEAD; z <= LENGTH; z++) {
                var floor = feet.offset(x, -1, z);
                level.getChunkAt(floor);
                level.setBlockAndUpdate(floor, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int up = 1; up <= HEADROOM; up++) {
                    level.setBlockAndUpdate(floor.above(up), Blocks.AIR.defaultBlockState());
                }
            }
        }
        player.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        player.lookAt(Anchor.EYES, player.getEyePosition().add(0, 0, LENGTH));
    }

    private static void frame(ServerPlayer player, LivingEntity actor) {
        player.teleportTo(actor.getX(), actor.getY(), actor.getZ() - CAMERA);
        player.lookAt(Anchor.EYES, actor.getEyePosition());
    }

    private static Roadside roadside(ServerPlayer player) {
        return new StoryBodies().read(player).map(StoryProgress::read).orElseThrow().getRoadside();
    }

    private static Component narration(String key) {
        return Component.translatable(KEY + key).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    private static Component said(String key) {
        return Component.translatable(KEY + key).withStyle(ChatFormatting.ITALIC).copy()
                .withStyle(ChatFormatting.GRAY);
    }

    private void capture(Minecraft client, String name, Stage next) {
        if (++steady < SETTLE) {
            return;
        }
        steady = 0;
        stage = Stage.CAPTURE;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../fallen-" + name + ".png"));
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
