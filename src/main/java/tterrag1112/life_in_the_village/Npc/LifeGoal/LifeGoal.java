package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One concrete life goal carried by an NPC. Spec
 * {@code docs/npc_redesign/07-life-goals.md} (section "LifeGoal record").
 *
 * <p>{@link #targetParam} is type-specific — the slug of an item, the
 * stringified UUID of a target NPC, the office id, etc. Goals that count
 * toward {@link #targetCount} (SAVE_AMOUNT, BEFRIEND_COUNT,
 * VISIT_VILLAGES, ...) tick {@link #progressCount} via subsystem hooks.
 * Goals that complete on a binary world-state condition leave
 * {@code targetCount = 1} and flip {@code progressCount} to 1 on
 * detection.</p>
 */
public record LifeGoal(
        UUID goalId,
        LifeGoalType type,
        long startTick,
        long targetTick,
        String targetParam,
        int targetCount,
        int progressCount,
        GoalStatus status,
        int importance,
        String narrative
) {

    public static LifeGoal newActive(LifeGoalType type, long startTick,
                                     String targetParam, int targetCount,
                                     int importance, String narrative) {
        return new LifeGoal(UUID.randomUUID(), type, startTick, 0L,
                targetParam == null ? "" : targetParam,
                Math.max(1, targetCount),
                0,
                GoalStatus.ACTIVE,
                Math.max(1, Math.min(10, importance)),
                narrative == null ? "" : narrative);
    }

    public float progressFraction() {
        if (status == GoalStatus.COMPLETED) return 1f;
        if (targetCount <= 0) return 0f;
        return Math.min(1f, (float) progressCount / (float) targetCount);
    }

    public boolean isExpired(long now) {
        return targetTick > 0L && now > targetTick;
    }

    /**
     * Phase 6.4.3.3 — block-amenity preferences this goal expresses,
     * for {@link tterrag1112.life_in_the_village.Npc.Brain.Behaviors
     * .SeekHouseBehavior} to bias home selection toward houses that
     * support the goal.
     *
     * <p>Resolution:</p>
     * <ul>
     *   <li>{@code REACH_SKILL_LEVEL} — parse targetParam as Skill and
     *       map to the typical workstation(s) for that skill.</li>
     *   <li>{@code FOUND_BUSINESS} — parse targetParam as Profession
     *       and return the workshop-quality amenity set (workstation
     *       + storage).</li>
     *   <li>Other types — empty set (no amenity preference yet).</li>
     * </ul>
     *
     * <p>Unmapped skills / professions return an empty set rather than
     * throwing, so adding a new goal target without an amenity
     * mapping is safe — housing falls back to proximity-only.</p>
     */
    public Set<AmenityType> preferredAmenities() {
        return switch (type) {
            case REACH_SKILL_LEVEL -> amenitiesForSkill(parseSkill(targetParam));
            case FOUND_BUSINESS    -> amenitiesForProfession(parseProfession(targetParam));
            default -> Set.of();
        };
    }

    private static Set<AmenityType> amenitiesForSkill(Skill s) {
        if (s == null) return Set.of();
        return switch (s) {
            case BAKING                   -> EnumSet.of(AmenityType.SMOKER, AmenityType.FURNACE);
            case PASTRY                   -> EnumSet.of(AmenityType.SMOKER, AmenityType.FURNACE);
            case MILLING                  -> EnumSet.of(AmenityType.GRINDSTONE);
            case FARMING, CROP_FARMING,
                 ORCHARDING               -> Set.of(); // GARDEN_PLOT deferred per 6.4.3.2
            case BLACKSMITHING,
                 TOOLSMITHING,
                 WEAPONSMITHING,
                 ARMORSMITHING            -> EnumSet.of(AmenityType.ANVIL, AmenityType.FURNACE);
            case CRAFTING                 -> EnumSet.of(AmenityType.CRAFTING_TABLE);
            // Phase 6.6.1.3 — production sub-skills land their amenity
            // preferences here. CANDLEMAKING intentionally returns {}
            // since CandlemakerProductionBehavior uses building origin
            // (no specific workstation block) — no amenity gates the
            // craft.
            case CARPENTRY                -> EnumSet.of(AmenityType.CRAFTING_TABLE);
            case MASONRY                  -> EnumSet.of(AmenityType.STONECUTTER);
            case WEAVING                  -> EnumSet.of(AmenityType.LOOM);
            case MEDICINE,
                 VILLAGE_MEDICINE,
                 COMBAT_MEDICINE          -> EnumSet.of(AmenityType.BREWING_STAND);
            default                       -> Set.of();
        };
    }

    private static Set<AmenityType> amenitiesForProfession(Profession p) {
        if (p == null || p == Profession.NONE) return Set.of();
        // Profession-side preferences are slightly richer than skill-side —
        // a founder wants storage too, not just the workstation. Chest
        // covers the storage need for any workshop-tier business.
        return switch (p) {
            case BAKER      -> EnumSet.of(AmenityType.SMOKER, AmenityType.FURNACE, AmenityType.CHEST);
            case MILLER     -> EnumSet.of(AmenityType.GRINDSTONE, AmenityType.CHEST);
            case BLACKSMITH -> EnumSet.of(AmenityType.ANVIL, AmenityType.FURNACE, AmenityType.CHEST);
            case CARPENTER  -> EnumSet.of(AmenityType.CRAFTING_TABLE, AmenityType.CHEST);
            // Phase 6.6.1.3 — production-trade founders want their
            // workstation + storage in the same building.
            case STONEMASON -> EnumSet.of(AmenityType.STONECUTTER, AmenityType.CHEST);
            case WEAVER     -> EnumSet.of(AmenityType.LOOM, AmenityType.CHEST);
            // CANDLEMAKER has no workstation block (works at building
            // origin); storage-only preference is still meaningful for
            // a founder choosing a house.
            case CANDLEMAKER -> EnumSet.of(AmenityType.CHEST);
            default         -> Set.of();
        };
    }

    private static Skill parseSkill(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return Skill.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Profession parseProfession(String name) {
        if (name == null || name.isEmpty()) return Profession.NONE;
        try { return Profession.valueOf(name); }
        catch (IllegalArgumentException e) { return Profession.NONE; }
    }

    public LifeGoal withProgress(int newProgress) {
        int clamped = Math.max(0, Math.min(targetCount, newProgress));
        return new LifeGoal(goalId, type, startTick, targetTick, targetParam,
                targetCount, clamped, status, importance, narrative);
    }

    public LifeGoal withStatus(GoalStatus newStatus) {
        return new LifeGoal(goalId, type, startTick, targetTick, targetParam,
                targetCount, progressCount, newStatus, importance, narrative);
    }

    public static final Codec<LifeGoal> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("goalId").forGetter(LifeGoal::goalId),
            LifeGoalType.CODEC.fieldOf("type").forGetter(LifeGoal::type),
            Codec.LONG.fieldOf("startTick").forGetter(LifeGoal::startTick),
            Codec.LONG.optionalFieldOf("targetTick", 0L).forGetter(LifeGoal::targetTick),
            Codec.STRING.optionalFieldOf("targetParam", "").forGetter(LifeGoal::targetParam),
            Codec.INT.optionalFieldOf("targetCount", 1).forGetter(LifeGoal::targetCount),
            Codec.INT.optionalFieldOf("progressCount", 0).forGetter(LifeGoal::progressCount),
            GoalStatus.CODEC.optionalFieldOf("status", GoalStatus.ACTIVE).forGetter(LifeGoal::status),
            Codec.INT.optionalFieldOf("importance", 5).forGetter(LifeGoal::importance),
            Codec.STRING.optionalFieldOf("narrative", "").forGetter(LifeGoal::narrative)
    ).apply(i, LifeGoal::new));
}
