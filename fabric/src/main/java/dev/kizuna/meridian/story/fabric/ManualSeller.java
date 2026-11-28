package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.Price;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

final class ManualSeller {
    static final String KIND = "seller";
    static final String KEY = VillageEncounters.PREFIX + KIND + ".";
    static final String ASSOCIATES = "meridian_story:seller_associates";
    static final int REST_DAYS = 3;
    static final int DEPART = 1200;
    static final int PURSE = 60;
    static final int GENUINE = 90;
    static final int BUY = 0;
    static final int LEAVE = 1;
    static final int PRACTICE = 2;
    static final int CLOSE = 3;
    private static final String READ = "reading";
    private static final String SOLD = "sold";
    private static final int WEAK_TICKS = 160;
    private static final int SILVER_UNITS = 20;
    private static final Identifier SILVER = Identifier.parse("meridian_currency:silver");
    private static final Identifier PURCHASE = Identifier.parse("meridian_story:buy_manual");
    private final VillageEncounters events;

    ManualSeller(VillageEncounters events) {
        this.events = events;
    }

    boolean open(ServerPlayer player, Roadside.Builder road, BlockPos site) {
        var builder = VillageEncounters.start(player, road, site, KIND);
        net.minecraft.world.item.Item item;
        if (builder.getRoll() >= GENUINE) {
            item = FallenCultivator.scroll(FallenCultivator.seed(player), builder.getNumber());
        } else {
            item = Items.BOOK;
        }
        var c = builder.setScroll(BuiltInRegistries.ITEM.getKey(item).toString())
                .setHeld(true)
                .setQuotedUnits(SILVER_UNITS)
                .build();
        if (!MarketStall.place(player.level(), site)) {
            return false;
        }
        var guest = VillageEncounters.arrive(player, site, c.getActor(), KEY + "actor", EntityTypes.WANDERING_TRADER);
        if (guest.isEmpty()) {
            MarketStall.remove(player.level(), site);
            return false;
        }
        var seller = guest.get();
        VillageEncounters.markStall(seller, site);
        StoryPoses.tend(seller, MarketStall.counter(site));
        var book = book(player, c);
        if (MobInventories.open(seller).insert(book) != 1 || !events.open(player, road, c)) {
            seller.discard();
            MarketStall.remove(player.level(), site);
            return false;
        }
        offer(player, c);
        return true;
    }

