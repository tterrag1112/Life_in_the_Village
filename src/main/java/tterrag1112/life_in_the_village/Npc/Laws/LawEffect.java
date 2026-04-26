package tterrag1112.life_in_the_village.Npc.Laws;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Per-law behaviour hook. Spec line 79.
 *
 * <p>Most laws implement only one or two of the default-no-op methods.
 * v1 split: hot-path hooks (price, tax, wage, schedule) are
 * <em>queried</em> by external code via the dedicated hook classes
 * ({@link tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks
 * LawPriceHooks}, {@link LawTaxHooks}, {@link LawScheduleHooks}) so
 * those callers don't have to walk the registry on every call. The
 * interface methods below are the authoritative source of truth — the
 * hook classes iterate {@link LawEffectRegistry} and call the relevant
 * default for each active law.</p>
 *
 * <p>Lifecycle hooks {@link #onEnact} / {@link #onRepeal} fire once per
 * transition. Use them for "publish a gossip seed", "schedule a one-
 * shot timer", "snapshot a baseline value" — nothing that needs to
 * track every game tick.</p>
 *
 * <p>Crime hooks ({@code modifyPunishment}, {@code blocksAction}) are
 * skeletal — the crime subsystem (next session, doc 19) will call them
 * once it lands its types. Phase 3 keeps the methods so doc 19 doesn't
 * have to retrofit them.</p>
 */
public interface LawEffect {

    /** Which {@link VillageLaw} this effect implements. */
    VillageLaw law();

    /** Fired exactly once when the law transitions vacant → enacted. */
    default void onEnact(ServerLevel level, Village village, VillageSavedData data, LawParams params) {}

    /** Fired exactly once when the law transitions enacted → vacant. */
    default void onRepeal(ServerLevel level, Village village, VillageSavedData data) {}

    // ── Tax / wage / treasury hooks (queried by LawTaxHooks) ──────────────

    /** Multiplier applied to baseline property tax. Default 1.0. */
    default double propertyTaxMultiplier(Village village, LawParams params) { return 1.0; }

    /** Multiplier applied to baseline market tax slice. Default 1.0. */
    default double marketTaxMultiplier(Village village, LawParams params) { return 1.0; }

    /** Daily subsidy bronze added to a profession's wage from treasury. Default 0. */
    default long subsidyForProfession(Village village, Profession profession, LawParams params) { return 0L; }

    /** Workshop-profit fraction redirected to treasury daily. Default 0. */
    default float profitsToTreasuryFraction(Village village, LawParams params) { return 0f; }

    // ── Channel-side hooks (queried by LawPriceHooks) ─────────────────────

    /** Optional hard cap (per-unit bronze) on a food item's quote. Default {@code -1} = no cap. */
    default long foodPriceCeiling(Village village, LawParams params) { return -1L; }
    /** Optional hard floor (per-unit bronze) on a food item's quote. Default {@code -1} = no floor. */
    default long foodPriceFloor(Village village, LawParams params) { return -1L; }

    /** Toll bronze added to caravan / visitor entry. 0 = no toll. */
    default long entryToll(Village village, LawParams params) { return 0L; }

    /** True when a caravan from outside this village is forbidden trade here. */
    default boolean blocksForeignCaravan(Village village, LawParams params) { return false; }

    // ── Schedule hooks (queried by LawScheduleHooks) ──────────────────────

    /**
     * Returns the daytick at which the HOME phase should override the
     * resolved phase, or {@code -1} when this law doesn't shift the
     * sleep window. CURFEW reads here.
     */
    default int curfewDaytickStart(Village village, LawParams params) { return -1; }

    /** True when this law marks the village as "festival attendance forced". */
    default boolean festivalAttendanceMandatory(Village village, LawParams params) { return false; }

    /** True when this law forces children into schooling daily. */
    default boolean compelsChildSchooling(Village village, LawParams params) { return false; }
}
