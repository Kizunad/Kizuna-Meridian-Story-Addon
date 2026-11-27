package dev.kizuna.meridian.story.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kizuna.meridian.story.server.StoryLine.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 在装入整张表之前解析并核对章、分支和引用。 */
public final class LineFormat {
    private static final int VERSION = 1;
    public static final int MAX_OPTIONS = 8;
    private static final int MAX_STACK = 64;
    private static final long DAY = 24_000;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]{1,100}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> ENDINGS = Set.of("win", "escape", "loss");

    private LineFormat() { }

    public static StoryLine parse(JsonObject json) {
        require(integer(json, "version") == VERSION, "剧情格式版本");
        String origin = "";
        if (json.has("origin")) {
            origin = resource(json, "origin");
        }
        String first = name(json, "first");
        Map<String, Chapter> chapters = new LinkedHashMap<>();
        var claims = new HashSet<String>();
        var references = new ArrayList<String>();
        var outcomes = new ArrayList<Map.Entry<String, String>>();
        json.getAsJsonObject("chapters").entrySet().forEach(entry -> {
            require(NAME.matcher(entry.getKey()).matches(), "章 ID " + entry.getKey());
            chapters.put(entry.getKey(), chapter(entry.getValue().getAsJsonObject(), claims, references, outcomes));
        });
        require(chapters.containsKey(first), "首章不存在");
        require(claims.containsAll(references), "信物引用不存在");
        for (var outcome : outcomes) {
            Chapter previous = chapters.get(outcome.getKey());
            require(previous != null && previous.on().stream()
                    .flatMap(trigger -> trigger.branches().entrySet().stream())
                    .anyMatch(branch -> branch.getValue().recorded(branch.getKey()).equals(outcome.getValue())),
                    "前章分支不存在");
        }
        validatePaths(first, chapters);
        var start = StoryLine.Start.ORIGIN;
        if (json.has("start")) {
            start = StoryLine.Start.valueOf(text(json, "start").toUpperCase(java.util.Locale.ROOT));
        }
        return new StoryLine(origin, first, start, Map.copyOf(chapters));
    }

    private static Chapter chapter(JsonObject json, Set<String> claims, List<String> references,
                                   List<Map.Entry<String, String>> outcomes) {
        List<Condition> when = new ArrayList<>();
        for (var value : json.getAsJsonArray("when")) {
            when.add(condition(value.getAsJsonObject(), outcomes));
        }
        List<Trigger> on = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var value : json.getAsJsonArray("on")) {
            Trigger trigger = trigger(value.getAsJsonObject(), claims, references);
            require(ids.add(trigger.id()), "同章事件重名");
            on.add(trigger);
        }
        for (Trigger trigger : on) {
            if (trigger.interaction() != null) {
                String before = trigger.interaction().recipient().beforeArrival();
                require(before.isEmpty() || on.stream().anyMatch(other -> other.raid() != null
                        && before.equals(other.raid().feud())), "须引用本章实际等待的来犯");
            }
        }
        require(!on.isEmpty() && on.size() <= MAX_OPTIONS, "每章的事件数越界");
        return new Chapter(optionalName(json, "prompt"), optionalName(json, "notice"), optionalName(json, "greeting"),
                List.copyOf(when), List.copyOf(on));
    }

    private static Condition condition(JsonObject condition, List<Map.Entry<String, String>> outcomes) {
        JsonObject params = condition.getAsJsonObject("params");
        return switch (text(condition, "type")) {
            case "origin_ready" -> (facts, state) -> true;
            case "online_days" -> {
                long ticks = Math.multiplyExact(DAY, integer(params, "days"));
                String from = text(params, "from");
                require(Set.of("start", "chapter").contains(from), "在线日的起点");
                yield (facts, state) -> {
                    long since = state.getStartedAt();
                    if (from.equals("chapter")) {
                        since = state.getEnteredAt();
                    }
                    return facts.ticks() - since >= ticks;
                };
            }
            case "heat_at_least" -> {
                double minimum = params.get("value").getAsDouble();
                require(Double.isFinite(minimum) && minimum >= 0, "风声下限");
                yield (facts, state) -> facts.heat() >= minimum;
            }
            case "realm_at_least" -> {
                String realm = text(params, "realm").toUpperCase(java.util.Locale.ROOT);
                int minimum = dev.kizuna.meridian.protocol.pb.BodyMessages.Realm.valueOf(realm).getNumber();
                require(minimum > 0, "境界名");
                yield (facts, state) -> facts.realm() >= minimum;
            }
            case "opened_at_least" -> {
                int minimum = integer(params, "count");
                yield (facts, state) -> facts.opened() >= minimum;
            }
            case "previous_outcome" -> {
                String previous = name(params, "chapter");
                String result = name(params, "is");
                outcomes.add(Map.entry(previous, result));
                yield (facts, state) -> result.equals(state.getOutcomesMap().get(previous));
            }
            default -> throw new IllegalArgumentException("未知章节条件：" + condition);
        };
    }

    private static Trigger trigger(JsonObject json, Set<String> claims, List<String> references) {
        String type = name(json, "type");
        String id = type;
        if (json.has("id")) {
            id = name(json, "id");
        }
        var branches = new HashMap<String, Branch>();
        json.getAsJsonObject("branches").entrySet().forEach(entry -> branches.put(entry.getKey(),
                branch(entry.getValue().getAsJsonObject(), type, claims)));
        JsonObject params = json.getAsJsonObject("params");
        if (type.equals("scene")) {
            require(branches.keySet().equals(Set.of("accepted")), "现场回执须有 accepted 分支");
            return new Trigger(id, Kind.SCENE, null, null, Map.copyOf(branches));
        }
        if (type.equals("raid_settled")) {
            require(branches.keySet().equals(ENDINGS), "来犯要有赢逃输三个分支");
            String feud = text(params, "feud");
            require(feud.isEmpty() || feud.matches("[a-z0-9_.-]+:[a-z0-9_./-]+#[0-9]+"), "仇家键");
            require(branches.entrySet().stream().allMatch(entry -> entry.getValue().actions().speech().isEmpty()
                    && (entry.getValue().actions().letter().isEmpty() || entry.getKey().equals("win"))), "落点动作须有胜局");
            Source source = Source.FULL;
            if (params.has("source")) {
                source = Source.valueOf(text(params, "source").toUpperCase(java.util.Locale.ROOT));
            }
            var raid = new Raid(feud, resource(params, "party"), source);
            return new Trigger(id, Kind.RAID, raid, null, Map.copyOf(branches));
        }
        require(branches.keySet().equals(Set.of("accepted")), "交互要有 accepted 分支");
        require(branches.get("accepted").actions().goodwill() == 0
                || resource(params, "to").equals("minecraft:villager"), "闲话须由村民传出");
        var interaction = interaction(type, params, references);
        Kind kind = switch (type) {
            case "hand_in" -> Kind.HAND_IN;
            case "talk" -> Kind.TALK;
            case "attack" -> Kind.ATTACK;
            default -> throw new IllegalArgumentException("未知交互：" + type);
        };
        return new Trigger(id, kind, null, interaction, Map.copyOf(branches));
    }

    private static Interaction interaction(String type, JsonObject params, List<String> references) {
        Place place = Place.ANYWHERE;
        if (params.has("place")) {
            place = switch (text(params, "place")) {
                case "village_bell" -> Place.VILLAGE_BELL;
                case "grave_visitor" -> Place.GRAVE_VISITOR;
                case "road_camp" -> Place.ROAD_CAMP;
                case "road_guide" -> Place.ROAD_GUIDE;
                default -> throw new IllegalArgumentException("未知交谈地点");
            };
        }
        String beforeArrival = "";
        if (params.has("before_arrival")) {
            beforeArrival = text(params, "before_arrival");
            require(!beforeArrival.isEmpty(), "缺少要先于哪一拨来人");
        }
        var to = new Recipient(resource(params, "to"), place, beforeArrival);
        String label = "screen.meridian_story." + type;
        if (params.has("label")) {
            label = name(params, "label");
        }
        if (type.equals("hand_in") || type.equals("talk") && params.has("claim")) {
            String claim = name(params, "claim");
            references.add(claim);
            int count = integer(params, "count");
            require(count > 0 && count <= MAX_STACK, "交物数越界");
            String item = resource(params, "item");
            require(item.equals("minecraft:paper"), "信物必须为纸");
            return new Interaction(to, claim, item, count, label);
        }
        return new Interaction(to, "", "", 0, label);
    }

    private static Branch branch(JsonObject json, String type, Set<String> claims) {
        String next = optionalName(json, "next");
        String finish = optionalName(json, "finish");
        if (json.has("stay")) {
            require(json.getAsJsonPrimitive("stay").isBoolean(), "stay 必须为布尔值");
        }
        boolean stay = json.has("stay") && json.get("stay").getAsBoolean();
        long destinations = java.util.stream.Stream.of(next, finish).filter(value -> !value.isEmpty()).count();
        int expectedDestinations = stay ? 0 : 1;
        require(destinations == expectedDestinations, "分支去向须唯一");
        String confirmation = optionalName(json, "confirmation");
        require(confirmation.isEmpty() || type.equals("hand_in"), "交物回执须用于交付分支");
        return new Branch(next, finish, optionalName(json, "record_as"), actions(json, type, claims), confirmation);
    }

    private static Actions actions(JsonObject json, String type, Set<String> claims) {
        int goodwill = 0;
        Delivery delivery = Delivery.LEADER_FALL;
        String letter = "";
        String speech = "";
        if (json.has("do")) {
            for (JsonElement element : json.getAsJsonArray("do")) {
                JsonObject action = element.getAsJsonObject();
                JsonObject params = action.getAsJsonObject("params");
                switch (name(action, "type")) {
                    case "letter" -> {
                        String at = text(params, "at");
                        delivery = Delivery.valueOf(at.toUpperCase(java.util.Locale.ROOT));
                        require(letter.isEmpty() && (type.equals("raid_settled")
                                || type.equals("hand_in") && delivery == Delivery.OWNER), "信物落点");
                        letter = name(params, "id");
                        claims.add(letter);
                    }
                    case "vouch" -> {
                        require(type.equals("hand_in") && goodwill == 0, "闲话须随交证");
                        goodwill = integer(params, "amount");
                        require(goodwill > 0, "闲话数量须为正");
                    }
                    case "speech" -> {
                        require(speech.isEmpty() && text(params, "speaker").equals("recipient"), "说话者");
                        speech = name(params, "key");
                    }
                    default -> throw new IllegalArgumentException("未知动作：" + action);
                }
            }
        }
        return new Actions(letter, delivery, speech, goodwill);
    }

    private static void validatePaths(String first, Map<String, Chapter> chapters) {
        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> canFinish = new HashSet<>();
        chapters.forEach((id, chapter) -> {
            Set<String> targets = new HashSet<>();
            for (var trigger : chapter.on()) {
                for (var branch : trigger.branches().values()) {
                    if (!branch.next().isEmpty()) {
                        require(chapters.containsKey(branch.next()), "缺少目标章 " + branch.next());
                        targets.add(branch.next());
                    }
                    if (!branch.finish().isEmpty()) {
                        canFinish.add(id);
                    }
                }
            }
            edges.put(id, targets);
        });
        Set<String> reachable = new HashSet<>(Set.of(first));
        int before;
        do {
            before = reachable.size() + canFinish.size();
            edges.forEach((id, targets) -> {
                if (reachable.contains(id)) {
                    reachable.addAll(targets);
                }
                if (targets.stream().anyMatch(canFinish::contains)) {
                    canFinish.add(id);
                }
            });
        } while (before != reachable.size() + canFinish.size());
        require(reachable.equals(chapters.keySet()) && canFinish.equals(reachable), "有章节不可达或无法结局");
    }

    private static String optionalName(JsonObject json, String field) {
        if (json.has(field)) {
            return name(json, field);
        }
        return "";
    }

    private static String resource(JsonObject json, String field) {
        String value = text(json, field);
        require(RESOURCE.matcher(value).matches(), "资源 ID " + value);
        return value;
    }

    private static String name(JsonObject json, String field) {
        String value = text(json, field);
        require(NAME.matcher(value).matches(), "标识符 " + value);
        return value;
    }

    private static String text(JsonObject json, String field) {
        var value = json.getAsJsonPrimitive(field);
        require(value != null && value.isString(), "缺少文本 " + field);
        return value.getAsString();
    }

    private static int integer(JsonObject json, String field) {
        var number = json.getAsJsonPrimitive(field);
        require(number != null && number.isNumber(), "缺少数值 " + field);
        int value = number.getAsBigDecimal().intValueExact();
        require(value >= 0, "不能为负：" + field);
        return value;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
