package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.humanoid.fabric.CrisisEncounters;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;

/** 集成服务器走原版实体交互，确认按钮经客户端连接回包后从权威身体取结局。 */
public final class DialogueScene {
    private DialogueScene() { }

    public static UUID open(MinecraftServer server, ServerPlayer player) {
        if (CrisisEncounters.current(player).isEmpty()) {
            return null;
        }
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "meridian debug origin set "
                + player.getScoreboardName() + " meridian_humanoid:disgraced");
        var actor = EntityTypes.VILLAGER.create(player.level(), EntitySpawnReason.COMMAND);
        if (actor == null) {
            throw new IllegalStateException("证人未生成");
        }
        actor.setNoAi(true /* noAi */);
        actor.setPos(player.position().add(1, 0, 0));
        player.level().addFreshEntity(actor);
        return actor.getUUID();
    }

    public static void reopen(ServerPlayer player, UUID id) {
        if (player.level().getEntity(id) instanceof Villager actor) {
            speak(player, actor);
        }
    }

    private static void speak(ServerPlayer player, Villager actor) {
        player.setShiftKeyDown(true /* sneaking */);
        UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, actor, null);
        player.setShiftKeyDown(false /* sneaking */);
    }

    public static boolean done(ServerPlayer player) {
        return new StoryBodies().read(player).map(StoryProgress::read)
                .filter(progress -> progress.containsLines("meridian_story_test:register"))
                .map(progress -> progress.getLinesOrThrow("meridian_story_test:register").getEnding())
                .map("declined"::equals)
                .orElse(false);
    }
}
