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
    /** Relaxation time constant (ticks) — τ ≈ 3 in-game days; half-life ≈ 2 days. */
    private static final float TAU = 72000f;
    /** Multiplier when an act serves one of the faith's esteemed virtues. */
    private static final float ALIGNED_BONUS = 1.5f;

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

    // ── Earning ──────────────────────────────────────────────────────────────

    /** Grants favour with {@code religionId} for {@code act}, deity-demand-weighted
     *  and clamped to the player's piety-tier cap in that faith. No-op for a null
     *  faith or a player with no standing in it (cap 0). */
    public static void award(ServerLevel level, UUID playerId, String religionId,
                             FavourAct act, long now) {
        awardConcept(level, playerId, religionId, act, act.concept, now);
    }

    /** Virtue hook — grants {@link FavourAct#VIRTUE} weighted by {@code concept}
     *  (the specific virtue the player's act served). Ready for player-virtue hooks
     *  (D2's player side was thin); used by the debug command this phase. */
    public static void awardVirtue(ServerLevel level, UUID playerId, String religionId,
                                   FaithConcept concept, long now) {
        awardConcept(level, playerId, religionId, FavourAct.VIRTUE, concept, now);
    }

    private static void awardConcept(ServerLevel level, UUID playerId, String religionId,
                                     FavourAct act, FaithConcept concept, long now) {
        if (religionId == null) return;
        RiteSavedData data = RiteSavedData.get(level);
        PietyTier tier = tierFor(data, playerId, religionId);
        float cap = cap(tier);
        if (cap <= 0f) return;                              // no standing → can't hold favour
        float weight = aligned(religionId, concept) ? ALIGNED_BONUS : 1f;
        float current = current(level, playerId, religionId, now);
        float next = clamp(current + act.base * weight, 0f, cap);
        data.getOrCreatePlayerFavour(playerId).set(religionId, next, now);
        data.markDirty();
    }

    /** Debug/test grant — adds raw favour bypassing the piety cap (so V2 spend can be
     *  exercised regardless of the tester's piety), clamped to {@link #MAX_FAVOUR}. */
    public static float debugGrant(ServerLevel level, UUID playerId, String religionId,
                                   float amount, long now) {
        if (religionId == null) return 0f;
        RiteSavedData data = RiteSavedData.get(level);
        float next = clamp(current(level, playerId, religionId, now) + amount, 0f, MAX_FAVOUR);
        data.getOrCreatePlayerFavour(playerId).set(religionId, next, now);
        data.markDirty();
        return next;
    }

    // ── Spending (V2's entry point) ──────────────────────────────────────────

    /** Spends {@code amount} favour with {@code religionId} if available. Returns
     *  true on success (favour debited), false if insufficient. The V2 miracle
     *  layer calls this; a debug command exercises it now. */
    public static boolean spend(ServerLevel level, UUID playerId, String religionId,
                                float amount, long now) {
        if (religionId == null || amount <= 0f) return false;
        RiteSavedData data = RiteSavedData.get(level);
        float current = current(level, playerId, religionId, now);
        if (current < amount) return false;
        data.getOrCreatePlayerFavour(playerId).set(religionId, current - amount, now);
        data.markDirty();
        return true;
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** The player's current favour with {@code religionId} — the stored value
     *  relaxed to {@code now} and clamped to the piety-tier cap. A player with no
     *  entry sits at the tier equilibrium (passive accrual). */
    public static float current(ServerLevel level, UUID playerId, String religionId, long now) {
        RiteSavedData data = RiteSavedData.get(level);
        PietyTier tier = tierFor(data, playerId, religionId);
        float eq = equilibrium(tier);
        float cap = cap(tier);
        PlayerFavour fav = data.getPlayerFavour(playerId).orElse(null);
        PlayerFavour.Entry e = fav == null ? null : fav.raw(religionId);
        if (e == null) return clamp(eq, 0f, cap);          // passive baseline for the tier
        float dt = Math.max(0f, now - e.lastTick());
        float relaxed = eq + (e.amount() - eq) * (float) Math.exp(-dt / TAU);
        return clamp(relaxed, 0f, cap);
    }

    // ── Piety coupling (cap + equilibrium per tier in THIS faith) ─────────────

    /** The piety tier of the player's belief in {@code religionId} (per-faith, not
     *  just their primary), so favour is capped/baselined by standing in that faith
     *  — and fades when they lapse in it. Public for the V2 miracle tier-gate. */
    public static PietyTier tierIn(ServerLevel level, UUID playerId, String religionId) {
        return tierFor(RiteSavedData.get(level), playerId, religionId);
    }

    private static PietyTier tierFor(RiteSavedData data, UUID playerId, String religionId) {
        PietyComponent piety = data.getPlayerPiety(playerId).orElse(null);
        float strength = piety == null ? 0f : piety.beliefIn(religionId);
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

    /** True when {@code concept} is one of the faith's authored virtues (D1) — the
     *  deity-demand alignment that earns the {@link #ALIGNED_BONUS}. */
    private static boolean aligned(String religionId, FaithConcept concept) {
        if (concept == null) return false;
        ReligionIdentity id = ReligionIdentity.get(religionId);
        if (id == null) return false;
        return id.virtues().stream().anyMatch(v -> v.concept() == concept);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
