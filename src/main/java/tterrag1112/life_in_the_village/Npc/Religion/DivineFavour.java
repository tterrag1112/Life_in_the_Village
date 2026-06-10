package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Divine Layer V1 — the <b>Divine Favour</b> economy: the one helper the existing
 * religious acts call to grant a deity's regard, and the one V2 miracles will call
 * to spend it. Favour is <b>distinct from piety</b> (piety is belief; favour is
 * standing) but <b>built on it</b>: each deity's favour relaxes toward, and is
 * capped by, the player's piety tier <i>in that faith</i> — so a lapsed believer's
 * favour fades, and a devout one passively holds a baseline.
 *
 * <h3>The single relaxation model (lazy — no per-tick scan)</h3>
 * A deity's favour is stored as {@code (amount, lastTick)} ({@link PlayerFavour})
 * and relaxed on read toward the piety-tier <b>equilibrium</b> with time constant
 * {@link #TAU}: {@code v' = eq + (v − eq)·e^(−Δt/τ)}, clamped to {@code [0, cap]}.
 * This one formula gives all three behaviours the design asks for:
 * <ul>
 *   <li><b>passive accrual</b> — a devout-but-idle player's favour rises to the
 *       tier equilibrium;</li>
 *   <li><b>gentle decay</b> — favour earned above the equilibrium by active service
 *       drifts back down to it (slow: τ ≈ 3 in-game days, half-life ≈ 2 days);</li>
 *   <li><b>lapse-fade</b> — losing the faith drops the tier (eq = cap = 0) so
 *       favour fades to nothing.</li>
 * </ul>
 *
 * <h3>Deity-demand weighting</h3>
 * An act that serves what the deity esteems earns more: the act carries a
 * {@link FaithConcept}; if that concept is one of the faith's authored virtues
 * ({@link ReligionIdentity#virtues()}, D1 — the structured form of the deity's
 * {@code demands}), the grant is multiplied by {@link #ALIGNED_BONUS}. This is what
 * makes each faith's favour economy distinct (a Sunstead adherent's GENEROSITY-laden
 * giving earns the bonus; a faith that doesn't esteem generosity earns the base).
 *
 * <p><b>Player-primary</b> (NPC favour later). No deity <i>effects</i> this phase —
 * this is the ledger + earning + spend API; miracles (V2) are the first consumer of
 * {@link #spend}.</p>
 */
public final class DivineFavour {

    private DivineFavour() {}

    /** Absolute ceiling on any deity's favour (the PIOUS-tier cap). */
    public static final float MAX_FAVOUR = 100f;
    /** Divine Layer V4 — the negative pole: favour is SIGNED. Below zero is
     *  displeasure (sacrilege drives it down); this is the floor (deep wrath). The
     *  positive side stays piety-tier-capped; only the floor is symmetric. */
    public static final float DISPLEASURE_FLOOR = -100f;
    /** Displeasure thresholds (favour ≤): omen (warning) → curse → wrath. */
    public static final float OMEN_AT  = -1f;
    public static final float CURSE_AT = -25f;
    public static final float WRATH_AT = -60f;
    /** Relaxation time constant (ticks) — τ ≈ 3 in-game days; half-life ≈ 2 days. */
    private static final float TAU = 72000f;
    /** Multiplier when an act serves one of the faith's esteemed virtues. */
    private static final float ALIGNED_BONUS = 1.5f;

    /** Divine Layer V4 — a deity's displeasure depth, by signed-favour band. */
    public enum DispleasureTier { NONE, OMEN, CURSE, WRATH }

    /** Classifies a (signed) favour value into a displeasure band. */
    public static DispleasureTier displeasureOf(float favour) {
        if (favour <= WRATH_AT) return DispleasureTier.WRATH;
        if (favour <= CURSE_AT) return DispleasureTier.CURSE;
        if (favour <= OMEN_AT)  return DispleasureTier.OMEN;
        return DispleasureTier.NONE;
    }

    /**
     * The existing religious acts favour hooks into, each with a base grant and the
     * {@link FaithConcept} it serves (null = no specific demand, so no deity bonus).
     * {@link #VIRTUE} takes its concept per call (the act that triggered it).
     */
    public enum FavourAct {
        OFFERING       (6f, FaithConcept.GENEROSITY),
        TITHE          (4f, FaithConcept.GENEROSITY),
        ATTEND_RITE    (4f, null),
        COMMISSION_RITE(6f, FaithConcept.GENEROSITY),
        PILGRIMAGE     (12f, null),
        VIRTUE         (5f, null);

        final float base;
        final FaithConcept concept;

        FavourAct(float base, FaithConcept concept) { this.base = base; this.concept = concept; }
    }

    // =========================================================================
    // Religion-facing CONVENIENCE layer (F1a sub-stage 3a)
    //
    // The religious ACTS happen in a religion's terms (a player attends a *Sunstead*
    // rite); favour flows to that religion's god(s). These fan-out helpers are the
    // ONE home for the later multi-god policy — single-god starters resolve to just
    // the primary, behaviour-identical to before. Call sites keep their religionId.
    // =========================================================================

    /** Awards {@code act} to every god the religion venerates + fires the V3 calling
     *  hook (the calling is a religion-keyed task). */
    public static void awardForReligion(ServerLevel level, UUID playerId,
                                        String religionId, FavourAct act, long now) {
        Religion r = Religions.get(level, religionId);
        if (r == null) return;
        for (God g : GodRegistry.godsFor(r)) award(level, playerId, g.id(), act, now);
        // Divine Layer V3 — an act may fulfil a standing divine calling (religion-
        // keyed); the bonus uses addCappedForReligion (no calling re-trigger).
        DivineVision.onFavourAct(level, playerId, religionId, act, now);
    }

    /** Drives displeasure with every god the religion venerates (V4 sacrilege). */
    public static void offendForReligion(ServerLevel level, UUID playerId,
                                         String religionId, float amount, long now) {
        Religion r = Religions.get(level, religionId);
        if (r == null) return;
        for (God g : GodRegistry.godsFor(r)) offend(level, playerId, g.id(), amount, now);
    }

    /** Capped (no-hook) favour add to the religion's god(s) — the V3 calling bonus. */
    public static void addCappedForReligion(ServerLevel level, UUID playerId,
                                            String religionId, float amount, long now) {
        Religion r = Religions.get(level, religionId);
        if (r == null) return;
        for (God g : GodRegistry.godsFor(r)) addCapped(level, playerId, g.id(), amount, now);
    }

    /** The religion's headline favour — its PRIMARY god's current standing (0 if
     *  god-less); the readout/sacrilege debug reads in religion terms. */
    public static float currentForReligion(ServerLevel level, UUID playerId,
                                           String religionId, long now) {
        Religion r = Religions.get(level, religionId);
        God g = r == null ? null : GodRegistry.primaryGod(r).orElse(null);
        return g == null ? 0f : current(level, playerId, g.id(), now);
    }

    // =========================================================================
    // Core, god-keyed API. The storage methods take a god id; favour is stored
    // per god (PlayerFavour). The god's tier/cap come from the best piety among the
    // religions that venerate it (resolved per-world via GodRegistry.religionsVenerating).
    // =========================================================================

    /** Grants favour with {@code godId} for {@code act}, demand-weighted by the GOD's
     *  virtues, clamped to the god's piety-tier cap. */
    public static void award(ServerLevel level, UUID playerId, String godId,
                             FavourAct act, long now) {
        awardConcept(level, playerId, godId, act, act.concept, now);
    }

    /** Grants {@link FavourAct#VIRTUE} weighted by {@code concept} to {@code godId}. */
    public static void awardVirtue(ServerLevel level, UUID playerId, String godId,
                                   FaithConcept concept, long now) {
        awardConcept(level, playerId, godId, FavourAct.VIRTUE, concept, now);
    }

    private static void awardConcept(ServerLevel level, UUID playerId, String godId,
                                     FavourAct act, FaithConcept concept, long now) {
        if (godId == null) return;
        RiteSavedData data = RiteSavedData.get(level);
        float cap = cap(tierFor(level, data, playerId, godId));
        if (cap <= 0f) return;                              // no standing → can't hold favour
        float weight = aligned(godId, concept) ? ALIGNED_BONUS : 1f;
        // Lower bound is the displeasure floor (not 0): a repenting, displeased
        // player's act climbs GRADUALLY out of the negative, not instantly to 0.
        float next = clamp(current(level, playerId, godId, now) + act.base * weight,
                DISPLEASURE_FLOOR, cap);
        data.getOrCreatePlayerFavour(playerId).set(godId, next, now);
        data.markDirty();
    }

    /** Adds favour respecting the god's piety-tier cap, WITHOUT the calling hook
     *  (used for the V3 calling-fulfilment bonus). */
    public static void addCapped(ServerLevel level, UUID playerId, String godId,
                                 float amount, long now) {
        if (godId == null) return;
        RiteSavedData data = RiteSavedData.get(level);
        float cap = cap(tierFor(level, data, playerId, godId));
        if (cap <= 0f) return;
        float next = clamp(current(level, playerId, godId, now) + amount,
                DISPLEASURE_FLOOR, cap);
        data.getOrCreatePlayerFavour(playerId).set(godId, next, now);
        data.markDirty();
    }

    /** Divine Layer V4 — sacrilege drives the god's favour DOWN into displeasure. */
    public static void offend(ServerLevel level, UUID playerId, String godId,
                              float amount, long now) {
        if (godId == null || amount <= 0f) return;
        RiteSavedData data = RiteSavedData.get(level);
        float cap = cap(tierFor(level, data, playerId, godId));
        if (cap <= 0f) return;
        float next = clamp(current(level, playerId, godId, now) - amount,
                DISPLEASURE_FLOOR, cap);
        data.getOrCreatePlayerFavour(playerId).set(godId, next, now);
        data.markDirty();
    }

    /** Debug/test grant — raw favour to {@code godId} bypassing the piety cap,
     *  clamped to {@link #MAX_FAVOUR}. */
    public static float debugGrant(ServerLevel level, UUID playerId, String godId,
                                   float amount, long now) {
        if (godId == null) return 0f;
        RiteSavedData data = RiteSavedData.get(level);
        float next = clamp(current(level, playerId, godId, now) + amount,
                DISPLEASURE_FLOOR, MAX_FAVOUR);
        data.getOrCreatePlayerFavour(playerId).set(godId, next, now);
        data.markDirty();
        return next;
    }

    /** Spends {@code amount} of {@code godId}'s favour if available. */
    public static boolean spend(ServerLevel level, UUID playerId, String godId,
                                float amount, long now) {
        if (godId == null || amount <= 0f) return false;
        RiteSavedData data = RiteSavedData.get(level);
        float current = current(level, playerId, godId, now);
        if (current < amount) return false;
        data.getOrCreatePlayerFavour(playerId).set(godId, current - amount, now);
        data.markDirty();
        return true;
    }

    /** The player's current favour with {@code godId} — stored value relaxed to
     *  {@code now}, clamped to the god's piety-tier cap. No entry → tier equilibrium. */
    public static float current(ServerLevel level, UUID playerId, String godId, long now) {
        RiteSavedData data = RiteSavedData.get(level);
        PietyTier tier = tierFor(level, data, playerId, godId);
        float eq = equilibrium(tier);
        float cap = cap(tier);
        PlayerFavour fav = data.getPlayerFavour(playerId).orElse(null);
        PlayerFavour.Entry e = fav == null ? null : fav.raw(godId);
        if (e == null) return clamp(eq, 0f, cap);          // no entry → positive baseline
        float dt = Math.max(0f, now - e.lastTick());
        float relaxed = eq + (e.amount() - eq) * (float) Math.exp(-dt / TAU);
        return clamp(relaxed, DISPLEASURE_FLOOR, cap);
    }

    // ── Piety coupling (god's tier = best belief among venerating religions) ──

    /** The piety tier the favour cap/equilibrium for {@code godId} derive from:
     *  piety is belief in a RELIGION, so a god's tier is the BEST tier among the
     *  religions that venerate it (single-god starters → the one religion's belief). */
    public static PietyTier tierForGod(ServerLevel level, UUID playerId, String godId) {
        return tierFor(level, RiteSavedData.get(level), playerId, godId);
    }

    private static PietyTier tierFor(ServerLevel level, RiteSavedData data,
                                     UUID playerId, String godId) {
        PietyComponent piety = data.getPlayerPiety(playerId).orElse(null);
        if (piety == null) return PietyTier.UNAFFILIATED;
        float strength = 0f;
        for (String religionId : GodRegistry.religionsVenerating(level, godId)) {
            strength = Math.max(strength, piety.beliefIn(religionId));
        }
        if (strength < 0.2f) return PietyTier.UNAFFILIATED;
        if (strength < 0.5f) return PietyTier.FAITHFUL;
        if (strength < 0.8f) return PietyTier.DEVOUT;
        return PietyTier.PIOUS;
    }

    /** Passive baseline favour the tier settles at (idle worshipper holds this). */
    private static float equilibrium(PietyTier tier) {
        return switch (tier) {
            case UNAFFILIATED -> 0f;
            case FAITHFUL     -> 5f;
            case DEVOUT       -> 15f;
            case PIOUS        -> 30f;
        };
    }

    /** Per-tier favour ceiling (active service earns up toward this). */
    private static float cap(PietyTier tier) {
        return switch (tier) {
            case UNAFFILIATED -> 0f;
            case FAITHFUL     -> 30f;
            case DEVOUT       -> 60f;
            case PIOUS        -> MAX_FAVOUR;
        };
    }

    /** True when {@code concept} is one of the GOD's demanded virtues — the
     *  deity-demand alignment that earns the {@link #ALIGNED_BONUS}. */
    private static boolean aligned(String godId, FaithConcept concept) {
        if (concept == null) return false;
        God g = GodRegistry.get(godId);
        if (g == null) return false;
        return g.virtues().stream().anyMatch(v -> v.concept() == concept);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
