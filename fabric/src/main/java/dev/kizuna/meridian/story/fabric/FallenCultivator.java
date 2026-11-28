package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 通用事件 0：倒在路边、怀里只有一卷书的修士。救护、夺卷与走开共用同一名伤者和同一份原卷。 */
final class FallenCultivator {
    static final String PURSUIT = "meridian_story:fallen_pursuit";
    static final String SCHOOL = "meridian_story:fallen_school";
    static final String GUEST = "meridian_story.fallen";
    static final TagKey<Item> SCROLLS = TagKey.create(Registries.ITEM,
            Identifier.parse("meridian_story:roadside_scrolls"));
    static final long REST = 3 * CrisisRules.DAY_TICKS;
    static final int RESCUE = 0;
    static final int LEAVE = 1;
    static final String OPEN = "open";
    static final String WREST = "wrest";
    static final String ESCORT = "escort";
    /** 分几下轻伤，避免一下打毁某个部位；零血部位普通治疗救不回来。 */
    static final float WOUND = 3.0F;
    static final int CUTS = 3;
    /** 入世满一秒、身体认领之后再落伤。 */
    static final int SETTLE = 20;
    /** 原版一级瞬间治疗的回复量。 */
    static final float HEAL = 4.0F;
    private static final String KEY = "story.meridian_story.fallen.";
    private static final String IDENTITY = "meridian_story:roadside_scroll";
    private static final int TILE = 16;
    private static final int MEMORY = 256;
    private static final int REACH = 5;
    private static final int AWAY = 24;
    private static final int TOGETHER = 16;
    private static final int WITNESS = 16;
    private static final int FOLLOW = 3;
    private static final int AHEAD = 4;
    private static final int HALF_LENGTH = 3;
    private static final int HALF_WIDTH = 2;
    private static final int HEADROOM = 3;
    private static final int DAY = 24_000;
    private static final int DUSK = 13_000;
    private static final int DEPART = 1_200;
    private static final long SCROLL_SALT = 0xD1B54A32D192ED03L;
    private static final String OWNER = "meridian_story.fallen.owner.";
    private final Map<UUID, LoneStarRoad.Step> previous = new HashMap<>();
    private final Map<UUID, String> sessions = new HashMap<>();
    private final Map<UUID, Vec3> deaths = new HashMap<>();
    private final Set<Mob> loaded = new HashSet<>();
    private final StoryStore store;
    private final VillageEncounters encounters;
    final MountainEncounters mountain;

    FallenCultivator(StoryStore store) {
        this.store = store;
        encounters = new VillageEncounters(store);
        mountain = new MountainEncounters(store);
    }

