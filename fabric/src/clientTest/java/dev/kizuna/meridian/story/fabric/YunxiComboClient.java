package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesBirths;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/** 冻结集成服务端逐拍推进：轻撩被持守接住、连珠首拍、末拍破架，再拍回剑打身后一人。 */
public final class YunxiComboClient implements ClientModInitializer {
    private enum Frame {
        ORIGIN, SETTLE, SETUP, GUARD_UP, BURNED, LIFT_HIT, CHAIN_START, FIRST_BEAT, FINAL_BEAT, TURN, RETURN_HIT, DONE
    }

    private static final int TIMEOUT = 3000;
    private static final int VIEW_SETTLE = 8;
    private static final int ORIGIN_SETTLE = 40;
    private static final int GUARD_READY = 10;
    private static final int LIFT_HIT_TICK = 4;
    private static final int CHAIN_AFTER_HIT = 12;
    private static final int FIRST_BEAT_TICK = 6;
    private static final int BEAT_GAP = 7;
    private static final int RETURN_HIT_TICK = 7;
    private static final int RETURN_WAIT = 30;
    private static final int FACE_BACK = 180;
    private static final double REACH = 1.5;
    private static final double REAR = 1.6;
    private static final float VIEW_PITCH = 38;
    /** 起轻撩时守方该剩的余力：够付到末拍前的持守和前两击，末拍加倍后刚好不够。 */
    private static final double LIFT_BUDGET_JOULES = 27900;
    private static final double HOLD_JOULES_PER_TICK = 500;
    private final List<YunxiCombo.Outcome> completed = new ArrayList<>();
    private Frame frame = Frame.ORIGIN;
    private CompletableFuture<Void> shot;
    private ServerPlayer player;
    private Mob defender;
    private Cow rear;
    private float rearHealth;
    private long due;
    private int ticks;
    private int steady;

