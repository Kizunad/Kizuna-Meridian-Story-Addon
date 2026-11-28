package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.fabric.client.ClientFrames;
import dev.kizuna.playeranimation.client.PlayerAnimationClient;
import dev.kizuna.playeranimation.client.PlayerPlayback;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;

import static org.apache.commons.lang3.Validate.isTrue;

/**
 * NPC 日常姿态的真客户端录像：算命人坐到火边烤火、挨打后快版起身；摊主靠上柜台；三个村民随机换姿态。
 * 机位先架好再请人，进入段也拍得到。每刻（村民那段每两刻）在客户端线程重画一帧存到 ../npc-pose-frames/&lt;场景&gt;/，
 * 相邻两帧核对是相隔这么多的世界刻；frames.tsv 记下每帧的世界刻和各演员正在播的片段，帧序列另用 ffmpeg 合成视频。
 */
public final class NpcPoseRecording implements FabricClientGameTest {
    private static final String SCENE = "npc-poses";
    private static final Path FRAMES = Path.of("../npc-pose-frames");
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int GROUND_Y = 65;
    private static final int SETTLE = 40;
    private static final int CAMERA_FOV = 50;
    // tp 摆的是脚，旁观者的眼睛在脚上方 1.62 格；机位写的是眼睛的位置。
    private static final double EYE_HEIGHT = 1.62;
    private static final int EVERY_TICK = 1;
    // 算命人：到场 1 秒后坐下（24 刻），烤火约 5 秒后挨打，快版起身 15 刻，再看 2.5 秒（起身后要歇 10 秒才坐回去）。
    private static final BlockPos FIRE = new BlockPos(0, GROUND_Y, 0);
    private static final int SEER_HIT = 150;
    private static final int SEER_TICKS = 220;
    // 摊主：柜台在北，摊主站在南面一格；到场 1 秒后用 0.6 秒靠上柜台，再看 8 秒。
    private static final BlockPos COUNTER = new BlockPos(12, GROUND_Y, 0);
    private static final int STALL_TICKS = 200;
    // 村民：三个有 AI、腿速清零的村民站成一排，录 80 秒；一种姿态摆 20–60 秒，这段时间里会换。
    private static final int VILLAGER_TICKS = 1600;
    private static final int VILLAGER_EVERY = 2;
    private static final double[] VILLAGER_X = {22.5, 24.5, 26.5};
    private static final double VILLAGER_Z = 0.5;
    private static final float HIT = 1;
    private static final Shot SEER_SHOT = new Shot(2.4, 66.3, -0.9, 0.5, 65.6, 1.3);
    private static final Shot STALL_SHOT = new Shot(14.2, 66.5, -1.4, 12.5, 65.9, 1.2);
    private static final Shot VILLAGER_SHOT = new Shot(24.5, 66.6, 5.6, 24.5, 65.9, 0.5);

    /** 机位：眼睛在 (x, y, z)，看向 (atX, atY, atZ)。 */
    private record Shot(double x, double y, double z, double atX, double atY, double atZ) { }

    /** 一段录像：场景名、录多少刻、每几刻一帧、要记录片段的演员。 */
    private record Take(String name, int ticks, int every, List<Integer> actors) { }

