package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.currency.CurrencyMod;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.Price;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.SlainAdult;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

final class FathersDebt {
    static final String KIND = "debt";
    static final String KEY = MountainEncounters.PREFIX + KIND + ".";
    static final String RETURN = "meridian_story:youth_return";
    static final String MASTER = "meridian_story:youth_master";
    static final String RETRY = "meridian_story:youth_retry";
    static final long REST = 8 * MountainEncounters.DAY;
    private static final long AGE = 5 * MountainEncounters.DAY;
    private static final long TRAINING = 30 * MountainEncounters.DAY;
    private static final int PAY_GOOD = 65;
    private static final int TRUTH_GOOD = 60;
    private static final int SILVER_UNITS = 10;
    private static final Price COMPENSATION = new Price(Identifier.parse("meridian_currency:silver"), SILVER_UNITS);
    private static final Identifier PAYMENT = Identifier.parse("meridian_story:compensation");
    private static final int PAY = 0;
    private static final int TRUTH = 1;
    private static final int YIELD_TICKS = 60;
    private static final double YIELD_FRACTION = 0.5;
    private final MountainEncounters events;

    FathersDebt(MountainEncounters events) {
        this.events = events;
    }

    Optional<SlainAdult> father(ServerPlayer player, StoryStore.View view) {
        var road = view.progress().getRoadside();
        long now = view.facts().ticks();
        if (now < road.getRestByKindOrDefault(KIND, 0L)) {
            return Optional.empty();
        }
        return view.crisis().getSlainAdultsList().stream()
                .filter(dead -> dead.getKiller().equals(player.getStringUUID()) && now - dead.getDiedAt() >= AGE)
                .filter(dead -> road.getMountainHistoryMap().values().stream()
                        .noneMatch(c -> c.getVictim().equals(dead.getVictim())))
                .findFirst();
    }

    static Optional<BlockPos> trail(ServerPlayer player) {
        var level = player.level();
        var feet = player.blockPosition();
        var biome = level.getBiome(feet);
        var ground = feet.below();
        if (level.getBlockState(feet).is(Blocks.DIRT_PATH)) {
            ground = feet;
        }
        if (!biome.is(BiomeTags.IS_FOREST) && !biome.is(BiomeTags.IS_MOUNTAIN)
                && !level.getBlockState(ground).is(Blocks.DIRT_PATH)) {
            return Optional.empty();
        }
        return FallenCultivator.site(level, ground.above(), player.getDirection());
    }

    boolean open(ServerPlayer player, Roadside road, BlockPos site, SlainAdult father) {
        var c = MountainEncounters.scene(player, road, KIND, site)
                .setVictim(father.getVictim())
                .setQuotedUnits(SILVER_UNITS)
                .build();
        var youthForm = new HumanoidVisitors.Guest<>(EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI);
        var actor = HumanoidVisitors.arrive(player.level(), site, UUID.fromString(c.getActor()),
                Component.translatable(KEY + "name"), youthForm);
        if (actor.isEmpty()) {
            return false;
        }
        var youth = actor.get();
        youth.addTag(HumanoidVisitors.YOUTH);
        MountainEncounters.mark(player, youth);
        youth.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        if (!events.open(player, road, c)) {
            youth.discard();
            return false;
        }
        offer(player, c);
        return true;
    }

    private Optional<SlainAdult> evidence(ServerPlayer player, RoadsideCase c) {
        return CrisisEncounters.current(player).stream().flatMap(r -> r.getSlainAdultsList().stream())
                .filter(dead -> dead.getVictim().equals(c.getVictim()))
                .filter(dead -> dead.getKiller().equals(player.getStringUUID()))
                .findFirst();
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        var found = evidence(player, c);
        if (found.isEmpty() || !c.getStage().equals(MountainEncounters.OPEN)) {
            return;
        }
        var father = found.get();
        Component name = Component.translatable(father.getTypeName());
        if (!father.getName().isEmpty()) {
            name = Component.literal(father.getName());
        }
        player.sendSystemMessage(Component.translatable(KEY + "opening", name));
        var cost = Component.translatable(KEY + "pay.cost", CurrencyMod.catalog().format(COMPENSATION));
        var row = Component.empty().append(MountainEncounters.button(c, KEY + "pay", PAY, cost));
        row.append("  ").append(Component.translatable(KEY + "fight"));
        if (!c.getAccountHeard() && father.getFirstStrike() == SlainAdult.FirstStrike.VICTIM) {
            row.append("  ").append(MountainEncounters.button(c, KEY + "truth", TRUTH,
                    Component.translatable(KEY + "truth.cost")));
        }
        player.sendSystemMessage(row);
    }

