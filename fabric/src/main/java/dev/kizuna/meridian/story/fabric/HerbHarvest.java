package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.Price;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;

final class HerbHarvest {
    static final String KIND = "herb";
    static final String KEY = MountainEncounters.PREFIX + KIND + ".";
    static final long REST = 5 * MountainEncounters.DAY;
    static final int PAIR = 2;
    static final int SHARE = 0;
    static final int INQUIRE = 1;
    static final int SELL = 2;
    private static final int HARVEST_GOOD = 60;
    private static final int SALE_GOOD = 70;
    private static final int SILVER_UNITS = 20;
    private static final int ONE_LIANG = 10;
    static final String AMBUSH = "meridian_story:herb_ambush";
    private static final Identifier SALE = Identifier.parse("meridian_story:sell_trail");
    private static final Identifier SILVER = Identifier.parse("meridian_currency:silver");
    private static final float STRIKE = 2.0F;
    private static final int GUARD_RADIUS = 3;
    private final MountainEncounters events;

    HerbHarvest(MountainEncounters events) {
        this.events = events;
    }

    void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer owner && level instanceof ServerLevel server) {
                if (pick(owner, pos)) {
                    return false;
                }
                String supported = HerbLedge.get(server).owner(pos.above());
                return supported == null || supported.isEmpty();
            }
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer owner && hand == InteractionHand.MAIN_HAND
                    && pick(owner, hit.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private boolean pick(ServerPlayer player, BlockPos pos) {
        var level = player.level();
        String owner = HerbLedge.get(level).owner(pos);
        if (owner == null || owner.isEmpty()) {
            return false;
        }
        if (player.isAlive() && !player.isSpectator() && player.isWithinBlockInteractionRange(pos, 0)) {
            events.current(player).filter(c -> c.getActor().equals(owner) && c.getPlant() == pos.asLong())
                    .ifPresent(c -> harvest(player, c));
        }
        return true;
    }

    boolean open(ServerPlayer player, Roadside road, HerbLedge.Site site) {
        var c = MountainEncounters.scene(player, road, KIND, site.foot())
                .setPlant(site.flower().asLong())
                .setRemaining(PAIR)
                .setGuardian(UUID.randomUUID().toString())
                .setBuyer(UUID.randomUUID().toString())
                .build();
        var partner = guest(player, site.partner(), c.getActor(), "partner", EntityTypes.VINDICATOR);
        var merchant = new HumanoidVisitors.Guest<>(EntityTypes.WANDERING_TRADER, HumanoidTier.TRUE_QI);
        var buyer = HumanoidVisitors.arrive(player.level(), site.buyer(), UUID.fromString(c.getBuyer()),
                Component.translatable(KEY + "buyer"), merchant);
        buyer.ifPresent(actor -> MountainEncounters.mark(player, actor));
        var wolf = guest(player, site.foot(), c.getGuardian(), "wolf", EntityTypes.WOLF);
        var level = player.level();
        boolean planted = partner.isPresent() && buyer.isPresent() && wolf.isPresent()
                && HerbLedge.get(level).plant(level, site.flower(), c.getActor());
        if (!planted || !events.open(player, road, c)) {
            partner.ifPresent(Mob::discard);
            buyer.ifPresent(Mob::discard);
            wolf.ifPresent(Mob::discard);
            HerbLedge.get(level).retire(level, site.flower(), c.getActor());
            return false;
        }
        offer(player, c);
        return true;
    }

    private static <T extends Mob> Optional<T> guest(ServerPlayer player, BlockPos pos, String id,
                                                      String role, EntityType<T> type) {
        var mob = HumanoidVisitors.arrive(player.level(), pos, UUID.fromString(id),
                Component.translatable(KEY + role), type);
        mob.ifPresent(actor -> MountainEncounters.mark(player, actor));
        return mob;
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (!unclaimed(c)) {
            return;
        }
        MountainEncounters.narrate(player, c, "opening");
        var row = Component.empty().append(MountainEncounters.button(c, KEY + "share", SHARE,
                Component.translatable(KEY + "share.cost")));
        row.append("  ").append(MountainEncounters.button(c, KEY + "inquire", INQUIRE,
                Component.translatable(KEY + "inquire.cost")));
        row.append("  ").append(Component.translatable(KEY + "solo"));
        player.sendSystemMessage(row);
    }

    private static boolean unclaimed(RoadsideCase c) {
        return c.getStage().equals(MountainEncounters.OPEN) || c.getStage().equals("quoted");
    }

    void strike(ServerPlayer player, RoadsideCase c, Mob actor) {
        if (c.getGuardian().equals(actor.getStringUUID()) && unclaimed(c)) {
            var next = c.toBuilder().setStage("solo").build();
            if (events.update(player, c, next)) {
                MountainEncounters.fight(player, actor);
            }
        }
    }

    Optional<Component> choose(ServerPlayer player, RoadsideCase c, int option) {
        if (!unclaimed(c)) {
            return Optional.empty();
        }
        String id = c.getBuyer();
        if (option == SHARE) {
            id = c.getActor();
        }
        var actor = MountainEncounters.actor(player, id).filter(mob -> MountainEncounters.near(player, mob));
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        if (option == SHARE) {
            if (events.update(player, c, c.toBuilder().setStage("shared").build())) {
                return Optional.of(Component.translatable(KEY + "agreed"));
            }
        } else if (option == INQUIRE) {
            return quote(player, c, actor.get());
        } else if (option == SELL && c.getStage().equals("quoted")) {
            return sell(player, c, actor.get());
        }
        return Optional.empty();
    }

    private static ItemStack marked(RoadsideCase c, Item item, int count) {
        var stack = new ItemStack(item, count);
        var tag = new CompoundTag();
        tag.putString("meridian_story:harvest", c.getActor());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static ItemStack map(RoadsideCase c) {
        var map = marked(c, Items.MAP, 1);
        map.set(DataComponents.CUSTOM_NAME, Component.translatable(KEY + "map"));
        return map;
    }

    private Optional<Component> quote(ServerPlayer player, RoadsideCase c, Mob buyer) {
        var money = CurrencyService.get(player.level().getServer());
        for (int units = SILVER_UNITS; units >= ONE_LIANG; units -= ONE_LIANG) {
            var price = new Price(SILVER, units);
            try {
                money.quote(money.merchant(buyer), money.player(player), price);
            } catch (TradeRejected rejected) {
                continue;
            }
            var next = c.toBuilder()
                    .setStage("quoted")
                    .setQuotedUnits(units)
                    .build();
            if (!c.equals(next) && !events.update(player, c, next)) {
                return Optional.empty();
            }
            var amount = CurrencyMod.catalog().format(price);
            var row = Component.translatable(KEY + "quote", amount).append("  ");
            var confirm = new StoryChat.Selection(c.getActor(), saleRevision(c, units), SELL);
            row.append(StoryChat.button(KEY + "sell", Component.translatable(KEY + "sell.cost", amount),
                    net.minecraft.ChatFormatting.GOLD, confirm));
            player.sendSystemMessage(row);
            return Optional.of(Component.empty());
        }
        return Optional.of(Component.translatable(KEY + "poor"));
    }

    private static UUID operation(ServerPlayer player, RoadsideCase c) {
        return CurrencyService.operationId(player.getUUID(), UUID.fromString(c.getActor()), SALE, 1);
    }

    static long saleRevision(RoadsideCase c, long units) {
        return -(c.getNumber() * (SILVER_UNITS + 1) + units);
    }

    private Optional<Component> sell(ServerPlayer player, RoadsideCase c, Mob buyer) {
        var money = CurrencyService.get(player.level().getServer());
        var id = operation(player, c);
        if (money.receipt(id).isEmpty()) {
            var map = map(c);
            var bag = player.getInventory();
            if (!bag.add(map.copy())) {
                return Optional.of(Component.translatable(KEY + "space"));
            }
            try {
                var price = new Price(SILVER, c.getQuotedUnits());
                money.settle(id, money.quote(money.merchant(buyer), money.player(player), price, map));
            } catch (TradeRejected rejected) {
                take(player, map);
                return Optional.of(Component.translatable(KEY + "poor"));
            }
        }
        sold(player, c, buyer, id);
        return Optional.of(Component.empty());
    }

    private void sold(ServerPlayer player, RoadsideCase c, Mob buyer, UUID id) {
        var sold = c.toBuilder()
                .setPayment(id.toString())
                .setRemaining(0)
                .setStage("sold")
                .build();
        if (!events.update(player, c, sold)) {
            return;
        }
        var flower = BlockPos.of(c.getPlant());
        if (HerbLedge.get(player.level()).harvest(player.level(), flower, c.getActor())) {
            giveMob(player, buyer, marked(c, Items.WITHER_ROSE, PAIR));
        }
        if (c.getRoll() < SALE_GOOD) {
            end(player, sold, "sold_good", "");
        } else {
            MountainEncounters.narrate(player, sold, "sold_bad");
            MountainEncounters.fight(player, buyer);
        }
    }

    private void harvest(ServerPlayer player, RoadsideCase c) {
        boolean shared = c.getStage().equals("shared");
        if (c.getRemaining() != PAIR || !(shared || unclaimed(c) || c.getStage().equals("solo"))) {
            return;
        }
        var partner = MountainEncounters.actor(player, c.getActor());
        if (shared && partner.isEmpty()) {
            return;
        }
        var pos = BlockPos.of(c.getPlant());
        var level = player.level();
        if (!HerbLedge.get(level).harvest(level, pos, c.getActor())) {
            return;
        }
        int kept = shared ? 1 : PAIR;
        String stage = shared ? "divided" : "descent";
        var picked = c.toBuilder()
                .setStage(stage)
                .setRemaining(0)
                .setCarriedFlowers(kept)
                .build();
        if (!events.update(player, c, picked)) {
            level.setBlockAndUpdate(pos, Blocks.WITHER_ROSE.defaultBlockState());
            return;
        }
        player.getInventory().placeItemBackInInventory(marked(c, Items.WITHER_ROSE, kept));
        if (shared) {
            var gatherer = partner.orElseThrow();
            giveMob(player, gatherer, marked(c, Items.WITHER_ROSE, 1));
            if (c.getRoll() < HARVEST_GOOD) {
                end(player, picked, "shared_good", "");
            } else {
                MountainEncounters.narrate(player, picked, "shared_bad");
                MountainEncounters.fight(player, gatherer);
            }
        }
    }

    private static void giveMob(ServerPlayer player, Mob mob, ItemStack stack) {
        MobInventories.open(mob).insert(stack);
        if (!stack.isEmpty()) {
            mob.spawnAtLocation(player.level(), stack);
        }
    }

    private static ItemStack take(ServerPlayer player, ItemStack expected) {
        var bag = player.getInventory();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            if (ItemStack.isSameItemSameComponents(bag.getItem(slot), expected)) {
                return bag.removeItem(slot, expected.getCount());
            }
        }
        return ItemStack.EMPTY;
    }

    void leave(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals("divided")) {
            var partner = MountainEncounters.actor(player, c.getActor());
            if (partner.isPresent()) {
                if (seize(player, partner.get(), c, 1) == 1) {
                    end(player, c.toBuilder().setCarriedFlowers(0).build(), "lost", "");
                    return;
                }
            }
        }
        end(player, c, "departed", "");
    }

    static int carried(ServerPlayer player, RoadsideCase c) {
        var bag = player.getInventory();
        var flower = marked(c, Items.WITHER_ROSE, 1);
        int count = 0;
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            var stack = bag.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, flower)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    void rob(ServerPlayer player, RoadsideCase c, Mob thief) {
        if (seize(player, thief, c, PAIR) > 0 && carried(player, c) == 0) {
            MountainEncounters.narrate(player, c, "lost");
        }
    }

    private static int seize(ServerPlayer player, Mob thief, RoadsideCase c, int limit) {
        var flower = marked(c, Items.WITHER_ROSE, limit);
        int taken = 0;
        while (taken < limit) {
            var lost = take(player, flower.copyWithCount(limit - taken));
            if (lost.isEmpty()) {
                break;
            }
            taken += lost.getCount();
            giveMob(player, thief, lost);
        }
        if (!player.isAlive() && taken < limit) {
            taken += takeFallen(player, thief, flower, limit - taken);
        }
        return taken;
    }

    private static int takeFallen(ServerPlayer player, Mob thief, ItemStack flower, int limit) {
        var drops = player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                player.getBoundingBox().inflate(MountainEncounters.REACH),
                drop -> ItemStack.isSameItemSameComponents(drop.getItem(), flower));
        int taken = 0;
        for (var drop : drops) {
            var stack = drop.getItem().copy();
            var lost = stack.split(limit - taken);
            taken += lost.getCount();
            if (stack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(stack);
            }
            giveMob(player, thief, lost);
            if (taken == limit) {
                break;
            }
        }
        return taken;
    }

    void died(ServerPlayer player, RoadsideCase c, Mob mob) {
        if (mob.getStringUUID().equals(c.getGuardian())) {
            return;
        }
        if (c.getStage().equals("divided") && mob.getStringUUID().equals(c.getActor())
                || c.getStage().equals("sold") && mob.getStringUUID().equals(c.getBuyer())) {
            end(player, c, "won", "");
        } else if (unclaimed(c) || c.getStage().equals("shared") && c.getActor().equals(mob.getStringUUID())) {
            end(player, c, "gone", "");
        }
    }

    void tick(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals("quoted")) {
            UUID id = operation(player, c);
            if (CurrencyService.get(player.level().getServer()).receipt(id).isPresent()) {
                MountainEncounters.actor(player, c.getBuyer()).ifPresent(buyer -> sold(player, c, buyer, id));
                return;
            }
        }
        if (c.getStage().equals("descent") && player.blockPosition().getY() <= BlockPos.of(c.getSite()).getY()) {
            int held = carried(player, c);
            var returned = c.toBuilder().setCarriedFlowers(held).build();
            if (held != PAIR) {
                end(player, returned, "departed", "");
            } else if (c.getRoll() < HARVEST_GOOD) {
                end(player, c, "solo_good", "");
            } else {
                end(player, c, "returning", AMBUSH);
            }
        } else if (c.getStage().equals("sold") || c.getStage().equals("divided")) {
            String enemy = c.getActor();
            if (c.getStage().equals("sold")) {
                enemy = c.getBuyer();
            }
            MountainEncounters.actor(player, enemy).ifPresent(mob -> pursue(player, mob));
        } else if (c.getRemaining() > 0
                && !player.level().getBlockState(BlockPos.of(c.getPlant())).is(Blocks.WITHER_ROSE)) {
            end(player, c, "gone", "");
        } else if (c.getRemaining() > 0 && !c.getStage().equals("shared")) {
            MountainEncounters.actor(player, c.getGuardian())
                    .filter(wolf -> wolf.distanceTo(player) <= GUARD_RADIUS && wolf.hasLineOfSight(player))
                    .ifPresent(wolf -> MountainEncounters.fight(player, wolf));
        }
    }

    private static void pursue(ServerPlayer player, Mob enemy) {
        MountainEncounters.fight(player, enemy);
        if (enemy.isWithinMeleeAttackRange(player) && enemy.hasLineOfSight(player)) {
            enemy.swing(InteractionHand.MAIN_HAND);
            player.hurtServer(player.level(), enemy.damageSources().mobAttack(enemy), STRIKE);
        }
    }

    private void end(ServerPlayer player, RoadsideCase c, String ending, String party) {
        var level = player.level().getServer().overworld();
        HerbLedge.get(level).retire(level, BlockPos.of(c.getPlant()), c.getActor());
        events.end(player, c, ending, party, 0);
    }
}
