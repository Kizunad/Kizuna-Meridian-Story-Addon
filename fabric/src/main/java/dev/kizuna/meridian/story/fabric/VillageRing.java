package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.vanilla.fabric.LethalHitVeto;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

final class VillageRing extends RoadShow implements LethalHitVeto {
    static final String KIND = "village_ring";
    static final String ENFORCERS = "meridian_story:ring_enforcers";
    static final int ENTER = 1;
    static final int BET = 2;
    private static final int THIRD = 2;
    private static final int THREE = 3;
    private static final int NORMAL = 70;
    private static final int WHITE_WINS = 50;
    private static final int EXPOSED = 55;
    private static final int BOUT_DELAY = 40;
    private static final int VILLAGE_RADIUS = 32;
    private static final String DUEL = "duel";
    private static final String SHOW = "exhibition";
    private static final String WHITE = "white";
    private static final String OTHER = "second";
    private static final String HOUSE = "house";
    private static final String FRAUD = "expose";
    private static final List<Role> CAST = List.of(
            new Role("patron", new BlockPos(-6, 0, 2), EntityTypes.WANDERING_TRADER, HumanoidTier.TRUE_QI),
            new Role(HOUSE, new BlockPos(6, 0, 1), EntityTypes.WANDERING_TRADER, HumanoidTier.TRUE_QI),
            new Role("bettor", new BlockPos(6, 0, -1), EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI),
            new Role(WHITE, new BlockPos(-3, 1, 2), EntityTypes.VILLAGER, HumanoidTier.MORTAL),
            new Role(OTHER, new BlockPos(2, 1, -3), EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI),
            new Role("third", new BlockPos(-6, 0, -2), EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI),
            new Role("master", new BlockPos(7, 0, 2), EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI));

    VillageRing(MountainEncounters events) {
        super(events, KIND);
    }

    boolean nearVillage(ServerPlayer player, BlockPos center, BlockPos feet) {
        var level = player.level();
        var area = new AABB(center).inflate(VILLAGE_RADIUS);
        return level.canSeeSky(center) && !level.getEntitiesOfClass(Villager.class,
                area, mob -> mob.isAlive() && level.isVillage(mob.blockPosition())
                        && !mob.entityTags().contains("meridian_humanoid.visitor")).isEmpty()
                && FranticCultivator.gate(level, feet);
    }

    @Override
    List<Role> cast() {
        return CAST;
    }

    @Override
    boolean reserveDeposits(ServerPlayer player, RoadsideCase c) {
        return RingWagers.deposit(player, c, actor(player, c, "bettor").orElseThrow());
    }

    @Override
    void opened(ServerPlayer player, RoadsideCase c) {
        if (player.level().getBlockEntity(site(c).offset(RoadVenue.TABLE)) instanceof Container box) {
            var lots = new ItemStack(Items.PAPER);
            lots.set(DataComponents.CUSTOM_NAME, Component.translatable(key("lots")));
            box.setItem(0, lots);
            box.setChanged();
        }
    }

    @Override
    void offer(ServerPlayer player, RoadsideCase c) {
        if (!c.getStage().equals(OPEN)) {
            return;
        }
        MountainEncounters.narrate(player, c, "opening");
        button(player, c, ENTER, "enter", Component.translatable(key("enter.cost")));
        var house = actor(player, c, HOUSE);
        if (house.isPresent() && RingWagers.available(player, c, house.get())) {
            var amount = CurrencyMod.catalog().format(ScenePurse.copper(ScenePurse.STAKE));
            button(player, c, BET, "bet", Component.translatable(key("bet.cost"), amount));
        }
    }