    void register() {
        encounters.register();
        mountain.register();
        // 只有本人对伤者的主动攻击锁定夺卷；伤者的普通右键不再打开流浪商人的货单。
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer owner && entity instanceof Mob actor && guest(actor)) {
                attacked(owner, actor);
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(entity instanceof Mob actor) || !guest(actor)) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer owner) {
                talk(owner, actor);
            }
            return InteractionResult.SUCCESS;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Mob actor && guest(actor)) {
                deaths.put(actor.getUUID(), actor.position());
            }
        });
        // 收场时伤者所在区块可能没加载；他随区块回来时按主人的存档核对，案已收就放他走。
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob actor && guest(actor)) {
                loaded.add(actor);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            loaded.forEach(actor -> settle(server, actor));
            loaded.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            loaded.clear();
            previous.clear();
            sessions.clear();
            deaths.clear();
        });
    }

    private void settle(MinecraftServer server, Mob actor) {
        // 主人不在线时留到下回载入再核对，免得误放仍在进行的一场。
        if (actor.isRemoved() || !guest(actor)) {
            return;
        }
        var owner = actor.entityTags().stream().filter(tag -> tag.startsWith(OWNER)).findFirst()
                .map(tag -> UUID.fromString(tag.substring(OWNER.length())));
        if (owner.isEmpty()) {
            release(actor);
            return;
        }
        var player = server.getPlayerList().getPlayer(owner.get());
        if (player == null) {
            return;
        }
        store.read(player).map(view -> view.progress().getRoadside())
                .filter(roadside -> !roadside.hasCurrent()
                        || !roadside.getCurrent().getActor().equals(actor.getStringUUID()))
                .ifPresent(closed -> release(actor));
    }

    void leave(ServerPlayer player) {
        previous.remove(player.getUUID());
        sessions.remove(player.getUUID());
    }

    private static boolean guest(Mob actor) {
        return actor.entityTags().contains(GUEST);
    }

    void tick(ServerPlayer player, StoryStore.View view) {
        mountain.bequest.promise.tick(player, view);
        var roadside = view.progress().getRoadside();
        if (roadside.hasCurrent()) {
            previous.remove(player.getUUID());
            var current = roadside.getCurrent();
            if (mountain.tick(player, view, current)) {
                return;
            }
            if (!current.getKind().isEmpty()) {
                encounters.tick(player, current);
                return;
            }
            follow(player, view, current);
            return;
        }
        if (roadside.getInquiryAt() > 0 || roadside.getInquiryTicket() > 0) {
            previous.remove(player.getUUID());
            if (roadside.getInquiryParty().isEmpty()) {
                inquire(player, view, roadside);
            } else {
                encounters.followup(player, view);
            }
            return;
        }
        if (!mountain.revisit(player, view)) {
            encounter(player, view, roadside);
        }
    }

    // ---- 开场 ----

    private void encounter(ServerPlayer player, StoryStore.View view, Roadside roadside) {
        // 主世界白天至黄昏、实际徒步进入未抽过的合格路肩时才抽一次签。
        var level = player.level();
        long now = view.facts().ticks();
        long settled = Math.max(view.crisis().getRoadSettledAt(), view.crisis().getSettledAt());
        if (level.dimension() != Level.OVERWORLD || !player.isAlive() || player.isSpectator() || player.isCreative()
                || !player.onGround() || player.isPassenger() || player.isInWater() || player.getAbilities().flying
                || view.crisis().hasPending() || now - settled < LoneStarRoad.REST
                || Math.floorMod(level.getOverworldClockTime(), DAY) >= LastRequest.NIGHT) {
            previous.remove(player.getUUID());
            return;
        }
        var step = LoneStarRoad.Step.sample(player);
        if (!step.followed(previous.put(player.getUUID(), step))) {
            return;
        }
        if (!mountain.bequest.approach(player, view) && Math.floorMod(level.getOverworldClockTime(), DAY) < DUSK) {
            drawAt(player, view, roadside);
        }
    }

    private void drawAt(ServerPlayer player, StoryStore.View view, Roadside roadside) {
        var level = player.level();
        long now = view.facts().ticks();
        var feet = player.blockPosition();
        long tile = tile(feet);
        if (roadside.getTilesList().contains(tile) || mountain.encounter(player, view, tile)) {
            return;
        }
        var drawn = remember(roadside, tile);
        long seed = seed(player);
        var direction = player.getDirection();
        var ahead = feet.relative(direction, AHEAD);
        boolean village = level.isLoaded(ahead) && level.isVillage(ahead);
        if (village && now >= roadside.getRestByKindOrDefault(FranticCultivator.KIND, 0L)
                && FranticCultivator.gate(level, ahead)) {
            if (!RoadsideRules.opens(seed, tile) || !encounters.frantic.open(player, drawn, ahead)) {
                save(player, roadside, drawn.build());
            }
            return;
        }
        var site = site(level, feet, direction);
        if (site.isEmpty()) {
            return;
        }
        if (!village && RoadsideRules.opensStall(seed, tile)
                && now >= roadside.getRestByKindOrDefault(ManualSeller.KIND, 0L)
                && encounters.seller.open(player, drawn, site.get())) {
            return;
        }
        if (now >= roadside.getRestUntil()) {
            if (RoadsideRules.opens(seed, tile)) {
                openFallen(player, roadside, drawn, site.get(), seed);
            } else {
                save(player, roadside, drawn.build());
            }
        }
    }

    private void openFallen(ServerPlayer player, Roadside roadside, Roadside.Builder drawn, BlockPos site, long seed) {
        long number = roadside.getCases() + 1;
        var current = RoadsideCase.newBuilder()
                .setNumber(number)
                .setActor(UUID.randomUUID().toString())
                .setSite(site.asLong())
                .setRoll(RoadsideRules.roll(seed, number))
                .setStage(OPEN)
                .setScroll(BuiltInRegistries.ITEM.getKey(scroll(seed, number)).toString())
                .setHeld(true)
                .setArrivedAt(player.level().getGameTime())
                .build();
        var actor = arrive(player, current);
        if (actor.isEmpty()) {
            return;
        }
        var opened = drawn.setCases(number).setCurrent(current).clearLastEnding().build();
        if (!save(player, roadside, opened)) {
            actor.get().discard();
            return;
        }
    }

    static long tile(BlockPos feet) {
        return BlockPos.asLong(Math.floorDiv(feet.getX(), TILE), 0, Math.floorDiv(feet.getZ(), TILE));
    }

    static Roadside.Builder remember(Roadside roadside, long tile) {
        var next = roadside.toBuilder();
        if (roadside.getTilesCount() >= MEMORY) {
            var history = roadside.getTilesList();
            next.clearTiles().addAllTiles(history.subList(1, history.size()));
        }
        return next.addTiles(tile);
    }

    static Optional<BlockPos> site(ServerLevel level, BlockPos feet, Direction facing) {
        // 前方 7×5 的草地或土路上方留出三格可穿行的空间（矮草、花不碍事），伤者躺在正中。
        var side = facing.getClockWise();
        var center = feet.relative(facing, AHEAD);
        for (int along = -HALF_LENGTH; along <= HALF_LENGTH; along++) {
            for (int across = -HALF_WIDTH; across <= HALF_WIDTH; across++) {
                var column = center.relative(facing, along).relative(side, across);
                if (!level.isLoaded(column)) {
                    return Optional.empty();
                }
                var ground = level.getBlockState(column.below());
                if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT_PATH) && !ground.is(BlockTags.DIRT)) {
                    return Optional.empty();
                }
                for (int up = 0; up < HEADROOM; up++) {
                    var pos = column.above(up);
                    var state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.of(center);
    }

    static Item scroll(long seed, long number) {
        // 只取登记为允许剧情授予的通用卷，按本场场次号固定挑一卷；伤者怀里只有这一卷的一份。
        var pool = new ArrayList<Item>();
        BuiltInRegistries.ITEM.getTagOrEmpty(SCROLLS).forEach(holder -> pool.add(holder.value()));
        if (pool.isEmpty()) {
            throw new IllegalStateException("路边伤者的卷池 " + SCROLLS.location() + " 为空");
        }
        return pool.get(RandomSource.create(seed ^ number * SCROLL_SALT).nextInt(pool.size()));
    }

    private static Optional<Mob> arrive(ServerPlayer player, RoadsideCase current) {
        var name = Component.translatable(KEY + "actor");
        return HumanoidVisitors.arrive(player.level(), BlockPos.of(current.getSite()),
                UUID.fromString(current.getActor()), name, EntityTypes.WANDERING_TRADER).map(actor -> {
                    actor.addTag(GUEST);
                    actor.addTag(OWNER + player.getStringUUID());
                    return actor;
                });
    }

    private void wound(ServerPlayer player, RoadsideCase current, Mob actor) {
        // 落下可治疗的伤才开场；伤不上或没剩可治的伤势就悄悄散场，不起冷却。
        if (player.level().getGameTime() - current.getArrivedAt() < SETTLE) {
            return;
        }
        var level = player.level();
        for (int cut = 0; cut < CUTS && actor.isAlive(); cut++) {
            actor.invulnerableTime = 0;
            actor.hurtServer(level, level.damageSources().generic(), WOUND);
        }
        if (!injured(actor)) {
            if (store.change(player, view -> view.progress().toBuilder().setRoadside(view.progress().getRoadside()
                    .toBuilder().clearCurrent()).build())) {
                actor.discard();
            }
            return;
        }
        var wounded = current.toBuilder().setWounded(true).build();
        if (update(player, current, wounded)) {
            offer(player, actor, wounded);
        }
    }

    private void offer(ServerPlayer player, Mob actor, RoadsideCase current) {
        StoryChat.narrate(player, KEY + "opening");
        String nonce = sessions.computeIfAbsent(player.getUUID(), ignored -> UUID.randomUUID().toString());
        var row = Component.empty();
        if (injured(actor) && potion(player) >= 0) {
            var rescue = new StoryChat.Selection(nonce, current.getNumber(), RESCUE);
            row.append(StoryChat.button(KEY + "rescue", Component.translatable(KEY + "rescue.cost"),
                    ChatFormatting.GOLD, rescue)).append("  ");
        }
        row.append(Component.literal("[").append(Component.translatable(KEY + "wrest")).append("]")
                .withStyle(ChatFormatting.RED)).append("  ");
        var walk = new StoryChat.Selection(nonce, current.getNumber(), LEAVE);
        row.append(StoryChat.button(KEY + "leave", Component.translatable(KEY + "leave.cost"),
                ChatFormatting.GRAY, walk));
        player.sendSystemMessage(row);
    }

    private void talk(ServerPlayer player, Mob actor) {
        // 潜行交谈重取未过期的同一组选项。
        var current = current(player, actor);
        if (player.isShiftKeyDown() && current.filter(found -> found.getStage().equals(OPEN) && found.getWounded())
                .isPresent()
                && near(player, actor)) {
            offer(player, actor, current.get());
        }
    }

    // ---- 选择 ----

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        var eventReply = encounters.choose(player, selection).or(() -> mountain.choose(player, selection));
        if (eventReply.isPresent()) {
            return eventReply;
        }
        if (!selection.nonce().equals(sessions.get(player.getUUID()))) {
            return Optional.empty();
        }
        var view = store.read(player);
        var current = view.map(found -> found.progress().getRoadside())
                .filter(Roadside::hasCurrent).map(Roadside::getCurrent)
                .filter(found -> found.getNumber() == selection.revision() && found.getStage().equals(OPEN));
        var actor = current.flatMap(found -> actor(player, found)).filter(found -> near(player, found));
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        return switch (selection.option()) {
            case RESCUE -> rescue(player, view.get(), current.get(), actor.get());
            case LEAVE -> close(player, current.get(), "left", UnaryOperator.identity(), actor)
                    ? Optional.of(line(KEY + "leave.done")) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<Component> rescue(ServerPlayer player, StoryStore.View view, RoadsideCase current, Mob actor) {
        // 先验药瓶与伤势；宿主确实回了血才扣这瓶并交还空瓶，未提交的治疗保留药瓶。
        int slot = potion(player);
        if (slot < 0 || !injured(actor)) {
            return Optional.empty();
        }
        float before = actor.getHealth();
        actor.heal(HEAL);
        if (!(actor.getHealth() > before) || !actor.isAlive()) {
            return Optional.empty();
        }
        player.getInventory().getItem(slot).shrink(1);
        give(player, new ItemStack(Items.GLASS_BOTTLE));
        if (RoadsideRules.good(current.getRoll())) {
            // 先登记卷已离手，再把那一份交到本人手上；已经离手的不再补造。
            var handed = current.toBuilder().setHeld(false).build();
            if (!current.getHeld() || !update(player, current, handed)) {
                return Optional.empty();
            }
            give(player, original(player, current));
            close(player, handed, "rescued", UnaryOperator.identity(), Optional.of(actor));
            return Optional.of(line(KEY + "rescue.good"));
        }
        var escort = current.toBuilder().setStage(ESCORT).setHealed(true).setTicket(ticket(view)).build();
        update(player, current, escort);
        CrisisEncounters.requestRoad(player, PURSUIT, escort.getTicket());
        return Optional.of(Component.empty());
    }

    private void attacked(ServerPlayer player, Mob actor) {
        // 首个本人主动攻击先锁夺卷并登记在场证人，之后每次出手都再试着把卷夺进行囊。
        var found = current(player, actor);
        if (found.isEmpty() || !found.get().getWounded() || found.get().getStage().equals(ESCORT)) {
            return;
        }
        var current = found.get();
        if (current.getStage().equals(OPEN)) {
            var locked = current.toBuilder().setStage(WREST).setWitnessed(witnessed(player, actor)).build();
            if (!update(player, current, locked)) {
                return;
            }
            sessions.remove(player.getUUID());
            current = locked;
        }
        // 行囊有空位才夺得下来；先登记卷已离手，再把那一份放进行囊。
        if (current.getHeld() && player.getInventory().getFreeSlot() >= 0
                && update(player, current, current.toBuilder().setHeld(false).build())) {
            player.getInventory().add(original(player, current));
        }
    }

    private static boolean witnessed(ServerPlayer player, Mob actor) {
        var around = actor.getBoundingBox().inflate(WITNESS);
        return !player.level().getEntitiesOfClass(LivingEntity.class, around, other -> other != actor
                && other != player && other.isAlive() && (other instanceof Player || other instanceof AbstractVillager))
                .isEmpty();
    }

    // ---- 进行中 ----

    private void follow(ServerPlayer player, StoryStore.View view, RoadsideCase before) {
        var actor = actor(player, before);
        var death = deaths.get(UUID.fromString(before.getActor()));
        boolean dead = death != null || actor.isEmpty() && player.level().isLoaded(BlockPos.of(before.getSite()));
        var current = before;
        if (death != null && before.getHeld()) {
            // 伤者死在怀里还揣着卷时，那一份落在他倒下的地方；先登记再掉落，掉过就不再掉。
            current = before.toBuilder().setHeld(false).build();
            if (!update(player, before, current)) {
                return;
            }
            var dropped = new ItemEntity(player.level(), death.x, death.y, death.z, original(player, before));
            player.level().addFreshEntity(dropped);
        }
        double distance = Math.sqrt(player.blockPosition().distSqr(BlockPos.of(current.getSite())));
        switch (current.getStage()) {
            case OPEN -> {
                if (!dead && !current.getWounded()) {
                    actor.ifPresent(found -> wound(player, before, found));
                } else if (dead) {
                    end(player, current, "gone", KEY + "gone", UnaryOperator.identity(), actor);
                } else if (distance > AWAY || player.level().dimension() != Level.OVERWORLD) {
                    close(player, current, "passed", UnaryOperator.identity(), actor);
                }
            }
            case WREST -> wrest(player, view, current, actor, distance);
            case ESCORT -> escort(player, view, current, actor, dead);
            default -> close(player, current, "passed", UnaryOperator.identity(), actor);
        }
    }

    private void wrest(ServerPlayer player, StoryStore.View view, RoadsideCase current, Optional<Mob> actor,
                       double distance) {
        // 拿到原卷才揭签；有人看见时必走门人追查。未夺到就离开不转移卷，也不宣告得手。
        if (carries(player, current)) {
            if (!current.getWitnessed() && RoadsideRules.good(current.getRoll())) {
                end(player, current, "wrested", KEY + "wrest.good", UnaryOperator.identity(), actor);
                return;
            }
            long due = view.facts().ticks() + RoadsideRules.inquiryDelay(current.getRoll(), CrisisRules.DAY_TICKS);
            end(player, current, "marked", KEY + "wrest.bad", next -> next.setInquiryAt(due), actor);
        } else if (distance > AWAY || player.level().dimension() != Level.OVERWORLD) {
            end(player, current, "missed", KEY + "wrest.missed", UnaryOperator.identity(), actor);
        }
    }

    private void escort(ServerPlayer player, StoryStore.View view, RoadsideCase current, Optional<Mob> actor,
                        boolean dead) {
        // 追兵到场才喝止，随后伤者跟着本人；那拨人收场时仍在身边才算护送成功。
        if (dead) {
            end(player, current, "died", KEY + "escort.died", UnaryOperator.identity(), actor);
            return;
        }
        var pending = view.crisis().getPending();
        boolean ours = view.crisis().hasPending() && pending.getRoadRequest() == current.getTicket();
        if (!current.getShouted()) {
            if (ours && pending.getArrived()) {
                if (update(player, current, current.toBuilder().setShouted(true).build())) {
                    actor.ifPresent(found -> found.setNoAi(false /* noAi */));
                    StoryChat.narrate(player, KEY + "rescue.bad");
                }
            } else if (!ours && Math.sqrt(player.blockPosition().distSqr(BlockPos.of(current.getSite()))) > AWAY) {
                end(player, current, "scattered", KEY + "escort.lost", UnaryOperator.identity(), actor);
            } else if (!ours) {
                CrisisEncounters.requestRoad(player, PURSUIT, current.getTicket());
            }
            return;
        }
        if (ours) {
            actor.filter(found -> found.distanceTo(player) > FOLLOW)
                    .ifPresent(found -> found.getNavigation().moveTo(player, 1.0));
            return;
        }
        if (actor.filter(found -> found.distanceTo(player) <= TOGETHER).isPresent()) {
            end(player, current, "escorted", KEY + "escort.safe", UnaryOperator.identity(), actor);
        } else {
            end(player, current, "scattered", KEY + "escort.lost", UnaryOperator.identity(), actor);
        }
    }

    private void inquire(ServerPlayer player, StoryStore.View view, Roadside roadside) {
        // 门人追查与出身仇家分开记，只用本案自己的轻场签号；到场时亮出朱印，之后照常交战或离场。
        var crisis = view.crisis();
        long ticket = roadside.getInquiryTicket();
        if (ticket > 0) {
            boolean ours = crisis.hasPending() && crisis.getPending().getRoadRequest() == ticket;
            if (ours && crisis.getPending().getArrived() && !roadside.getInquiryShown()) {
                if (save(player, roadside, roadside.toBuilder().setInquiryShown(true).build())) {
                    StoryChat.narrate(player, KEY + "school");
                }
            } else if (!ours) {
                save(player, roadside, roadside.toBuilder().clearInquiryTicket().clearInquiryShown()
                        .setLastEnding("inquired").build());
            }
            return;
        }
        if (view.facts().ticks() < roadside.getInquiryAt()) {
            return;
        }
        long next = ticket(view);
        if (CrisisEncounters.requestRoad(player, SCHOOL, next)) {
            save(player, roadside, roadside.toBuilder().clearInquiryAt().setInquiryTicket(next).build());
        }
    }

    // ---- 实物 ----

    static CustomData identity(ServerPlayer player, RoadsideCase current) {
        var mark = new CompoundTag();
        mark.putString("owner", player.getStringUUID());
        mark.putLong("case", current.getNumber());
        var tag = new CompoundTag();
        tag.put(IDENTITY, mark);
        return CustomData.of(tag);
    }

    static boolean original(ServerPlayer player, RoadsideCase current, ItemStack stack) {
        return !stack.isEmpty() && identity(player, current).equals(stack.get(DataComponents.CUSTOM_DATA));
    }

    static boolean carries(ServerPlayer player, RoadsideCase current) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (original(player, current, inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    static ItemStack original(ServerPlayer player, RoadsideCase current) {
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(current.getScroll())));
        stack.set(DataComponents.CUSTOM_DATA, identity(player, current));
        return stack;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false /* throwRandomly */);
        }
    }

    static int potion(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(Items.POTION)
                    && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.HEALING)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean injured(LivingEntity actor) {
        return actor.isAlive() && actor.getHealth() < actor.getMaxHealth();
    }

    // ---- 存取 ----

    private Optional<RoadsideCase> current(ServerPlayer player, Mob actor) {
        return store.read(player).map(view -> view.progress().getRoadside())
                .filter(Roadside::hasCurrent).map(Roadside::getCurrent)
                .filter(found -> found.getActor().equals(actor.getStringUUID()));
    }

    static Optional<Mob> actor(ServerPlayer player, RoadsideCase current) {
        if (player.level().getEntity(UUID.fromString(current.getActor())) instanceof Mob actor && actor.isAlive()
                && guest(actor)) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }

    private static boolean near(ServerPlayer player, LivingEntity actor) {
        return player.isAlive() && !player.isSpectator() && actor.isAlive() && actor.level() == player.level()
                && player.distanceToSqr(actor) <= REACH * REACH;
    }

    private static Component line(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.ITALIC);
    }

    static long seed(ServerPlayer player) {
        var id = player.getUUID();
        return player.level().getSeed() ^ id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }

    private static long ticket(StoryStore.View view) {
        // 与孤星行路共用生人的轻场签号，取两边已用过的最大号再加一。
        return Math.max(view.crisis().getRoadRequest(), view.progress().getRoad().getDraws()) + 1;
    }

    private boolean close(ServerPlayer player, RoadsideCase current, String ending,
                          UnaryOperator<Roadside.Builder> also, Optional<Mob> actor) {
        // 收场即开始本人同类冷却；仍在场的伤者恢复行动，片刻后自行离开。
        boolean closed = change(player, current, view -> also.apply(view.progress().getRoadside().toBuilder()
                .clearCurrent()
                .setLastEnding(ending)
                .setRestUntil(view.facts().ticks() + REST)));
        if (closed) {
            sessions.remove(player.getUUID());
            deaths.remove(UUID.fromString(current.getActor()));
            actor.ifPresent(FallenCultivator::release);
        }
        return closed;
    }

    private void end(ServerPlayer player, RoadsideCase current, String ending, String key,
                     UnaryOperator<Roadside.Builder> also, Optional<Mob> actor) {
        if (close(player, current, ending, also, actor)) {
            StoryChat.narrate(player, key);
        }
    }

    private static void release(Mob actor) {
        actor.removeTag(GUEST);
        actor.setNoAi(false /* noAi */);
        if (actor instanceof WanderingTrader trader) {
            trader.setDespawnDelay(DEPART);
        }
    }

    private boolean update(ServerPlayer player, RoadsideCase current, RoadsideCase next) {
        return change(player, current, view -> view.progress().getRoadside().toBuilder().setCurrent(next));
    }

    private boolean change(ServerPlayer player, RoadsideCase current,
                           Function<StoryStore.View, Roadside.Builder> compute) {
        return store.change(player, view -> {
            var roadside = view.progress().getRoadside();
            if (!roadside.getCurrent().equals(current)) {
                return view.progress();
            }
            return view.progress().toBuilder().setRoadside(compute.apply(view)).build();
        });
    }

    private boolean save(ServerPlayer player, Roadside before, Roadside next) {
        return store.change(player, view -> {
            Progress progress = view.progress();
            if (!progress.getRoadside().equals(before)) {
                return progress;
            }
            return progress.toBuilder().setRoadside(next).build();
        });
    }
}
