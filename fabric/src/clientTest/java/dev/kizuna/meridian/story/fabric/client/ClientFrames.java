package dev.kizuna.meridian.story.fabric.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** 录像截帧：在客户端线程按指定尺寸把当前画面重画一遍再存图，世界不多走一刻，相邻两帧就是相邻两刻。 */
public final class ClientFrames {
    private ClientFrames() { }

    public static void capture(Minecraft client, Path path, int width, int height) {
        var renderer = client.gameRenderer;
        var window = client.getWindow();
        int shownWidth = window.getWidth();
        int shownHeight = window.getHeight();
        var target = renderer.mainRenderTarget();
        try {
            window.setWidth(width);
            window.setHeight(height);
            target.resize(width, height);
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
            window.setWidth(shownWidth);
            window.setHeight(shownHeight);
            target.resize(shownWidth, shownHeight);
        }
    }
}
