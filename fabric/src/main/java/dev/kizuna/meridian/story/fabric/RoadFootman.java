package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeech;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.story.server.StoryLine.Place;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;

final class RoadFootman {
    private static final int CAMP_RADIUS = 12;
    private static final int SEARCH = 4;
    private static final String GUEST = "meridian_story.road_footman";

    private RoadFootman() { }

    private static final Map<UUID, Villager> guests = new HashMap<>();
    private static final Set<Villager> reloaded = new HashSet<>();

    static void register() {
        // 存档中的旧演员可能随远处区块稍后回来。加载回调结束后再让失约的演员退场。
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager guest && guest.entityTags().contains(GUEST)) {
                reloaded.add(guest);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var guest : reloaded) {
                if (!guest.isRemoved() && !guests.containsValue(guest)) {
                    guest.discard();
                }
            }
            reloaded.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            guests.clear();
            reloaded.clear();
        });
    }

    static Villager visitor(ServerPlayer player) {
        var guest = guests.get(player.getUUID());
        if (guest == null || guest.isRemoved()) {
            return null;
        }
        return guest;
    }

    static void leave(ServerPlayer player) {
        var guest = guests.remove(player.getUUID());
        if (guest != null && !guest.isRemoved()) {
            guest.discard();
        }
    }

    static void tick(ServerPlayer player, StoryStore.View view) {
        // 若上次换道后断线，按章节和原场号继续撤队。
        var state = view.progress().getLinesMap().get(LoneStarRoad.LINE);
        if (state == null) {
            return;
        }
        String chapter = state.getChapter();
        long diverted = view.progress().getRoad().getDivertedRequest();
        if (diverted > 0 && pursuit(view) && view.crisis().getPending().getRoadRequest() == diverted) {
            CrisisEncounters.leaveRoad(player, diverted);
            return;
        }
        var guest = visitor(player);
        BlockPos target = target(player, view, chapter);
        if (target == null || !state.getEnding().isEmpty()) {
            if (guest != null) {
                leave(player);
            }
            return;
        }
        if (guest != null && guest.blockPosition().closerThan(target, CAMP_RADIUS)) {
            return;
        }
        if (guest != null) {
            leave(player);
        }
        arrive(player, view, target);
    }

    private static void arrive(ServerPlayer player, StoryStore.View view, BlockPos target) {
        var level = player.level();
        var feet = BlockPos.findClosestMatch(target, SEARCH, SEARCH,
                pos -> level.isLoaded(pos) && level.getBlockState(pos.below()).isSolid()
                        && !level.getBlockState(pos.below()).is(BlockTags.CAMPFIRES)
                        && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir());
        feet.flatMap(pos -> HumanoidVisitors.arrive(level, pos, UUID.randomUUID(),
                Component.translatable("story.meridian_story.road.footman"))).ifPresent(actor -> {
            actor.addTag(GUEST);
            if (level.getBlockState(target).is(BlockTags.CAMPFIRES)) {
                // 借宿的脚夫坐在营火边烤火；追兵到时那一程在路上引路，站着。
                StoryPoses.seat(level, actor, target);
            }
            guests.put(player.getUUID(), actor);
            if (pursuit(view)) {
                var line = Component.translatable("story.meridian_story.road.guide");
                HumanoidSpeech.SPOKE.invoker().spoke(actor, player, line);
            }
        });
    }

    private static BlockPos target(ServerPlayer player, StoryStore.View view, String chapter) {
        var road = view.progress().getRoad();
        if (chapter.startsWith("night_") && pursuit(view)) {
            return player.blockPosition().relative(player.getDirection(), SEARCH);
        }
        if (!chapter.equals("walk") && road.hasCamp()) {
            var camp = BlockPos.of(road.getCamp());
            if (player.blockPosition().closerThan(camp, CAMP_RADIUS)
                    && player.level().getBlockState(camp).is(BlockTags.CAMPFIRES)) {
                return camp;
            }
        }
        return null;
    }

    private static boolean pursuit(StoryStore.View view) {
        var pending = view.crisis().getPending();
        return pending.getRoadRequest() > 0 && pending.getArrived() && pending.getParty().equals(LoneStarRoad.PURSUIT);
    }

    static boolean recipient(ServerPlayer player, StoryStore.View view, LivingEntity actor, Place place) {
        // 点击时再核对同一玩家、角色、营地或正在到场的那拨追兵。
        if (player.level().dimension() != Level.OVERWORLD || actor != visitor(player)) {
            return false;
        }
        if (place == Place.ROAD_GUIDE) {
            return pursuit(view);
        }
        var camp = target(player, view, "lodge");
        return camp != null && actor.blockPosition().closerThan(camp, CAMP_RADIUS);
    }
}
