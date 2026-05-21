package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;
import java.util.Optional;

/**
 * Phase 6.3.3.g.2 — built-in animal roster definitions. Registered at
 * mod init via {@link #registerAll}; the 5 entries cover the
 * core farming animals (chickens / pigs / cows / sheep / bees).
 *
 * <p>Parameter values are content-tunable defaults from the 6.3.3.g
 * spec; future tuning passes adjust population caps, production
 * cadences, and breeding cooldowns based on play feedback.
 *
 * <h3>Bee handling note</h3>
 * <p>Bees are conceptually hive-based rather than individual: each
 * slot represents a hive, the wrapped EntityType.BEE serves as the
 * realized representative. {@link BreedingRule#minAdultPairs} is 1
 * for bees (a hive splits / swarms on its own); for other animals
 * the standard 2-adult pair requirement applies.
 */
public final class AnimalRosterDefinitions {

    private AnimalRosterDefinitions() {}

    public static final Identifier CHICKEN = id("animal/chicken");
    public static final Identifier PIG     = id("animal/pig");
    public static final Identifier COW     = id("animal/cow");
    public static final Identifier SHEEP   = id("animal/sheep");
    public static final Identifier BEE     = id("animal/bee");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
    }

    /** Register all 5 animal rosters. Idempotent — calling twice is harmless
     *  (RosterRegistry.register overwrites by id). */
    public static void registerAll() {
        // CHICKEN — 12-tick egg cadence per spec, paired breeding via seeds.
        RosterRegistry.register(new RosterDefinition(
                CHICKEN, EntityType.CHICKEN,
                /* defaultPopulation */ 4,
                /* maxPopulation */ 12,
                /* productionPeriodTicks */ 12000L,
                List.of(new ItemStack(Items.EGG, 1)),
                Optional.of(new BreedingRule(
                        List.of(new ItemStack(Items.WHEAT_SEEDS, 1)),
                        /* minAdultPairs */ 2,
                        /* cooldownTicks */ 24000L)),
                List.of(new ItemStack(Items.CHICKEN, 1),
                        new ItemStack(Items.FEATHER, 1)),
                /* growthPeriodTicks */ 6000L));

        // PIG — no passive production; carrot breeding; slaughter for meat.
        RosterRegistry.register(new RosterDefinition(
                PIG, EntityType.PIG,
                2, 8,
                /* productionPeriodTicks */ Long.MAX_VALUE / 2L, // never
                List.of(),
                Optional.of(new BreedingRule(
                        List.of(new ItemStack(Items.CARROT, 1)), 2, 48000L)),
                List.of(new ItemStack(Items.PORKCHOP, 2)),
                24000L));

        // COW — daily milk; wheat breeding. Milk-bucket production is
        // simplified: the cycle drops MILK_BUCKET into storage without
        // consuming an empty bucket (v1 simplification; future tuning can
        // gate on building bucket inventory).
        RosterRegistry.register(new RosterDefinition(
                COW, EntityType.COW,
                2, 8,
                /* productionPeriodTicks */ 24000L,
                List.of(new ItemStack(Items.MILK_BUCKET, 1)),
                Optional.of(new BreedingRule(
                        List.of(new ItemStack(Items.WHEAT, 1)), 2, 48000L)),
                List.of(new ItemStack(Items.BEEF, 2),
                        new ItemStack(Items.LEATHER, 1)),
                36000L));

        // SHEEP — wool every 1.5 days; wheat breeding.
        RosterRegistry.register(new RosterDefinition(
                SHEEP, EntityType.SHEEP,
                3, 12,
                /* productionPeriodTicks */ 36000L,
                List.of(new ItemStack(Items.WHITE_WOOL, 1)),
                Optional.of(new BreedingRule(
                        List.of(new ItemStack(Items.WHEAT, 1)), 2, 48000L)),
                List.of(new ItemStack(Items.MUTTON, 2),
                        new ItemStack(Items.WHITE_WOOL, 1)),
                24000L));

        // BEE — hive-based; honey + comb every 2 days; flower-fed swarming.
        // No slaughter outputs; hives are not slaughtered (hive destruction
        // for wax handled separately via a destroy-hive content path).
        RosterRegistry.register(new RosterDefinition(
                BEE, EntityType.BEE,
                /* defaultPopulation */ 1,
                /* maxPopulation */ 5,
                /* productionPeriodTicks */ 48000L,
                List.of(new ItemStack(Items.HONEY_BOTTLE, 1),
                        new ItemStack(Items.HONEYCOMB, 1)),
                // minAdultPairs=1 — single hive can swarm.
                Optional.of(new BreedingRule(
                        List.of(new ItemStack(Items.POPPY, 4)), 1, 96000L)),
                /* slaughterOutputs */ List.of(),
                /* growthPeriodTicks */ 0L));
    }
}