    private interface TickAction {
        void at(int tick);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!SCENE.equals(System.getProperty("meridian.story.scene"))) {
            return;
        }
        try (var world = context.worldBuilder().create()) {
            prepare(context, world);
            fortuneTeller(context, world.getServer());
            bookstall(context, world.getServer());
            villagers(context, world.getServer());
            Files.writeString(Path.of("../clienttest-results.txt"), "success=true\n");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
        var host = world.getServer();
        host.runCommand("fill -8 64 -8 32 80 12 air");
        host.runCommand("fill -8 64 -8 32 64 12 grass_block");
        host.runCommand("time set noon");
        host.runCommand("weather clear");
        host.runCommand("gamerule advance_time false");
        host.runCommand("gamemode spectator @a");
        context.runOnClient(client -> {
            client.options.fov().set(CAMERA_FOV);
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
    }

    private static void fortuneTeller(ClientGameTestContext context, TestServerContext host) throws IOException {
        place(host, FIRE, "campfire");
        look(context, host, SEER_SHOT);
        int seer = host.computeOnServer(server -> {
            var villager = actor(server, EntityTypes.VILLAGER.create(server.overworld(), EntitySpawnReason.COMMAND),
                    FIRE.getX() + 0.5, FIRE.getZ() + 2.5);
            StoryPoses.seat(server.overworld(), villager, FIRE);
            return villager.getId();
        });
        var take = new Take("fortune-teller", SEER_TICKS, EVERY_TICK, List.of(seer));
        var seen = record(context, host, take, tick -> {
            if (tick == SEER_HIT) {
                host.runOnServer(server -> {
                    var level = server.overworld();
                    var villager = (Villager) level.getEntity(seer);
                    villager.hurtServer(level, level.damageSources().generic(), HIT);
                });
            }
        });
        expect(take, seen, "campfire_sit_down_robed", "campfire_warm_loop_robed", "campfire_stand_up_quick_robed");
        host.runOnServer(server -> server.overworld().getEntity(seer).discard());
        place(host, FIRE, "air");
    }

    private static void bookstall(ClientGameTestContext context, TestServerContext host) throws IOException {
        place(host, COUNTER, "spruce_planks");
        look(context, host, STALL_SHOT);
        int seller = host.computeOnServer(server -> {
            var trader = actor(server, EntityTypes.WANDERING_TRADER.create(server.overworld(),
                    EntitySpawnReason.COMMAND), COUNTER.getX() + 0.5, COUNTER.getZ() + 1.5);
            // 摊主平时靠柜台或叉腰二选一；录像只留靠柜台，确定拍到这一种。
            StoryPoses.tend(trader, COUNTER);
            StoryPoses.drop(trader, StoryPoses.AKIMBO);
            return trader.getId();
        });
        var take = new Take("bookstall", STALL_TICKS, EVERY_TICK, List.of(seller));
        var seen = record(context, host, take, tick -> { });
        expect(take, seen, "idle_lean_counter_enter_robed", "idle_lean_counter_robed");
        host.runOnServer(server -> server.overworld().getEntity(seller).discard());
        place(host, COUNTER, "air");
    }

    private static void villagers(ClientGameTestContext context, TestServerContext host) throws IOException {
        look(context, host, VILLAGER_SHOT);
        List<Integer> ids = host.computeOnServer(server -> {
            var found = new ArrayList<Integer>();
            for (double x : VILLAGER_X) {
                var villager = EntityTypes.VILLAGER.create(server.overworld(), EntitySpawnReason.COMMAND);
                villager.snapTo(x, GROUND_Y, VILLAGER_Z, 0, 0);
                villager.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                server.overworld().addFreshEntity(villager);
                found.add(villager.getId());
            }
            return found;
        });
        // 随机换的是哪几种不定，不在这里断言；frames.tsv 里逐帧记着每个村民在播什么。
        record(context, host, new Take("villagers", VILLAGER_TICKS, VILLAGER_EVERY, ids), tick -> { });
        host.runOnServer(server -> ids.forEach(id -> server.overworld().getEntity(id).discard()));
    }

    private static <T extends Mob> T actor(MinecraftServer server, T mob, double x, double z) {
        // 和剧情仓请人一样：关掉 AI，由剧本摆位置、写标签。
        mob.snapTo(x, GROUND_Y, z, 0, 0);
        mob.setNoAi(true /* noAi */);
        server.overworld().addFreshEntity(mob);
        return mob;
    }

    private static void place(TestServerContext host, BlockPos pos, String block) {
        host.runCommand(String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block));
    }

    private static void look(ClientGameTestContext context, TestServerContext host, Shot shot) {
        double dx = shot.atX() - shot.x();
        double dy = shot.atY() - shot.y();
        double dz = shot.atZ() - shot.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        host.runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f",
                shot.x(), shot.y() - EYE_HEIGHT, shot.z(), yaw, pitch));
        context.waitTicks(SETTLE);
    }

    private static Set<String> record(ClientGameTestContext context, TestServerContext host, Take take,
                                      TickAction action) throws IOException {
        var directory = Files.createDirectories(FRAMES.resolve(take.name()));
        var rows = new ArrayList<String>();
        var seen = new TreeSet<String>();
        long previous = -1;
        for (int tick = 0; tick < take.ticks(); tick++) {
            action.at(tick);
            context.waitTick();
            if (tick % take.every() == 0) {
                long now = host.computeOnServer(server -> server.overworld().getGameTime());
                isTrue(previous < 0 || now == previous + take.every(), "相邻两帧不是相隔 %d 刻：%d → %d",
                        take.every(), previous, now);
                previous = now;
                var path = directory.resolve(String.format(Locale.ROOT, "frame-%04d.png", rows.size()));
                String playing = context.computeOnClient(client -> sample(client, path, take.actors(), seen));
                rows.add(rows.size() + "\t" + now + "\t" + playing);
            }
        }
        var table = "frame\tworld_tick\tentity:clip@seconds/weight\n" + String.join("\n", rows) + "\n";
        Files.writeString(directory.resolve("frames.tsv"), table);
        return seen;
    }

    private static String sample(Minecraft client, Path path, List<Integer> actors, Set<String> seen) {
        // 与渲染同一时刻读 PA 的显式片段：片名、剪辑时刻和混合权重。
        ClientFrames.capture(client, path, WIDTH, HEIGHT);
        var clips = new ArrayList<String>();
        double now = PlayerPlayback.now(1);
        for (int id : actors) {
            var playback = PlayerAnimationClient.PLAYBACK.entity(id);
            if (playback != null) {
                for (var clip : playback.active(now)) {
                    seen.add(clip.entry().id());
                    clips.add(String.format(Locale.ROOT, "%d:%s@%.3f/%.3f", id, clip.entry().id(), clip.time(),
                            clip.weight()));
                }
            }
        }
        return String.join(" ", clips);
    }

    private static void expect(Take take, Set<String> seen, String... clips) {
        isTrue(seen.containsAll(List.of(clips)), "%s 预期播到 %s，实播 %s", take.name(), List.of(clips), seen);
    }
}
