package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.human.fabric.HumanInheritance;
import dev.kizuna.meridian.humanoid.fabric.HumanoidDonations;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.story.fabric.BequestGoods.Gift;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Bequest;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import dev.kizuna.meridian.story.server.RoadsideRules;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

final class LastRequest {
    static final String KIND = "bequest";
    static final String KEY = MountainEncounters.PREFIX + KIND + ".";
    static final int ACCEPT = 0;
    static final int DECLINE = 1;
    static final int DELIVER = 2;
    static final int REPLACE = 3;
    static final int STUDY = 4;
    static final int DUSK = 12000;
    static final int NIGHT = 16000;
    static final long REST = 8 * MountainEncounters.DAY;
    static final long TERM = 5 * MountainEncounters.DAY;
    static final long RETURN = 3 * MountainEncounters.DAY;
    static final String FUNDED = "receiving";
    static final String ENEMY = "meridian_story:bequest_enemy";
    static final String ACCUSER = "meridian_story:bequest_accuser";
    private static final String TAG = "meridian_story.bequest_actor";
    private static final int PEACEFUL = 65;
    private static final int NOTES = 60;
    private static final int LOOT = 50;
    private static final int CHEST_DAMAGE = 4;
    private final MountainEncounters events;
    private final Set<UUID> receiving = new HashSet<>();
    final BequestPromise promise;

    LastRequest(MountainEncounters events, StoryStore store) {
        this.events = events;
        promise = new BequestPromise(store);
    }

    static Component text(String suffix, Object... args) {
        return Component.translatable(KEY + suffix, args);
    }

    void register() {
        promise.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((actor, level) -> {
            if (actor.entityTags().contains(TAG)) {
                MountainRetreats.get(level).loaded(level, actor.getUUID());
            }
        });
        UseEntityCallback.EVENT.register((user, level, hand, target, hit) -> {
            if (!(target instanceof Mob mob) || !mob.entityTags().contains(TAG)) {
                return InteractionResult.PASS;
            }
            if (user instanceof ServerPlayer player && player.isShiftKeyDown()
                    && MountainEncounters.near(player, mob)) {
                events.current(player).filter(c -> c.getActor().equals(mob.getStringUUID()))
                        .ifPresent(c -> offer(player, c));
                promise.atDisciple(player, mob);
            }
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((user, level, hand, target, hit) -> {
            if (!(target instanceof Mob mob) || !mob.entityTags().contains(TAG)) {
                return InteractionResult.PASS;
            }
            if (user instanceof ServerPlayer player && MountainEncounters.near(player, mob)) {
                attack(player, mob);
            }
            return InteractionResult.SUCCESS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!entity.entityTags().contains(TAG)) {
                return true;
            }
            if (entity instanceof Mob mob && source.getEntity() instanceof ServerPlayer player
                    && source.getDirectEntity() instanceof Projectile arrow && arrow.getOwner() == player) {
                attack(player, mob);
            }
            return false;
        });
    }

    boolean approach(ServerPlayer player, StoryStore.View view) {
        var level = player.level();
        var time = Math.floorMod(level.getOverworldClockTime(), MountainEncounters.DAY);
        if (time < DUSK || time >= NIGHT || view.facts().ticks()
                < view.progress().getRoadside().getRestByKindOrDefault(KIND, 0L)) {
            return false;
        }
        var retreats = MountainRetreats.get(level);
        var feet = player.blockPosition();
        var nearby = retreats.nearby(feet);
        if (nearby.isEmpty()) {
            if (RoadsideRules.opens(FallenCultivator.seed(player), FallenCultivator.tile(feet))
                    && FateSites.gate(player).isPresent()) {
                retreats.search(player);
            }
            return false;
        }
        var site = nearby.get();
        if (!site.caseId().isEmpty() || !site.at(MountainRetreats.ELDER).closerThan(feet, MountainEncounters.REACH)
                || !site.walkable(level)) {
            return false;
        }
        return FateSites.gate(player).flatMap(gate -> FateSites.feet(level, gate))
                .filter(gate -> MountainRetreats.reachable(level, site.mouth(), gate))
                .map(gate -> open(player, view, site, gate)).orElse(false);
    }

    private boolean open(ServerPlayer player, StoryStore.View view, MountainRetreats.Site site, BlockPos gate) {
        var road = view.progress().getRoadside();
        var terms = Bequest.newBuilder()
                .setGate(gate.asLong())
                .setSerial(1)
                .build();
        var c = MountainEncounters.scene(player, road, KIND, site.mouth())
                .setDisciple(UUID.randomUUID().toString())
                .setBequest(terms)
                .build();
        var level = player.level();
        var elderId = UUID.fromString(c.getActor());
        var discipleId = UUID.fromString(c.getDisciple());
        var form = new HumanoidVisitors.Guest<>(EntityTypes.VILLAGER, HumanoidTier.TRUE_QI);
        var elder = HumanoidVisitors.arrive(level, site.at(MountainRetreats.ELDER), elderId, text("elder"), form);
        if (elder.isEmpty()) {
            return false;
        }
        var host = elder.get();
        var disciple = HumanoidVisitors.arrive(level, gate, discipleId, text("disciple"));
        if (disciple.isEmpty() || !BequestGoods.stock(host, c) || !events.open(player, road, c)) {
            HumanoidVisitors.depart(level, elderId);
            HumanoidVisitors.depart(level, discipleId);
            return false;
        }
        host.addTag(TAG);
        host.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                net.minecraft.world.phys.Vec3.atCenterOf(site.mouth().above()));
        host.setYHeadRot(host.getYRot());
        host.setYBodyRot(host.getYRot());
        disciple.get().addTag(TAG);
        MountainRetreats.get(level).assign(site, c.getActor());
        offer(player, c);
        return true;
    }

