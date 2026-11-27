package dev.kizuna.meridian.story.fabric.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.playeranimation.client.PlayerAnimationClient;
import dev.kizuna.playeranimation.client.PlayerPlayback;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import static org.apache.commons.lang3.Validate.isTrue;

final class YunxiFrames {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final Path directory;
    private final Set<String> seen = new HashSet<>();
    private final List<String> rows = new ArrayList<>();
    private long previousTick = -1;

    YunxiFrames(ClientGameTestContext context, TestSingleplayerContext world, String name) throws IOException {
        this.context = context;
        this.world = world;
        directory = Files.createDirectories(Path.of("../yunxi-frames", name));
    }

    double position() {
        return world.getServer().computeOnServer(server -> YunxiRecording.player(server).getX());
    }

    void advance(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            context.waitTick();
            long now = world.getServer().computeOnServer(server -> server.overworld().getGameTime());
            isTrue(rows.isEmpty() || now == previousTick + 1, "相邻画面没有对应相邻世界刻");
            previousTick = now;
            int frame = rows.size();
            var path = directory.resolve(String.format(Locale.ROOT, "frame-%04d.png", frame));
            String playing = context.computeOnClient(client -> sample(client, path));
            rows.add(frame + "\t" + now + "\t" + position() + "\t" + playing);
        }
    }

    private String sample(Minecraft client, Path path) {
        // 与渲染相同的帧内插值，直接记录 PA 的片名、剪辑时刻和混合权重。
        capture(client, path);
        var playback = PlayerAnimationClient.PLAYBACK.player(client.player.getUUID());
        var clips = new ArrayList<String>();
        if (playback != null) {
            for (var clip : playback.active(PlayerPlayback.now(1))) {
                String id = clip.entry().id();
                if (id.startsWith("yunxi_")) {
                    seen.add(id);
                }
                clips.add(String.format(Locale.ROOT, "%s@%.3f/%.3f", id, clip.time(), clip.weight()));
            }
        }
        return String.join(" ", clips);
    }

    void finish(String... expected) throws IOException {
        var table = "frame\tworld_tick\tplayer_x\tclip@seconds/weight\n" + String.join("\n", rows) + "\n";
        Files.writeString(directory.resolve("frames.tsv"), table);
        isTrue(seen.equals(Set.of(expected)), directory + " 预期 " + List.of(expected) + " 实播 " + seen);
    }

    private static void capture(Minecraft client, Path path) {
        var renderer = client.gameRenderer;
        var window = client.getWindow();
        int width = window.getWidth();
        int height = window.getHeight();
        var target = renderer.mainRenderTarget();
        try {
            window.setWidth(WIDTH);
            window.setHeight(HEIGHT);
            target.resize(WIDTH, HEIGHT);
            renderer.update(DeltaTracker.ONE);
            renderer.extract(DeltaTracker.ONE, true /* renderLevel */);
            renderer.render(DeltaTracker.ONE, true /* renderLevel */);
            RenderSystem.getDevice().createCommandEncoder().submit();
            Screenshot.takeScreenshot(target, image -> {
                try (image) {
                    image.writeToFile(path);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } finally {
            window.setWidth(width);
            window.setHeight(height);
            target.resize(width, height);
        }
    }
}
