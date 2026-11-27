package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.ParcelEscrow;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Phase;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Site;
import dev.kizuna.meridian.story.fabric.TempleMarkets.Stall;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.MarketVisit;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.undead.fabric.NightVisits;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;

final class MidnightMarket {
    static final String KEY = "story.meridian_story.midnight.";
    static final Identifier ACTION = Identifier.parse("meridian_story:midnight_market");
    static final int MIDNIGHT = 18_000;
    static final int DAY = 24_000;
    private static final int SEARCH_INTERVAL = 16;
    private static final int APPROACH = 10;
    private static final int VISITOR_DISTANCE = 8;
    static final int TRUE_RELIC = 65;
    static final int TRUE_SEAL = 60;
    private static final long COOLDOWN = 7L * DAY;
    private final Map<UUID, LoneStarRoad.Step> previous = new HashMap<>();
    private final Map<String, Integer> reminders = new HashMap<>();
    private final StoryStore store;

    MidnightMarket(StoryStore store) {
        this.store = store;
    }

    void register() {
        AttackEntityCallback.EVENT.register((p, level, hand, entity, hit) -> {
            if (p instanceof ServerPlayer player && entity instanceof Mob mob
                    && mob.entityTags().contains(TempleMarkets.TAG)
                    && VillageEncounters.near(player, mob)) {
                strike(player, mob);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, damage) -> {
            if (entity instanceof Mob mob && mob.entityTags().contains(TempleMarkets.TAG)) {
                if (source.getEntity() instanceof ServerPlayer player
                        && source.getDirectEntity() instanceof AbstractArrow) {
                    strike(player, mob);
                }
                return false;
            }
            return true;
        });
        UseEntityCallback.EVENT.register((p, level, hand, entity, hit) -> {
            if (!(entity instanceof Mob mob) || !mob.entityTags().contains(TempleMarkets.TAG)) {
                return InteractionResult.PASS;
            }
            if (p instanceof ServerPlayer player && p.isShiftKeyDown() && VillageEncounters.near(player, mob)) {
                store.read(player).ifPresent(view -> visitAt(player, view, mob));
            }
            return InteractionResult.SUCCESS;
        });
    }

    private void visitAt(ServerPlayer player, StoryStore.View view, Mob mob) {
        var markets = TempleMarkets.get(player.level());
        markets.nearby(mob.blockPosition()).map(markets::site)
                .filter(s -> isTrader(s, mob) && view.progress().containsMarketVisits(key(player, s)))
                .ifPresent(s -> invite(player, view, s));
    }

    private static boolean isTrader(Site site, Mob mob) {
        var id = mob.getUUID();
        return site.phase() == Phase.OPEN && (site.merchant(Stall.RELICS).equals(id)
                || site.merchant(Stall.PARCELS).equals(id));
    }

    private void strike(ServerPlayer player, Mob mob) {
        var level = player.level();
        var markets = TempleMarkets.get(level);
        markets.nearby(mob.blockPosition()).map(markets::site).filter(s -> isTrader(s, mob))
                .filter(s -> trading(level.getOverworldClockTime(), s.dawn())).ifPresent(site -> {
                    markets.close(level, site, Optional.of(player.getUUID()));
                    StoryChat.narrate(player, KEY + "strike");
                });
    }

    static boolean trading(long clock, long dawn) {
        return Math.floorMod(clock, DAY) >= MIDNIGHT && ParcelEscrow.nextDawn(clock) == dawn;
    }

    private static boolean free(StoryStore.View view) {
        var road = view.progress().getRoadside();
        return !view.crisis().hasPending() && !road.hasCurrent() && road.getInquiryTicket() == 0;
    }

    private enum Movement { WALKING, STILL }

    private Movement movement(ServerPlayer player, StoryStore.View view) {
        if (player.level().dimension() != Level.OVERWORLD || !player.isAlive() || player.isSpectator()
                || player.isCreative() || !player.onGround() || player.isPassenger() || player.isInWater()
                || player.getAbilities().flying || !free(view)) {
            previous.remove(player.getUUID());
            return Movement.STILL;
        }
        var step = LoneStarRoad.Step.sample(player);
        if (step.followed(previous.put(player.getUUID(), step))) {
            return Movement.WALKING;
        }
        return Movement.STILL;
    }

    void leave(ServerPlayer player) {
        previous.remove(player.getUUID());
        store.read(player).ifPresent(v -> v.progress().getMarketVisitsMap().keySet().forEach(reminders::remove));
    }

    void tick(ServerPlayer player, StoryStore.View view) {
        var level = player.level();
        var overworld = level.getServer().overworld();
        var markets = TempleMarkets.get(overworld);
        var fate = view.progress().getFate();
        if (fate.hasTemple()) {
            markets.adopt(overworld, BlockPos.of(fate.getTemple()));
        }
        var motion = movement(player, view);
        for (var entry : view.progress().getMarketVisitsMap().entrySet()) {
            follow(player, entry.getKey(), entry.getValue(), motion);
        }
        long clock = level.getOverworldClockTime();
        if (motion != Movement.WALKING || Math.floorMod(clock, DAY) < MIDNIGHT
                || view.facts().ticks() < view.progress().getMarketRestUntil()) {
            return;
        }
        var feet = player.blockPosition();
        var nearby = markets.nearby(feet);
        if (nearby.isEmpty()) {
            // 每处新路段稳定抽签，只有走到合格网格才试放完整前庭。
            long tile = FallenCultivator.tile(feet);
            if (RoadsideRules.opens(level.getSeed(), tile) && Math.floorMod(view.facts().ticks() / LoneStarRoad.SECOND,
                    SEARCH_INTERVAL) == 0) {
                FateSites.placeTemple(player, base -> TempleMarkets.room(player, base));
            }
            return;
        }
        var site = markets.site(nearby.get());
        if (!site.yard().closerThan(feet, APPROACH) || site.banned().contains(player.getUUID())) {
            return;
        }
        var opened = markets.open(player, site);
        if (opened != null && !view.progress().containsMarketVisits(key(player, opened))) {
            invite(player, view, opened);
        }
    }

    static String key(ServerPlayer player, Site site) {
        return TempleMarkets.id(player.getStringUUID() + "/" + site.visit()).toString();
    }

    void invite(ServerPlayer player, StoryStore.View view, Site site) {
        if (player.isSpectator() || player.isCreative() || !free(view) || site.banned().contains(player.getUUID())
                || site.phase() != Phase.OPEN || !trading(player.level().getOverworldClockTime(), site.dawn())
                || !site.yard().closerThan(player.blockPosition(), APPROACH)) {
            return;
        }
        String key = key(player, site);
        var visit = view.progress().getMarketVisitsOrDefault(key, null);
        if (visit == null) {
            if (view.progress().getMarketRestUntil() > view.facts().ticks()) {
                return;
            }
            var id = UUID.fromString(key);
            var seed = FallenCultivator.seed(player);
            var relic = MarketOrders.listed(site, site.actor(player.level(), Stall.RELICS))
                    .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                    .orElse("");
            var fresh = MarketVisit.newBuilder()
                    .setRelicItem(relic)
                    .setCopperPrice(MarketOrders.RELIC_PRICE)
                    .setSilverFragments(MarketOrders.SILVER)
                    .setAltar(site.altar().asLong())
                    .setDawn(site.dawn())
                    .setCursed(RoadsideRules.roll(seed, id.getMostSignificantBits()) >= TRUE_RELIC)
                    .setPaper(RoadsideRules.roll(seed, id.getLeastSignificantBits()) >= TRUE_SEAL)
                    .build();
            if (update(player, key, null, fresh)) {
                StoryChat.narrate(player, KEY + "opening");
                new MarketOrders(player, key, fresh).offer(site);
            }
        } else if (!visit.getClosed()) {
            new MarketOrders(player, key, visit).offer(site);
        }
    }

    private boolean update(ServerPlayer player, String key, MarketVisit before, MarketVisit after) {
        return store.change(player, v -> {
            if (!java.util.Objects.equals(v.progress().getMarketVisitsOrDefault(key, null), before)) {
                return v.progress();
            }
            if (before == null && (!free(v) || v.progress().getMarketRestUntil() > v.facts().ticks()
                    || v.progress().getMarketVisitsMap().values().stream().anyMatch(visit -> !visit.getClosed()))) {
                return v.progress();
            }
            var next = v.progress().toBuilder().putMarketVisits(key, after);
            if (before != null && !before.getClosed() && after.getClosed()
                    && !dispersed(player, after)) {
                next.setMarketRestUntil(v.facts().ticks() + COOLDOWN);
            }
            return next.build();
        });
    }

    private static boolean dispersed(ServerPlayer player, MarketVisit visit) {
        var market = TempleMarkets.get(player.level().getServer().overworld()).site(BlockPos.of(visit.getAltar()));
        return market != null && market.dispersed().contains(visit.getDawn());
    }

    private void follow(ServerPlayer player, String key, MarketVisit visit, Movement motion) {
        boolean visitPending = visit.getCursed() && visit.getBought() && switch (visit.getPursuit()) {
            case "WON", "ESCAPED", "LOST" -> false;
            default -> true;
        };
        if (visit.getClosed() && !visitPending && (!visit.getSold() || visit.getOpened())) {
            return;
        }
        var level = player.level().getServer().overworld();
        var site = TempleMarkets.get(level).site(BlockPos.of(visit.getAltar()));
        var orders = new MarketOrders(player, key, visit);
        var after = reconcile(player, site, visit, orders);
        if (!after.equals(visit)) {
            if (update(player, key, visit, after) && after.getClosed() && !after.getBought() && !after.getSold()
                    && !dispersed(player, visit) && level.getOverworldClockTime() >= visit.getDawn()) {
                StoryChat.narrate(player, KEY + "dawn");
            }
            return;
        }
        if (!visit.getParcelRequest().isEmpty()) {
            finishParcel(player, key, visit, orders).ifPresent(player::sendSystemMessage);
            return;
        }
        if (visit.getSold() && !visit.getOpened()) {
            remind(player, key, visit, orders);
        }
        if (visitPending && (visit.getClosed()
                || site != null && !site.yard().closerThan(player.blockPosition(), APPROACH))) {
            pursue(player, key, visit, orders, motion);
        }
    }

    private MarketVisit reconcile(ServerPlayer player, Site site, MarketVisit visit, MarketOrders orders) {
        long clock = player.level().getServer().overworld().getOverworldClockTime();
        var next = visit.toBuilder();
        if (!visit.getClosed()) {
            if (!visit.getBought() && orders.bought()) {
                next.setBought(true /* bought */);
            }
            if (!visit.getSold() && orders.sold()) {
                next.setSold(true /* sold */);
            }
            if (site == null || site.phase() != Phase.OPEN || site.dawn() != visit.getDawn()
                    || !trading(clock, visit.getDawn())) {
                next.setClosed(true /* closed */);
            }
        }
        return next.build();
    }

    private void remind(ServerPlayer player, String key, MarketVisit visit, MarketOrders orders) {
        int action = MarketOrders.REISSUE;
        if (orders.carriesParcel()) {
            if (player.level().getServer().overworld().getOverworldClockTime() < visit.getDawn()) {
                reminders.remove(key);
                return;
            }
            action = MarketOrders.OPEN;
        }
        if (!Integer.valueOf(action).equals(reminders.put(key, action))) {
            orders.remind(action);
        }
    }

    private void pursue(ServerPlayer player, String key, MarketVisit visit, MarketOrders orders, Movement motion) {
        var ticket = orders.ticket();
        var status = NightVisits.status(player, ticket);
        if (status == NightVisits.Status.WAITING) {
            if (motion != Movement.WALKING || !orders.carriesRelic()) {
                return;
            }
            var ahead = player.blockPosition().relative(player.getDirection().getOpposite(), VISITOR_DISTANCE);
            var feet = FateSites.feet(player.level(), ahead);
            if (feet.isEmpty()) {
                return;
            }
            status = NightVisits.request(player, ticket, feet.get());
        }
        if (status != NightVisits.Status.WAITING && !status.name().equals(visit.getPursuit())) {
            var next = visit.toBuilder().setPursuit(status.name()).build();
            if (update(player, key, visit, next) && status == NightVisits.Status.PRESENT
                    && visit.getPursuit().isEmpty()) {
                StoryChat.narrate(player, KEY + "scratching");
            }
        }
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection choice) {
        var view = store.read(player).orElse(null);
        if (view == null || !player.isAlive() || player.isSpectator()) {
            return Optional.empty();
        }
        var visit = view.progress().getMarketVisitsOrDefault(choice.nonce(), null);
        if (visit == null) {
            return Optional.empty();
        }
        var orders = new MarketOrders(player, choice.nonce(), visit);
        try {
            if (choice.option() == MarketOrders.OPEN || choice.option() == MarketOrders.REISSUE) {
                return redeem(player, choice, visit, orders);
            }
            return trade(player, choice, visit, orders, view);
        } catch (TradeRejected rejected) {
            return Optional.of(MarketOrders.text("unavailable"));
        }
    }

    private Optional<Component> trade(ServerPlayer player, StoryChat.Selection choice, MarketVisit visit,
                                      MarketOrders orders, StoryStore.View view) {
        var level = player.level();
        if (visit.getClosed() || player.isCreative() || level.dimension() != Level.OVERWORLD || !free(view)
                || !trading(level.getOverworldClockTime(), choice.revision()) || choice.revision() != visit.getDawn()) {
            return Optional.empty();
        }
        var site = TempleMarkets.get(level).site(BlockPos.of(visit.getAltar()));
        if (site == null || site.phase() != Phase.OPEN || site.dawn() != visit.getDawn()
                || site.banned().contains(player.getUUID())) {
            return Optional.empty();
        }
        var stall = choice.option() == MarketOrders.BUY ? Stall.RELICS : Stall.PARCELS;
        var actor = site.actor(level, stall);
        if (actor == null || !VillageEncounters.near(player, actor)) {
            return Optional.empty();
        }
        var next = visit.toBuilder();
        String line;
        if (choice.option() == MarketOrders.BUY && !visit.getBought()) {
            if (!orders.bought() && !orders.buy(site, actor)) {
                return Optional.of(MarketOrders.text("unavailable"));
            }
            next.setBought(true /* bought */);
            line = "bought";
        } else if (choice.option() == MarketOrders.SELL && !visit.getSold()) {
            orders.sell(actor);
            next.setSold(true /* sold */);
            line = "sold";
        } else {
            return Optional.empty();
        }
        if (update(player, choice.nonce(), visit, next.build())) {
            return Optional.of(MarketOrders.text(line));
        }
        return Optional.empty();
    }

    private Optional<Component> redeem(ServerPlayer player, StoryChat.Selection choice, MarketVisit visit,
                                       MarketOrders orders) {
        if (!visit.getSold() || visit.getOpened() || !visit.getParcelRequest().isEmpty()
                || choice.revision() != visit.getSerial()) {
            return Optional.empty();
        }
        String request = choice.option() == MarketOrders.REISSUE ? "reissue" : "open";
        var pending = visit.toBuilder().setParcelRequest(request).build();
        if (!update(player, choice.nonce(), visit, pending)) {
            return Optional.empty();
        }
        return finishParcel(player, choice.nonce(), pending, orders);
    }

    private Optional<Component> finishParcel(ServerPlayer player, String key, MarketVisit visit, MarketOrders orders) {
        var next = visit.toBuilder().clearParcelRequest();
        String line;
        try {
            if (visit.getParcelRequest().equals("reissue")) {
                orders.reissue();
                next.setSerial(Math.incrementExact(visit.getSerial()));
                line = "replacement";
            } else {
                orders.open();
                next.setOpened(true /* opened */);
                line = visit.getPaper() ? "paper" : "silver";
            }
        } catch (TradeRejected rejected) {
            line = "unavailable";
        }
        if (update(player, key, visit, next.build())) {
            reminders.remove(key);
            return Optional.of(MarketOrders.text(line));
        }
        return Optional.empty();
    }
}
