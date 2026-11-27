package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeech;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStrikeScope;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStrikeScope.Strike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.GuardState;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.DamageEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 冢前试剑的一场：应约人、陈恪、场界、开场时锁定的手和剑，以及他眼下的这次守式。
 * 状态只在内存；胜场只认云溪连招的破架回执，中止条件逐刻核对，结束时通知一次。
 */
final class RivalDuel {
    static final String GUARD = "meridian_story:rival_guard";
    private static final String PREFIX = "story.meridian_story.rival.";
    private static final int ARENA_RADIUS = 8;
    private static final int ARENA_ELEVATION = 4;
    private static final double TICKS_PER_SECOND = 20;
    /** 连珠两拍相隔 7 刻；首拍被接住后，余力要够付到末拍为止的持守。 */
    private static final int BEAT_GAP_TICKS = 7;
    /** 首拍后留给末拍的命中余量：普通末拍付一倍架得住，加倍的末拍付两倍跨过去。 */
    private static final double BREAK_MARGIN = 1.5;
    /** 架子掉了以后隔几刻再试着重架；破防禁守和余力不足时施放会被拒，下一轮再来。 */
    private static final int RAISE_INTERVAL = 2;
    private static final double DEGREES_PER_RADIAN = 180 / Math.PI;
    private static final float FACING_OFFSET = 90;

    /** 应约不成的缘由；前三种由他开口提示，各带他那句台词的键尾。 */
    enum Refusal {
        UNLEARNED("unlearned"), UNARMED("unarmed"), OUTSIDE("outside"), BUSY("");

        final String line;

        Refusal(String line) {
            this.line = line;
        }
    }

    enum Result { WON, ABORTED }

    /** 一场结束：应约人仍在线时带上本人，陈恪仍在世界里时带上他；两者都可能为空。 */
    record Ended(UUID challenger, Optional<ServerPlayer> player, UUID rival, Optional<Mob> guest, Result result) {
        Ended {
            Objects.requireNonNull(challenger);
            Objects.requireNonNull(player);
            Objects.requireNonNull(rival);
            Objects.requireNonNull(guest);
            Objects.requireNonNull(result);
        }
    }

    @FunctionalInterface
    interface Listener {
        void ended(Ended ended);
    }

    static final Event<Listener> ENDED = EventFactory.createArrayBacked(Listener.class, listeners -> ended -> {
        for (Listener listener : listeners) {
            listener.ended(ended);
        }
    });

    /** 场界：登记空坪所在的维度和中心，水平半径与上下高差内算在场里。 */
    record Arena(ResourceKey<Level> dimension, BlockPos center) {
        boolean contains(Entity entity) {
            BlockPos feet = entity.blockPosition();
            long dx = (long) feet.getX() - center.getX();
            long dz = (long) feet.getZ() - center.getZ();
            return entity.level().dimension().equals(dimension) && dx * dx + dz * dz <= ARENA_RADIUS * ARENA_RADIUS
                    && Math.abs(feet.getY() - center.getY()) <= ARENA_ELEVATION;
        }
    }

    private static final class Match {
        private final MinecraftServer server;
        private final UUID challenger;
        private final UUID rival;
        private final Arena arena;
        private final InteractionHand hand;
        private final ItemStack sword;
        private String guardCastId = "";
        private boolean announced;
        private boolean interrupted;
        private boolean won;
        private long nextRaise;
        private double lastAvailable = Double.NaN;
        private double drainTarget = Double.NaN;

        private Match(ServerPlayer challenger, UUID rival, Arena arena, InteractionHand hand) {
            this.server = challenger.level().getServer();
            this.challenger = challenger.getUUID();
            this.rival = rival;
            this.arena = arena;
            this.hand = hand;
            this.sword = challenger.getItemInHand(hand).copy();
        }
    }

    private static final Map<UUID, Match> matches = new HashMap<>();

    private RivalDuel() {
        // 只登记监听，场次以应约人为键。
    }

    static void register() {
        // 连招回执先于本观察方登记：首拍到这里时连招记录已经写好。
        YunxiCombo.COMPLETED.register(RivalDuel::completed);
        VanillaDamageFabric.registerDamageObserver(RivalDuel::observe);
        ServerTickEvents.END_SERVER_TICK.register(RivalDuel::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> matches.clear());
    }