    @Override
    public void onInitializeClient() {
        if ("yunxi-combo".equals(System.getProperty("meridian.story.scene"))) {
            YunxiCombo.COMPLETED.register(completed::add);
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (frame == Frame.DONE && shot == null) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("连招实拍等待超时 " + frame);
            }
            if (client.player == null || client.getSingleplayerServer() == null || client.gui.overlay() != null) {
                return;
            }
            client.gui.toastManager().clear();
            advance(client);
        } catch (RuntimeException failure) {
            shot = null;
            finish(client, failure.toString());
        }
    }

    private void advance(Minecraft client) {
        // 画面停在哪一拍由冻结的主世界决定；截屏和随后的服务端动作都完成后才看下一拍。
        if (shot != null) {
            if (!shot.isDone()) {
                return;
            }
            shot.join();
            shot = null;
            if (frame == Frame.DONE) {
                finish(client, "");
            }
            return;
        }
        var server = client.getSingleplayerServer();
        if (frame == Frame.ORIGIN) {
            // 先经正式出身入口定下守墓人，选牌界面收起后再布置场景。
            UUID id = client.player.getUUID();
            server.submit(() -> YunxiScrolls.origin(server.getPlayerList().getPlayer(id), GraveVisits.ORIGIN)).join();
            frame = Frame.SETTLE;
            return;
        }
        if (frame == Frame.SETTLE) {
            if (++steady >= ORIGIN_SETTLE) {
                steady = 0;
                frame = Frame.SETUP;
            }
            return;
        }
        if (frame == Frame.SETUP) {
            client.gui.setScreen(null);
            // 背后视角压低俯角，越过攻方头顶同时看到对面和身后的人；打击只认水平朝向。
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            client.player.setXRot(VIEW_PITCH);
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
            UUID id = client.player.getUUID();
            server.submit(() -> setup(server.getPlayerList().getPlayer(id))).join();
            return;
        }
        if (!server.submit(() -> player.level().getGameTime() >= due).join() || ++steady < VIEW_SETTLE) {
            return;
        }
        steady = 0;
        Frame current = frame;
        client.player.setXRot(VIEW_PITCH);
        if (client.gui.screen() != null) {
            throw new IllegalStateException("画面被界面挡住 " + client.gui.screen().getClass().getSimpleName());
        }
        server.submit(() -> verify(current)).join();
        CompletableFuture<Void> captured = capture(client, current);
        shot = captured.thenRunAsync(() -> next(current), server);
    }

    private void setup(ServerPlayer actor) {
        // 攻方升到真炁并学齐六式；守方是持剑的卫道士，面向攻方起通用格挡。
        player = actor;
        player.level().getServer().tickRateManager().setFrozen(true /* frozen */);
        player.setGameMode(GameType.SURVIVAL);
        YunxiPracticeBody.practitioner(player);
        var service = TechniqueFabric.service();
        for (String form : List.of(THRUST, LIFT, PARRY, CLEAVE, RETURN, TWIN)) {
            service.learn(player.getUUID(), form);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Vec3 look = player.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 spot = player.position().add(look.scale(REACH));
        defender = EntityTypes.VINDICATOR.create(player.level(), EntitySpawnReason.COMMAND);
        float facing = player.getYRot() + FACE_BACK;
        defender.snapTo(spot.x, spot.y, spot.z, facing, 0);
        defender.setYBodyRot(facing);
        defender.setYHeadRot(facing);
        defender.setNoAi(true /* aiDisabled */);
        defender.setNoGravity(true /* noGravity */);
        HumanoidSpeciesBirths.birth(defender, HumanoidTier.MORTAL);
        defender.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        if (!player.level().addFreshEntity(defender)) {
            throw new IllegalStateException("守方未生成");
        }
        var guard = castFor(defender.getUUID(), GUARD);
        if (!guard.hasBinding()) {
            throw new IllegalStateException("守方无法起格挡 " + guard);
        }
        step(GUARD_READY, Frame.GUARD_UP);
    }

    private void verify(Frame current) {
        var guard = TechniqueFabric.service().activeGuard(defender.getUUID());
        switch (current) {
            case GUARD_UP, BURNED, LIFT_HIT, CHAIN_START, FIRST_BEAT -> {
                if (guard.isEmpty()) {
                    throw new IllegalStateException("守方不在架上 " + current);
                }
            }
            case FINAL_BEAT -> {
                boolean broken = completed.size() == 1 && completed.getFirst().broken()
                        && completed.getFirst().attacker().equals(player.getUUID());
                if (!broken || guard.isPresent()) {
                    throw new IllegalStateException("末拍没有破架 " + completed + " 仍守=" + guard.isPresent());
                }
            }
            case RETURN_HIT -> {
                if (rear.getHealth() >= rearHealth) {
                    throw new IllegalStateException("回剑没有伤到身后的人");
                }
            }
            default -> { }
        }
    }

    private void next(Frame current) {
        switch (current) {
            case GUARD_UP -> burn();
            case BURNED -> {
                start(LIFT);
                step(LIFT_HIT_TICK, Frame.LIFT_HIT);
            }
            case LIFT_HIT -> step(CHAIN_AFTER_HIT, Frame.CHAIN_START);
            case CHAIN_START -> {
                start(TWIN);
                step(FIRST_BEAT_TICK, Frame.FIRST_BEAT);
            }
            case FIRST_BEAT -> step(BEAT_GAP, Frame.FINAL_BEAT);
            case FINAL_BEAT -> {
                defender.discard();
                placeRear();
                step(RETURN_WAIT, Frame.TURN);
            }
            case TURN -> {
                start(RETURN);
                step(RETURN_HIT_TICK, Frame.RETURN_HIT);
            }
            case RETURN_HIT -> {
                player.level().getServer().tickRateManager().setFrozen(false /* frozen */);
                frame = Frame.DONE;
            }
            default -> { }
        }
    }

    private void burn() {
        // 持守每刻按固定功率扣体力；整刻推进，把守方余力耗到起轻撩的预算。
        double available = available(defender.getUUID());
        int count = (int) Math.floor((available - LIFT_BUDGET_JOULES) / HOLD_JOULES_PER_TICK);
        if (count < 1) {
            throw new IllegalStateException("守方余力不足以走完连招 " + available);
        }
        step(count, Frame.BURNED);
    }

    private void placeRear() {
        Vec3 look = player.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 spot = player.position().subtract(look.scale(REAR));
        rear = new Cow(EntityTypes.COW, player.level());
        rear.setPos(spot.x, spot.y, spot.z);
        rear.setNoAi(true /* noAi */);
        rear.setNoGravity(true /* noGravity */);
        if (!player.level().addFreshEntity(rear)) {
            throw new IllegalStateException("身后目标未生成");
        }
        rearHealth = rear.getHealth();
    }

    private static double available(UUID entity) {
        var body = TechniqueFabric.service().observe(entity).orElseThrow().body();
        return HumanStamina.from(body).availableJoules();
    }

    private void start(String form) {
        var outcome = castFor(player.getUUID(), form);
        if (!outcome.hasBinding()) {
            throw new IllegalStateException("实拍起手失败 " + form + " " + outcome);
        }
    }

    private static CastOutcome castFor(UUID entity, String form) {
        var service = TechniqueFabric.service();
        var body = service.observe(entity).orElseThrow().body();
        var reference = TechniqueReference.newBuilder()
                .setId(form)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return service.cast(entity, request);
    }

    private void step(int count, Frame next) {
        due = player.level().getGameTime() + count;
        if (!player.level().getServer().tickRateManager().stepGameIfPaused(count)) {
            throw new IllegalStateException("测试世界未冻结");
        }
        frame = next;
    }

    private static CompletableFuture<Void> capture(Minecraft client, Frame current) {
        // 只有四个画面要留图，其余拍直接放行。
        String name = switch (current) {
            case LIFT_HIT -> "lift-guarded";
            case FIRST_BEAT -> "twin-first";
            case FINAL_BEAT -> "twin-final-break";
            case RETURN_HIT -> "return-rear";
            default -> "";
        };
        if (name.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var result = new CompletableFuture<Void>();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../yunxi-p3-" + name + ".png"));
                result.complete(null);
            } catch (IOException failure) {
                result.completeExceptionally(new UncheckedIOException(failure));
            }
        });
        return result;
    }

    private void finish(Minecraft client, String error) {
        frame = Frame.DONE;
        try {
            Files.writeString(Path.of("../clienttest-results.txt"), "success=" + error.isEmpty() + "\nerror=" + error);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        client.stop();
    }
}