    static Optional<Mob> elder(ServerPlayer player, RoadsideCase c) {
        return MountainEncounters.actor(player, c.getActor());
    }

    static boolean eligible(ServerPlayer player, RoadsideCase c) {
        var operation = UUID.fromString(c.getActor());
        var jade = Gift.JADE.stack(c);
        return elder(player, c).filter(actor -> BequestGoods.carries(actor, jade)).isPresent()
                && HumanInheritance.preview(player, operation, new HumanoidDonations(operation)).isPresent();
    }

    void offer(ServerPlayer player, RoadsideCase c) {
        if (!c.getStage().equals(MountainEncounters.OPEN)) {
            return;
        }
        StoryChat.narrate(player, KEY + "opening");
        var choices = Component.empty();
        if (eligible(player, c)) {
            var gate = BlockPos.of(c.getBequest().getGate());
            choices.append(MountainEncounters.button(c, KEY + "accept", ACCEPT,
                    text("accept.cost", gate.toShortString()))).append("  ");
        }
        choices.append(MountainEncounters.button(c, KEY + "decline", DECLINE, text("decline.cost")));
        player.sendSystemMessage(choices);
        player.sendSystemMessage(text("attack"));
    }

    Optional<Component> choose(ServerPlayer player, RoadsideCase c, int option) {
        var elder = elder(player, c).filter(actor -> MountainEncounters.near(player, actor));
        if (player.isCreative() || elder.isEmpty() || !c.getStage().equals(MountainEncounters.OPEN)) {
            return Optional.empty();
        }
        if (option == ACCEPT && eligible(player, c) && player.getInventory().getFreeSlot() >= 0) {
            var pending = c.toBuilder().setStage(FUNDED).build();
            if (events.update(player, c, pending)) {
                receive(player, pending);
                return Optional.of(Component.empty());
            }
        } else if (option == DECLINE && decline(player, c, elder.get())) {
            return Optional.of(Component.empty());
        }
        return Optional.empty();
    }

    private void receive(ServerPlayer player, RoadsideCase c) {
        UUID id = UUID.fromString(c.getActor());
        if (!receiving.add(id)) {
            return;
        }
        var donor = new HumanoidDonations(id);
        var server = player.level().getServer();
        HumanInheritance.accept(player, id, donor).whenCompleteAsync((receipt, error) -> {
            receiving.remove(id);
            if (error != null || server.getPlayerList().getPlayer(player.getUUID()) != player
                    || events.current(player).filter(c::equals).isEmpty()) {
                return;
            }
            if (receipt.isEmpty()) {
                if (elder(player, c).isPresent() && donor.lookup(id)
                        .filter(dev.kizuna.meridian.server.BodyExports::pending).isEmpty()) {
                    events.update(player, c, c.toBuilder().setStage(MountainEncounters.OPEN).build());
                }
                return;
            }
            var paid = receipt.get();
            var terms = c.getBequest().toBuilder()
                    .setMeridian(paid.getTarget())
                    .setCreditedQi(paid.getCreditedQi())
                    .setFundingDigest(paid.getFundingDigest())
                    .build();
            var next = c.toBuilder()
                    .setStage("paid")
                    .setBequest(terms)
                    .build();
            if (events.update(player, c, next)) {
                handOver(player, next);
            }
        }, server);
    }

