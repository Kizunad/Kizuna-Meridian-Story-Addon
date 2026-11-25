package dev.kizuna.meridian.story.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Claim;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.StoryLine.Branch;
import java.util.Map;

public final class StoryProgress {
    public static final String KEY = "meridian_story:progress";
    private static final int SCHEMA = 1;
    private static final Progress EMPTY = Progress.newBuilder().setSchema(SCHEMA).build();

    private StoryProgress() { }

    public static Progress read(BodySnapshot body) {
        Any stored = body.getAddonProgressMap().get(KEY);
        if (stored == null) {
            return EMPTY;
        }
        try {
            Progress progress = stored.unpack(Progress.class);
            if (progress.getSchema() != SCHEMA) {
                throw new IllegalArgumentException("未知剧情进度版本 " + progress.getSchema());
            }
            progress.getLinesMap().forEach(StoryProgress::validate);
            return progress;
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("剧情进度无法解码", invalid);
        }
    }

    private static void validate(String id, LineState state) {
        if (id.isBlank() || state.getChapter().isBlank() || state.getRevision() < 1 || state.getStartedAt() < 0
                || state.getEnteredAt() < state.getStartedAt() || state.getLastEncounter() < 0) {
            throw new IllegalArgumentException("剧情进度损坏：" + id);
        }
        for (Claim claim : state.getClaimsMap().values()) {
            if (claim.getEncounter() <= 0 || claim.getEncounter() > state.getLastEncounter()
                    || claim.getChapter().isEmpty() || claim.getDimension().isEmpty()
                    || !Double.isFinite(claim.getX()) || !Double.isFinite(claim.getY())
                    || !Double.isFinite(claim.getZ())) {
                throw new IllegalArgumentException("信物凭据损坏：" + id);
            }
        }
    }

    public static Progress start(Progress progress, Map<String, StoryLine> lines, StoryLine.Facts facts) {
        var updated = progress.toBuilder();
        lines.forEach((id, line) -> {
            if (!progress.containsLines(id) && line.eligible(facts.origin())) {
                var state = LineState.newBuilder()
                        .setStartedAt(facts.ticks())
                        .setEnteredAt(facts.ticks())
                        .setChapter(line.first())
                        .setRevision(1)
                        .build();
                updated.putLines(id, state);
            }
        });
        return updated.build();
    }

    public static LineState advance(LineState before, String outcome, Branch branch, long ticks) {
        var state = before.toBuilder()
                .setRevision(Math.incrementExact(before.getRevision()))
                .putOutcomes(before.getChapter(), branch.recorded(outcome));
        if (!branch.next().isEmpty()) {
            state.setChapter(branch.next());
            state.setEnteredAt(ticks);
        }
        if (!branch.finish().isEmpty()) {
            state.setEnding(branch.finish());
        }
        return state.build();
    }
}
