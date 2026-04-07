package tterrag1112.life_in_the_village.Village.Economy.Currency;

import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Random;

/**
 * Defines starting wealth ranges per profession.
 * Values are in bronze. CurrencyValue.of() converts automatically.
 *
 * Ranges are intentionally wide so villages feel economically varied
 * rather than every NPC being a clone. A blacksmith who starts wealthy
 * will buy iron faster and produce more early; a poor one has to wait
 * until wages accumulate — this creates emergent pacing differences.
 */
public final class NpcStartingWealth {

    private NpcStartingWealth() {}

    // Bronze values. 1 silver = 64 bronze, 1 gold = 64 silver = 4096 bronze.

    /** Returns a randomised starting wealth for a newly spawned NPC. */
    public static CurrencyValue forProfession(Profession profession,
                                              Random rng) {
        long bronze = switch (profession) {
            // Merchants are the wealthiest — they need coins to operate
            case MERCHANT       -> range(rng, 1500, 3000);   // ~0.5–1 gold
            // Skilled tradespeople have moderate savings
            case BLACKSMITH     -> range(rng,  400,  900);
            case CARPENTER      -> range(rng,  300,  700);
            case MINER          -> range(rng,  250,  600);
            // Farmers have seasonal income — variable
            case FARMER         -> range(rng,  150,  500);
            case FARMHAND       -> range(rng,   80,  250);
            // Service workers are paid in wages, lower savings
            case INNKEEPER      -> range(rng,  300,  700);
            case STOCKPILE_KEEPER -> range(rng, 200, 500);
            case GUARD          -> range(rng,  100,  300);
            case BUILDER        -> range(rng,  200,  500);
            // Leaders hold more — they're effectively landowners
            case VILLAGE_LEADER -> range(rng, 1000, 2500);
            case KINGDOM_RULER  -> range(rng, 3000, 6000);
            // Unemployed citizens have little
            case CITIZEN, NONE  -> range(rng,   30,  150);
            // Guild members
            case GUILDMASTER    -> range(rng,  500, 1200);
            case GUILDWORKER    -> range(rng,  100,  300);
            // Adventurers carry variable amounts
            case ADVENTURER     -> range(rng,   50,  400);
            // Default for anything not listed
            default             -> range(rng,   50,  200);
        };

        return CurrencyValue.of(bronze);
    }

    private static long range(Random rng, long min, long max) {
        return min + (long)(rng.nextDouble() * (max - min));
    }
}