    private void handOver(ServerPlayer player, RoadsideCase c) {
        var jade = Gift.JADE.stack(c);
        var elder = elder(player, c).filter(actor -> MountainEncounters.near(player, actor));
        if (BequestGoods.slot(player, jade) < 0 && (elder.isEmpty() || !BequestGoods.give(player, elder.get(), jade))) {
            return;
        }
        String ending = "accepted";
        String party = "";
        if (c.getRoll() >= PEACEFUL) {
            ending = "marked";
            party = ENEMY;
        }
        events.end(player, c, ending, party, 0);
    }

    boolean tick(ServerPlayer player, RoadsideCase c) {
        switch (c.getStage()) {
            case FUNDED -> receive(player, c);
            case "paid" -> handOver(player, c);
            default -> {
                if (player.level().dimension() != Level.OVERWORLD || !player.isAlive()
                        || !BlockPos.of(c.getSite()).closerThan(player.blockPosition(), MountainEncounters.AWAY)) {
                    events.end(player, c, "gone", "", 0);
                }
            }
        }
        return true;
    }

    private boolean decline(ServerPlayer player, RoadsideCase c, Mob elder) {
        if (c.getRoll() < NOTES) {
            return BequestGoods.give(player, elder, Gift.NOTES.stack(c)) && events.end(player, c, "notes_given", "", 0);
        }
        var stack = BequestGoods.take(elder, Gift.NOTES.stack(c));
        if (stack.isEmpty()) {
            return false;
        }
        var note = elder.spawnAtLocation(player.level(), stack);
        if (note == null) {
            dev.kizuna.meridian.inventory.MobInventories.open(elder).insert(stack);
            return false;
        }
        var site = MountainRetreats.get(player.level()).site(c.getSite());
        if (site != null) {
            note.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(site.at(MountainRetreats.TABLE).above()));
            note.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
        note.setNeverPickUp();
        note.setUnlimitedLifetime();
        var left = c.toBuilder().setGiftItem(note.getStringUUID()).build();
        return events.end(player, left, "notes_left", "", RETURN);
    }

    private void attack(ServerPlayer player, Mob mob) {
        if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
            return;
        }
        events.current(player).filter(c -> KIND.equals(c.getKind()) && c.getActor().equals(mob.getStringUUID())
                && MountainEncounters.OPEN.equals(c.getStage())).ifPresent(c -> {
            if (c.getRoll() < LOOT) {
                if (BequestGoods.give(player, mob, Gift.BUNDLE.stack(c))) {
                    events.end(player, c, "looted", "", 0);
                }
            } else {
                var hit = new VanillaDamageFabric.DirectDamage(player, player.damageSources().mobAttack(mob),
                        "chest", CHEST_DAMAGE);
                var result = VanillaDamageFabric.damageDirect(hit);
                if (result.isPresent() && result.get().health().getDamageResolution().getLossesCount() > 0) {
                    events.end(player, c, "struck", "", 0);
                }
            }
        });
    }

    void close(ServerPlayer player, RoadsideCase c) {
        if (c.getBequest().getDue() > 0) {
            promise.retirePaid(player, c);
            return;
        }
        retire(player, c);
    }

    static void retire(ServerPlayer player, RoadsideCase c) {
        var level = player.level().getServer().overworld();
        var sites = MountainRetreats.get(level);
        sites.depart(level, UUID.fromString(c.getActor()));
        if (c.getBequest().getDue() == 0 && !c.getEnding().equals("notes_left")) {
            sites.depart(level, UUID.fromString(c.getDisciple()));
        }
        if (!c.getEnding().equals("notes_left")) {
            MountainRetreats.get(level).release(c.getSite(), c.getActor());
        }
    }
}