    Optional<Component> choose(ServerPlayer player, RoadsideCase c, int option) {
        var actor = MountainEncounters.actor(player, c.getActor()).filter(mob -> MountainEncounters.near(player, mob));
        if (!c.getStage().equals(MountainEncounters.OPEN) || actor.isEmpty()) {
            return Optional.empty();
        }
        if (option == PAY) {
            var money = CurrencyService.get(player.level().getServer());
            var id = payment(player, c);
            try {
                if (money.receipt(id).isEmpty()) {
                    money.settle(id, money.quote(money.player(player), money.merchant(actor.get()), COMPENSATION));
                }
            } catch (TradeRejected rejected) {
                return Optional.of(Component.translatable(KEY + "unpaid"));
            }
            paid(player, c, id);
        } else if (option == TRUTH && !c.getAccountHeard() && evidence(player, c)
                .filter(dead -> dead.getFirstStrike() == SlainAdult.FirstStrike.VICTIM).isPresent()) {
            var heard = c.toBuilder().setAccountHeard(true /* heard */).build();
            if (c.getRoll() < TRUTH_GOOD) {
                events.end(player, heard, "truth_good", "", 0);
            } else if (events.update(player, c, heard)) {
                MountainEncounters.narrate(player, c, "truth_bad");
                offer(player, heard);
            }
        } else {
            return Optional.empty();
        }
        return Optional.of(Component.empty());
    }

    private static UUID payment(ServerPlayer player, RoadsideCase c) {
        return CurrencyService.operationId(player.getUUID(), UUID.fromString(c.getActor()), PAYMENT, 1);
    }

    private void paid(ServerPlayer player, RoadsideCase c, UUID id) {
        var paid = c.toBuilder().setPayment(id.toString()).build();
        if (c.getRoll() < PAY_GOOD) {
            events.end(player, paid, "paid_good", "", 0);
        } else {
            events.end(player, paid, "paid_bad", RETURN, TRAINING);
        }
    }

    void strike(ServerPlayer player, RoadsideCase c, Mob youth) {
        var next = c.toBuilder()
                .setStage("fight")
                .setLastStrike(player.level().getGameTime())
                .build();
        if (events.update(player, c, next)) {
            youth.setPose(Pose.STANDING);
            MountainEncounters.fight(player, youth);
        }
    }

    void hurt(ServerPlayer player, RoadsideCase c, Mob youth, BodySnapshot body) {
        if (c.getKind().equals(KIND) && c.getStage().equals("fight")
                && c.getLastStrike() == player.level().getGameTime() && losingGrip(body)) {
            var next = c.toBuilder().setStage("down").build();
            if (events.update(player, c, next)) {
                youth.getNavigation().stop();
                youth.setTarget(null);
                youth.setNoAi(true /* noAi */);
                youth.setPose(Pose.SITTING);
            }
        }
    }

    private static boolean losingGrip(BodySnapshot body) {
        // 以实际部位伤势判定握刀不稳或站立困难；身体受伤后原版血条仍可能是满的。
        for (var part : body.getStructure().getPartsList()) {
            String id = part.getId();
            if (id.endsWith("forearm") || id.endsWith("hand") || id.endsWith("leg") || id.endsWith("foot")) {
                double threshold = part.getMaxHp() * YIELD_FRACTION;
                if (body.getPartStatesList().stream().anyMatch(s -> s.getId().equals(id) && s.getHp() <= threshold)) {
                    return true;
                }
            }
        }
        return false;
    }

    void died(ServerPlayer player, RoadsideCase c, Entity killer) {
        if (killer == player) {
            long delay = RoadsideRules.inquiryDelay(c.getRoll(), MountainEncounters.DAY);
            events.end(player, c, "killed", MASTER, delay);
        } else {
            events.end(player, c, "gone", "", 0);
        }
    }

    void separated(ServerPlayer player, RoadsideCase c) {
        events.end(player, c, "separated", RETRY, REST);
    }

    void tick(ServerPlayer player, RoadsideCase c) {
        if (c.getStage().equals(MountainEncounters.OPEN)) {
            UUID id = payment(player, c);
            if (CurrencyService.get(player.level().getServer()).receipt(id).isPresent()) {
                paid(player, c, id);
                return;
            }
        }
        var actor = MountainEncounters.actor(player, c.getActor());
        if (actor.isEmpty() && player.level().isLoaded(BlockPos.of(c.getSite()))) {
            events.end(player, c, "gone", "", 0);
        } else if (actor.isPresent() && c.getStage().equals("down")
                && player.level().getGameTime() - c.getLastStrike() >= YIELD_TICKS) {
            events.end(player, c, "spared", RETURN, REST);
        }
    }
}
