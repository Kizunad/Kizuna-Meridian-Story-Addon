package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeech;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.LineState;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.server.LineFormat;
import dev.kizuna.meridian.story.server.StoryLine;
import dev.kizuna.meridian.story.server.StoryLine.Kind;
import dev.kizuna.meridian.story.server.StoryLine.Trigger;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;

final class StoryDialogue {
    private static final int RANGE = 5;
    private static final int BELL_RADIUS = 5;
    private static final int LIFETIME = 600;
    private final Map<UUID, Conversation> conversations = new HashMap<>();
    private final StoryStore store;

    StoryDialogue(StoryStore store) {
        this.store = store;
    }

    private record Choice(String line, LineState state, StoryLine definition, Trigger trigger, Action action) { }
    private enum Action { BRANCH, REPLACE }
    private record Conversation(String nonce, UUID actor, ResourceKey<Level> dimension, long expires,
                                List<Choice> choices) { }

    void clear() {
        conversations.clear();
    }

    void leave(ServerPlayer player) {
        conversations.remove(player.getUUID());
    }

    private static boolean near(ServerPlayer player, LivingEntity actor) {
        return player.isAlive() && !player.isSpectator() && actor.isAlive() && actor.level() == player.level()
                && player.distanceToSqr(actor) <= RANGE * RANGE;
    }

    boolean interact(ServerPlayer player, LivingEntity actor, Map<String, StoryLine> lines, Kind event) {
        if (lines.isEmpty() || !near(player, actor)) {
            return false;
        }
        var view = store.read(player);
        if (view.isEmpty()) {
            return false;
        }
        var choices = choices(view.get(), player, actor, lines, event);
        if (choices.isEmpty()) {
            return false;
        }
        if (event == Kind.ATTACK) {
            if (apply(player, actor, choices.getFirst())) {
                conversations.remove(player.getUUID());
            }
        } else {
            StoryPoses.talk(actor);
            offer(player, actor, choices);
        }
        return true;
    }

    private static List<Choice> choices(StoryStore.View view, ServerPlayer player, LivingEntity actor,
                                        Map<String, StoryLine> lines, Kind event) {
        var choices = new ArrayList<Choice>();
        lines.forEach((id, line) -> {
            LineState state = view.progress().getLinesMap().get(id);
            if (state == null || !state.getEnding().isEmpty() || !line.eligible(view.facts().origin())) {
                return;
            }
            var chapter = line.chapters().get(state.getChapter());
            if (chapter == null || !chapter.ready(view.facts(), state)) {
                return;
            }
            for (var trigger : chapter.on()) {
                if (trigger.interaction() == null || (event == Kind.ATTACK) != (trigger.kind() == Kind.ATTACK)
                        || choices.size() >= LineFormat.MAX_OPTIONS) {
                    continue;
                }
                var choice = new Choice(id, state, line, trigger, Action.BRANCH);
                if (id.equals(LoneStarRoad.LINE) && trigger.id().equals("escort")
                        && FateRoad.grade(view.progress()) == FateRoad.Grade.DRAWN) {
                    continue;
                }
                if (atRecipient(view, actor, trigger, player)) {
                    addChoice(choices, player, choice);
                } else if (campReplacement(view, player, actor, choice)) {
                    choices.add(new Choice(id, state, line, trigger, Action.REPLACE));
                }
            }
        });
        return List.copyOf(choices);
    }

    private static void addChoice(List<Choice> choices, ServerPlayer player, Choice choice) {
        var interaction = choice.trigger().interaction();
        String reward = interaction.claim();
        if (reward.isEmpty()) {
            choices.add(choice);
            return;
        }
        var claim = choice.state().getClaimsMap().get(reward);
        if (claim == null || claim.getSpent()) {
            return;
        }
        var letter = new StoryLetters(player.getStringUUID(), choice.line(), reward, claim);
        if (letter.carried(player) >= interaction.count()) {
            choices.add(choice);
        } else if (choices.stream().noneMatch(other -> other.action() == Action.REPLACE
                && other.line().equals(choice.line()) && other.trigger().interaction().claim().equals(reward))) {
            var replacement = new Choice(choice.line(), choice.state(), choice.definition(), choice.trigger(),
                    Action.REPLACE);
            choices.add(replacement);
        }
    }

    private void offer(ServerPlayer player, LivingEntity actor, List<Choice> choices) {
        var conversation = invitation(player, actor, choices);
        var first = choices.getFirst();
        var chapter = first.definition().chapters().get(first.state().getChapter());
        StoryChat.narrate(player, chapter.prompt());
        if (first.line().equals(LoneStarRoad.LINE) && first.state().getChapter().equals("lodge")) {
            store.read(player).filter(view -> FateRoad.grade(view.progress()) == FateRoad.Grade.DRAWN)
                    .ifPresent(ignored -> StoryChat.narrate(player, FateScenes.PREFIX + "fear"));
        }
        if (!chapter.greeting().isEmpty()) {
            HumanoidSpeech.SPOKE.invoker().spoke(actor, player, Component.translatable(chapter.greeting()));
        }
        player.sendSystemMessage(buttons(conversation));
    }

