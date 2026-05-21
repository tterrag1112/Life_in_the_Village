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
    BEEKEEPING(ANIMAL_HUSBANDRY, 0.25);

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
