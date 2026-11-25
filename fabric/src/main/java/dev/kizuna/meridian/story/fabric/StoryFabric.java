package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.story.server.StoryLine;
import dev.kizuna.meridian.story.server.StoryLine.Kind;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.LoggerFactory;

public final class StoryFabric implements ModInitializer {
    private static final int INTERVAL = 20;
    private Map<String, StoryLine> lines = Map.of();
    private final Map<UUID, Map<String, String>> shown = new HashMap<>();
    private StoryStore store;
    private StoryRaids raids;
    private StoryDialogue dialogue;

    @Override
    public void onInitialize() {
        GrittedCounter.initialize();
        store = new StoryStore();
        raids = new StoryRaids(store);
        dialogue = new StoryDialogue(store);
        StoryChat.register(dialogue);
        registerResources();
        Origins.CHOSEN.register((player, origin) -> ready(player, origin.id().toString()));
        Origins.READY.register((player, origin) ->
                ready(player, origin.map(value -> value.id().toString()).orElse("")));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            shown.remove(handler.player.getUUID());
            store.leave(handler.player);
            dialogue.leave(handler.player);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % INTERVAL == 0) {
                server.getPlayerList().getPlayers().forEach(this::tick);
            }
        });
        registerInteractions();
    }

    private void registerResources() {
        var event = ServerLifecycleEvents.END_DATA_PACK_RELOAD;
        var after = Identifier.parse("meridian_story:after_crisis");
        event.addPhaseOrdering(Identifier.parse("meridian_humanoid:after_origins"), after);
        event.register(after, (server, resources, success) -> {
            if (success) {
                try {
                    load(server);
                } catch (RuntimeException invalid) {
                    LoggerFactory.getLogger("meridian_story").error("保留重载前的剧情定义", invalid);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(this::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            lines = Map.of();
            dialogue.clear();
            store.clear();
            shown.clear();
        });
    }

    private void load(MinecraftServer server) {
        lines = StoryDefinitions.load(server);
        dialogue.clear();
        server.getPlayerList().getPlayers().forEach(this::tick);
    }

    private void registerInteractions() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer owner && owner.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND
                    && entity instanceof LivingEntity actor && dialogue.interact(owner, actor, lines, Kind.TALK)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer owner && entity instanceof LivingEntity actor) {
                dialogue.interact(owner, actor, lines, Kind.ATTACK);
            }
            return InteractionResult.PASS;
        });
    }

    private void showChapters(ServerPlayer player, StoryStore.View view) {
        view.progress().getLinesMap().forEach((id, state) -> {
            var line = lines.get(id);
            if (line == null || !line.eligible(view.facts().origin()) || !state.getEnding().isEmpty()) {
                return;
            }
            var chapter = line.chapters().get(state.getChapter());
            if (chapter == null || chapter.notice().isEmpty()) {
                return;
            }
            var history = shown.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
            if (!state.getChapter().equals(history.put(id, state.getChapter()))) {
                StoryChat.narrate(player, chapter.notice());
            }
        });
    }

    private void ready(ServerPlayer player, String origin) {
        store.ready(player, origin);
        if (!lines.isEmpty()) {
            tick(player);
        }
    }

    private void tick(ServerPlayer player) {
        if (lines.isEmpty() && !StoryRaids.hasWatch(player)) {
            return;
        }
        var initial = store.read(player);
        if (initial.isEmpty()) {
            return;
        }
        var view = initial.get();
        StoryLetters.deliverPending(player, store, view.progress());
        showChapters(player, view);
        if (lines.entrySet().stream().anyMatch(entry -> entry.getValue().eligible(view.facts().origin())
                && !view.progress().containsLines(entry.getKey()))) {
            store.change(player, current -> StoryProgress.start(current.progress(), lines, current.facts()));
            store.read(player).ifPresent(current -> raids.tick(player, current, lines));
        } else {
            raids.tick(player, view, lines);
        }
    }
}
