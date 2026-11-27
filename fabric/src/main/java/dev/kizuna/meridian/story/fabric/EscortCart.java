package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;

final class EscortCart extends RoadShow {
    static final String KIND = "escort_cart";
    static final String WARRANT = "meridian_story:convoy_warrant";
    static final long WARRANT_INTERVAL = 2 * MountainEncounters.DAY;
    static final String GUARDS = "guards";
    static final String BANDITS = "bandits";
    static final String WATCH = "watch";
    static final String CLAIM = "claim";
    static final int WAIT = 1;
    static final int RETURN = 2;
    private static final int THANKS = 65;
    private static final int SHARE = 50;
    private static final int FOUND = 40;
    private static final int WARRANTS = 2;
    private static final int BANK_RADIUS = 3;
    private static final int RETREAT = 14;
    private static final int ROUT = 2;
    private static final int START_DELAY = 80;
    private static final int FORK_REACH = 3;
    private static final List<String> DEFENDERS = List.of("patron", "guard");
    private static final List<String> RAIDERS = List.of("chief", "bandit");
    private static final List<Role> CAST = List.of(
            new Role("patron", new BlockPos(2, 0, 0), EntityTypes.WANDERING_TRADER, HumanoidTier.TRUE_QI),
            new Role("guard", new BlockPos(2, 0, 2), EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI),
            new Role("chief", new BlockPos(-2, 0, -1), EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI),
            new Role("bandit", new BlockPos(-2, 0, 1), EntityTypes.PILLAGER, HumanoidTier.MORTAL));

    EscortCart(MountainEncounters events) {
        super(events, KIND);
    }

    @Override
    List<Role> cast() {
        return CAST;
    }

    boolean escort(ServerPlayer player, Roadside drawn, BlockPos center) {
        var favor = drawn.getMountainHistoryMap().values().stream().filter(c -> c.getEscortFavor()
                && c.getBanner().equals(KIND) && c.getSite() != center.asLong()).findFirst();
        if (favor.isEmpty()) {
            return false;
        }
        UUID visit = UUID.randomUUID();
        var c = MountainEncounters.scene(player, drawn, KIND, center)
                .setStage("escort")
                .setBanner(KIND)
                .putCast("guard", visit.toString())
                .build();
        var level = player.level();
        var venue = RoadVenue.get(level);
        if (!venue.place(level, center, KIND, c.getActor())) {
            return false;
        }
        var guest = HumanoidVisitors.arrive(level, center.offset(RoadVenue.BANK), visit,
                Component.translatable(key("guard")));
        if (guest.isEmpty() || !events.open(player, drawn, c, favor)) {
            HumanoidVisitors.depart(level, visit);
            venue.release(level, center, KIND, c.getActor());
            return false;
        }
        MountainEncounters.mark(player, guest.get());
        MountainEncounters.narrate(player, c, "favor");
        return true;
    }

    private void accompany(ServerPlayer player, RoadsideCase c) {
        var guard = actor(player, c, "guard");
        if (guard.isEmpty()) {
            leave(player, c);
            return;
        }
        var mob = guard.get();
        mob.setNoAi(false /* noAi */);
        mob.getNavigation().moveTo(player, 1);
        var fork = site(c).offset(RoadVenue.FORK);
        if (player.blockPosition().distSqr(fork) <= FORK_REACH * FORK_REACH && MountainEncounters.near(player, mob)) {
            end(player, c, "fork");
        }
    }

