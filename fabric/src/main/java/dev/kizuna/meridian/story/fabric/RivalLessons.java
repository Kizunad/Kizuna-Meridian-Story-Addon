package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RivalRefusal;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerPlayer;

/**
 * 陈恪口授《寻隙剑谱》：按卷内顺序逐式交给功法库验权，境界或经脉不够的式被拒、留作待传。
 * 授过的式按位记在守墓记录里，往后不再重授；有式被拒就记下当时的境界和开脉数，两者有变化他才再来。
 */
final class RivalLessons {
    static final List<String> FORMS = List.of(
            "meridian_story:side_step", "meridian_story:held_thrust", "meridian_story:back_lift");
    private static final int ALL = (1 << FORMS.size()) - 1;

    /** 这次口授新记为已授的式和被功法库拒绝的式，各按卷内顺序占位。 */
    record Lesson(int covered, int refused) {
        boolean finished() {
            return refused == 0;
        }
    }

    private RivalLessons() {
    }

    /** 胜后还有没授的式，并且上次被拒以后境界或开脉有了变化。 */
    static boolean owed(StoryStore.View view) {
        var grave = view.progress().getGrave();
        if (!grave.getRivalWon() || grave.getRivalTaught() == ALL) {
            return false;
        }
        if (!grave.hasRivalRefusal()) {
            return true;
        }
        return !grave.getRivalRefusal().equals(refusal(view));
    }

    /** 逐式提交还没授的式：本人已会的直接记为已授，功法库以境界或经脉拒绝的留到下次。 */
    static Lesson teach(ServerPlayer player, StoryStore.View view) {
        var service = TechniqueFabric.service();
        Set<String> known = service.known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .collect(Collectors.toSet());
        int taught = view.progress().getGrave().getRivalTaught();
        int covered = 0;
        int refused = 0;
        for (int index = 0; index < FORMS.size(); index++) {
            int bit = 1 << index;
            String form = FORMS.get(index);
            if ((taught & bit) != 0) {
                continue;
            }
            if (!known.contains(form)) {
                try {
                    service.learn(player.getUUID(), form);
                } catch (TechniqueService.LearningDeniedException denied) {
                    refused |= bit;
                    continue;
                }
            }
            covered |= bit;
        }
        return new Lesson(covered, refused);
    }

    /** 把这次授到的式并进记录；授齐了清掉被拒情形，还有被拒的就记下此刻的境界和开脉数。 */
    static Progress record(StoryStore.View view, Lesson lesson) {
        var progress = view.progress();
        var grave = progress.getGrave();
        var next = grave.toBuilder().setRivalTaught(grave.getRivalTaught() | lesson.covered());
        if (lesson.finished()) {
            next.clearRivalRefusal();
        } else {
            next.setRivalRefusal(refusal(view));
        }
        var updated = next.build();
        return progress.toBuilder().setGrave(updated).build();
    }

    private static RivalRefusal refusal(StoryStore.View view) {
        var facts = view.facts();
        return RivalRefusal.newBuilder()
                .setRealm(facts.realm())
                .setOpened(facts.opened())
                .build();
    }
}
