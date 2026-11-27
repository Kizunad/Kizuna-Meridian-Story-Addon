package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.YunxiPracticeBody;
import dev.kizuna.meridian.story.fabric.client.ChatChoices;
import dev.kizuna.meridian.story.server.StoryProgress;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.story.fabric.YunxiForms.*;

/**
 * 陈恪冢前试剑实拍：先用调试指令把他请到没学剑的守墓人面前，点 [请前辈指教] 只得一句提示；
 * 学齐两式后走远再回冢，他按正式条件到场约剑，点同一枚按钮开场，冻结服务端逐拍推进轻撩、连珠首拍与末拍。
 * 认招后他问学不学《寻隙剑谱》，真点 [请教剑法] 学齐三式。
 * 拍提示、到场、成架、轻撩被接住、破架认招、问学和学成七张。
 */
public final class RivalClient implements ClientModInitializer {
    private enum Frame {
        ORIGIN, SETTLE, SITE, CALLED, REFUSED, AWAY, RETURN, ACCEPT, OPENED, GUARD_UP, LIFT_HIT, CHAIN_START,
        FIRST_BEAT, FINAL_BEAT, RECOGNIZED, ASKED, TEACH, TAUGHT, DONE
    }

    private static final int TIMEOUT = 12000;
    private static final int VIEW_SETTLE = 10;
    private static final int ORIGIN_SETTLE = 40;
    private static final int AWAY_TICKS = 60;
    private static final int AWAY_DISTANCE = 100;
    private static final int WATCH_DISTANCE = 3;
    private static final int GUARD_READY = 12;
    private static final int LIFT_HIT_TICK = 4;
    private static final int CHAIN_AFTER_HIT = 12;
    private static final int FIRST_BEAT_TICK = 6;
    private static final int BEAT_GAP = 7;
    /** 受击变红约 10 刻，等它退了再拍认招，台词气泡仍在。 */
    private static final int SPEECH_TICKS = 12;
    private static final double REACH = 1.5;
    /** 从南面入冢方向朝北看：镜头落在敞开的冢口，主碑在他身后。 */
    private static final float FACE_NORTH = 180;
    /** 俯角压到 55°，他整个人越过应约人的头顶露出来。 */
    private static final float VIEW_PITCH = 55;
    private final ChatChoices choices = new ChatChoices();
    private final List<RivalDuel.Ended> ended = new ArrayList<>();
    private Frame frame = Frame.ORIGIN;
    private CompletableFuture<Void> shot;
    private ServerPlayer player;
    private long due;
    private int ticks;
    private int steady;

