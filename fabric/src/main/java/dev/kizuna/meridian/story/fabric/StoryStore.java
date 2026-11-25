package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.StoryLine.Facts;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;

final class StoryStore {
    private static final int WRITE_ATTEMPTS = 3;
    private final StoryBodies bodies = new StoryBodies();
    private final Map<UUID, String> origins = new HashMap<>();
    private final Set<UUID> broken = new HashSet<>();

    record View(BodySnapshot body, Progress progress, Facts facts, CrisisRecord crisis) { }

    void ready(ServerPlayer player, String origin) {
        origins.put(player.getUUID(), origin);
    }

    void leave(ServerPlayer player) {
        origins.remove(player.getUUID());
        broken.remove(player.getUUID());
    }

    void clear() {
        origins.clear();
        broken.clear();
    }

    Optional<View> read(ServerPlayer player) {
        UUID id = player.getUUID();
        String origin = origins.get(id);
        if (origin == null || broken.contains(id)) {
            return Optional.empty();
        }
        var crisis = CrisisEncounters.current(player);
        if (crisis.isEmpty()) {
            return Optional.empty();
        }
        try {
            return bodies.read(player).map(body -> view(body, crisis.get(), origin));
        } catch (IllegalArgumentException corrupt) {
            broken.add(id);
            LoggerFactory.getLogger("meridian_story").error("保留玩家 {} 损坏的剧情进度", id, corrupt);
            return Optional.empty();
        }
    }

    private static View view(BodySnapshot body, CrisisRecord crisis, String origin) {
        long opened = body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .count();
        var facts = new Facts(origin, crisis.getOnlineTicks(), crisis.getHeat(), body.getRealmValue(), opened);
        return new View(body, StoryProgress.read(body), facts, crisis);
    }

    boolean change(ServerPlayer player, Function<View, Progress> compute) {
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            var current = read(player);
            if (current.isEmpty()) {
                return false;
            }
            View view = current.get();
            Progress next = compute.apply(view);
            if (next.equals(view.progress())) {
                return false;
            }
            if (bodies.write(player, view.body(), next)) {
                return true;
            }
        }
        return false;
    }
}
