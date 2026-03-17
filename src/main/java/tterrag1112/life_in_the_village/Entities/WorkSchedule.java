package tterrag1112.life_in_the_village.Entities;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

public class WorkSchedule {

    public record TimeWindow(int startTick, int endTick) {
        public boolean isActive(long dayTime) {
            long t = dayTime % 24000;
            if (startTick <= endTick) {
                return t >= startTick && t < endTick;
            } else {
                // Wraps midnight e.g. 22000-2000
                return t >= startTick || t < endTick;
            }
        }
    }

    // Minecraft day: dawn=0, noon=6000, dusk=12000, midnight=18000
    public static TimeWindow getWorkWindow(Profession profession) {
        return switch (profession) {
            case FARMER, FARMHAND  -> new TimeWindow(500, 11000);
            case MERCHANT          -> new TimeWindow(3000, 15000);
            case BLACKSMITH        -> new TimeWindow(1000, 13000);
            case GUARD             -> new TimeWindow(0, 24000);   // always working
            case BUILDER           -> new TimeWindow(1000, 12000);
            case STOCKPILE_KEEPER  -> new TimeWindow(2000, 13000);
            default                -> new TimeWindow(2000, 12000);
        };
    }

    public static TimeWindow getSleepWindow(Profession profession) {
        return switch (profession) {
            case FARMER, FARMHAND  -> new TimeWindow(13000, 23500);
            case MERCHANT          -> new TimeWindow(16000, 23500);
            case GUARD             -> new TimeWindow(19000, 23000);
            default                -> new TimeWindow(14000, 23500);
        };
    }

    public static TimeWindow getSocialWindow(Profession profession) {
        // Social time is between work end and sleep start
        return switch (profession) {
            case FARMER, FARMHAND  -> new TimeWindow(11000, 13000);
            case MERCHANT          -> new TimeWindow(15000, 16000);
            case GUARD             -> new TimeWindow(19000, 20000);
            default                -> new TimeWindow(12000, 14000);
        };
    }

    public static boolean isWorkTime(TownspersonMob mob) {
        return getWorkWindow(mob.getProfession())
                .isActive(mob.level().getDayTime());
    }

    public static boolean isSleepTime(TownspersonMob mob) {
        return getSleepWindow(mob.getProfession())
                .isActive(mob.level().getDayTime());
    }

    public static boolean isSocialTime(TownspersonMob mob) {
        return getSocialWindow(mob.getProfession())
                .isActive(mob.level().getDayTime());
    }

    public static boolean shouldBeHome(TownspersonMob mob) {
        // Guards never go home on schedule
        if (mob.getProfession() == Profession.GUARD) return false;
        return isSleepTime(mob);
    }
}