    @Override
    public void onInitializeClient() {
        if ("rival".equals(System.getProperty("meridian.story.scene"))) {
            choices.listen();
            RivalDuel.ENDED.register(ended::add);
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        if (frame == Frame.DONE && shot == null) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("陈恪实拍等待超时 " + frame);
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
        UUID id = client.player.getUUID();
        if (frame.ordinal() >= Frame.CALLED.ordinal()) {
            // 每刻都压住俯角：服务端传送会把视角改回去，截下的是上一帧。
            client.player.setXRot(VIEW_PITCH);
        }
        if (frame.ordinal() < Frame.CALLED.ordinal() || frame == Frame.AWAY) {
            settle(client, server, id);
        } else if (frame.ordinal() < Frame.AWAY.ordinal()) {
            refuse(client, server);
        } else if (frame.ordinal() < Frame.GUARD_UP.ordinal()) {
            meet(client, server);
        } else if (frame.ordinal() < Frame.ASKED.ordinal()) {
            stepped(client, server);
        } else {
            lesson(client, server);
        }
    }

    private void settle(Minecraft client, MinecraftServer server, UUID id) {
        // 定出身、等冢址建好和遗物交付后请他来；学剑后先走远：下一次回冢才算。
        switch (frame) {
            case ORIGIN -> {
                // 先经正式出身入口定下守墓人，选牌界面收起后再布置。
                server.submit(() -> YunxiScrolls.origin(server.getPlayerList().getPlayer(id), GraveVisits.ORIGIN))
                        .join();
                frame = Frame.SETTLE;
            }
            case SETTLE -> {
                if (++steady >= ORIGIN_SETTLE) {
                    steady = 0;
                    client.gui.setScreen(null);
                    frame = Frame.SITE;
                }
            }
            case SITE -> {
                if (server.submit(() -> ready(server.getPlayerList().getPlayer(id))).join()) {
                    server.submit(() -> summon(server.getPlayerList().getPlayer(id))).join();
                    client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    frame = Frame.CALLED;
                }
            }
            default -> {
                if (++steady >= AWAY_TICKS) {
                    steady = 0;
                    server.submit(this::comeBack).join();
                    frame = Frame.RETURN;
                }
            }
        }
    }

    private void refuse(Minecraft client, MinecraftServer server) {
        // 他约剑后真点 [请前辈指教]；没学两式只得一句提示、邀请留着，拍下后这张邀请不再点。
        if (frame == Frame.CALLED) {
            if (server.submit(() -> rival() != null).join() && choices.offered() && choices.locate(client)) {
                choices.click(client);
                client.gui.setScreen(null);
                frame = Frame.REFUSED;
            }
        } else if (choices.offered() && ++steady >= VIEW_SETTLE) {
            steady = 0;
            server.submit(this::refused).join();
            choices.dismiss();
            shot = capture(client, "unlearned").thenRunAsync(() -> {
                prepare();
                frame = Frame.AWAY;
            }, server);
        }
    }

    private void meet(Minecraft client, MinecraftServer server) {
        // 他到场约剑后拍一张，再真点聊天里的第一枚按钮 [请前辈指教]，开场后站到他正面。
        switch (frame) {
            case RETURN -> {
                if (server.submit(() -> rival() != null).join() && choices.offered() && ++steady >= VIEW_SETTLE) {
                    steady = 0;
                    shot = capture(client, "arrive").thenRun(() -> frame = Frame.ACCEPT);
                }
            }
            case ACCEPT -> {
                if (choices.locate(client)) {
                    choices.click(client);
                    client.gui.setScreen(null);
                    frame = Frame.OPENED;
                }
            }
            default -> {
                if (server.submit(() -> RivalDuel.active(player.getUUID())).join()) {
                    server.submit(this::squareUp).join();
                    frame = Frame.GUARD_UP;
                }
            }
        }
    }

    private void stepped(Minecraft client, MinecraftServer server) {
        // 画面停在哪一拍由冻结的主世界决定；截屏和随后的服务端动作都完成后才看下一拍。
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
        CompletableFuture<Void> captured = switch (current) {
            case GUARD_UP -> capture(client, "guard");
            case LIFT_HIT -> capture(client, "lift");
            case RECOGNIZED -> capture(client, "recognized");
            default -> CompletableFuture.completedFuture(null);
        };
        shot = captured.thenRunAsync(() -> next(current), server);
    }

    private void lesson(Minecraft client, MinecraftServer server) {
        // 认招后他接着问学不学：拍下这一问，真点 [请教剑法]，学成的灰字出来后再拍一张。
        switch (frame) {
            case ASKED -> {
                if (choices.offered() && ++steady >= VIEW_SETTLE) {
                    steady = 0;
                    shot = capture(client, "asked").thenRun(() -> frame = Frame.TEACH);
                }
            }
            case TEACH -> {
                if (choices.locate(client)) {
                    choices.click(client);
                    client.gui.setScreen(null);
                    frame = Frame.TAUGHT;
                }
            }
            default -> {
                if (++steady >= VIEW_SETTLE) {
                    steady = 0;
                    server.submit(this::taught).join();
                    frame = Frame.DONE;
                    shot = capture(client, "taught");
                }
            }
        }
    }

    private static boolean ready(ServerPlayer actor) {
        var site = GraveSite.get(actor.level());
        if (site.anchor().isEmpty()) {
            return false;
        }
        var body = new StoryBodies().read(actor);
        return body.isPresent() && StoryProgress.read(body.orElseThrow()).getGrave().getDelivered()
                == GraveItems.GIFTS.size();
    }

    private void summon(ServerPlayer actor) {
        // 还没学剑：持铁剑经调试指令请他，站到冢前等他约剑。
        player = actor;
        var server = player.level().getServer();
        // 超平坦测试世界里有史莱姆游荡，碰到任何一方都算外人插手、比试散场；实拍改和平难度。
        server.setDifficulty(Difficulty.PEACEFUL, true /* forced */);
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "meridian debug rival summon " + player.getScoreboardName());
        comeBack();
    }

    private void refused() {
        if (rival() == null || RivalDuel.active(player.getUUID())) {
            throw new IllegalStateException("没学两式应约后陈恪不该开场或离开 " + ended);
        }
    }

