package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.OriginPhysique.ArmBurst;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;

final class FateRoad {
    static final String LINE = "meridian_story:lone_star_fate";
    static final String STRAY = "meridian_story:baleful_road";
    static final String AMBUSH = "meridian_story:escort_ambush";
    static final String HIRED = "meridian_story:hired_deserter";
    static final String MASTER = "meridian_story:temple_master";
    private static final int ESCORT_CHANCE = 65;

    enum Grade {
        UNSET(40, 8, 0.10, ArmBurst.NORMAL),
        DRAWN(50, 12, 0.15, ArmBurst.STRONG),
        STILLED(20, 8, 0.05, ArmBurst.WEAK);

        final int chance;
        final int seconds;
        final double speed;
        final ArmBurst power;

        Grade(int chance, int seconds, double speed, ArmBurst power) {
            this.chance = chance;
            this.seconds = seconds;
            this.speed = speed;
            this.power = power;
        }
    }

    private FateRoad() { }

    static LineState state(Progress progress) {
        return progress.getLinesOrDefault(LINE, LineState.getDefaultInstance());
    }

    static Grade grade(Progress progress) {
        return switch (state(progress).getEnding()) {
            case "drawn" -> Grade.DRAWN;
            case "stilled" -> Grade.STILLED;
            default -> Grade.UNSET;
        };
    }

    static int chance(StoryStore.View view) {
        if (state(view.progress()).getChapter().equals("walking")) {
            return ESCORT_CHANCE;
        }
        return grade(view.progress()).chance;
    }

    static String party(StoryStore.View view) {
        return switch (state(view.progress()).getChapter()) {
            case "walking" -> AMBUSH;
            case "paid_hand" -> HIRED;
            default -> STRAY;
        };
    }
}
