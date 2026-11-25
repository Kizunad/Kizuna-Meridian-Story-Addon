package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/** 跳过随机地形与征兆等待，保留正式头目死亡、逃局、在线时钟与身体写回。 */
public final class StoryRaidFixture {
    private static final double WITNESS_HEAT = 4;
    private StoryRaidFixture() { }

    public static void arrive(ServerPlayer player, Mob leader, String feud) {
        arrive(player, leader, feud, "meridian_humanoid:captors");
    }

    public static void arrive(ServerPlayer player, Mob leader, String feud, String party) {
        var pending = Pending.newBuilder()
                .setParty(party)
                .setFeud(feud)
                .setArrived(true /* arrived */)
                .setLeader(leader.getStringUUID())
                .addRaiders(leader.getStringUUID())
                .setLeaderSpecies("minecraft:pillager")
                .setLeaderTier("wandering_qi")
                .build();
        CrisisFabric.engine().update(player, record -> record.toBuilder().setPending(pending).build());
        CrisisRaiders.track(player.getUUID(), List.of(leader.getUUID()));
    }

    public static boolean watching(ServerPlayer player, String chapter, String feud) {
        return CrisisEncounters.current(player)
                .filter(record -> record.getFeudsList().stream()
                        .anyMatch(entry -> entry.getKey().equals(feud) && entry.getStarted()))
                .filter(record -> record.getWatchesMap().keySet().stream()
                        .anyMatch(key -> key.contains("|" + chapter + "|")))
                .isPresent();
    }

    public static void oldOrigin(ServerPlayer player) {
        CrisisFabric.engine().update(player, record -> record.toBuilder().clearWatches().clearFeuds().build());
    }

    public static void due(ServerPlayer player) {
        CrisisFabric.engine().update(player, record -> record.toBuilder()
                .setOnlineTicks(record.getFeuds(0).getDueAt())
                .build());
    }

    public static void nextOmen(ServerPlayer player) {
        CrisisFabric.engine().update(player, record -> {
            var pending = record.getPending();
            if (!record.hasPending() || pending.getNoteGiven() || pending.getOmensGiven() >= pending.getOmenAtCount()) {
                return record;
            }
            long when = pending.getOmenAt(pending.getOmensGiven());
            return record.toBuilder().setOnlineTicks(Math.max(when, record.getOnlineTicks())).build();
        });
    }

    public static void passDay(ServerPlayer player) {
        CrisisFabric.engine().update(player, record -> record.toBuilder()
                .setOnlineTicks(record.getOnlineTicks() + CrisisRules.DAY_TICKS)
                .setHeat(WITNESS_HEAT)
                .build());
    }
}
