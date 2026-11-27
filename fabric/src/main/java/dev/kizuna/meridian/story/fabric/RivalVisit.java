package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeech;
import dev.kizuna.meridian.humanoid.fabric.HumanoidVisitors;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.Progress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 陈恪来冢前探墓：守墓人首次祭扫过、学会月弧轻撩和连珠两剑后，下一次回冢到场就见到他，不走冢边来客的抽签。
 * 约剑走原版聊天按钮；应约由服务端复核后交给比试，散场写冷却、破架写「已赢待传」。
 * 胜后他接着问学不学《寻隙剑谱》；改日、退出或有式境界不到时，往后回冢他再来接着教，不用再比。
 */
final class RivalVisit {
    private static final String PREFIX = "story.meridian_story.rival.";
    private static final int ACCEPT = 0;
    private static final int DECLINE = 1;
    /** 约剑和口授的邀请各用一个修订号：胜后留在聊天栏里的约剑按钮按过期处理。 */
    private static final long DUEL = 1;
    private static final long LESSON = 2;
    /** 认招后停这么多刻再问学不学，头顶那句认招先让人看完。 */
    private static final int ASK_DELAY = 60;
    private final StoryStore store;
    private final Map<UUID, Arrival> arrivals = new HashMap<>();
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Set<UUID> summoned = new HashSet<>();
    private final Map<UUID, Long> recognized = new HashMap<>();

    /** 本次回冢开头的判定：来没来、婉拒过没有。整个逗留期都按这一次算。 */
    private record Arrival(long enteredAt, boolean due) { }

    /** 发给本人的当前邀请：票据和它属于约剑还是口授。 */
    private record Offer(String nonce, long revision) { }

    RivalVisit(StoryStore store) {
        this.store = store;
        RivalDuel.ENDED.register(this::ended);
    }

    /** 调试入口 /meridian debug rival summon [玩家]：跳过来访条件，本人在冢前时请他到场，复核与比试照常。 */
    void registerCommand() {
        MeridianCommands.register(MeridianCommands.Branch.DEBUG, "rival", MeridianCommands.GAMEMASTER,
                (node, registry) -> {
                    var summon = Commands.literal("summon");
                    MeridianCommands.optionalPlayers(summon, (parent, targets) -> parent.executes(
                            command -> summon(command.getSource(), targets.resolve(command))));
                    node.then(summon);
                });
    }

    private int summon(CommandSourceStack source, Collection<ServerPlayer> players) {
        for (var player : players) {
            summoned.add(player.getUUID());
        }
        source.sendSuccess(() -> Component.literal("陈恪会在本人到冢前、空坪空着时到场：" + players.size() + " 人"),
                false /* broadcastToOps */);
        return players.size();
    }

    /** 冢边抽签之前调用：陈恪这次在场就返回真，空坪由他占用。 */
    boolean visit(ServerPlayer player, StoryStore.View view, GraveSite site, long enteredAt) {
        UUID owner = player.getUUID();
        if (!arrival(player, view, enteredAt).due()) {
            return false;
        }
        var level = (ServerLevel) player.level();
        UUID guest = guest(owner);
        if (!site.receive(level, owner, guest)) {
            return false;
        }
        // 调试请来的那一次等到空坪空出来才算数。
        summoned.remove(owner);
        boolean present = level.getEntity(guest) != null;
        var arrived = arrive(level, site.guest(), guest);
        if (arrived.isEmpty()) {
            return true;
        }
        var rival = arrived.orElseThrow();
        if (!present) {
            greet(player, rival, view);
        } else if (!RivalDuel.engaged(guest)) {
            RivalDuel.face(rival, player);
            // 同一次回冢里刚破了架：认招说完，他接着问学不学。
            if (RivalLessons.owed(view) && !asked(owner) && paused(owner, level.getGameTime())) {
                recognized.remove(owner);
                ask(player, rival, "teach_offer");
            }
        }
        return true;
    }

    private Arrival arrival(ServerPlayer player, StoryStore.View view, long enteredAt) {
        // 新的一次回冢：上次的邀请作废，来不来按这次开头算；调试请来的这次照来。
        UUID owner = player.getUUID();
        var arrival = arrivals.get(owner);
        if (arrival == null || arrival.enteredAt() != enteredAt) {
            offers.remove(owner);
            arrival = new Arrival(enteredAt, due(player, view));
        }
        if (summoned.contains(owner)) {
            arrival = new Arrival(enteredAt, true /* due */);
        }
        arrivals.put(owner, arrival);
        return arrival;
    }