    static ItemStack book(ServerPlayer player, RoadsideCase c) {
        var book = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.getScroll())));
        var mark = new CompoundTag();
        mark.putString("seller", c.getActor());
        mark.putString("owner", player.getStringUUID());
        // 货币流水使用 JSON，案号写成字符串才能在重放后保留相同的 NBT 类型。
        mark.putString("case", Long.toString(c.getNumber()));
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        if (c.getRoll() < GENUINE) {
            book.set(DataComponents.CUSTOM_NAME, Component.translatable(KEY + "fake"));
        }
        return book;
    }

    static boolean carries(ServerPlayer player, RoadsideCase c) {
        var expected = book(player, c);
        var bag = player.getInventory();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            if (ItemStack.isSameItemSameComponents(bag.getItem(slot), expected)) {
                return true;
            }
        }
        return false;
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals(READ)) {
            var practice = VillageEncounters.button(c, KEY + "practice", PRACTICE, line("practice.cost"));
            var close = VillageEncounters.button(c, KEY + "close", CLOSE, line("close.cost"));
            player.sendSystemMessage(Component.empty().append(practice).append("  ").append(close));
        } else if (c.getHeld()) {
            VillageEncounters.actor(player, c.getActor()).ifPresent(actor -> {
                StoryChat.narrate(player, KEY + "opening");
                var cost = Component.translatable(KEY + "buy.cost", CurrencyMod.catalog().format(price(c)));
                try {
                    quote(player, c, actor);
                } catch (TradeRejected rejected) {
                    cost.append(" ").append(rejection(rejected));
                }
                var buy = VillageEncounters.button(c, KEY + "buy", BUY, cost);
                var leave = VillageEncounters.button(c, KEY + "leave", LEAVE, line("leave.cost"));
                player.sendSystemMessage(Component.empty().append(buy).append("  ").append(line("wreck"))
                        .append("  ").append(leave));
            });
        }
    }

    private static CurrencyService.Quote quote(ServerPlayer player, RoadsideCase c, Mob actor) {
        var money = CurrencyService.get(player.level().getServer());
        return money.quote(money.player(player), money.merchant(actor), price(c), book(player, c));
    }

    private static Price price(RoadsideCase c) {
        return new Price(SILVER, c.getQuotedUnits());
    }

    private static UUID operation(ServerPlayer player, RoadsideCase c) {
        return CurrencyService.operationId(player.getUUID(), UUID.fromString(c.getActor()), PURCHASE, 1);
    }

    Optional<Component> choose(ServerPlayer player, RoadsideCase c, int option) {
        var actor = VillageEncounters.actor(player, c.getActor())
                .filter(found -> VillageEncounters.near(player, found));
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        if (c.getStage().equals(READ)) {
            return read(player, c, option);
        }
        if (!c.getHeld()) {
            return Optional.empty();
        }
        if (option == LEAVE && events.end(player, c, "passed_stall", KEY + "leave.done", "")) {
            return Optional.of(Component.empty());
        }
        if (option != BUY) {
            return Optional.empty();
        }
        var money = CurrencyService.get(player.level().getServer());
        UUID operation = operation(player, c);
        try {
            if (money.receipt(operation).isEmpty()) {
                money.settle(operation, quote(player, c, actor.get()));
            }
        } catch (TradeRejected rejected) {
            return Optional.of(rejection(rejected));
        }
        reveal(player, c, operation);
        return Optional.of(Component.empty());
    }

    private static Component rejection(TradeRejected rejected) {
        if (rejected.reason() == TradeRejected.Reason.FUNDS) {
            return line("poor");
        }
        return line("unavailable");
    }

    private void reveal(ServerPlayer player, RoadsideCase c, UUID operation) {
        boolean genuine = c.getRoll() >= GENUINE;
        String stage = genuine ? SOLD : READ;
        var sold = c.toBuilder()
                .setPayment(operation.toString())
                .setHeld(false)
                .setStage(stage)
                .build();
        if (events.update(player, c, sold)) {
            String key = genuine ? "buy.good" : "buy.bad";
            StoryChat.narrate(player, KEY + key);
            offer(player, sold);
        }
    }

    private Optional<Component> read(ServerPlayer player, RoadsideCase c, int option) {
        if (option != CLOSE && option != PRACTICE || !carries(player, c)) {
            return Optional.empty();
        }
        boolean practice = option == PRACTICE;
        var finished = c.toBuilder().setStage(SOLD).setPracticed(practice).build();
        if (!events.update(player, c, finished)) {
            return Optional.empty();
        }
        if (practice) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAK_TICKS));
            return Optional.of(line("practice.done"));
        }
        return Optional.of(line("close.done"));
    }

    void wreck(ServerPlayer player, RoadsideCase c, Mob actor) {
        MarketStall.overturn(player.level(), BlockPos.of(c.getSite()));
        var purse = MobInventories.open(actor);
        boolean dropped = dropPurse(player, actor, purse);
        var remaining = book(player, c);
        boolean held = purse.snapshot().stream()
                .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, remaining));
        var finished = c.toBuilder()
                .setHeld(held)
                .setPurseDropped(dropped)
                .build();
        if (c.getRoll() >= PURSE) {
            events.end(player, finished, "seller_marked", "", ASSOCIATES);
        } else {
            String key = "";
            if (dropped) {
                key = KEY + "wreck.good";
            }
            events.end(player, finished, "seller_fled", key, "");
        }
    }

    private static boolean dropPurse(ServerPlayer player, Mob actor, MobInventory purse) {
        boolean dropped = false;
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            var stack = purse.peek(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var removed = purse.extract(slot, stack.getCount());
            if (actor.spawnAtLocation(player.level(), removed) != null) {
                dropped = true;
            } else {
                purse.insertAt(slot, removed);
            }
        }
        return dropped;
    }

    boolean recoverSale(ServerPlayer player, RoadsideCase c) {
        if (c.getHeld()) {
            UUID id = operation(player, c);
            if (CurrencyService.get(player.level().getServer()).receipt(id).isPresent()) {
                reveal(player, c, id);
                return true;
            }
        }
        return false;
    }

    void gone(ServerPlayer player, RoadsideCase c) {
        String ending = c.getHeld() ? "stall_gone" : "manual_bought";
        events.end(player, c, ending, "", "");
    }

    private static Component line(String suffix) {
        return Component.translatable(KEY + suffix);
    }
}
