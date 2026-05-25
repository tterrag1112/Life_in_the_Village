package tterrag1112.life_in_the_village.Npc.Skills;

import com.mojang.serialization.Codec;

import javax.annotation.Nullable;

/**
 * The cross-profession proficiencies. Persist when an NPC switches
 * professions; drive office competence, apprenticeship matching, and
 * production speed/quality multipliers (see
 * {@code docs/npc_redesign/05-skill-system.md}).
 *
 * <h3>Phase 6.3.2.b — hierarchical tree</h3>
 * <p>Each value optionally carries a {@link #parent} and a
 * {@link #propRate}. {@link SkillComponent#addXp} cascades: XP awarded
 * to a child propagates upward to the parent at {@code propRate} of the
 * post-buff amount. Depth is bounded (3 tiers max as of 6.3.2.b).</p>
 *
 * <p>Existing flat skills declared first so their ordinals stay stable
 * for save backwards-compat. New subskills are appended at the end.
 * Codec uses {@code valueOf(name)} so new values load cleanly and
 * missing values on old saves just don't appear (default to 0 XP).</p>
 */
public enum Skill {
    // ── Tier 0 (flat / parents) — ordinals 0..7 must NOT shift ──────────────
    FARMING(null, 0.0),
    CRAFTING(null, 0.0),
    COMBAT(null, 0.0),
    COMMERCE(null, 0.0),
    SOCIAL(null, 0.0),
    LITERACY(null, 0.0),
    SURVIVAL(null, 0.0),
    MEDICINE(null, 0.0),

    // ── Tier 1 (Phase 6.3.2.b additions) — appended for save stability ─────
    MELEE(COMBAT, 0.25),
    RANGED(COMBAT, 0.25),
    MAGIC(COMBAT, 0.25),
    BLACKSMITHING(CRAFTING, 0.25),
    COMBAT_MEDICINE(MEDICINE, 0.25),
    VILLAGE_MEDICINE(MEDICINE, 0.25),

    // ── Tier 2 (Phase 6.3.2.b additions) ───────────────────────────────────
    TOOLSMITHING(BLACKSMITHING, 0.25),
    WEAPONSMITHING(BLACKSMITHING, 0.25),
    ARMORSMITHING(BLACKSMITHING, 0.25),

    // ── Tier 1 (Phase 6.3.3.g additions — animal husbandry) ────────────────
    /** Animal-husbandry parent. Cascades 25% → FARMING. Used by the
     *  ANIMAL_SPECIALIST / ANIMAL_TENDER FarmRoles for tending livestock. */
    ANIMAL_HUSBANDRY(FARMING, 0.25),

    // ── Tier 2 (Phase 6.3.3.g additions — beekeeping) ──────────────────────
    /** Beekeeping sub-skill. Cascades 25% → ANIMAL_HUSBANDRY (which then
     *  cascades 25% → FARMING). Used by hive-tending behaviors. The
     *  highest-BEEKEEPING NPC in a village becomes the de facto apiarist;
     *  no formal Specialization needed. */
    BEEKEEPING(ANIMAL_HUSBANDRY, 0.25),

    // ── Tier 1 (Phase 6.3.3.i additions — crop specialization) ─────────────
    /** Crop-farming sub-skill. Cascades 25% → FARMING. Earned from
     *  harvest / replant on non-ORCHARD crop plots. The crop_focus
     *  farmer specialization reads this for its 20-level promotion gate. */
    CROP_FARMING(FARMING, 0.25),

    // ── Tier 2 (Phase 6.3.3.i additions — orcharding) ──────────────────────
    /** Orcharding sub-skill. Cascades 25% → CROP_FARMING (then 25% →
     *  FARMING). Used for ORCHARD-type crop plots. Future content
     *  surfaces VINTNER / ORCHARDIST emergent specialties off the
     *  highest-ORCHARDING NPC in a village (no formal spec needed). */
    ORCHARDING(CROP_FARMING, 0.25),

    // ── Tier 1 (Phase 6.3.4.9 additions — milling) ─────────────────────────
    /** Grinding / milling sub-skill. Cascades 25% → CRAFTING. MILLER's
     *  primary skill axis. Earned from any MillerProductionBehavior
     *  recipe (wheat → flour, bones → bone_meal, sugar_cane → sugar).
     *  Pre-6.3.4.9 MILLER routed production XP to CROP_FARMING under
     *  FARMING, treating grinding as an agricultural sub-task; the
     *  correction reflects that milling is a CRAFTING-family
     *  processing activity, distinct from raising crops. */
    MILLING(CRAFTING, 0.25),

    // ── Tier 1 (Phase 6.3.4.10 additions — baking) ─────────────────────────
    /** Baking sub-skill. Cascades 25% → CRAFTING. BAKER's primary
     *  skill axis. Earned from baking staples (BREAD, COOKIE).
     *  Homestead-level food self-sufficiency lives here — anyone with
     *  BAKING + a furnace can make bread; BAKER's edge is mastering
     *  the PASTRY child below for sweet/decorative goods. */
    BAKING(CRAFTING, 0.25),

    // ── Tier 2 (Phase 6.3.4.10 additions — pastry) ─────────────────────────
    /** Pastry sub-skill. Cascades 25% → BAKING (then 25% → CRAFTING).
     *  Rarer / harder to develop — earned from sweets and
     *  decorative goods (PUMPKIN_PIE, CAKE). Three-level propagation
     *  via SkillComponent.addXp recursion: PASTRY xp → BAKING ¼ →
     *  CRAFTING 1/16. */
    PASTRY(BAKING, 0.25),

    // ── Tier 1 (Phase 6.6.1.2 additions — production-trade sub-skills) ─────
    /** Woodworking sub-skill. Cascades 25% → CRAFTING. CARPENTER's
     *  primary skill axis. Earned from log→planks, plank→furniture,
     *  fence/door/stair recipes. Mirrors the BAKING/MILLING pattern:
     *  homestead-level woodwork lives here; future sub-skills
     *  (FINE_CARPENTRY, SHIPWRIGHTING) cascade through this. */
    CARPENTRY(CRAFTING, 0.25),
    /** Stoneworking sub-skill. Cascades 25% → CRAFTING. STONEMASON's
     *  primary skill axis. Earned from cut-stone, brick, slab/stair/
     *  wall recipes at the stonecutter. */
    MASONRY(CRAFTING, 0.25),
    /** Textile sub-skill. Cascades 25% → CRAFTING. WEAVER's primary
     *  skill axis. Earned from string→wool, wool→carpet, banner work
     *  at the loom. */
    WEAVING(CRAFTING, 0.25),
    /** Wax / light sub-skill. Cascades 25% → CRAFTING. CANDLEMAKER's
     *  primary skill axis. Earned from candle (honeycomb+string) and
     *  torch (stick+coal) recipes. No dedicated workstation — the
     *  craft happens at the building origin. */
    CANDLEMAKING(CRAFTING, 0.25);

    @Nullable private final Skill parent;
    private final double propRate;

    Skill(@Nullable Skill parent, double propRate) {
        this.parent = parent;
        this.propRate = propRate;
    }

    @Nullable public Skill parent()   { return parent; }
    public double propRate()          { return propRate; }
    public boolean hasParent()        { return parent != null; }

    public static final Codec<Skill> CODEC =
            Codec.STRING.xmap(Skill::valueOf, Skill::name);
}
