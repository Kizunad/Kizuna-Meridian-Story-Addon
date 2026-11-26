package dev.kizuna.meridian.story.fabric;

import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.story.protocol.pb.StoryMessages.GraveKeeper;
import dev.kizuna.meridian.story.server.StoryProgress;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** 查询守墓人在登记冢内的祭扫进度。 */
public final class GraveInheritance {
    private GraveInheritance() { }

    public static boolean sweptAtGrave(ServerPlayer player) {
        var site = GraveSite.get(player.level());
        return site.anchor().filter(pos -> GraveVisits.inside(player.level().dimension(), player.blockPosition(), pos))
                .isPresent() && record(player).filter(GraveKeeper::getFirstSwept).isPresent();
    }

    private static Optional<GraveKeeper> record(ServerPlayer player) {
        if (Origins.chosen(player).filter(origin -> origin.id().toString().equals(GraveVisits.ORIGIN)).isEmpty()) {
            return Optional.empty();
        }
        return new StoryBodies().read(player).map(StoryProgress::read)
                .filter(progress -> progress.hasGrave())
                .map(progress -> progress.getGrave());
    }
}
