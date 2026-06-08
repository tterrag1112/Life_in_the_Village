// src/main/java/tterrag1112/life_in_the_village/Profession/Profession.java
package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

public enum Profession {
    NONE, CITIZEN, MERCHANT, WANDERING_TRADER, FARMER,
    /**
     * Phase 6.3.3.f — FARMHAND has been folded into FARMER as the
     * APPRENTICE employment tier (see {@code EmploymentTier}). The enum
     * value is retained as a {@code @Deprecated} load-time alias so
     * {@code Profession.valueOf("FARMHAND")} still resolves for legacy
     * saves; {@code TownspersonMob.readAdditionalSaveData} rewrites
     * the persisted value to {@code FARMER} on first load (one-shot
     * migration; idempotent on subsequent loads). No new code should
     * reference this value.
     */
    @Deprecated FARMHAND,
    BLACKSMITH, BUILDER,
    GUARD, STOCKPILE_KEEPER, INNKEEPER, MINER,
    VILLAGE_LEADER, KINGDOM_RULER, CARPENTER,
    MILLER,          // MILLER building — grinds wheat to flour
    BAKER,           // BAKERY building — bakes bread from flour or wheat
    STONEMASON,      // STONEMASON building — cuts stone into bricks/slabs/stairs
    WEAVER,          // WEAVER building — wool into carpets and cloth goods
    CANDLEMAKER,     // CANDLEMAKER building — honeycomb + string into candles
    GUILDMASTER, GUILDWORKER, ADVENTURER, COMPANY_WORKER,

    // ── Capital professions ───────────────────────────────────────────────────

    /**
     * Town crier and royal announcer. Assigned to the TOWN_HALL or
     * CHANCELLERY. Announces events, greets players, and broadcasts
     * kingdom decrees via chat messages. Wanders the main plaza during
     * the day.
     */
    HERALD,

    /**
     * Head of the CHANCELLERY. Manages the kingdom's administrative
     * affairs — tax collection records, trade agreements, and expansion
     * requests. The highest-ranking NPC below the KINGDOM_RULER.
     */
    CHANCELLOR,

    /**
     * Researcher assigned to the SCHOLARS_RETREAT (or LIBRARY when no
     * retreat exists). Studies books, authors original works, and
     * teaches advanced lessons. Phase 2 task 17 wires the work goal.
     */
    SCHOLAR,

    /**
     * Spiritual leader assigned to the TEMPLE. Performs blessings
     * during festivals. Provides a minor wellbeing bonus to nearby NPCs.
     * Interactable — trades rare items for reputation.
     */
    PRIEST,

    /**
     * Literate craftsman of the SCRIBE_WORKSHOP. Writes letters, copies
     * books, and drafts contracts on commission. Phase 2 task 17.
     */
    SCRIBE,

    /**
     * Curator of the LIBRARY. Catalogs and lends books, runs schooling
     * sessions, and archives village records. Phase 2 task 17.
     */
    LIBRARIAN,

    /**
     * Diagnoses and treats sick or injured villagers from the
     * HEALER_HUT. Produces remedies between treatments and runs the
     * around-the-clock plague response. Phase 3 task 21.
     */
    HEALER,

    // ── Track D1 (Phase 0) — kingdom-tier office professions ────────────────
    // These are placeholders for D3's office-driven population logic.
    // No goal class, no workplace binding, no NPC ever spawns with one
    // of these professions until D3. Listed so {@code OfficeRegistry}
    // has the eligibility tokens it needs.

    /**
     * Track D1 — head of the kingdom's standing forces. Bound to the
     * {@code kingdom_general} office. D3 wires the muster + patrol
     * behaviour goal.
     */
    GENERAL,

    /**
     * Track D1 — judicial officer for kingdom-tier law. Bound to the
     * {@code kingdom_magistrate} office. D3 wires court / sentencing.
     */
    MAGISTRATE,

    /**
     * Track D1 — head of the kingdom's intelligence service. Bound to
     * the {@code kingdom_spymaster} office. D3 wires informants /
     * intrigue.
     */
    SPYMASTER,

    /**
     * Track D1 — envoy responsible for inter-kingdom relations. Bound
     * to the {@code kingdom_diplomat} office. D3 wires treaty
     * negotiation and embassy protocol.
     */
    DIPLOMAT,

    /**
     * Religion Rework R6 — monastic. Assigned to a MONASTERY or ABBEY;
     * takes the building's faith (like shrine clergy) but is NOT a
     * rite-officiant (no ordination / rite-claim — those gate on PRIEST).
     * A multi-skilled generalist whose varied monastic crafts come from
     * developed skills via the M1/M2 skills-first primitive (wired in R6b).
     * Appended at the enum tail so existing ordinals are unshifted.
     */
    MONK;

    public String getDisplayName() {
        return switch (this) {
            case NONE             -> "Unemployed";
            case CITIZEN          -> "Citizen";
            case KINGDOM_RULER    -> "Ruler";
            case VILLAGE_LEADER   -> "Village Leader";
            case STOCKPILE_KEEPER -> "Stockpile Keeper";
            case COMPANY_WORKER   -> "Business Worker";
            default -> name().charAt(0) + name().substring(1).toLowerCase();
        };
    }

    public static Profession professionFor(BuildingType type) {
        return switch (type) {
            case BLACKSMITH      -> Profession.BLACKSMITH;
            case FARMHOUSE       -> Profession.FARMER;
            case MINE            -> Profession.MINER;
            case MARKET          -> Profession.MERCHANT;
            case INN             -> Profession.INNKEEPER;
            case STOCKPILE       -> Profession.STOCKPILE_KEEPER;
            case GUARD_TOWER     -> Profession.GUARD;
            case CARPENTRY       -> Profession.CARPENTER;
            case GUILD_HALL,
                 GUILD_HALL_CRAFTSMEN,
                 GUILD_HALL_MERCHANTS,
                 GUILD_HALL_AGRICULTURAL,
                 GUILD_HALL_RELIGIOUS,
                 GUILD_HALL_SCHOLARLY -> Profession.GUILDWORKER;
            case TOWN_HALL       -> Profession.VILLAGE_LEADER;
            case CASTLE          -> Profession.KINGDOM_RULER;
            case CHANCELLERY     -> Profession.CHANCELLOR;
            case LIBRARY         -> Profession.LIBRARIAN;
            case SCRIBE_WORKSHOP -> Profession.SCRIBE;
            case SCHOLARS_RETREAT-> Profession.SCHOLAR;
            case TEMPLE, CHAPEL, SHRINE -> Profession.PRIEST;
            case MONASTERY, ABBEY -> Profession.MONK;
            case HEALER_HUT      -> Profession.HEALER;
            case TREASURY        -> Profession.GUARD;   // guarded, not staffed
            case HOUSE           -> Profession.NONE;
            case MILLER          -> Profession.MILLER;
            case STONEMASON      -> Profession.STONEMASON;
            case WEAVER          -> Profession.WEAVER;
            case CANDLEMAKER     -> Profession.CANDLEMAKER;
            case BAKERY ->  Profession.BAKER;
            default              -> Profession.NONE;
        };
    }
}