    @Override
    boolean choose(ServerPlayer player, RoadsideCase c, int option) {
        if (!c.getStage().equals(OPEN) || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (option == ENTER && nearby(player, c, "patron")) {
            var next = c.toBuilder().setStage(DUEL).build();
            if (events.update(player, c, next)) {
                position(player, site(c).above().west());
                startBout(player, next);
                return true;
            }
        }
        if (option == BET && nearby(player, c, HOUSE)) {
            var house = actor(player, c, HOUSE).orElseThrow();
            if (RingWagers.accept(player, c, house)) {
                var next = c.toBuilder()
                        .setStage(SHOW)
                        .setPayment("white_sash_stake")
                        .setArrivedAt(player.level().getGameTime())
                        .build();
                return events.update(player, c, next);
            }
        }
        return false;
    }

    private static void position(Entity entity, BlockPos pos) {
        entity.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static String opponent(RoadsideCase c) {
        return switch (c.getBoutsCount()) {
            case 0 -> WHITE;
            case 1 -> OTHER;
            default -> c.getRoll() < NORMAL ? "third" : "master";
        };
    }

    private static void startBout(ServerPlayer player, RoadsideCase c) {
        actor(player, c, opponent(c)).ifPresent(mob -> {
            position(mob, site(c).above().east());
            mob.setPose(Pose.STANDING);
        });
    }

    static boolean onStage(Entity e, RoadsideCase c) {
        var p = e.blockPosition();
        var base = site(c);
        return Math.abs(p.getX() - base.getX()) <= RoadVenue.PLATFORM
                && Math.abs(p.getZ() - base.getZ()) <= RoadVenue.PLATFORM && p.getY() > base.getY();
    }

    @Override
    void strike(ServerPlayer player, RoadsideCase c, Mob mob) {
        if (c.getStage().equals(OPEN) && !onStage(player, c) && c.getCastOrThrow(HOUSE).equals(mob.getStringUUID())
                && player.level().getBlockEntity(site(c).offset(RoadVenue.TABLE)) instanceof Container box
                && box.getItem(0).is(Items.PAPER)) {
            var exposed = c.toBuilder().setStage(FRAUD).build();
            if (events.update(player, c, exposed)) {
                var lots = box.removeItem(0, 1);
                box.setChanged();
                mob.spawnAtLocation(player.level(), lots);
                expose(player, exposed);
            }
        }
    }

    private void expose(ServerPlayer player, RoadsideCase c) {
        if (c.getRoll() < EXPOSED) {
            if (RingWagers.thank(player, c)) {
                end(player, c, "exposed");
            } else if (actor(player, c, "bettor").isEmpty()) {
                end(player, c, "left");
            }
        } else {
            end(player, c.toBuilder().setReturnParty(ENFORCERS).build(), "pursuit");
        }
    }

    private record Pair(ServerPlayer player, RoadsideCase scene, Mob opponent) { }

    private Optional<Pair> duel(Hit hit) {
        ServerPlayer owner;
        Mob opponent;
        if (hit.target() instanceof ServerPlayer p && hit.attacker() instanceof Mob m) {
            owner = p;
            opponent = m;
        } else if (hit.attacker() instanceof ServerPlayer p && hit.target() instanceof Mob m) {
            owner = p;
            opponent = m;
        } else {
            return Optional.empty();
        }
        return events.current(owner).filter(c -> c.getKind().equals(KIND) && c.getStage().equals(DUEL)
                && c.getBoutsCount() < THREE
                && opponent.getStringUUID().equals(c.getCastOrDefault(opponent(c), ""))
                && onStage(owner, c) && onStage(opponent, c)).map(c -> new Pair(owner, c, opponent));
    }

    @Override
    public boolean protects(Hit hit) {
        return duel(hit).isPresent() || exhibition(hit).isPresent();
    }

    @Override
    public void spared(Hit hit) {
        duel(hit).ifPresent(pair -> {
            if (hit.target() == pair.player()) {
                defeat(pair.player(), pair.scene(), "injured");
            } else {
                victory(pair.player(), pair.scene(), pair.opponent());
            }
        });
        exhibition(hit).ifPresent(pair -> finishShow(pair.player(), pair.scene(), pair.opponent()));
    }

    private Optional<Pair> exhibition(Hit hit) {
        if (!(hit.target() instanceof Mob loser) || !(hit.attacker() instanceof Mob attacker)
                || !loser.entityTags().contains(MountainEncounters.GUEST)) {
            return Optional.empty();
        }
        return loser.level().getServer().getPlayerList().getPlayers().stream()
                .flatMap(owner -> events.current(owner).filter(c -> c.getStage().equals(SHOW)
                        && c.getCastOrThrow(WHITE).equals(attacker.getStringUUID())
                        && c.getCastOrThrow(OTHER).equals(loser.getStringUUID()))
                        .map(c -> new Pair(owner, c, loser)).stream()).findFirst();
    }

    private void victory(ServerPlayer player, RoadsideCase c, Mob loser) {
        var next = c.toBuilder().addBouts("won/" + loser.getStringUUID()).build();
        if (events.update(player, c, next)) {
            HumanoidVisitors.restrain(loser);
            if (next.getBoutsCount() < THREE) {
                startBout(player, next);
            } else {
                reward(player, next);
            }
        }
    }

    private void reward(ServerPlayer player, RoadsideCase c) {
        if (award(player, c)) {
            boolean witnessed = CrisisEncounters.ringVictory(player, c.getNumber());
            var awarded = c.toBuilder()
                    .setRenowned(witnessed)
                    .setPayment("award")
                    .build();
            end(player, awarded, "won");
        }
    }

    private void defeat(ServerPlayer player, RoadsideCase c, String reason) {
        boolean injured = reason.equals("injured");
        var lost = c.toBuilder()
                .addBouts(reason + "/" + opponent(c))
                .setWounded(injured)
                .build();
        if (injured && c.getRoll() >= NORMAL && c.getBoutsCount() == THIRD) {
            end(player, lost, "hurt");
        } else {
            end(player, lost, "left");
        }
    }

    @Override
    void tick(ServerPlayer player, RoadsideCase c) {
        switch (c.getStage()) {
            case DUEL -> {
                if (c.getBoutsCount() == THREE) {
                    reward(player, c);
                } else {
                    duelTick(player, c);
                }
            }
            case SHOW -> exhibitionTick(player, c);
            case "payout" -> payout(player, c);
            case FRAUD -> expose(player, c);
            default -> { }
        }
    }

    private void duelTick(ServerPlayer player, RoadsideCase c) {
        var opponent = actor(player, c, opponent(c));
        if (opponent.isEmpty() || !onStage(player, c)) {
            defeat(player, c, "left");
        } else if (!onStage(opponent.get(), c)) {
            victory(player, c, opponent.get());
        } else {
            HumanoidVisitors.engage(opponent.get(), player);
        }
    }

    private void exhibitionTick(ServerPlayer player, RoadsideCase c) {
        var white = actor(player, c, WHITE);
        var other = actor(player, c, OTHER);
        if (white.isEmpty() || other.isEmpty()) {
            leave(player, c);
        } else if (player.level().getGameTime() - c.getArrivedAt() >= BOUT_DELAY) {
            if (c.getRoll() >= WHITE_WINS) {
                finishShow(player, c, white.get());
            } else if (!onStage(other.get(), c)) {
                finishShow(player, c, other.get());
            } else {
                HumanoidVisitors.engage(white.get(), other.get());
            }
        }
    }

    private void finishShow(ServerPlayer player, RoadsideCase c, Mob loser) {
        boolean lost = loser.getStringUUID().equals(c.getCastOrThrow(WHITE));
        RingWagers.Result result = lost ? RingWagers.Result.HOUSE : RingWagers.Result.WHITE;
        HumanoidVisitors.restrain(loser);
        var decided = c.toBuilder()
                .setStage("payout")
                .setWinner(result.name())
                .addBouts(result.name())
                .build();
        if (events.update(player, c, decided)) {
            payout(player, decided);
        }
    }

    private void payout(ServerPlayer player, RoadsideCase c) {
        var winner = RingWagers.Result.valueOf(c.getWinner());
        if (RingWagers.settle(player, c, winner)) {
            String ending = winner == RingWagers.Result.WHITE ? "bet_good" : "bet_bad";
            end(player, c, ending);
        }
    }

    @Override
    boolean returnDeposits(ServerPlayer player, RoadsideCase c) {
        return RingWagers.returnDeposit(player, c);
    }

    @Override
    void leave(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals("payout")) {
            payout(player, c);
            return;
        }
        if (RingWagers.settle(player, c, RingWagers.Result.CANCEL)) {
            if (DUEL.equals(c.getStage())) {
                defeat(player, c, "left");
            } else {
                end(player, c, "left");
            }
        }
    }
}
