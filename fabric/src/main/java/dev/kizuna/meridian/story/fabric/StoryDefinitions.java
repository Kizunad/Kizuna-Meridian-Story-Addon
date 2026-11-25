package dev.kizuna.meridian.story.fabric;

import com.google.gson.JsonParser;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.story.server.LineFormat;
import dev.kizuna.meridian.story.server.StoryLine;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

final class StoryDefinitions {
    private static final String DIRECTORY = "meridian_story/line";
    private static final String SUFFIX = ".json";

    private StoryDefinitions() { }

    static Map<String, StoryLine> load(MinecraftServer server) {
        Map<String, StoryLine> lines = new TreeMap<>();
        var files = server.getResourceManager().listResources(DIRECTORY, id -> id.getPath().endsWith(SUFFIX));
        for (var file : files.entrySet()) {
            Identifier path = file.getKey();
            String relative = path.getPath();
            String name = relative.substring(DIRECTORY.length() + 1, relative.length() - SUFFIX.length());
            try (var reader = file.getValue().openAsReader()) {
                StoryLine line = LineFormat.parse(JsonParser.parseReader(reader).getAsJsonObject());
                validateReferences(line);
                lines.put(path.withPath(name).toString(), line);
            } catch (IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("剧情文件 " + path, invalid);
            }
        }
        return Collections.unmodifiableMap(lines);
    }

    private static void validateReferences(StoryLine line) {
        if (!line.origin().isEmpty() && !Origins.all().containsKey(Identifier.parse(line.origin()))) {
            throw new IllegalArgumentException("未知出身 " + line.origin());
        }
        for (var chapter : line.chapters().values()) {
            for (var trigger : chapter.on()) {
                if (trigger.raid() != null) {
                    var raid = trigger.raid();
                    if (!CrisisEncounters.partyExists(raid.party()) || !raid.feud().isEmpty()
                            && !CrisisEncounters.feudUses(raid.feud(), raid.party())) {
                        throw new IllegalArgumentException("来犯引用无效 " + raid);
                    }
                } else {
                    String recipient = trigger.interaction().recipient().id();
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(recipient))) {
                        throw new IllegalArgumentException("未知角色 " + recipient);
                    }
                    boolean speaks = trigger.branches().values().stream()
                            .anyMatch(branch -> !branch.actions().speech().isEmpty());
                    if (speaks && HumanoidSpecies.fromEntityId(recipient).filter(HumanoidSpecies::brainManaged)
                            .isEmpty()) {
                        throw new IllegalArgumentException("气泡说话者须为生人 " + recipient);
                    }
                }
            }
        }
    }
}
