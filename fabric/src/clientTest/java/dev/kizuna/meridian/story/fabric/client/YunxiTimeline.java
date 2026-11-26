package dev.kizuna.meridian.story.fabric.client;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 集成服务端冻结在指定拍，等客户端画面和截图完成后再放行下一段。 */
public final class YunxiTimeline {
    private enum Frame {
        ORDINARY_START, ORDINARY_BEFORE, ORDINARY_HIT_FRAME, COOLDOWN, GUARD, RECOVER,
        RIPOSTE_START, RIPOSTE_BEFORE, RIPOSTE_HIT_FRAME, DONE
    }

    private static final int COOLDOWN_TICKS = 80;
    private static final int VIEW_SETTLE = 6;
    private static final double RANGE = 1.6;
    private static final double SIDE = 0.55;
    private final ServerPlayer player;
    private final Cow target;
    private Frame frame = Frame.ORDINARY_START;
    private CompletableFuture<Void> shot;
    private long due;
    private int steady;
    private float health;

    public YunxiTimeline(Minecraft client) {
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        if (!client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        player = server.submit(() -> server.getPlayerList().getPlayer(id)).join();
        target = server.submit(() -> {
            server.tickRateManager().setFrozen(true /* frozen */);
            YunxiPracticeBody.practitioner(player);
            TechniqueFabric.service().learn(player.getUUID(), CLEAVE);
            var cow = new Cow(EntityTypes.COW, player.level());
            var look = player.getLookAngle().multiply(1, 0, 1).normalize();
            var side = look.cross(new net.minecraft.world.phys.Vec3(0, 1, 0)).scale(SIDE);
            cow.setPos(player.position().add(look.scale(RANGE)).add(side));
            cow.setNoAi(true /* noAi */);
            cow.setNoGravity(true /* noGravity */);
            if (!player.level().addFreshEntity(cow)) {
                throw new IllegalStateException("时间轴目标未生成");
            }
            health = cow.getHealth();
            start(CLEAVE, 0);
            due = player.level().getGameTime();
            return cow;
        }).join();
    }

    public boolean tick(Minecraft client) {
        // 画面在哪一拍由主世界决定，实际截屏等渲染管线回执。
        if (shot != null) {
            if (!shot.isDone()) {
                return false;
            }
            shot.join();
            shot = null;
        }
        var server = client.getSingleplayerServer();
        if (!server.submit(() -> player.level().getGameTime() >= due).join() || ++steady < VIEW_SETTLE) {
            return false;
        }
        steady = 0;
        Frame current = frame;
        server.submit(() -> verify(current)).join();
        switch (current) {
            case ORDINARY_START -> capture(client, "ordinary-0");
            case ORDINARY_BEFORE -> capture(client, "ordinary-8");
            case ORDINARY_HIT_FRAME -> capture(client, "ordinary-9");
            case RIPOSTE_START -> capture(client, "riposte-0");
            case RIPOSTE_BEFORE -> capture(client, "riposte-4");
            case RIPOSTE_HIT_FRAME -> capture(client, "riposte-5");
            default -> { }
        }
        if (shot != null) {
            shot = shot.thenRunAsync(() -> advance(current), server);
        } else {
            server.submit(() -> advance(current)).join();
        }
        return current == Frame.DONE;
    }

    private void verify(Frame current) {
        switch (current) {
            case ORDINARY_BEFORE, RIPOSTE_BEFORE -> {
                if (target.getHealth() != health) {
                    throw new IllegalStateException("目标提前受伤 " + current);
                }
            }
            case ORDINARY_HIT_FRAME, RIPOSTE_HIT_FRAME -> {
                if (target.getHealth() >= health) {
                    throw new IllegalStateException("落剑拍未伤目标 " + current);
                }
            }
            default -> { }
        }
    }

    private void advance(Frame current) {
        // 两次斜劈之间等完正常冷却，第二次先让本人真实挡住一次来击。
        switch (current) {
            case ORDINARY_START -> step(ORDINARY_HIT - 1, Frame.ORDINARY_BEFORE);
            case ORDINARY_BEFORE -> step(1, Frame.ORDINARY_HIT_FRAME);
            case ORDINARY_HIT_FRAME -> step(ORDINARY_HIT + COOLDOWN_TICKS + 1, Frame.COOLDOWN);
            case COOLDOWN -> {
                start(PARRY, 0);
                step(WARMUP, Frame.GUARD);
            }
            case GUARD -> {
                incoming(player, target);
                if (!progress(player).getActiveCast().getSustained().getGuardContact()) {
                    throw new IllegalStateException("时间轴未记录实际格中");
                }
                step(RECOVERY, Frame.RECOVER);
            }
            case RECOVER -> {
                health = target.getHealth();
                start(CLEAVE, SKIP);
                frame = Frame.RIPOSTE_START;
            }
            case RIPOSTE_START -> step(RIPOSTE_HIT - 1, Frame.RIPOSTE_BEFORE);
            case RIPOSTE_BEFORE -> step(1, Frame.RIPOSTE_HIT_FRAME);
            case RIPOSTE_HIT_FRAME -> frame = Frame.DONE;
            case DONE -> player.level().getServer().tickRateManager().setFrozen(false /* frozen */);
        }
    }

    private void start(String form, int offset) {
        var outcome = cast(player, form);
        if (!outcome.hasBinding() || progress(player).getActiveCast().getClipStartMillis() != offset) {
            throw new IllegalStateException("时间轴起播错误 " + outcome);
        }
    }

    private void step(int ticks, Frame next) {
        due = player.level().getGameTime() + ticks;
        if (!player.level().getServer().tickRateManager().stepGameIfPaused(ticks)) {
            throw new IllegalStateException("测试世界未冻结");
        }
        frame = next;
    }

    private void capture(Minecraft client, String name) {
        var result = new CompletableFuture<Void>();
        shot = result;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../yunxi-" + name + ".png"));
                result.complete(null);
            } catch (IOException failure) {
                result.completeExceptionally(new UncheckedIOException(failure));
            }
        });
    }
}