    private Conversation invitation(ServerPlayer player, LivingEntity actor, List<Choice> choices) {
        var owner = player.getUUID();
        var actorId = actor.getUUID();
        var dimension = actor.level().dimension();
        long now = player.level().getGameTime();
        var existing = conversations.get(owner);
        if (existing != null && actorId.equals(existing.actor()) && dimension.equals(existing.dimension())
                && now < existing.expires() && choices.equals(existing.choices())) {
            return existing;
        }
        var fresh = new Conversation(UUID.randomUUID().toString(), actorId, dimension, now + LIFETIME, choices);
        conversations.put(owner, fresh);
        return fresh;
    }

    private static Component buttons(Conversation conversation) {
        var row = Component.empty();
        var choices = conversation.choices();
        String nonce = conversation.nonce();
        for (int index = 0; index < choices.size(); index++) {
            Choice choice = choices.get(index);
            var selection = new StoryChat.Selection(nonce, choice.state().getRevision(), index);
            var interaction = choice.trigger().interaction();
            String label = interaction.label();
            Component hint = Component.translatable("chat.meridian_story.free");
            ChatFormatting color = ChatFormatting.AQUA;
            if (choice.action() == Action.REPLACE) {
                label = "screen.meridian_story.replace";
            } else if (!interaction.claim().isEmpty()) {
                String cost = "chat.meridian_story.show";
                if (choice.trigger().kind() == Kind.HAND_IN) {
                    cost = "screen.meridian_story.cost";
                    color = ChatFormatting.GOLD;
                }
                hint = Component.translatable(cost, letterName(choice), interaction.count());
            }
            row.append(StoryChat.button(label, hint, color, selection)).append("  ");
        }
        var later = new StoryChat.Selection(nonce, choices.getFirst().state().getRevision(), StoryChat.LATER);
        var hint = Component.translatable("chat.meridian_story.defer");
        return row.append(StoryChat.button("screen.meridian_story.later", hint, ChatFormatting.GRAY, later));
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        UUID owner = player.getUUID();
        Conversation conversation = conversations.get(owner);
        if (conversation == null || !conversation.nonce().equals(selection.nonce())) {
            return Optional.empty();
        }
        conversations.remove(owner);
        if (player.level().getGameTime() >= conversation.expires()
                || !player.level().dimension().equals(conversation.dimension())
                || !(player.level().getEntity(conversation.actor()) instanceof LivingEntity actor)
                || !near(player, actor)) {
            return Optional.empty();
        }
        int option = selection.option();
        if (option == StoryChat.LATER) {
            var first = conversation.choices().getFirst();
            return store.read(player).filter(view -> selection.revision() == first.state().getRevision()
                            && valid(view, actor, player, first))
                    .map(view -> Component.translatable("chat.meridian_story.later"));
        }
        if (option < 0 || option >= conversation.choices().size()) {
            return Optional.empty();
        }
        Choice choice = conversation.choices().get(option);
        if (selection.revision() != choice.state().getRevision() || !apply(player, actor, choice)) {
            return Optional.empty();
        }
        return Optional.of(confirmation(choice));
    }

    private static Component letterName(Choice choice) {
        return Component.translatable(StoryLetters.nameKey(choice.line(), choice.trigger().interaction().claim()));
    }

    private static Component confirmation(Choice choice) {
        if (choice.action() == Action.REPLACE) {
            return Component.translatable("chat.meridian_story.replaced");
        }
        if (choice.trigger().kind() == Kind.HAND_IN) {
            String key = choice.trigger().branches().get("accepted").confirmation();
            if (!key.isEmpty()) {
                return Component.translatable(key);
            }
            return Component.translatable("chat.meridian_story.taken", letterName(choice));
        }
        if (!choice.trigger().interaction().claim().isEmpty()) {
            return Component.translatable("chat.meridian_story.keep", letterName(choice));
        }
        return Component.translatable("chat.meridian_story.agreed");
    }

