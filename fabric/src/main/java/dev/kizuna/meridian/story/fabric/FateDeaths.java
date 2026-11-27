package dev.kizuna.meridian.story.fabric;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** 本人离线或身体暂不可写时，同行者的真实死亡回执随世界保存。 */
final class FateDeaths extends SavedData {
    private final Map<String, String> deaths;
    private static final Codec<FateDeaths> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(FateDeaths::new, data -> data.deaths);
    private static final SavedDataType<FateDeaths> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_story:escort_deaths"), () -> new FateDeaths(Map.of()), CODEC, null);

    private FateDeaths(Map<String, String> saved) {
        deaths = new HashMap<>(saved);
    }

    static FateDeaths get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    void died(String owner, UUID escort) {
        deaths.put(owner, escort.toString());
        setDirty();
    }

    String receipt(String owner) {
        return deaths.getOrDefault(owner, "");
    }

    void acknowledge(String owner, String escort) {
        if (deaths.remove(owner, escort)) {
            setDirty();
        }
    }
}
