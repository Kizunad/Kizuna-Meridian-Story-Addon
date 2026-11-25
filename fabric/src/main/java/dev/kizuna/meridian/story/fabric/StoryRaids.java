package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidReceipt;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import dev.kizuna.meridian.humanoid.server.CrisisRecords;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Claim;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.StoryLine;
import dev.kizuna.meridian.story.server.StoryLine.Trigger;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

final class StoryRaids {
    private static final String PREFIX = "meridian_story|";
    private final StoryStore store;

    StoryRaids(StoryStore store) {
        this.store = store;
    }

    static boolean hasWatch(ServerPlayer player) {
        return CrisisEncounters.current(player).stream().flatMap(record -> record.getWatchesMap().keySet().stream())
                .anyMatch(key -> key.startsWith(PREFIX));
    }

    void tick(ServerPlayer player, StoryStore.View view, Map<String, StoryLine> lines) {
        Set<String> watched = new HashSet<>();
        lines.forEach((id, line) -> {
            LineState state = view.progress().getLinesMap().get(id);
            if (state == null || !line.eligible(view.facts().origin()) || !state.getEnding().isEmpty()) {
                return;
            }
            var chapter = line.chapters().get(state.getChapter());
            if (chapter == null) {
                return;
            }
            for (Trigger trigger : chapter.on()) {
                if (trigger.raid() != null) {
                    var wait = new Wait(id, state, trigger);
                    observe(player, view, wait, chapter, watched);
                }
            }
        });
        for (String key : view.crisis().getWatchesMap().keySet()) {
            if (key.startsWith(PREFIX) && !watched.contains(key)) {
                CrisisEncounters.cancel(player, key);
            }
        }
        // 只在章节提交后清理旧回执；删除定义时保留，恢复同 ID 后可接着走。
        view.crisis().getReceiptsMap().forEach((key, receipt) -> {
            String[] parts = key.split("[|]", -1);
            if (parts.length == KEY_PARTS && key.startsWith(PREFIX) && lines.containsKey(parts[1])) {
                LineState state = view.progress().getLinesMap().get(parts[1]);
                var definition = lines.get(parts[1]);
                if (state != null && definition.chapters().containsKey(state.getChapter())
                        && (state.getLastEncounter() >= receipt.getEncounter()
                        || !Long.toString(state.getRevision()).equals(parts[3]))) {
                    CrisisEncounters.acknowledge(player, key, receipt);
                }
            }
        });
    }

    private static final int KEY_PARTS = 5;

    private record Wait(String line, LineState state, Trigger trigger) {
        String key() {
            return PREFIX + line + "|" + state.getChapter() + "|" + state.getRevision() + "|" + trigger.id();
        }
    }

    private void observe(ServerPlayer player, StoryStore.View view, Wait wait, StoryLine.Chapter chapter,
                         Set<String> watched) {
        String key = wait.key();
        RaidReceipt receipt = view.crisis().getReceiptsMap().get(key);
        var raid = wait.trigger().raid();
        if (receipt != null) {
            if (!receipt.getPlayer().equals(player.getStringUUID()) || !receipt.getParty().equals(raid.party())
                    || !receipt.getFeud().equals(raid.feud()) || !wait.trigger().branches().containsKey(
                            receipt.getOutcome())) {
                return;
            }
            consume(player, wait, receipt);
        } else if (chapter.ready(view.facts(), wait.state())) {
            watched.add(key);
            var request = RaidWatch.newBuilder()
                    .setFeud(raid.feud())
                    .setParty(raid.party())
                    .build();
            CrisisEncounters.watch(player, key, request);
        }
    }

    private void consume(ServerPlayer player, Wait wait, RaidReceipt receipt) {
        var branch = wait.trigger().branches().get(receipt.getOutcome());
        boolean committed = store.change(player, current -> advance(current, wait, receipt, branch));
        if (!committed) {
            return;
        }
        if (!branch.actions().letter().isEmpty() && branch.actions().delivery() == StoryLine.Delivery.LEADER_FALL) {
            var source = claim(wait, receipt);
            var letter = new StoryLetters(player.getStringUUID(), wait.line(), branch.actions().letter(), source);
            letter.drop(player);
        }
        CrisisEncounters.acknowledge(player, wait.key(), receipt);
    }

    private static Progress advance(StoryStore.View current, Wait wait, RaidReceipt receipt, StoryLine.Branch branch) {
        Progress progress = current.progress();
        LineState state = progress.getLinesMap().get(wait.line());
        if (!wait.state().equals(state) || state.getLastEncounter() >= receipt.getEncounter()
                || !receipt.equals(current.crisis().getReceiptsMap().get(wait.key()))) {
            return progress;
        }
        // 生人写宿主失败时内存中也有回执；等同一场收场在身体可重读，才取得章节的领取权。
        var saved = CrisisRecords.read(current.body());
        if (!receipt.equals(saved.getReceiptsMap().get(wait.key()))) {
            return progress;
        }
        var next = StoryProgress.advance(state, receipt.getOutcome(), branch, receipt.getOnlineTicks()).toBuilder()
                .setLastEncounter(receipt.getEncounter());
        if (!branch.actions().letter().isEmpty()) {
            var source = claim(wait, receipt);
            if (branch.actions().delivery() == StoryLine.Delivery.OWNER) {
                var gift = source.toBuilder().setAwaitingDelivery(true /* awaitingDelivery */).build();
                next.putClaims(branch.actions().letter(), gift);
            } else {
                next.putClaims(branch.actions().letter(), source);
            }
        }
        return progress.toBuilder().putLines(wait.line(), next.build()).build();
    }

    private static Claim claim(Wait wait, RaidReceipt receipt) {
        var fall = receipt.getFall();
        return Claim.newBuilder()
                .setChapter(wait.state().getChapter())
                .setEncounter(receipt.getEncounter())
                .setDimension(fall.getDimension())
                .setX(fall.getX())
                .setY(fall.getY())
                .setZ(fall.getZ())
                .build();
    }
}
