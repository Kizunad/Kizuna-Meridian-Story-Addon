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
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
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
    private GraveVisits graves;
    private RivalVisit rival;
    private LoneStarRoad road;
    private FallenCultivator roadside;
    private StoryDialogue dialogue;
    private FateScenes fate;
    private MidnightMarket market;

    @Override
    public void onInitialize() {
        GraveSite.registerCommand();
        GraveItems.initialize();
        GraveMood.initialize();
        GrittedCounter.initialize();
        YunxiCombo.register();
        RivalSwordsman.register();
        RivalDuel.register();
        store = new StoryStore();
        rival = new RivalVisit(store);
        rival.registerCommand();
        graves = new GraveVisits(store, rival);
        road = new LoneStarRoad(store);
        road.registerCamp();
        RoadFootman.register();
        roadside = new FallenCultivator(store);
        roadside.register();
        raids = new StoryRaids(store);
        dialogue = new StoryDialogue(store);
        var forge = new GraveForge(store);
        fate = new FateScenes(store);
        fate.register();
        market = new MidnightMarket(store);
        market.register();
        StoryChat.market = market;
        StoryChat.register(dialogue, forge, roadside, fate, rival);
        registerResources();
        Origins.CHOSEN.register((player, origin) -> ready(player, origin.id().toString()));
        Origins.READY.register((player, origin) ->
                ready(player, origin.map(value -> value.id().toString()).orElse("")));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                rival.leave(player);
                graves.leave(player);
                road.leave(player);
                roadside.leave(player);
            }
        });
        var departure = Identifier.parse("meridian_story:before_encounter_departure");
        ServerPlayerEvents.LEAVE.addPhaseOrdering(departure, Event.DEFAULT_PHASE);
        ServerPlayerEvents.LEAVE.register(departure, player -> {
            shown.remove(player.getUUID());
            market.leave(player);
            forge.leave(player);
            fate.leave(player);
            road.leave(player);
            roadside.leave(player);
            rival.leave(player);
            graves.leave(player);
            store.leave(player);
            dialogue.leave(player);
        });
        var roadPhase = Identifier.parse("meridian_story:after_encounters");
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, roadPhase);
        ServerTickEvents.END_SERVER_TICK.register(roadPhase, server -> {
            server.getPlayerList().getPlayers().forEach(graves::trackPosition);
            if (server.getTickCount() % INTERVAL == 0) {
                TempleMarkets.get(server.overworld()).tick(server.overworld());
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
        fate.definitions(lines.get(FateRoad.LINE));
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
        graves.tick(player, view);
        road.tick(player, view, lines.get(LoneStarRoad.LINE));
        roadside.tick(player, view);
        market.tick(player, view);
        fate.tick(player, view);
        StoryLetters.deliverPending(player, store, view.progress());
        showChapters(player, view);
        if (lines.entrySet().stream().anyMatch(entry -> entry.getValue().eligible(view.facts().origin())
                && entry.getValue().start() == StoryLine.Start.ORIGIN
                && !view.progress().containsLines(entry.getKey()))) {
            store.change(player, current -> StoryProgress.start(current.progress(), lines, current.facts()));
        }
        store.read(player).ifPresent(current -> raids.tick(player, current, lines));
    }
}