    static Optional<Refusal> open(ServerPlayer challenger, Mob rival, Arena arena) {
        // 服务端复核本人会两式、手里有合用的剑、站在空坪内；都满足才锁定手和剑并立架。
        UUID id = challenger.getUUID();
        if (matches.containsKey(id) || engaged(rival.getUUID())) {
            return Optional.of(Refusal.BUSY);
        }
        if (!RivalVisit.ready(challenger)) {
            return Optional.of(Refusal.UNLEARNED);
        }
        Optional<InteractionHand> hand = swordHand(challenger);
        if (hand.isEmpty()) {
            return Optional.of(Refusal.UNARMED);
        }
        if (!arena.contains(challenger)) {
            return Optional.of(Refusal.OUTSIDE);
        }
        var match = new Match(challenger, rival.getUUID(), arena, hand.orElseThrow());
        matches.put(id, match);
        face(rival, challenger);
        raise(rival, match);
        return Optional.empty();
    }

    static boolean active(UUID challenger) {
        return matches.containsKey(challenger);
    }

    static boolean engaged(UUID rival) {
        return matches.values().stream().anyMatch(match -> match.rival.equals(rival));
    }

    /** 应约人离线、死亡时立即收场：已锁定的胜场照常结算，其余散场。 */
    static void abort(ServerPlayer challenger) {
        var match = matches.get(challenger.getUUID());
        if (match == null) {
            return;
        }
        Result result = Result.ABORTED;
        if (match.won) {
            result = Result.WON;
        }
        settle(match, challenger, rival(match), result);
    }

    static void face(Mob rival, Entity target) {
        // 关了 AI 的来客不会自己转头，按水平方向同时设身、头和朝向。
        double dx = target.getX() - rival.getX();
        double dz = target.getZ() - rival.getZ();
        float yaw = Mth.wrapDegrees((float) (Math.atan2(dz, dx) * DEGREES_PER_RADIAN) - FACING_OFFSET);
        rival.setYRot(yaw);
        rival.setYBodyRot(yaw);
        rival.setYHeadRot(yaw);
        rival.setXRot(0);
    }