    private static boolean due(ServerPlayer player, StoryStore.View view) {
        // 旧识的见面挂着时让给旧识。赢过的人有式待传他就来接着教；
        // 没赢的首次祭扫过、已会月弧轻撩和连珠两剑才来约剑，散场当天不再来。
        GraveKeeper grave = view.progress().getGrave();
        var meeting = view.progress().getLinesMap().get(GraveVisits.LINE);
        boolean friendWaiting = grave.getLot().equals("friend") && meeting != null && meeting.getEnding().isEmpty();
        if (friendWaiting) {
            return false;
        }
        if (grave.getRivalWon()) {
            return RivalLessons.owed(view);
        }
        long now = view.facts().ticks();
        boolean resting = grave.hasRivalSetback() && now - grave.getRivalSetback() < CrisisRules.DAY_TICKS;
        return grave.getFirstSwept() && !resting && ready(player);
    }

    static boolean ready(ServerPlayer player) {
        var known = TechniqueFabric.service().known(player.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .toList();
        return known.contains(YunxiCombo.LIFT) && known.contains(YunxiCombo.TWIN);
    }

    /** 陈恪的身份按守墓人固定，重登后在场的同一人能重新绑定。 */
    static UUID guest(UUID owner) {
        String id = RivalSwordsman.ID + "/" + owner;
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
    }

    /** 在落脚点请来陈恪（已在场则沿用），学会他的守架并让他持剑。 */
    static Optional<RivalSwordsman> arrive(ServerLevel level, BlockPos feet, UUID visit) {
        var name = Component.translatable(PREFIX + "name");
        var rival = HumanoidVisitors.arrive(level, feet, visit, name, RivalSwordsman.type());
        rival.ifPresent(RivalVisit::equip);
        return rival;
    }

    private static void equip(Mob rival) {
        // 学式要读身体，第一次读会按物种出生身体；必须持兵器，徒手格挡几下就把前臂打废。
        var service = TechniqueFabric.service();
        boolean known = service.known(rival.getUUID()).stream()
                .anyMatch(entry -> entry.technique().getId().equals(RivalDuel.GUARD));
        if (!known) {
            service.learn(rival.getUUID(), RivalDuel.GUARD);
        }
        if (!rival.getMainHandItem().is(Items.IRON_SWORD)) {
            rival.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }
    }

    private void greet(ServerPlayer player, Mob rival, StoryStore.View view) {
        RivalDuel.face(rival, player);
        if (view.progress().getGrave().getRivalWon()) {
            // 赢过的人再回冢，他来接着教，不再比。
            StoryChat.narrate(player, PREFIX + "resume_scene");
            ask(player, rival, "resume");
            return;
        }
        StoryChat.narrate(player, PREFIX + "scene");
        speak(rival, player, "greeting");
        invite(player, rival, "invite");
    }

    private void invite(ServerPlayer player, Mob rival, String line) {
        speak(rival, player, line);
        String nonce = offer(player.getUUID(), DUEL);
        player.sendSystemMessage(choices("accept", "decline", nonce, DUEL));
    }

    private void ask(ServerPlayer player, Mob rival, String line) {
        speak(rival, player, line);
        String nonce = offer(player.getUUID(), LESSON);
        player.sendSystemMessage(choices("teach_accept", "teach_defer", nonce, LESSON));
    }

    private boolean paused(UUID owner, long now) {
        Long at = recognized.get(owner);
        return at == null || now - at >= ASK_DELAY;
    }

    private boolean asked(UUID owner) {
        var offer = offers.get(owner);
        return offer != null && offer.revision() == LESSON;
    }

    private String offer(UUID owner, long revision) {
        // 同一张邀请重复点只重显；新的一次回冢、从约剑转到口授都换新的票据。
        var current = offers.get(owner);
        if (current == null || current.revision() != revision) {
            current = new Offer(UUID.randomUUID().toString(), revision);
            offers.put(owner, current);
        }
        return current.nonce();
    }

    private static Component choices(String accept, String decline, String nonce, long revision) {
        var yes = new StoryChat.Selection(nonce, revision, ACCEPT);
        var no = new StoryChat.Selection(nonce, revision, DECLINE);
        return Component.empty()
                .append(StoryChat.button(PREFIX + accept, Component.translatable(PREFIX + accept + "_hint"),
                        ChatFormatting.GOLD, yes))
                .append(" ")
                .append(StoryChat.button(PREFIX + decline, Component.translatable(PREFIX + decline + "_hint"),
                        ChatFormatting.GRAY, no));
    }

    Optional<Component> choose(ServerPlayer player, StoryChat.Selection selection) {
        // 票据只属于发出它的本人；陈恪不在场、修订号或选项不对都按过期处理。
        UUID owner = player.getUUID();
        var offer = offers.get(owner);
        if (offer == null || !offer.nonce().equals(selection.nonce())) {
            return Optional.empty();
        }
        Mob rival = null;
        if (player.level().getEntity(guest(owner)) instanceof Mob mob && mob.isAlive()) {
            rival = mob;
        }
        if (rival == null || selection.revision() != offer.revision()) {
            offers.remove(owner);
            return Optional.empty();
        }
        if (selection.option() != ACCEPT && selection.option() != DECLINE) {
            return Optional.empty();
        }
        if (offer.revision() == LESSON) {
            return lesson(player, rival, selection.option());
        }
        if (selection.option() == DECLINE) {
            return Optional.of(decline(player));
        }
        return Optional.of(accept(player, rival));
    }

    private Component accept(ServerPlayer player, Mob rival) {
        var arena = new RivalDuel.Arena(Level.OVERWORLD, rival.blockPosition());
        var refusal = RivalDuel.open(player, rival, arena);
        if (refusal.filter(RivalDuel.Refusal.BUSY::equals).isPresent()) {
            return Component.translatable(PREFIX + "busy");
        }
        if (refusal.isPresent()) {
            // 不满足就由他开口点一句，邀请原样保留。
            invite(player, rival, refusal.orElseThrow().line);
            return Component.empty();
        }
        offers.remove(player.getUUID());
        return Component.translatable(PREFIX + "stand");
    }

    private Optional<Component> lesson(ServerPlayer player, Mob rival, int option) {
        // 只教本人记录里赢过的人；学成、留待传还是改日，这次回冢他都告辞。
        if (option == DECLINE) {
            speak(rival, player, "teach_deferred");
            send(player);
            return Optional.of(Component.empty());
        }
        var view = store.read(player).filter(current -> current.progress().getGrave().getRivalWon());
        if (view.isEmpty()) {
            offers.remove(player.getUUID());
            return Optional.empty();
        }
        var lesson = RivalLessons.teach(player, view.orElseThrow());
        store.change(player, current -> RivalLessons.record(current, lesson));
        // 先是跟着他走完一遍的旁白，再是他的告辞或交代。
        if (lesson.covered() != 0) {
            StoryChat.narrate(player, PREFIX + "taught");
        }
        speak(rival, player, lesson.finished() ? "teach_done" : "teach_pending");
        send(player);
        return Optional.of(Component.empty());
    }

    private Component decline(ServerPlayer player) {
        // 婉拒只管这一次回冢：他告辞，空坪让出来，下次回来再见。
        send(player);
        return Component.translatable(PREFIX + "declined");
    }

    private void send(ServerPlayer player) {
        // 这次回冢不再请他来：作废邀请，让出空坪，他随之离开。
        UUID owner = player.getUUID();
        offers.remove(owner);
        var arrival = arrivals.get(owner);
        if (arrival != null) {
            arrivals.put(owner, new Arrival(arrival.enteredAt(), false /* due */));
        }
        var level = player.level().getServer().overworld();
        GraveSite.get(level).release(level, owner);
    }

    void leave(ServerPlayer player) {
        // 离线、死亡时先散场，场次结算要用到本人的剧情记录。
        RivalDuel.abort(player);
        offers.remove(player.getUUID());
        arrivals.remove(player.getUUID());
        summoned.remove(player.getUUID());
        recognized.remove(player.getUUID());
    }

    private void ended(RivalDuel.Ended ended) {
        // 破架落「已赢待传」并记下认招的那一刻；散场记下这一刻并送客，改天再约。
        ended.player().ifPresent(player -> store.change(player, view -> settle(view, ended.result())));
        if (ended.result() == RivalDuel.Result.WON) {
            ended.player().ifPresent(player -> recognized.put(player.getUUID(), player.level().getGameTime()));
            return;
        }
        ended.guest().ifPresent(rival -> HumanoidVisitors.depart((ServerLevel) rival.level(), ended.rival()));
        ended.player().ifPresent(this::send);
    }

    private static Progress settle(StoryStore.View view, RivalDuel.Result result) {
        var progress = view.progress();
        if (!GraveVisits.ORIGIN.equals(view.facts().origin()) || !progress.hasGrave()) {
            return progress;
        }
        var grave = progress.getGrave();
        GraveKeeper next;
        if (result == RivalDuel.Result.WON) {
            next = grave.toBuilder().setRivalWon(true /* rivalWon */).build();
        } else {
            next = grave.toBuilder().setRivalSetback(view.facts().ticks()).build();
        }
        return progress.toBuilder().setGrave(next).build();
    }

    private static void speak(Mob rival, ServerPlayer player, String line) {
        HumanoidSpeech.SPOKE.invoker().spoke(rival, player, Component.translatable(PREFIX + line));
    }
}
