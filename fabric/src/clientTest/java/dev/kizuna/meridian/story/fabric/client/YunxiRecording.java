package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.story.fabric.YunxiForms;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;
import static org.apache.commons.lang3.Validate.isTrue;

/** 在真实客户端逐刻记录施放、格中和还击，画面与逐帧表写入同一场景目录。 */
public final class YunxiRecording implements FabricClientGameTest {
    public record Camera(double x, double y, double z, float yaw, float pitch) { }
    private static final String THRUST = "meridian_story:wind_thrust";
    private static final String LIFT = "meridian_story:moon_lift";
    private static final int LEAD = 8;
    private static final int THRUST_TICKS = 11;
    private static final int LIFT_TICKS = 12;
    private static final int TAIL = 10;
    private static final int CONTACT_TICK = WARMUP + 3;
    private static final int COOLDOWN = 100;
    private static final int SETTLE = 12;
    private static final int CAMERA_FOV = 44;
    private static final int FIRST_PERSON_FOV = 70;
    private static final int EAST = -90;
    private static final int WEST = 90;
    private static final int GROUND_Y = 65;
    private static final double START_X = 0.5;
    private static final double LANE_Z = 0.5;
    private static final double REACH = 1.55;
    private static final double WALK_START = -3;
    private static final double CAMERA_Y = GROUND_Y + 1.18;
    private static final float CAMERA_PITCH = 3;
    private static final Camera RIGHT = new Camera(1.15, CAMERA_Y, 4.4, 180, CAMERA_PITCH);
    private static final Camera LEFT = new Camera(1.15, CAMERA_Y, -3.4, 0, CAMERA_PITCH);
    private static final Camera WIDE = new Camera(-0.3, CAMERA_Y, 6.4, 180, CAMERA_PITCH);
    private static volatile Camera camera;
    private Vindicator target;

