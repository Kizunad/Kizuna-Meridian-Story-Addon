package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.currency.CurrencyCatalog;
import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.ParcelEscrow;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Site;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Stall;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.MarketVisit;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

final class MarketOrders {
    static final int BUY = 0;
    static final int SELL = 1;
    static final int OPEN = 2;
    static final int REISSUE = 3;
    static final int RELIC_PRICE = 36;
    static final int SILVER = 2;
    private static final int VARIETIES = 3;
    private static final Identifier FRAGMENT = Identifier.parse("meridian_currency:silver_fragment");
    private static final String TICKET = "meridian_story_relic";
    private final ServerPlayer player;
    private final String key;
    private final MarketVisit record;
    private final CurrencyService money;

    MarketOrders(ServerPlayer player, String key, MarketVisit record) {
        this.player = player;
        this.key = key;
        this.record = record;
        money = CurrencyService.get(player.level().getServer());
    }

    static ItemStack relic(Site site) {
        UUID visit = site.visit();
        var item = switch (Math.floorMod(visit.getLeastSignificantBits(), VARIETIES)) {
            case 0 -> new ItemStack(FallenCultivator.scroll(visit.getMostSignificantBits(), site.dawn()));
            case 1 -> BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            default -> new ItemStack(Items.IRON_SWORD);
        };
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.putString(TICKET, visit.toString()));
        return item;
    }

    static boolean stock(Mob merchant, Site site) {
        var bag = MobInventories.open(merchant);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            var item = bag.peek(slot);
            var data = item.get(DataComponents.CUSTOM_DATA);
            if (data != null && data.copyTag().contains(TICKET)) {
                // 原夜未售的实物仍属原商人，新夜给同一件货换价签。
                String label = site.visit().toString();
                CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.putString(TICKET, label));
                bag.exchange(slot, item);
                return true;
            }
        }
        return bag.insert(relic(site)) == 1;
    }

    static Optional<ItemStack> listed(Site site, Mob seller) {
        if (seller == null) {
            return Optional.empty();
        }
        var money = CurrencyService.get(((net.minecraft.server.level.ServerLevel) seller.level()).getServer());
        String ticket = site.visit().toString();
        return money.merchant(seller).contents().stream().filter(stack -> {
            var data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && ticket.equals(data.copyTag().getStringOr(TICKET, ""));
        }).findFirst();
    }

    private UUID order(String branch) {
        return TempleMarkets.id(key + "/" + branch);
    }

    UUID ticket() {
        return order("relic");
    }

    private ParcelEscrow.Offer parcel() {
        var wrapper = new ItemStack(Items.RABBIT_HIDE);
        wrapper.set(DataComponents.CUSTOM_NAME, text("parcel"));
        var contents = record.getPaper() ? ParcelEscrow.Contents.PAPER : ParcelEscrow.Contents.SILVER;
        var payment = new CurrencyCatalog.Bundle(FRAGMENT, record.getSilverFragments());
        var seal = new ParcelEscrow.Seal(wrapper, payment, contents, record.getDawn());
        return new ParcelEscrow.Offer(order("parcel"), new ItemStack(Items.AMETHYST_SHARD), seal);
    }

    private boolean hasMark(String name, String value, String serial, String item) {
        var bag = player.getInventory();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            var stack = bag.getItem(slot);
            var data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                var tag = data.copyTag();
                if (value.equals(tag.getStringOr(name, "")) && (serial.isEmpty()
                        || serial.equals(tag.getStringOr("meridian_currency_seal", "")))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean carriesRelic() {
        var tag = TempleMarkets.id(record.getAltar() + "/" + record.getDawn());
        return hasMark(TICKET, tag.toString(), "", record.getRelicItem());
    }

    boolean carriesParcel() {
        return hasMark("meridian_currency_order", order("parcel").toString(), Integer.toString(record.getSerial()),
                "minecraft:rabbit_hide");
    }

    boolean bought() {
        return money.receipt(ticket()).isPresent();
    }

    boolean sold() {
        return money.receipt(order("parcel")).isPresent();
    }

    void offer(Site site) {
        var seller = site.actor(player.level(), Stall.RELICS);
        if (!record.getBought() && seller != null) {
            var price = ScenePurse.copper(record.getCopperPrice());
            listed(site, seller).filter(this::quoted).ifPresent(relic ->
                    button("buy", BUY, text("buy.cost", relic.getHoverName(), CurrencyMod.catalog().format(price)),
                            record.getDawn()));
        }
        var buyer = site.actor(player.level(), Stall.PARCELS);
        if (!record.getSold() && buyer != null) {
            try {
                var offer = parcel();
                money.parcels().preview(player, buyer, offer);
                var cost = text("sell.cost", offer.goods().getHoverName(), record.getSilverFragments());
                button("sell", SELL, cost, record.getDawn());
            } catch (TradeRejected unavailable) {
                // 收货人有足额原银、玩家有这行实货时才挂牌。
            }
        }
    }

    void remind(int action) {
        String label = action == OPEN ? "open" : "reissue";
        button(label, action, text(label + ".cost"), record.getSerial());
    }

    private void button(String label, int action, Component cost, long revision) {
        var selection = new StoryChat.Selection(key, revision, action);
        var button = StoryChat.button(MidnightMarket.KEY + label, cost, ChatFormatting.GOLD, selection,
                MidnightMarket.ACTION);
        player.sendSystemMessage(Component.empty().append(button).append(" ").append(cost));
    }

    private boolean quoted(ItemStack relic) {
        return BuiltInRegistries.ITEM.getKey(relic.getItem()).toString().equals(record.getRelicItem());
    }

    boolean buy(Site site, Mob seller) {
        var price = ScenePurse.copper(record.getCopperPrice());
        var relic = listed(site, seller).filter(this::quoted);
        if (relic.isEmpty()) {
            return false;
        }
        var quote = money.quote(money.player(player), money.merchant(seller), price, relic.get());
        money.settle(ticket(), quote);
        return true;
    }

    void sell(Mob buyer) {
        money.parcels().sell(player, buyer, parcel());
    }

    void open() {
        money.parcels().open(player, order("parcel"), record.getSerial());
    }

    void reissue() {
        money.parcels().reissue(player, order("parcel"), Math.incrementExact(record.getSerial()));
    }

    static Component text(String suffix, Object... args) {
        return Component.translatable(MidnightMarket.KEY + suffix, args);
    }
}
