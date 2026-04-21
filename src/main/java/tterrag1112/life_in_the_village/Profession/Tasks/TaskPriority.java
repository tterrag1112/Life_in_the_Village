package tterrag1112.life_in_the_village.Profession.Tasks;

import com.mojang.serialization.Codec;

/**
 * Priority tiers for profession tasks and guild quests.
 *
 * <h3>Assignment rules</h3>
 * Tasks are issued to players in priority order. A player is always shown
 * their highest-priority active tasks first. BUSYWORK tasks are only issued
 * when the player has no CRITICAL, HIGH, or NORMAL tasks pending.
 *
 * <h3>Generation sources</h3>
 * <ul>
 *   <li>{@code CRITICAL} — generated when a village need reaches the CRITICAL
 *       level (e.g. no food, no iron for blacksmith). Ties directly to
 *       {@code VillageNeed.NeedLevel.CRITICAL}.</li>
 *   <li>{@code HIGH} — generated when an active {@code CraftingOrder} exists
 *       that the player's profession can fulfil, or when a building has a
 *       flagged shortage.</li>
 *   <li>{@code NORMAL} — regular operational tasks: standard quotas, routine
 *       deliveries, day-to-day production runs.</li>
 *   <li>{@code LOW} — busywork tasks with atmosphere but no real consequence
 *       if skipped. Should never crowd out real work.</li>
 * </ul>
 */
public enum TaskPriority {

    /**
     * Village or building survival depends on this task being completed.
     * Shown with a red indicator. Examples: "Farmer is out of seeds",
     * "Blacksmith has no raw iron — all production is halted".
     */
    CRITICAL(4),

    /**
     * An active order or tracked demand requires this task.
     * Shown with an orange indicator. Examples: "Deliver 8 iron swords to
     * the guard tower (order from garrison)", "Bring wheat to the baker
     * (crafting order active)".
     */
    HIGH(3),

    /**
     * Regular operational work that keeps the building running smoothly.
     * Shown with a yellow indicator. Examples: "Craft 16 oak planks for
     * building stock", "Mine raw iron for the weekly quota".
     */
    NORMAL(2),

    /**
     * Low-importance filler tasks. Only issued when no CRITICAL, HIGH,
     * or NORMAL tasks are pending. Shown with a grey indicator.
     * Examples: "Clean the workshop", "Walk the perimeter and report back".
     */
    LOW(1);

    private final int weight;

    TaskPriority(int weight) { this.weight = weight; }

    /** Numeric weight — higher = more important. Useful for sorting. */
    public int weight() { return weight; }

    /** True if this priority should suppress any lower-priority task issuance. */
    public boolean suppressesLower() { return this == CRITICAL || this == HIGH; }

    public static final Codec<TaskPriority> CODEC =
            Codec.STRING.xmap(TaskPriority::valueOf, TaskPriority::name);
}