    public static Camera camera() { return camera; }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            prepare(context, world);
            attack(context, world, THRUST, THRUST_TICKS, InteractionHand.MAIN_HAND);
            attack(context, world, LIFT, LIFT_TICKS, InteractionHand.MAIN_HAND);
            attack(context, world, CLEAVE, ORDINARY_HIT * 2, InteractionHand.MAIN_HAND);
            counter(context, world);
            walking(context, world);
            attack(context, world, THRUST, THRUST_TICKS, InteractionHand.OFF_HAND);
            firstPerson(context, world);
            Files.writeString(Path.of("../clienttest-results.txt"), "success=true\n");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            camera = null;
        }
    }

    private static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
        var host = world.getServer();
        host.runCommand("fill -16 64 -16 16 80 16 air");
        host.runCommand("fill -16 64 -16 16 64 16 grass_block");
        host.runCommand("fill -10 64 -2 10 64 3 smooth_stone");
        host.runCommand("time set noon");
        host.runCommand("weather clear");
        host.runCommand("gamerule advance_time false");
        host.runOnServer(server -> {
            var player = player(server);
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            YunxiPracticeBody.practitioner(player);
            for (String form : List.of(THRUST, LIFT, PARRY, CLEAVE)) {
                TechniqueFabric.service().learn(player.getUUID(), form);
            }
        });
    }

    private YunxiFrames stage(ClientGameTestContext context, TestSingleplayerContext world, String name,
                              InteractionHand hand, Camera view) throws IOException {
        var host = world.getServer();
        host.runOnServer(server -> {
            if (target != null) {
                target.discard();
            }
            var player = player(server);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.setItemInHand(hand, new ItemStack(Items.IRON_SWORD));
        });
        double x = view == WIDE ? WALK_START : START_X;
        host.runCommand("tp @a " + x + " " + GROUND_Y + " " + LANE_Z + " " + EAST + " 0");
        context.getInput().lookAt(EAST, 0);
        if (view != WIDE) {
            host.runOnServer(server -> target = spawnTarget(player(server)));
        }
        context.runOnClient(client -> {
            camera = view;
            var type = view == null ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
            client.options.setCameraType(type);
            int fov = view == null ? FIRST_PERSON_FOV : CAMERA_FOV;
            client.options.fov().set(fov);
            if (client.gui.hud.isHidden() != (view != null)) {
                client.gui.hud.toggle();
            }
            client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
        });
        context.waitTicks(COOLDOWN);
        return new YunxiFrames(context, world, name);
    }

    private static Vindicator spawnTarget(ServerPlayer player) {
        var level = player.level();
        var mob = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(START_X + REACH, GROUND_Y, LANE_Z, WEST, 0);
        mob.setYBodyRot(WEST);
        mob.setYHeadRot(WEST);
        mob.setNoAi(true /* noAi */);
        mob.setSilent(true /* silent */);
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
        isTrue(level.addFreshEntity(mob), "未能生成试剑目标");
        return mob;
    }

    private void attack(ClientGameTestContext context, TestSingleplayerContext world, String form,
                        int duration, InteractionHand hand) throws IOException {
        boolean off = hand == InteractionHand.OFF_HAND;
        String move = form.substring(form.indexOf(':') + 1);
        String suffix = off ? "-off" : "-main";
        Camera view = off ? LEFT : RIGHT;
        var frames = stage(context, world, move + suffix, hand, view);
        frames.advance(LEAD);
        start(world, form, 0);
        frames.advance(duration + TAIL);
        String clipSuffix = off ? "_off" : "";
        frames.finish("yunxi_" + move + clipSuffix);
    }

    private void counter(ClientGameTestContext context, TestSingleplayerContext world) throws IOException {
        var frames = stage(context, world, "parry-counter", InteractionHand.MAIN_HAND, RIGHT);
        frames.advance(LEAD);
        start(world, PARRY, 0);
        frames.advance(CONTACT_TICK);
        world.getServer().runOnServer(server -> {
            var player = player(server);
            target.swing(InteractionHand.MAIN_HAND);
            YunxiForms.incoming(player, target);
            var guard = progress(player).getActiveCast().getSustained();
            isTrue(guard.getGuardContact() && guard.hasEndRequestedMillis(), "正面来击没有产生实挡回执");
        });
        frames.advance(RECOVERY);
        start(world, CLEAVE, SKIP);
        frames.advance(RIPOSTE_HIT + ORDINARY_HIT + TAIL);
        frames.finish("yunxi_flash_parry", "yunxi_flash_parry_hit", "yunxi_cloud_cleaver");
    }

    private void walking(ClientGameTestContext context, TestSingleplayerContext world) throws IOException {
        var frames = stage(context, world, "parry-walk", InteractionHand.MAIN_HAND, WIDE);
        context.getInput().holdKey(GLFW.GLFW_KEY_W);
        try {
            frames.advance(LEAD);
            start(world, PARRY, 0);
            double start = frames.position();
            frames.advance(WARMUP + HOLD);
            isTrue(frames.position() > start + 1, "持守途中没有移动一格");
            frames.advance(RECOVERY);
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        }
        frames.advance(TAIL);
        frames.finish("yunxi_flash_parry", "yunxi_flash_parry_lower");
    }

    private void firstPerson(ClientGameTestContext context, TestSingleplayerContext world) throws IOException {
        var frames = stage(context, world, "first-person", InteractionHand.MAIN_HAND, null);
        frames.advance(LEAD);
        start(world, THRUST, 0);
        frames.advance(THRUST_TICKS + SETTLE);
        start(world, PARRY, 0);
        frames.advance(WARMUP + HOLD + RECOVERY + TAIL);
        frames.finish("yunxi_wind_thrust", "yunxi_flash_parry", "yunxi_flash_parry_lower");
    }

    private static void start(TestSingleplayerContext world, String form, int offset) {
        world.getServer().runOnServer(server -> {
            var player = player(server);
            TechniqueFabric.service().tick();
            var result = cast(player, form);
            isTrue(result.hasBinding(), "施放失败 " + form + " " + result);
            isTrue(progress(player).getActiveCast().getClipStartMillis() == offset, "起播时刻错误 " + form);
        });
    }

    static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
