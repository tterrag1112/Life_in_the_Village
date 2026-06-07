package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Map;

/**
 * Liveliness L4 — flavour text for NPC activity nameplates. Static string
 * pools keyed by an action key (and a per-profession crafting map); {@link
 * #pick} returns one at random. Callers must pick ONCE at action/phase entry
 * (not per tick) — {@code TownspersonMob.setActivityState} short-circuits on
 * {@code equals}, so a stable string per action holds the nameplate steady,
 * while a fresh pick each action gives variety across cycles/NPCs.
 *
 * <p>Polish only: this never changes what an NPC does, only the label. A
 * data-driven (JSON) pool is a possible later step — code map for v1.
 */
public final class ActivityFlavor {

    private ActivityFlavor() {}

    private static final Map<String, String[]> POOLS = Map.ofEntries(
            Map.entry("stroll", new String[]{
                    "Strolling the village", "Taking a walk", "Wandering about",
                    "Out for a stroll", "Taking in the day"}),
            Map.entry("rest", new String[]{
                    "Taking a breather", "Resting a moment", "Catching their breath",
                    "Having a sit"}),
            Map.entry("tidy", new String[]{
                    "Tidying the workshop", "Straightening up", "Organising the shop",
                    "Sweeping up"}),
            Map.entry("square", new String[]{
                    "Gathering at the square", "Chatting by the well",
                    "Milling about the square", "Passing the time"}),
            Map.entry("prod.gather", new String[]{
                    "Gathering materials", "Fetching supplies",
                    "Collecting what's needed", "Hauling materials"}),
            Map.entry("prod.deposit", new String[]{
                    "Depositing goods", "Stowing the haul", "Stocking the shelves",
                    "Putting things away"}),
            Map.entry("prod.craft", new String[]{
                    "Hard at work", "Busy at the bench", "Working away"}),
            Map.entry("farm.harvest", new String[]{
                    "Harvesting crops", "Bringing in the harvest",
                    "Picking the ripe crops", "Reaping the field"}),
            Map.entry("farm.replant", new String[]{
                    "Replanting crops", "Sowing seeds", "Planting the next crop",
                    "Working the soil"}),
            Map.entry("farm.animals", new String[]{
                    "Tending the animals", "Feeding the livestock",
                    "Checking the herd", "Minding the flock"}),
            Map.entry("farm.deposit", new String[]{
                    "Depositing harvest", "Storing the harvest", "Bringing in the crop"}));

    /** Per-profession crafting flavour; falls back to the {@code prod.craft} pool. */
    private static final Map<Profession, String[]> CRAFT = Map.ofEntries(
            Map.entry(Profession.BAKER, new String[]{
                    "Kneading dough", "Tending the oven", "Baking bread", "Shaping loaves"}),
            Map.entry(Profession.BLACKSMITH, new String[]{
                    "Hammering at the forge", "Working the bellows", "Tempering steel",
                    "Shaping metal"}),
            Map.entry(Profession.CARPENTER, new String[]{
                    "Sawing timber", "Planing boards", "Joining frames", "Sanding wood"}),
            Map.entry(Profession.STONEMASON, new String[]{
                    "Chiselling stone", "Dressing blocks", "Cutting masonry", "Setting stone"}),
            Map.entry(Profession.WEAVER, new String[]{
                    "Working the loom", "Spinning thread", "Weaving cloth", "Carding wool"}),
            Map.entry(Profession.CANDLEMAKER, new String[]{
                    "Dipping candles", "Pouring wax", "Trimming wicks", "Setting moulds"}),
            Map.entry(Profession.MILLER, new String[]{
                    "Grinding grain", "Working the millstone", "Sifting flour",
                    "Sacking the meal"}));

    /** A flavour string from {@code key}'s pool, or {@code key} itself if none. */
    public static String pick(String key, RandomSource rng) {
        String[] pool = POOLS.get(key);
        if (pool == null || pool.length == 0) return key;
        return pool[rng.nextInt(pool.length)];
    }

    /** Crafting flavour for a profession; generic {@code prod.craft} fallback. */
    public static String craft(Profession prof, RandomSource rng) {
        String[] pool = CRAFT.get(prof);
        if (pool != null && pool.length > 0) return pool[rng.nextInt(pool.length)];
        return pick("prod.craft", rng);
    }
}
