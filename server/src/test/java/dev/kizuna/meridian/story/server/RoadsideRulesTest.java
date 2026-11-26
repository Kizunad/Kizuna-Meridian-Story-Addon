package dev.kizuna.meridian.story.server;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RoadsideRulesTest {
    private static final long SEED = 20261126L;
    private static final int CASES = 3000;
    private static final long DAY = 24_000;

    @Test
    public void fixedSeedCoversBothSidesNearTwoThirds() {
        int good = 0;
        for (long number = 1; number <= CASES; number++) {
            int roll = RoadsideRules.roll(SEED, number);
            assertEquals("同一场次重算换了签", roll, RoadsideRules.roll(SEED, number));
            if (RoadsideRules.good(roll)) {
                good++;
            }
        }
        assertTrue("好签偏离三分之二：" + good, Math.abs(good - CASES * 2 / 3) < CASES / 20);
        assertTrue(RoadsideRules.good(RoadsideRules.GOOD - 1) && !RoadsideRules.good(RoadsideRules.GOOD));
    }

    @Test
    public void openingDrawIsStablePerTileAndInquiryWaitsThreeToFiveDays() {
        int opened = 0;
        for (long tile = 0; tile < CASES; tile++) {
            assertEquals(RoadsideRules.opens(SEED, tile), RoadsideRules.opens(SEED, tile));
            if (RoadsideRules.opens(SEED, tile)) {
                opened++;
            }
        }
        assertTrue("开场率偏离两成：" + opened, Math.abs(opened - CASES / 5) < CASES / 20);
        for (int roll = 0; roll < RoadsideRules.PERCENT; roll++) {
            long delay = RoadsideRules.inquiryDelay(roll, DAY);
            assertTrue(delay >= 3 * DAY && delay <= 5 * DAY);
        }
    }
}
