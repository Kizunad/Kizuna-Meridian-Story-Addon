package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyAccount;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

final class RingWagers {
    private static final String COVER = "house_cover";
    private static final String STAKE = "white_sash_stake";
    private static final String DEPOSIT = "bettor_deposit";
    private static final int ORIGINAL_STAKE = 5;

    private RingWagers() { }

    static boolean available(ServerPlayer player, RoadsideCase c, Mob house) {
        var purse = new ScenePurse(player, c);
        var price = ScenePurse.copper(ScenePurse.STAKE);
        return purse.canPay(purse.money.player(player), purse.till(STAKE), price)
                && (purse.paid(COVER) || purse.canPay(purse.money.merchant(house), purse.till(COVER), price));
    }

    static boolean accept(ServerPlayer player, RoadsideCase c, Mob house) {
        var purse = new ScenePurse(player, c);
        var price = ScenePurse.copper(ScenePurse.STAKE);
        if (!purse.canPay(purse.money.player(player), purse.till(STAKE), price) && !purse.paid(STAKE)) {
            return false;
        }
        if (!purse.move(COVER, purse.money.merchant(house), purse.till(COVER), price)) {
            return false;
        }
        return purse.move(STAKE, purse.money.player(player), purse.till(STAKE), price);
    }

    enum Result { WHITE, HOUSE, CANCEL }

    static boolean settle(ServerPlayer player, RoadsideCase c, Result result) {
        var purse = new ScenePurse(player, c);
        if (!purse.paid(COVER) && !purse.paid(STAKE)) {
            return true;
        }
        var house = RoadShow.actor(player, c, "house");
        var customer = purse.money.player(player);
        CurrencyAccount banker = purse.till("house_estate");
        if (house.isPresent()) {
            banker = purse.money.merchant(house.get());
        }
        CurrencyAccount winner = customer;
        if (result == Result.HOUSE) {
            winner = banker;
        }
        CurrencyAccount coverOwner = winner;
        if (result == Result.CANCEL) {
            coverOwner = banker;
        }
        var price = ScenePurse.copper(ScenePurse.STAKE);
        return (!purse.paid(STAKE) || purse.move("pay_stake", purse.till(STAKE), winner, price))
                && (!purse.paid(COVER) || purse.move("pay_cover", purse.till(COVER), coverOwner, price));
    }

    static boolean deposit(ServerPlayer player, RoadsideCase c, Mob bettor) {
        var purse = new ScenePurse(player, c);
        var deposit = ScenePurse.copper(ORIGINAL_STAKE);
        return purse.move(DEPOSIT, purse.money.merchant(bettor), purse.till(DEPOSIT), deposit);
    }

    static boolean returnDeposit(ServerPlayer player, RoadsideCase c) {
        var purse = new ScenePurse(player, c);
        if (!purse.paid(DEPOSIT) || purse.paid("return_deposit")) {
            return true;
        }
        var beneficiary = RoadShow.actor(player, c, "bettor").map(purse.money::merchant)
                .orElseGet(() -> purse.till("bettor_estate"));
        return purse.move("return_deposit", purse.till(DEPOSIT), beneficiary, ScenePurse.copper(ORIGINAL_STAKE));
    }

    static boolean thank(ServerPlayer player, RoadsideCase c) {
        var purse = new ScenePurse(player, c);
        return purse.paid(DEPOSIT) && returnDeposit(player, c) && RoadShow.actor(player, c, "bettor").map(mob ->
                purse.move("thanks", purse.money.merchant(mob), purse.money.player(player),
                        ScenePurse.copper(ScenePurse.THANKS))).orElse(false);
    }
}