    private static Optional<InteractionHand> swordHand(ServerPlayer player) {
        for (var hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(ItemTags.SWORDS)) {
                return Optional.of(hand);
            }
        }
        return Optional.empty();
    }

    private static void tick(MinecraftServer ignored) {
        for (var match : List.copyOf(matches.values())) {
            step(match);
        }
    }

    private static void step(Match match) {
        // 胜场在破架回执上已经锁定，这里只收尾；其余情形逐条核对中止条件，再转身、补架、压余力。
        ServerPlayer challenger = match.server.getPlayerList().getPlayer(match.challenger);
        Mob rival = rival(match);
        if (match.won) {
            settle(match, challenger, rival, Result.WON);
            return;
        }
        if (match.interrupted || rival == null || challenger == null || !challenger.isAlive()
                || challenger.isSpectator() || !match.arena.contains(challenger)) {
            settle(match, challenger, rival, Result.ABORTED);
            return;
        }
        face(rival, challenger);
        var service = TechniqueFabric.service();
        var guard = service.activeGuard(rival.getUUID());
        if (guard.isEmpty()) {
            if (rival.level().getGameTime() >= match.nextRaise) {
                raise(rival, match);
            }
        } else {
            match.guardCastId = guard.orElseThrow().castId();
            if (!match.announced) {
                match.announced = true;
                speak(rival, challenger, "ready");
            }
        }
        if (!Double.isNaN(match.drainTarget)) {
            drain(rival, match.drainTarget);
            match.drainTarget = Double.NaN;
        }
        match.lastAvailable = available(rival);
    }

    private static Mob rival(Match match) {
        var level = match.server.getLevel(match.arena.dimension());
        if (level != null && level.getEntity(match.rival) instanceof Mob mob && mob.isAlive()) {
            return mob;
        }
        return null;
    }

    private static void raise(Mob rival, Match match) {
        // 起守走正式施放；没受理就隔几刻再试，成架以活动守式为准。
        match.nextRaise = rival.level().getGameTime() + RAISE_INTERVAL;
        var body = TechniqueFabric.service().observe(rival.getUUID()).orElseThrow().body();
        var reference = TechniqueReference.newBuilder()
                .setId(GUARD)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        TechniqueFabric.service().cast(rival.getUUID(), request);
    }

    private static void observe(DamageEvent event) {
        // 旁人、环境或任何别的来源伤到双方之一就散场；应约人打陈恪照常比。
        UUID target = event.target().getUUID();
        Match match = matches.get(target);
        if (match == null) {
            match = matches.values().stream()
                    .filter(candidate -> candidate.rival.equals(target))
                    .findFirst()
                    .orElse(null);
        }
        if (match == null || match.won) {
            return;
        }
        boolean fair = event.attacker() != null && event.attacker().getUUID().equals(match.challenger)
                && target.equals(match.rival);
        if (!fair) {
            match.interrupted = true;
            return;
        }
        Optional<Strike> current = TechniqueStrikeScope.current(event.source());
        Optional<GuardState> guard = event.input().guarded();
        if (current.isPresent() && guard.isPresent() && guard.orElseThrow().castId().equals(match.guardCastId)) {
            measure(match, event, current.orElseThrow(), guard.orElseThrow());
        }
    }

    private static void measure(Match match, DamageEvent event, Strike strike, GuardState guard) {
        // 锁定的手和剑打出的连珠首拍、且已接上轻撩：按这一拍实付的命中费算出末拍前该剩的余力。
        var effect = strike.effect();
        boolean firstBeat = effect.getTechnique().getId().equals(YunxiCombo.TWIN)
                && !effect.getWeaponStrike().getStrike().getLastStrike();
        boolean locked = effect.getSelectedHand() == hand(match.hand)
                && YunxiCombo.sameSword(match.sword, strike.weapon());
        if (!firstBeat || !locked || !YunxiCombo.chained(match.challenger, effect.getCastId())
                || Double.isNaN(match.lastAvailable)) {
            return;
        }
        double hold = guard.guard().getStamina().getJoulesPerSecond() / TICKS_PER_SECOND;
        double after = HumanStamina.from(BodyProtobuf.fromMessage(event.result().body())).availableJoules();
        double fee = match.lastAvailable - after - hold;
        if (fee > 0) {
            match.drainTarget = hold * BEAT_GAP_TICKS + BREAK_MARGIN * fee;
        }
    }

    private static void completed(YunxiCombo.Outcome outcome) {
        // 只认本场：陈恪是守方，打出连招的正是应约人，破的是本场这次守式，末拍真把架打空，手里仍是开场那把剑。
        var match = matches.values().stream()
                .filter(candidate -> candidate.rival.equals(outcome.defender()))
                .findFirst()
                .orElse(null);
        if (match == null || match.won || match.interrupted || !outcome.broken()
                || !outcome.attacker().equals(match.challenger)
                || !outcome.guardCastId().equals(match.guardCastId)) {
            return;
        }
        ServerPlayer challenger = match.server.getPlayerList().getPlayer(match.challenger);
        if (challenger != null && YunxiCombo.sameSword(match.sword, challenger.getItemInHand(match.hand))) {
            match.won = true;
        }
    }

    private static void drain(Mob rival, double target) {
        // 余力只经功法宿主写疲劳往下压，与守方付命中费同一条路；已经低于目标就不动。
        var host = TechniqueFabric.service().statusHost(rival.getUUID()).orElseThrow();
        var body = host.inspect().body();
        var stamina = HumanStamina.from(body);
        double excess = stamina.availableJoules() - target;
        if (excess <= 0) {
            return;
        }
        var activity = BodyActivity.newBuilder().addAllTissueFatigue(stamina.cost(excess)).build();
        host.submit(new TechniqueHost.Submission(UUID.randomUUID().toString(), body.revision(), activity));
    }

    private static double available(Mob rival) {
        var body = TechniqueFabric.service().observe(rival.getUUID()).orElseThrow().body();
        return HumanStamina.from(body).availableJoules();
    }

    private static void settle(Match match, ServerPlayer challenger, Mob rival, Result result) {
        // 先摘掉场次再通知，监听方落档、送客时不会再看到这一场。
        matches.remove(match.challenger);
        if (rival != null && challenger != null) {
            if (result == Result.WON) {
                speak(rival, challenger, "recognized");
            } else {
                lower(rival);
                speak(rival, challenger, "interrupted");
            }
        }
        ENDED.invoker().ended(new Ended(match.challenger, Optional.ofNullable(challenger), match.rival,
                Optional.ofNullable(rival), result));
    }

    private static void lower(Mob rival) {
        // 散场时把还架着的剑收回；架已破或从未成架时什么也不做。
        var service = TechniqueFabric.service();
        var guard = service.activeGuard(rival.getUUID());
        if (guard.isEmpty()) {
            return;
        }
        var request = EndActiveRequest.newBuilder()
                .setBodyId(guard.orElseThrow().bodyId())
                .setCastId(guard.orElseThrow().castId())
                .build();
        service.endActive(rival.getUUID(), request);
    }

    private static void speak(Mob rival, ServerPlayer challenger, String line) {
        HumanoidSpeech.SPOKE.invoker().spoke(rival, challenger, Component.translatable(PREFIX + line));
    }

    private static Hand hand(InteractionHand hand) {
        if (hand == InteractionHand.OFF_HAND) {
            return Hand.OFF_HAND;
        }
        return Hand.MAIN_HAND;
    }
}