    private boolean apply(ServerPlayer player, LivingEntity actor, Choice choice) {
        if (choice.trigger().kind() != Kind.ATTACK && player.hurtTime > 0) {
            return false;
        }
        if (choice.action() == Action.REPLACE) {
            var view = store.read(player);
            if (view.isEmpty() || !valid(view.get(), actor, player, choice)) {
                return false;
            }
            var letter = letter(player, choice);
            int missing = choice.trigger().interaction().count() - letter.carried(player);
            return missing > 0 && player.getInventory().add(letter.letter(missing));
        }
        var branch = choice.trigger().branches().get("accepted");
        boolean committed = store.change(player, view -> advance(view, player, actor, choice));
        if (committed) {
            if (choice.trigger().kind() == Kind.HAND_IN) {
                letter(player, choice).take(player, choice.trigger().interaction().count());
            }
            if (branch.actions().goodwill() > 0 && actor instanceof Villager villager) {
                villager.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, branch.actions().goodwill());
            }
            if (!branch.actions().speech().isEmpty()) {
                HumanoidSpeech.SPOKE.invoker().spoke(actor, player, Component.translatable(branch.actions().speech()));
            }
            if (choice.line().equals(LoneStarRoad.LINE) && choice.trigger().id().equals("escort")) {
                store.read(player).filter(view -> FateRoad.grade(view.progress()) == FateRoad.Grade.STILLED)
                        .ifPresent(ignored -> StoryChat.narrate(player, FateScenes.PREFIX + "together"));
            }
        }
        return committed;
    }

    private static boolean atRecipient(StoryStore.View view, LivingEntity actor, Trigger trigger,
                                       ServerPlayer player) {
        var recipient = trigger.interaction().recipient();
        if (!BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString().equals(recipient.id())) {
            return false;
        }
        String feud = recipient.beforeArrival();
        var pending = view.crisis().getPending();
        if (!feud.isEmpty() && pending.getArrived() && feud.equals(pending.getFeud())) {
            return false;
        }
        if (!feud.isEmpty() && view.crisis().getReceiptsMap().entrySet().stream().anyMatch(entry ->
                entry.getKey().startsWith("meridian_story|") && feud.equals(entry.getValue().getFeud()))) {
            return false;
        }
        if (recipient.place() == StoryLine.Place.ROAD_CAMP || recipient.place() == StoryLine.Place.ROAD_GUIDE) {
            return RoadFootman.recipient(player, view, actor, recipient.place());
        }
        if (recipient.place() == StoryLine.Place.GRAVE_VISITOR) {
            return GraveVisits.recipient(view, player, actor);
        }
        if (recipient.place() == StoryLine.Place.ANYWHERE) {
            return true;
        }
        var level = player.level();
        if (level.dimension() != Level.OVERWORLD) {
            return false;
        }
        return BlockPos.findClosestMatch(actor.blockPosition(), BELL_RADIUS, BELL_RADIUS,
                pos -> level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.BELL) && level.isVillage(pos))
                .isPresent();
    }

    private static boolean campReplacement(StoryStore.View view, ServerPlayer player, LivingEntity actor,
                                           Choice choice) {
        var interaction = choice.trigger().interaction();
        if (interaction.recipient().place() != StoryLine.Place.ROAD_GUIDE
                || !RoadFootman.recipient(player, view, actor, StoryLine.Place.ROAD_CAMP)) {
            return false;
        }
        var claim = choice.state().getClaimsMap().get(interaction.claim());
        return claim != null && !claim.getSpent() && letter(player, choice).carried(player) == 0;
    }

    private static boolean valid(StoryStore.View view, LivingEntity actor, ServerPlayer player, Choice choice) {
        var state = view.progress().getLinesMap().get(choice.line());
        if (choice.line().equals(LoneStarRoad.LINE) && choice.trigger().id().equals("escort")
                && FateRoad.grade(view.progress()) == FateRoad.Grade.DRAWN) {
            return false;
        }
        return choice.state().equals(state) && near(player, actor)
                && (atRecipient(view, actor, choice.trigger(), player)
                || choice.action() == Action.REPLACE && campReplacement(view, player, actor, choice))
                && choice.definition().eligible(view.facts().origin())
                && choice.definition().chapters().get(state.getChapter()).ready(view.facts(), state);
    }

    private static StoryLetters letter(ServerPlayer player, Choice choice) {
        String reward = choice.trigger().interaction().claim();
        return new StoryLetters(player.getStringUUID(), choice.line(), reward, choice.state().getClaimsOrThrow(reward));
    }

    private static Progress advance(StoryStore.View view, ServerPlayer player, LivingEntity actor, Choice choice) {
        if (!valid(view, actor, player, choice)) {
            return view.progress();
        }
        var trigger = choice.trigger();
        var branch = trigger.branches().get("accepted");
        var next = StoryProgress.advance(choice.state(), "accepted", branch, view.facts().ticks()).toBuilder();
        if (!trigger.interaction().claim().isEmpty()) {
            StoryLetters letter = letter(player, choice);
            if (letter.claim().getSpent() || letter.carried(player) < trigger.interaction().count()) {
                return view.progress();
            }
            if (trigger.kind() == Kind.TALK) {
                return completed(view, choice, next.build());
            }
            var spent = letter.claim().toBuilder().setSpent(true /* spent */).build();
            next.putClaims(letter.reward(), spent);
            if (!branch.actions().letter().isEmpty()) {
                var gift = letter.claim().toBuilder()
                        .setChapter(choice.state().getChapter())
                        .setAwaitingDelivery(true /* awaitingDelivery */)
                        .build();
                next.putClaims(branch.actions().letter(), gift);
            }
        }
        return completed(view, choice, next.build());
    }

    private static Progress completed(StoryStore.View view, Choice choice, LineState next) {
        var trigger = choice.trigger();
        var progress = view.progress().toBuilder().putLines(choice.line(), next);
        if (trigger.interaction().recipient().place() == StoryLine.Place.ROAD_GUIDE) {
            long ticket = view.crisis().getPending().getRoadRequest();
            var diverted = view.progress().getRoad().toBuilder().setDivertedRequest(ticket).build();
            progress.setRoad(diverted);
        }
        return progress.build();
    }
}
