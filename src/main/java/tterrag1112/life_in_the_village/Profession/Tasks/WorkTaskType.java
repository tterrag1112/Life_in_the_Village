package tterrag1112.life_in_the_village.Profession.Tasks;

import com.mojang.serialization.Codec;

/**
 * Shared task-type vocabulary used by both {@code WorkAssignment} (profession
 * job boards) and {@code Quest} (guild quest boards).
 *
 * <h3>Why a shared enum?</h3>
 * A guild GATHER quest and a blacksmith GATHER job assignment differ in
 * context (who issued them, what rewards they pay) but the core mechanic is
 * identical: collect an item and bring it to a destination. Sharing the type
 * means completion detection, progress tracking, and UI labelling only need
 * to be written once.
 *
 * <h3>Categories</h3>
 * <ul>
 *   <li><b>Gathering</b> — GATHER, HARVEST (farm-specific gather)</li>
 *   <li><b>Production</b> — CRAFT, SMELT, PROCESS</li>
 *   <li><b>Logistics</b> — DELIVER, RESTOCK</li>
 *   <li><b>Combat / exploration</b> — HUNT, PATROL, ESCORT, SURVEY</li>
 *   <li><b>Social / administrative</b> — REPORT</li>
 *   <li><b>Filler</b> — BUSYWORK (timer-based, only issued when nothing more
 *       important exists)</li>
 * </ul>
 *
 * <h3>Completion detection</h3>
 * Each type is handled by {@code TaskCompletionEvents}:
 * <ul>
 *   <li>GATHER / DELIVER / RESTOCK → item deposited to destination building</li>
 *   <li>HARVEST → crop block break at assigned farm plot</li>
 *   <li>CRAFT / SMELT / PROCESS → relevant item crafted/smelted</li>
 *   <li>HUNT → mob death event matching target</li>
 *   <li>PATROL → all waypoints visited within time window</li>
 *   <li>ESCORT → NPC reached destination</li>
 *   <li>SURVEY / REPORT → player interacts with target NPC or block</li>
 *   <li>BUSYWORK → player spends time near the building</li>
 * </ul>
 */
public enum WorkTaskType {

    // ── Gathering ─────────────────────────────────────────────────────────────

    /**
     * Collect a specific item (from anywhere) and deposit it at a target
     * building. The item may need to be sourced from the world, other
     * buildings, or the market.
     *
     * <p>Completion: {@code targetCount} of {@code targetItem} deposited to
     * {@code destinationBuildingId}.</p>
     */
    GATHER,

    /**
     * Farm-specific gathering — harvest mature crops from an assigned plot.
     * Differs from GATHER in that the source is explicitly the farm plot and
     * the action is breaking fully-grown crop blocks, not just depositing items.
     *
     * <p>Completion: {@code targetCount} mature crop breaks on the assigned plot.</p>
     */
    HARVEST,

    // ── Production ────────────────────────────────────────────────────────────

    /**
     * Craft a specific item using a crafting table or workstation.
     *
     * <p>Completion: {@code targetCount} of {@code targetItem} crafted (recipe
     * output detected in the crafting result event).</p>
     */
    CRAFT,

    /**
     * Smelt raw materials in a furnace or blast furnace.
     *
     * <p>Completion: {@code targetCount} of {@code targetItem} taken from a
     * furnace output slot.</p>
     */
    SMELT,

    /**
     * Generic processing task — used for professions that transform inputs to
     * outputs without a crafting table (e.g. weaver, miller, candlemaker).
     * Completion is driven by production cycle events from the NPC goal layer.
     *
     * <p>Completion: NPC production cycle notification OR item deposited to
     * target building by the player.</p>
     */
    PROCESS,

    // ── Logistics ─────────────────────────────────────────────────────────────

    /**
     * Move items from a known source building to a known destination building.
     * Unlike GATHER, both endpoints are pre-defined; the player does not need
     * to source the item themselves.
     *
     * <p>Completion: {@code targetCount} of {@code targetItem} deposited to
     * {@code destinationBuildingId}.</p>
     */
    DELIVER,

    /**
     * Fill a building's supplies up to a target level.
     * Similar to DELIVER but the destination is the assigned workplace itself
     * and the source is the market or stockpile.
     *
     * <p>Completion: building storage reaches {@code targetCount} of the item.</p>
     */
    RESTOCK,

    // ── Combat & exploration ──────────────────────────────────────────────────

    /**
     * Kill a specified number of a specific mob type near the village or in a
     * designated area.
     *
     * <p>Completion: {@code targetCount} kills matching {@code targetMob}.</p>
     */
    HUNT,

    /**
     * Visit a set of waypoints within a time window — used for guard patrol
     * tasks and survey missions.
     *
     * <p>Completion: all registered waypoints visited before the deadline.</p>
     */
    PATROL,

    /**
     * Accompany a specific NPC from their current location to a target
     * position, protecting them along the way.
     *
     * <p>Completion: target NPC arrives at destination.</p>
     */
    ESCORT,

    /**
     * Explore a defined area and return with information. Mechanically
     * completed by the player reaching the target position and interacting
     * with a survey marker or similar block.
     *
     * <p>Completion: player interacts with the target block/NPC at the location.</p>
     */
    SURVEY,

    // ── Social / administrative ───────────────────────────────────────────────

    /**
     * Deliver a written book, message, or verbal report to a specific NPC.
     * Used for herald and chancellor tasks, and for high-level profession
     * assignments that require diplomatic interaction.
     *
     * <p>Completion: player interacts with the target NPC while holding the
     * required item (or after completing a prerequisite task).</p>
     */
    REPORT,

    // ── Filler ───────────────────────────────────────────────────────────────

    /**
     * Low-priority filler task with timer-based completion. Issued only when
     * no CRITICAL, HIGH, or NORMAL priority tasks exist for the player.
     * Examples: "clean up the workshop", "inspect the perimeter".
     *
     * <p>Completion: player spends a set number of ticks near the assigned
     * building (proximity timer, not item-based).</p>
     */
    BUSYWORK;

    // ── Codec ─────────────────────────────────────────────────────────────────

    public static final Codec<WorkTaskType> CODEC =
            Codec.STRING.xmap(WorkTaskType::valueOf, WorkTaskType::name);

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True if this task type is completed by delivering items to a building. */
    public boolean isItemDeliveryType() {
        return this == GATHER || this == DELIVER || this == RESTOCK;
    }

    /** True if this task type is completed by crafting or processing items. */
    public boolean isProductionType() {
        return this == CRAFT || this == SMELT || this == PROCESS;
    }

    /** True if this task type is completed by breaking crop blocks. */
    public boolean isFarmingType() {
        return this == HARVEST;
    }

    /** True if this task type is completed by killing entities. */
    public boolean isCombatType() {
        return this == HUNT;
    }

    /** True if this task is proximity or timer-based rather than item-based. */
    public boolean isTimerType() {
        return this == BUSYWORK || this == PATROL || this == ESCORT
                || this == SURVEY || this == REPORT;
    }

    /** Human-readable label for UI display. */
    public String displayName() {
        return switch (this) {
            case GATHER   -> "Gather";
            case HARVEST  -> "Harvest";
            case CRAFT    -> "Craft";
            case SMELT    -> "Smelt";
            case PROCESS  -> "Process";
            case DELIVER  -> "Deliver";
            case RESTOCK  -> "Restock";
            case HUNT     -> "Hunt";
            case PATROL   -> "Patrol";
            case ESCORT   -> "Escort";
            case SURVEY   -> "Survey";
            case REPORT   -> "Report";
            case BUSYWORK -> "Task";
        };
    }
}
