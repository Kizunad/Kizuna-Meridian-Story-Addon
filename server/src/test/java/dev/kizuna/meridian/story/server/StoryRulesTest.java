package dev.kizuna.meridian.story.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.Any;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryRulesTest {
    private static final String FIXTURE = "/data/meridian_story_test/meridian_story/line/register.json";
    private static final String ORIGIN = "meridian_humanoid:disgraced";
    private static final int DAY = 24_000;
    private static final int OPENED = 2;
    private static final double HEAT = 3;

    private static JsonObject data() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(StoryRulesTest.class.getResourceAsStream(
                FIXTURE)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    public void rejectsBrokenDestinationsFeudOutcomesAndClaims() throws Exception {
        var missing = data();
        missing.addProperty("first", "gone");
        assertThrows(IllegalArgumentException.class, () -> LineFormat.parse(missing));
        var raid = data();
        firstTrigger(raid, "visitors").getAsJsonObject("branches").remove("loss");
        assertThrows(IllegalArgumentException.class, () -> LineFormat.parse(raid));
        var handIn = data();
        firstTrigger(handIn, "witness").getAsJsonObject("params").addProperty("claim", "unknown");
        assertThrows(IllegalArgumentException.class, () -> LineFormat.parse(handIn));
        var trapped = data();
        var accepted = firstTrigger(trapped, "witness").getAsJsonObject("branches").getAsJsonObject("accepted");
        accepted.remove("finish");
        accepted.addProperty("stay", true /* stay */);
        assertThrows(IllegalArgumentException.class, () -> LineFormat.parse(trapped));
    }

    @Test
    public void checksEveryThresholdAndPreviousOutcome() throws Exception {
        var json = data();
        var conditions = json.getAsJsonObject("chapters").getAsJsonObject("witness").getAsJsonArray("when");
        conditions.forEach(value -> {
            var condition = value.getAsJsonObject();
            if (condition.get("type").getAsString().equals("opened_at_least")) {
                condition.getAsJsonObject("params").addProperty("count", OPENED);
            }
        });
        var chapter = LineFormat.parse(json).chapters().get("witness");
        var state = LineState.newBuilder()
                .setEnteredAt(1)
                .putOutcomes("visitors", "win")
                .build();
        var ready = new StoryLine.Facts(ORIGIN, DAY + 1, HEAT, 1, OPENED);
        assertTrue(chapter.ready(ready, state));
        assertFalse(chapter.ready(new StoryLine.Facts(ORIGIN, DAY, HEAT, 1, OPENED), state));
        assertFalse(chapter.ready(new StoryLine.Facts(ORIGIN, DAY + 1, HEAT - 1, 1, OPENED), state));
        assertFalse(chapter.ready(new StoryLine.Facts(ORIGIN, DAY + 1, HEAT, 0, OPENED), state));
        assertFalse(chapter.ready(new StoryLine.Facts(ORIGIN, DAY + 1, HEAT, 1, OPENED - 1), state));
        assertFalse(chapter.ready(ready, LineState.getDefaultInstance()));
    }

    @Test
    public void readyIsIdempotentAndUnknownDataIsPreserved() throws Exception {
        var empty = StoryProgress.read(BodySnapshot.getDefaultInstance());
        var line = LineFormat.parse(data());
        var known = new StoryLine.Facts(ORIGIN, 1, 0, 1, 0);
        var lines = Map.of("test:register", line);
        var waiting = StoryProgress.start(empty, lines, new StoryLine.Facts("", 0, 0, 1, 0));
        assertEquals(empty, waiting);
        var started = StoryProgress.start(waiting, lines, known);
        assertEquals(started, StoryProgress.start(started, lines, known));
        assertEquals(started, StoryProgress.start(started, Map.of(), known));
        var unknown = Progress.newBuilder().setSchema(Integer.MAX_VALUE).build();
        var body = BodySnapshot.newBuilder().putAddonProgress(StoryProgress.KEY, Any.pack(unknown)).build();
        assertThrows(IllegalArgumentException.class, () -> StoryProgress.read(body));
        assertEquals(Any.pack(unknown), body.getAddonProgressOrThrow(StoryProgress.KEY));
    }

    private static JsonObject firstTrigger(JsonObject line, String chapter) {
        return line.getAsJsonObject("chapters").getAsJsonObject(chapter).getAsJsonArray("on").get(0).getAsJsonObject();
    }
}