    private void prepare() {
        // 升到真炁、学齐云溪六式，记下首次祭扫后走远：下一次回冢他按正式条件来。
        YunxiPracticeBody.practitioner(player);
        var service = TechniqueFabric.service();
        for (String form : List.of(THRUST, LIFT, PARRY, CLEAVE, RETURN, TWIN)) {
            service.learn(player.getUUID(), form);
        }
        var bodies = new StoryBodies();
        var body = bodies.read(player).orElseThrow();
        var progress = StoryProgress.read(body);
        var swept = progress.getGrave().toBuilder().setFirstSwept(true /* firstSwept */).build();
        if (!bodies.write(player, body, progress.toBuilder().setGrave(swept).build())) {
            throw new IllegalStateException("首次祭扫未写入");
        }
        teleport(Vec3.atBottomCenterOf(GraveSite.get(player.level()).guest().south(AWAY_DISTANCE)), FACE_NORTH);
    }

    private void comeBack() {
        teleport(Vec3.atBottomCenterOf(GraveSite.get(player.level()).guest().south(WATCH_DISTANCE)), FACE_NORTH);
    }

    private void squareUp() {
        // 冻结主世界后站到他正面一步半，起手与拍点都按刻推进。
        player.level().getServer().tickRateManager().setFrozen(true /* frozen */);
        var rival = rival();
        teleport(rival.position().add(0, 0, REACH), FACE_NORTH);
        step(GUARD_READY, Frame.GUARD_UP);
    }

    private void verify(Frame current) {
        var rival = rival();
        if (rival == null) {
            throw new IllegalStateException("陈恪已离场 " + current + " " + ended);
        }
        var guard = TechniqueFabric.service().activeGuard(rival.getUUID());
        switch (current) {
            case GUARD_UP, LIFT_HIT, FIRST_BEAT -> {
                if (guard.isEmpty()) {
                    throw new IllegalStateException("陈恪不在架上 " + current);
                }
            }
            case RECOGNIZED -> {
                boolean won = ended.stream().anyMatch(entry -> entry.challenger().equals(player.getUUID())
                        && entry.result() == RivalDuel.Result.WON);
                if (!won || guard.isPresent()) {
                    throw new IllegalStateException("末拍没有破架认招 " + ended + " 仍守=" + guard.isPresent());
                }
            }
            default -> { }
        }
    }

    private void next(Frame current) {
        switch (current) {
            case GUARD_UP -> {
                start(LIFT);
                step(LIFT_HIT_TICK, Frame.LIFT_HIT);
            }
            case LIFT_HIT -> step(CHAIN_AFTER_HIT, Frame.CHAIN_START);
            case CHAIN_START -> {
                start(TWIN);
                step(FIRST_BEAT_TICK, Frame.FIRST_BEAT);
            }
            case FIRST_BEAT -> step(BEAT_GAP, Frame.FINAL_BEAT);
            case FINAL_BEAT -> step(SPEECH_TICKS, Frame.RECOGNIZED);
            case RECOGNIZED -> {
                player.level().getServer().tickRateManager().setFrozen(false /* frozen */);
                frame = Frame.ASKED;
            }
            default -> { }
        }
    }

    private void taught() {
        var known = TechniqueFabric.service().known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .toList();
        if (!known.containsAll(RivalLessons.FORMS)) {
            throw new IllegalStateException("点了请教剑法没学齐寻隙三式 " + known);
        }
    }

    private Entity rival() {
        return player.level().getEntity(RivalVisit.guest(player.getUUID()));
    }

    private void teleport(Vec3 point, float yaw) {
        player.teleportTo(player.level(), point.x, point.y, point.z, Set.of(), yaw, VIEW_PITCH,
                true /* resetCamera */);
    }

    private void start(String form) {
        var service = TechniqueFabric.service();
        var body = service.observe(player.getUUID()).orElseThrow().body();
        var reference = TechniqueReference.newBuilder()
                .setId(form)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        CastOutcome outcome = service.cast(player.getUUID(), request);
        if (!outcome.hasBinding()) {
            throw new IllegalStateException("实拍起手失败 " + form + " " + outcome);
        }
    }

    private void step(int count, Frame next) {
        due = player.level().getGameTime() + count;
        if (!player.level().getServer().tickRateManager().stepGameIfPaused(count)) {
            throw new IllegalStateException("测试世界未冻结");
        }
        frame = next;
    }

    private static CompletableFuture<Void> capture(Minecraft client, String name) {
        var result = new CompletableFuture<Void>();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../rival-r1-" + name + ".png"));
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
