package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import dev.kizuna.meridian.story.fabric.client.StoryInventory;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.status.StatusArrangement;
import dev.kizuna.meridian.humanoid.fabric.StoryRaidFixture;
import dev.kizuna.meridian.story.server.StoryProgress;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.components.ChatComponent.ChatMethod;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.kizuna.meridian.origin.fabric.client.OriginScreen;
import dev.kizuna.meridian.origin.fabric.client.StoryOriginCards;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class GraveClientTest implements ClientModInitializer {
    private static final int READY = 45;
    private static final int SETTLE = 50;
    private static final int TIMEOUT = 5200;
    private static final BlockPos[] CAMERAS = {
            new BlockPos(3, 8, 22), new BlockPos(-6, 4, 13), new BlockPos(4, 2, 14)
    };
    private static final BlockPos[] TARGETS = {
            new BlockPos(0, 1, 1), new BlockPos(0, 1, -1), new BlockPos(9, 1, 7)
    };
    private static final String[] NAMES = {"entrance", "stone", "shelter", "inheritance", "origin",
            "notes", "homesick", "vigil", "visitor", "fragment", "directions"};
    private final ChatChoices choices = new ChatChoices();
    private int ticks;
    private int frame;
    private int wait;
    private boolean started;
    private boolean placed;
    private boolean capturing;
    private boolean done;
    private BlockPos landmark;
    private static final int COURTYARD_EDGE = 8;
    private static final int CARD = 4;
    private static final int BOOK = 5;
    private static final int MELANCHOLY = 6;
    private static final int VIGIL = 7;
    private static final int VISITOR = 8;
    private static final int FRAGMENT = 9;
    private static final int DIRECTIONS = 10;
    private static final int ICON_HALF = 12;
    private static final int ABSENT_DAYS = 5;
    private static final int AWAY = 45;
    private static final int GUIDE_SLOT = 2;

    @Override
    public void onInitializeClient() {
        if ("grave".equals(System.getProperty("meridian.story.scene"))) {
            choices.listen();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (done || capturing) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("等待祖冢取景超时，镜位 " + frame);
            }
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null || client.gui.overlay() != null || ticks < READY) {
                return;
            }
            var who = client.player.getUUID();
            client.gui.toastManager().clear();
            if (!started) {
                started = server.submit(() -> start(server.getPlayerList().getPlayer(who))).join();
                return;
            }
            if (frame == NAMES.length) {
                finish(client, null);
                return;
            }
            if (frame == DIRECTIONS) {
                directions(client);
                return;
            }
            if (frame == CARD) {
                card(client);
                return;
            }
            if (frame >= BOOK) {
                if (frame == FRAGMENT) {
                    fragment(client);
                    return;
                }
                story(client);
                return;
            }
            if (!placed) {
                placed = server.submit(() -> camera(server.getPlayerList().getPlayer(who))).join();
                if (placed) {
                    client.gui.setScreen(null);
                    if (client.gui.hud.isHidden() != (frame < CAMERAS.length)) {
                        client.gui.hud.toggle();
                    }
                    if (frame == CAMERAS.length) {
                        client.gui.setScreen(new StoryInventory(client.player));
                    }
                }
            } else if (frame == 0 && !courtyardVisible(client)) {
                wait = 0;
            } else if (frame == CAMERAS.length && client.gui.screen() instanceof StoryInventory view
                    && !view.hover(client, "item.meridian_story.grave_sword")) {
                return;
            } else if (++wait >= SETTLE) {
                capture(client);
            }
        } catch (RuntimeException failure) {
            finish(client, failure.toString());
        }
    }

    private boolean courtyardVisible(Minecraft client) {
        var renderer = client.levelRenderer;
        return renderer.isSectionCompiledAndVisible(landmark) && renderer.hasRenderedAllSections()
                && renderer.isSectionCompiledAndVisible(landmark.west(COURTYARD_EDGE))
                && renderer.isSectionCompiledAndVisible(landmark.east(COURTYARD_EDGE));
    }

    private void card(Minecraft client) {
        if (!placed) {
            client.gui.setScreen(null);
            client.player.connection.sendCommand("meridian body origin");
            placed = true;
        }
        if (client.gui.screen() instanceof OriginScreen screen && StoryOriginCards.chosen(screen, GraveVisits.ORIGIN)
                && ++wait >= SETTLE) {
            capture(client);
        }
    }

    private void story(Minecraft client) {
        var server = client.getSingleplayerServer();
        var owner = client.player.getUUID();
        var player = server.getPlayerList().getPlayer(owner);
        if (!placed) {
            client.gui.setScreen(null);
            if (frame == BOOK) {
                server.submit(() -> {
                    player.getInventory().setSelectedSlot(1);
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(1));
                    GraveItems.NOTES.use(player.level(), player, InteractionHand.MAIN_HAND);
                }).join();
            } else if (frame == VISITOR) {
                if (!server.submit(() -> greet(player)).join()) {
                    return;
                }
                client.gui.hud.getChat().openScreen(ChatMethod.MESSAGE, ChatScreen::new);
            } else {
                server.submit(() -> approach(player)).join();
            }
            placed = true;
            return;
        }
        if (frame == BOOK && !(client.gui.screen() instanceof BookViewScreen)) {
            return;
        }
        if (frame == VISITOR && !choices.locate(client)) {
            return;
        }
        if (frame == MELANCHOLY || frame == VIGIL) {
            boolean sad = client.player.hasEffect(GraveMood.EFFECT);
            if (sad != (frame == MELANCHOLY)) {
                return;
            }
            if (!(client.gui.screen() instanceof InventoryScreen)) {
                client.gui.setScreen(new InventoryScreen(client.player));
            }
            if (frame == MELANCHOLY && !hoverMood(client)) {
                return;
            }
        }
        if (++wait >= SETTLE) {
            capture(client);
        }
    }

    private static boolean hoverMood(Minecraft client) {
        var layout = StatusIcons.lastLayout();
        for (int i = 0; i < layout.slots().size(); i++) {
            if (layout.slots().get(i) instanceof StatusArrangement.Single single
                    && single.effect().id().equals("meridian_story:homesick")) {
                var cell = layout.cells().get(i);
                long window = client.getWindow().handle();
                double scale = client.getWindow().getGuiScale();
                double x = (cell.left() + ICON_HALF) * scale;
                double y = (cell.top() + ICON_HALF) * scale;
                var callback = GLFW.glfwSetCursorPosCallback(window, null);
                GLFW.glfwSetCursorPosCallback(window, callback);
                if (callback == null) {
                    GLFW.glfwSetCursorPos(window, x, y);
                } else {
                    callback.invoke(window, x, y);
                }
                return true;
            }
        }
        return false;
    }

    private void fragment(Minecraft client) {
        if (!placed) {
            choices.click(client);
            placed = true;
        }
        if (!client.player.getInventory().contains(stack -> stack.is(GraveItems.FRAGMENT))) {
            return;
        }
        if (!(client.gui.screen() instanceof StoryInventory inventory)) {
            client.gui.setScreen(new StoryInventory(client.player));
            return;
        }
        if (!inventory.hover(client, "item.meridian_story.grave_fragment")) {
            return;
        }
        if (++wait >= SETTLE) {
            capture(client);
        }
    }

    private void directions(Minecraft client) {
        if (!placed) {
            client.gui.setScreen(null);
            client.player.getInventory().setSelectedSlot(GUIDE_SLOT);
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            placed = true;
        }
        var server = client.getSingleplayerServer();
        var stone = server.submit(() -> GraveSite.get(server.overworld()).anchor().orElseThrow()).join();
        var expected = Component.translatable("story.meridian_story.tomb.directions",
                stone.getX(), stone.getY(), stone.getZ());
        if (choices.received(expected) && ++wait >= SETTLE) {
            capture(client);
        }
    }

    private void approach(ServerPlayer player) {
        var site = GraveSite.get(player.level());
        var visitorSpot = site.guest().east(3);
        BlockPos feet;
        if (frame == MELANCHOLY) {
            feet = visitorSpot.east(AWAY);
            for (int day = 0; day < ABSENT_DAYS; day++) {
                StoryRaidFixture.passDay(player);
            }
        } else {
            feet = visitorSpot;
            var bodies = new StoryBodies();
            var body = bodies.read(player).orElseThrow();
            var progress = StoryProgress.read(body);
            var grave = progress.getGrave();
            int visits = grave.getVisits();
            while (!GraveVisits.lot(player, site, visits + 1).equals("friend")) {
                visits++;
            }
            var next = grave.toBuilder().setVisits(visits).build();
            bodies.write(player, body, progress.toBuilder().setGrave(next).build());
        }
        var point = Vec3.atBottomCenterOf(feet);
        player.teleportTo(player.level(), point.x, point.y, point.z, Set.of(), 90, 0, true /* resetCamera */);
    }

    private static boolean greet(ServerPlayer player) {
        var body = new StoryBodies().read(player).orElseThrow();
        var grave = StoryProgress.read(body).getGrave();
        if (!(player.level().getEntity(GraveVisits.visitor(player, grave)) instanceof LivingEntity guest)) {
            return false;
        }
        player.setShiftKeyDown(true /* sneaking */);
        var result = UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                guest, null);
        player.setShiftKeyDown(false /* sneaking */);
        return result.consumesAction();
    }

    private static boolean start(ServerPlayer player) {
        if (player == null || CrisisEncounters.current(player).isEmpty()) {
            return false;
        }
        var server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "difficulty peaceful");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "meridian debug origin set " + player.getScoreboardName() + " " + GraveVisits.ORIGIN);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        return true;
    }

    private boolean camera(ServerPlayer player) {
        var site = GraveSite.get(player.level()).anchor();
        if (site.isEmpty() || !player.getInventory().contains(stack -> stack.is(GraveItems.GUIDE))) {
            return false;
        }
        if (frame >= CAMERAS.length) {
            player.setGameMode(GameType.SURVIVAL);
            return true;
        }
        var stone = site.get();
        landmark = stone;
        player.setGameMode(GameType.SPECTATOR);
        Vec3 feet = Vec3.atBottomCenterOf(stone.offset(CAMERAS[frame]));
        Vec3 eye = feet.add(0, player.getEyeHeight(), 0);
        Vec3 direction = Vec3.atCenterOf(stone.offset(TARGETS[frame])).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        player.teleportTo(player.level(), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true /* resetCamera */);
        return true;
    }

    private void capture(Minecraft client) {
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../grave-" + NAMES[frame] + ".png"));
                client.execute(() -> {
                    frame++;
                    wait = 0;
                    placed = false;
                    capturing = false;
                });
            } catch (Exception failed) {
                client.execute(() -> finish(client, failed.toString()));
            }
        });
    }

    private void finish(Minecraft client, String error) {
        done = true;
        try {
            Files.writeString(Path.of("../clienttest-results.txt"), "success=" + (error == null) + "\nerror=" + error);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
        if (client.level != null) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.stop();
    }
}
