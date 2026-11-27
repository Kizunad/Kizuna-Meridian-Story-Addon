package dev.kizuna.meridian.story.server;

import java.util.SplittableRandom;

/** 路上事件的开场签与好坏签；同一种子总得同一结果，重进与重点都不重抽。 */
public final class RoadsideRules {
    public static final int PERCENT = 100;
    /** 同类合格新地点的开场率。 */
    public static final int OPEN_CHANCE = 20;
    /** 救护与夺卷的好签占三分之二。 */
    public static final int GOOD = 67;
    public static final int INQUIRY_MIN_DAYS = 3;
    public static final int INQUIRY_SPAN_DAYS = 3;
    private static final long TILE_SALT = 0x632BE59BD9B4E019L;
    private static final long CASE_SALT = 0x8CB92BA72F3D8DD7L;

    private RoadsideRules() { }

    public static boolean opens(long seed, long tile) {
        return draw(seed ^ tile * TILE_SALT) < OPEN_CHANCE;
    }

    /** 书摊与路边伤者各占两成，两种路肩事件不抢同一签。 */
    public static boolean opensStall(long seed, long tile) {
        int roll = draw(seed ^ tile * TILE_SALT);
        return roll >= OPEN_CHANCE && roll < OPEN_CHANCE + OPEN_CHANCE;
    }

    /** 开场时锁定的预签，取值 0–99。 */
    public static int roll(long seed, long number) {
        return draw(seed + number * CASE_SALT);
    }

    public static boolean good(int roll) {
        return roll < GOOD;
    }

    /** 坏签夺卷后，门人在三至五个在线日后上路。 */
    public static long inquiryDelay(int roll, long day) {
        return (INQUIRY_MIN_DAYS + Math.floorMod(roll, INQUIRY_SPAN_DAYS)) * day;
    }

    private static int draw(long seed) {
        return new SplittableRandom(seed).nextInt(PERCENT);
    }
}