    static ItemStack crate(RoadsideCase c) {
        var stack = new ItemStack(Items.CHEST);
        var tag = new CompoundTag();
        tag.putString("meridian_story:crate", c.getActor());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(MountainEncounters.PREFIX + KIND + ".crate"));
        return stack;
    }

    @Override
    void opened(ServerPlayer player, RoadsideCase c) {
        if (player.level().getBlockEntity(site(c).offset(RoadVenue.CRATE)) instanceof Container box) {
            box.setItem(0, crate(c));
            box.setChanged();
        }
    }

    @Override
    void offer(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals(CLAIM)) {
            button(player, c, RETURN, "return", Component.translatable(key("return.cost"), crate(c).getHoverName()));
        } else if (c.getStage().equals(OPEN)) {
            MountainEncounters.narrate(player, c, "opening");
            button(player, c, WAIT, WATCH, Component.translatable(key("watch.cost")));
        }
    }

    @Override
    boolean choose(ServerPlayer player, RoadsideCase c, int option) {
        if (option == WAIT && c.getStage().equals(OPEN) && onBank(player, c)) {
            return events.update(player, c, c.toBuilder().setStage(WATCH).build());
        }
        if (option == RETURN && c.getStage().equals(CLAIM) && nearby(player, c, "owner") && matchingBill(player, c)) {
            return deliver(player, c, "owner", "returned");
        }
        return false;
    }

    private static boolean onBank(ServerPlayer player, RoadsideCase c) {
        return player.blockPosition().distSqr(site(c).offset(RoadVenue.BANK)) <= BANK_RADIUS * BANK_RADIUS;
    }

    private static boolean matchingBill(ServerPlayer player, RoadsideCase c) {
        var mark = crate(c).get(DataComponents.CUSTOM_DATA);
        return actor(player, c, "owner").map(Mob::getOffhandItem)
                .filter(bill -> bill.is(Items.PAPER) && mark.equals(bill.get(DataComponents.CUSTOM_DATA))).isPresent();
    }

    @Override
    void strike(ServerPlayer player, RoadsideCase c, Mob mob) {
        if (!c.getStage().equals(OPEN)) {
            return;
        }
        String id = mob.getStringUUID();
        String side;
        if (DEFENDERS.stream().anyMatch(role -> id.equals(c.getCastOrDefault(role, "")))) {
            side = BANDITS;
        } else if (RAIDERS.stream().anyMatch(role -> id.equals(c.getCastOrDefault(role, "")))) {
            side = GUARDS;
        } else {
            return;
        }
        var chosen = c.toBuilder()
                .setFirstTarget(id)
                .setStage(side)
                .build();
        events.update(player, c, chosen);
    }

    @Override
    void tick(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals("escort")) {
            accompany(player, c);
            return;
        }
        if (c.getStage().equals(CLAIM)) {
            if (actor(player, c, "owner").isEmpty() || !hasCargo(player, c)) {
                end(player, c, "withdraw");
            }
            return;
        }
        if (c.getStage().equals("pursued")) {
            var pursuers = standing(player, c, team(c.getWinner()));
            pursuers.forEach(mob -> HumanoidVisitors.engage(mob, player));
            if (pursuers.isEmpty()) {
                end(player, c, "withdraw");
            }
            return;
        }
        if (c.getStage().equals(WATCH) && !onBank(player, c)) {
            leave(player, c);
            return;
        }
        var guards = standing(player, c, DEFENDERS);
        var bandits = standing(player, c, RAIDERS);
        if (!guards.isEmpty() && !bandits.isEmpty()) {
            if (!c.getStage().equals(OPEN) || player.level().getGameTime() - c.getArrivedAt() >= START_DELAY) {
                engage(guards, bandits, c.getStage().equals(BANDITS) ? player : null);
                engage(bandits, guards, c.getStage().equals(GUARDS) ? player : null);
            }
            return;
        }
        if (guards.isEmpty() && bandits.isEmpty()) {
            leave(player, c);
            return;
        }
        String winner = guards.isEmpty() ? BANDITS : GUARDS;
        settle(player, c, winner);
    }

    private static void engage(List<Mob> team, List<Mob> rivals, ServerPlayer enemyPlayer) {
        for (var mob : team) {
            var nearest = rivals.stream().min(Comparator.comparingDouble(rival -> mob.distanceTo(rival)))
                    .orElseThrow();
            if (enemyPlayer != null && mob.distanceTo(enemyPlayer) < mob.distanceTo(nearest)) {
                HumanoidVisitors.engage(mob, enemyPlayer);
            } else {
                HumanoidVisitors.engage(mob, nearest);
            }
        }
    }

    private static List<String> team(String side) {
        return side.equals(GUARDS) ? DEFENDERS : RAIDERS;
    }

    private static List<Mob> standing(ServerPlayer player, RoadsideCase c, List<String> roles) {
        return roles.stream().flatMap(role -> actor(player, c, role).stream())
                .filter(mob -> mob.getHealth() > ROUT && mob.blockPosition().distSqr(site(c)) < RETREAT * RETREAT)
                .toList();
    }

    private void settle(ServerPlayer player, RoadsideCase c, String winner) {
        var won = c.toBuilder().setWinner(winner).build();
        if (c.getStage().equals(WATCH)) {
            if (c.getRoll() < FOUND) {
                deliver(player, won, player.getStringUUID(), "salvage");
            } else {
                var chased = won.toBuilder().setStage("pursued").build();
                if (events.update(player, c, chased)) {
                    MountainEncounters.narrate(player, chased, "spotted");
                }
            }
        } else if (!winner.equals(c.getStage())) {
            leave(player, won);
        } else if (winner.equals(GUARDS)) {
            defend(player, c, won);
        } else if (c.getRoll() < SHARE) {
            deliver(player, won, player.getStringUUID(), "share");
        } else {
            var notice = won.toBuilder()
                    .setWarrants(WARRANTS)
                    .setReturnParty(WARRANT)
                    .build();
            var pos = site(c).offset(RoadVenue.FORK);
            if (player.level().getBlockState(pos).canBeReplaced() && player.level().setBlockAndUpdate(pos,
                    Blocks.OAK_SIGN.defaultBlockState())
                    && player.level().getBlockEntity(pos) instanceof SignBlockEntity sign) {
                sign.updateText(text -> text.setMessage(0, Component.translatable(key("notice"))), true /* front */);
                end(player, notice, "wanted");
            }
        }
    }

    private void defend(ServerPlayer player, RoadsideCase before, RoadsideCase won) {
        if (won.getRoll() < THANKS) {
            boolean present = actor(player, won, "patron").isPresent();
            if (present && award(player, won)) {
                var paid = won.toBuilder()
                        .setEscortFavor(true /* escortFavor */)
                        .setPayment("award")
                        .build();
                end(player, paid, "paid");
            } else if (present) {
                // 行囊没有收款空间时，赏银仍在本案钱匣。
                return;
            } else {
                leave(player, won);
            }
            return;
        }
        UUID visit = UUID.randomUUID();
        var owner = HumanoidVisitors.arrive(player.level(), site(won).offset(RoadVenue.FORK), visit,
                Component.translatable(key("owner")));
        if (owner.isPresent()) {
            MountainEncounters.mark(player, owner.get());
            var bill = new ItemStack(Items.PAPER);
            bill.set(DataComponents.CUSTOM_DATA, crate(won).get(DataComponents.CUSTOM_DATA));
            bill.set(DataComponents.CUSTOM_NAME, Component.translatable(key("bill")));
            owner.get().setItemSlot(EquipmentSlot.OFFHAND, bill);
            var claim = won.toBuilder()
                    .putCast("owner", visit.toString())
                    .setStage(CLAIM)
                    .build();
            if (events.update(player, before, claim)) {
                for (String id : before.getCastMap().values()) {
                    MountainEncounters.actor(player, id).ifPresent(HumanoidVisitors::restrain);
                }
                MountainEncounters.narrate(player, claim, "claimed");
                offer(player, claim);
            } else {
                HumanoidVisitors.depart(player.level(), visit);
            }
        }
    }

    private boolean hasCargo(ServerPlayer player, RoadsideCase c) {
        return player.level().getBlockEntity(site(c).offset(RoadVenue.CRATE)) instanceof Container box
                && ItemStack.isSameItemSameComponents(crate(c), box.getItem(0));
    }

    private boolean deliver(ServerPlayer player, RoadsideCase c, String holder, String ending) {
        if (!c.getCrateHolder().isEmpty() || !(player.level().getBlockEntity(site(c).offset(RoadVenue.CRATE))
                instanceof Container box) || !ItemStack.isSameItemSameComponents(crate(c), box.getItem(0))) {
            end(player, c, "withdraw");
            return false;
        }
        var transferred = c.toBuilder().setCrateHolder(holder).build();
        var current = events.current(player);
        if (current.isEmpty() || !current.get().getActor().equals(c.getActor())
                || !current.get().getCrateHolder().isEmpty() || !events.update(player, current.get(), transferred)) {
            return false;
        }
        var goods = box.removeItem(0, 1);
        box.setChanged();
        if (holder.equals("owner")) {
            var owner = actor(player, c, holder).orElseThrow();
            MobInventories.open(owner).insert(goods);
            if (!goods.isEmpty()) {
                owner.spawnAtLocation(player.level(), goods);
            }
        } else if (!player.getInventory().add(goods)) {
            player.drop(goods, false /* random velocity */);
        }
        end(player, transferred, ending);
        return true;
    }

    @Override
    void leave(ServerPlayer player, RoadsideCase c) {
        if (!c.getStage().equals(CLAIM)) {
            end(player, c, "withdraw");
        }
    }
}
