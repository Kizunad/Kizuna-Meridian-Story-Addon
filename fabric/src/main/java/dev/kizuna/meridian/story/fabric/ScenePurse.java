package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyAccount;
import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.Price;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.Collections;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class ScenePurse {
    private static final Identifier COPPER = Identifier.parse("meridian_currency:copper");
    private static final Identifier INGOT = Identifier.parse("meridian_currency:silver_sycee");
    private static final int SLOTS = 9;
    static final int STAKE = 100;
    static final int THANKS = 3;
    final CurrencyService money;
    private final ServerPlayer player;
    private final UUID scene;

    ScenePurse(ServerPlayer player, RoadsideCase c) {
        this.player = player;
        scene = UUID.fromString(c.getActor());
        money = CurrencyService.get(player.level().getServer());
    }

    Price prize() {
        var coin = CurrencyMod.catalog().denomination(INGOT);
        return new Price(coin.currency().id(), coin.units());
    }

    static Price copper(int units) {
        return new Price(COPPER, units);
    }

    CurrencyAccount till(String name) {
        return money.openTill(id(name), Collections.nCopies(SLOTS, ItemStack.EMPTY));
    }

    boolean paid(String key) {
        return money.receipt(id(key)).isPresent();
    }

    boolean move(String key, CurrencyAccount from, CurrencyAccount to, Price price) {
        var operation = id(key);
        var existing = money.receipt(operation);
        if (existing.isPresent()) {
            var parties = existing.get().parties();
            return from.id().equals(parties.payer()) && to.id().equals(parties.payee())
                    && existing.get().order().units() == price.units();
        }
        try {
            var quote = money.quote(from, to, price);
            money.settle(operation, quote);
            return true;
        } catch (TradeRejected rejected) {
            return false;
        }
    }

    boolean canPay(CurrencyAccount from, CurrencyAccount to, Price price) {
        try {
            money.quote(from, to, price);
            return true;
        } catch (TradeRejected rejected) {
            return false;
        }
    }

    private UUID id(String name) {
        var branch = Identifier.fromNamespaceAndPath("meridian_story", name);
        return CurrencyService.operationId(player.getUUID(), scene, branch, 0);
    }
}
