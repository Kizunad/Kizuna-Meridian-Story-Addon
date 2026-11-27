package dev.kizuna.meridian.story.server;

import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import java.util.List;
import java.util.Map;

/** 路径给出线 ID。章名、交互名和结局名都是可跨重载的身份。 */
public record StoryLine(String origin, String first, Start start, Map<String, Chapter> chapters) {
    public record Chapter(String prompt, String notice, String greeting, List<Condition> when, List<Trigger> on) {
        public boolean ready(Facts facts, LineState state) {
            return when.stream().allMatch(condition -> condition.test(facts, state));
        }
    }

    public enum Start { ORIGIN, GRAVE_VISIT, ROAD }

    public record Facts(String origin, long ticks, double heat, int realm, long opened) { }

    @FunctionalInterface
    public interface Condition {
        boolean test(Facts facts, LineState state);
    }

    public enum Kind { RAID, HAND_IN, TALK, ATTACK, SCENE }

    public enum Source { FULL, ROAD }

    public record Raid(String feud, String party, Source source) { }

    public enum Place { ANYWHERE, VILLAGE_BELL, GRAVE_VISITOR, ROAD_CAMP, ROAD_GUIDE }

    public record Recipient(String id, Place place, String beforeArrival) { }

    public record Interaction(Recipient recipient, String claim, String item, int count, String label) { }

    public record Trigger(String id, Kind kind, Raid raid, Interaction interaction, Map<String, Branch> branches) { }

    public enum Delivery { OWNER, LEADER_FALL, SEARCH }

    public record Actions(String letter, Delivery delivery, String speech, int goodwill) { }

    public record Branch(String next, String finish, String outcome, Actions actions, String confirmation) {
        public Branch(String next, String finish, String outcome, Actions actions) {
            this(next, finish, outcome, actions, "");
        }

        public String recorded(String event) {
            if (outcome.isEmpty()) {
                return event;
            }
            return outcome;
        }
    }

    public boolean eligible(String chosen) {
        return origin.isEmpty() || origin.equals(chosen);
    }
}
