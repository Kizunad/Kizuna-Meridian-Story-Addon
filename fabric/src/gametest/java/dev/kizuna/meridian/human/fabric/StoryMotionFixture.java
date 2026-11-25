package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import java.util.List;

/** 同一份在线快照交给步态宿主，按真实的一秒走、跑账单逐块核对。 */
public final class StoryMotionFixture {
    public enum Pace { WALK, SPRINT }
    private static final long SECOND = 1_000;

    private StoryMotionFixture() { }

    public static BodySnapshot move(BodySnapshot snapshot, Pace pace) {
        var body = new HumanPlayerBody(snapshot, List.of(), QiInventory.empty());
        long start = snapshot.settledAt().elapsedMillis();
        body.observe(start, HumanPlayerBody.Motion.valueOf(pace.name()));
        body.settle(start + SECOND);
        return body.snapshot();
    }
}
