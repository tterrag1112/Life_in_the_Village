// ============================================================
// FILE 1: src/main/java/tterrag1112/life_in_the_village/Village/Buildings/BuildingCondition.java
// ============================================================
package tterrag1112.life_in_the_village.Village.Buildings;

import com.mojang.serialization.Codec;

/**
 * Tracks how well-maintained a building is.
 *
 * <h3>Transitions</h3>
 * <pre>
 *   NEW → WEATHERED (set by VillageWeathering on village spawn)
 *   WEATHERED → MAINTAINED (BuilderMaintenanceGoal repairs it)
 *   WEATHERED → DILAPIDATED (no builder present for > 7 in-game days)
 *   MAINTAINED → WEATHERED (natural drift — maintenance is not free)
 *   DILAPIDATED → WEATHERED (builder begins repairs)
 *   DILAPIDATED → RUINED (no repair for > 21 in-game days — abandoned only)
 * </pre>
 *
 * RUINED buildings are visually distinct (open roof, structural gaps) and
 * generate no income / NPC assignments until repaired by the player or a builder.
 */
public enum BuildingCondition {

    /** Freshly placed — no weathering applied yet. */
    NEW,

    /** Builder has serviced this building recently. Stone/wood restored. */
    MAINTAINED,

    /** Standard post-weathering state — moss, minor cracks, worn edges. */
    WEATHERED,

    /**
     * Needs repair — significant cracked/missing blocks. NPCs complain.
     * The building still functions but its output/storage is penalised.
     */
    DILAPIDATED,

    /**
     * Structurally compromised — only reached by abandoned buildings with
     * no residents or assigned builder for a long time.
     * Building is non-functional until the player or a builder initiates repairs.
     */
    RUINED;

    public static final Codec<BuildingCondition> CODEC =
            Codec.STRING.xmap(BuildingCondition::valueOf, BuildingCondition::name);

    /** True if the building needs active repair work. */
    public boolean needsRepair() {
        return this == DILAPIDATED || this == RUINED;
    }

    /** True if the building is in a functional state (even if weathered). */
    public boolean isFunctional() {
        return this != RUINED;
    }

    /**
     * The next natural degradation state if nothing is done.
     * MAINTAINED degrades to WEATHERED; WEATHERED to DILAPIDATED; DILAPIDATED to RUINED.
     * NEW and RUINED do not auto-degrade further.
     */
    public BuildingCondition degrade() {
        return switch (this) {
            case NEW        -> WEATHERED;
            case MAINTAINED -> WEATHERED;
            case WEATHERED  -> DILAPIDATED;
            case DILAPIDATED -> RUINED;
            case RUINED     -> RUINED;
        };
    }

    /** The state reached after one round of builder maintenance. */
    public BuildingCondition repair() {
        return switch (this) {
            case RUINED      -> DILAPIDATED; // one round just starts recovery
            case DILAPIDATED -> WEATHERED;
            case WEATHERED   -> MAINTAINED;
            case MAINTAINED  -> MAINTAINED;
            case NEW         -> MAINTAINED;
        };
    }
}