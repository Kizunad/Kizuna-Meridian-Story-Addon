package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Roadside;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.RoadsideCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

abstract class RoadShow {
    static final String OPEN = MountainEncounters.OPEN;
    static final long REST = 5 * MountainEncounters.DAY;
    static final int DAYLIGHT = 12000;
    final MountainEncounters events;
    final String kind;

    record Role(String name, BlockPos offset, EntityType<? extends Mob> type, HumanoidTier tier) { }

    RoadShow(MountainEncounters events, String kind) {
        this.events = events;
        this.kind = kind;
    }

    abstract List<Role> cast();
    abstract void offer(ServerPlayer player, RoadsideCase c);
    abstract boolean choose(ServerPlayer player, RoadsideCase c, int option);
    abstract void strike(ServerPlayer player, RoadsideCase c, Mob mob);
    abstract void tick(ServerPlayer player, RoadsideCase c);
    abstract void leave(ServerPlayer player, RoadsideCase c);

    boolean open(ServerPlayer player, Roadside drawn, BlockPos center) {
        var level = player.level();
        var venue = RoadVenue.get(level);
        var scene = MountainEncounters.scene(player, drawn, kind, center);
        scene.setArrivedAt(level.getGameTime());
        scene.setBanner(kind);
        String id = scene.getActor();
        if (!venue.place(level, center, kind, id)) {
            return false;
        }
        for (var role : cast()) {
            UUID visit = UUID.randomUUID();
            var name = Component.translatable(key(role.name()));
            var form = new HumanoidVisitors.Guest<>(role.type(), role.tier());
            var actor = HumanoidVisitors.arrive(level, center.offset(role.offset()), visit, name, form);
            if (actor.isEmpty()) {
                dismiss(player, scene.build());
                return false;
            }
            scene.putCast(role.name(), visit.toString());
            MountainEncounters.mark(player, actor.get());
        }
        var c = scene.build();
        var purse = new ScenePurse(player, c);
        var patron = actor(player, c, "patron").orElseThrow();
        var account = purse.money.merchant(patron);
        if (!purse.move("reserve_prize", account, purse.till("prize"), purse.prize())) {
            dismiss(player, c);
            return false;
        }
        if (!reserveDeposits(player, c) || !events.open(player, drawn, c)) {
            returnDeposits(player, c);
            refund(player, c);
            dismiss(player, c);
            return false;
        }
        opened(player, c);
        offer(player, c);
        return true;
    }

    void opened(ServerPlayer player, RoadsideCase c) { }

    boolean reserveDeposits(ServerPlayer player, RoadsideCase c) {
        return true;
    }

    boolean returnDeposits(ServerPlayer player, RoadsideCase c) {
        return true;
    }

    static Optional<Mob> actor(ServerPlayer player, RoadsideCase c, String role) {
        return MountainEncounters.actor(player, c.getCastOrDefault(role, ""));
    }

    static boolean nearby(ServerPlayer player, RoadsideCase c, String role) {
        return actor(player, c, role).filter(mob -> MountainEncounters.near(player, mob)).isPresent();
    }

    String key(String suffix) {
        return MountainEncounters.PREFIX + kind + "." + suffix;
    }

    void button(ServerPlayer player, RoadsideCase c, int index, String label, Component hint) {
        player.sendSystemMessage(Component.empty().append(MountainEncounters.button(c, key(label), index, hint)));
    }

    static BlockPos site(RoadsideCase c) {
        return BlockPos.of(c.getSite());
    }

    void end(ServerPlayer player, RoadsideCase c, String ending) {
        if (!returnDeposits(player, c) || !refund(player, c)) {
            return;
        }
        if (events.end(player, c, ending, c.getReturnParty(), 0)) {
            for (String id : c.getCastMap().values()) {
                MountainEncounters.actor(player, id).ifPresent(mob -> {
                    mob.removeTag(MountainEncounters.GUEST);
                    HumanoidVisitors.withdraw(mob, player.getUUID());
                });
            }
            var level = player.level().getServer().overworld();
            RoadVenue.get(level).release(level, site(c), kind, c.getActor());
        }
    }

    private static boolean refund(ServerPlayer player, RoadsideCase c) {
        var purse = new ScenePurse(player, c);
        if (purse.paid("award") || !purse.paid("reserve_prize")) {
            return true;
        }
        var patron = actor(player, c, "patron");
        if (patron.isEmpty()) {
            // 已故发赏人的预存实物仍留在案号钱匣。
            return true;
        }
        return purse.move("refund_prize", purse.till("prize"), purse.money.merchant(patron.get()), purse.prize());
    }

    static boolean award(ServerPlayer player, RoadsideCase c) {
        var purse = new ScenePurse(player, c);
        return purse.move("award", purse.till("prize"), purse.money.player(player), purse.prize());
    }

    private void dismiss(ServerPlayer player, RoadsideCase c) {
        var level = player.level().getServer().overworld();
        for (String id : c.getCastMap().values()) {
            HumanoidVisitors.depart(level, UUID.fromString(id));
        }
        RoadVenue.get(level).release(level, site(c), kind, c.getActor());
    }
}
