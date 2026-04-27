package tterrag1112.life_in_the_village.Npc.Laws;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Village-scoped laws a leader can enact. Spec line 19 — 22 entries
 * across {@link LawCategory#ECONOMY}, {@link LawCategory#CRIME},
 * {@link LawCategory#SOCIAL}, and {@link LawCategory#ECONOMIC_RESTRICTION}.
 *
 * <p>Each law carries:</p>
 * <ul>
 *   <li>{@link #category()}</li>
 *   <li>{@link #enactmentDifficulty()} — 1..10, drives the per-month roll
 *   in {@link LawDecisionEngine}.</li>
 *   <li>{@link #popularityBase()} — −50..+50; villagers' default reaction
 *   before per-villager trait fit.</li>
 *   <li>{@link #conflicts()} — laws that cannot coexist; enactment of any
 *   law in the set blocks the other(s).</li>
 *   <li>{@link #traitFit()} — multiplier for popularity / NPC-leader-bias
 *   derivation per axis. Empty for a-political laws.</li>
 * </ul>
 *
 * <p>Spec "Open decisions" #4: 25+ laws is OK; ~10 see use in v1. The
 * registry tolerates unused entries.</p>
 */
public enum VillageLaw {

    // ── Economy ────────────────────────────────────────────────────────────

    TOLL_ENTRY               (LawCategory.ECONOMY, 4,  -10,
                              Set.of(),
                              Map.of(TraitAxis.GENEROSITY, -0.4f, TraitAxis.AMBITION, +0.3f)),
    MARKET_TAX_DOUBLE        (LawCategory.ECONOMY, 5,  -25,
                              Set.of("MARKET_TAX_REDUCED"),
                              Map.of(TraitAxis.GENEROSITY, -0.5f, TraitAxis.AMBITION, +0.3f)),
    MARKET_TAX_REDUCED       (LawCategory.ECONOMY, 5,  +25,
                              Set.of("MARKET_TAX_DOUBLE"),
                              Map.of(TraitAxis.GENEROSITY, +0.5f, TraitAxis.COMPASSION, +0.2f)),
    SUBSIDIZE_FARMER         (LawCategory.ECONOMY, 4,  +15,
                              Set.of(),
                              Map.of(TraitAxis.COMPASSION, +0.5f, TraitAxis.GENEROSITY, +0.3f)),
    SUBSIDIZE_BLACKSMITH     (LawCategory.ECONOMY, 4,  +5,
                              Set.of(),
                              Map.of(TraitAxis.AMBITION, +0.4f, TraitAxis.INDUSTRY, +0.2f)),
    SUBSIDIZE_SCHOLAR        (LawCategory.ECONOMY, 5,  0,
                              Set.of(),
                              Map.of(TraitAxis.HONESTY, +0.3f, TraitAxis.COMPASSION, +0.2f)),
    SUBSIDIZE_HEALER         (LawCategory.ECONOMY, 4,  +20,
                              Set.of(),
                              Map.of(TraitAxis.COMPASSION, +0.6f)),
    PROFITS_TO_TREASURY      (LawCategory.ECONOMY, 6,  -15,
                              Set.of(),
                              Map.of(TraitAxis.GENEROSITY, -0.4f, TraitAxis.AMBITION, +0.4f)),
    PROPERTY_TAX_DOUBLE      (LawCategory.ECONOMY, 5,  -30,
                              Set.of("PROPERTY_TAX_WAIVED"),
                              Map.of(TraitAxis.AMBITION, +0.3f, TraitAxis.GENEROSITY, -0.5f)),
    PROPERTY_TAX_WAIVED      (LawCategory.ECONOMY, 6,  +35,
                              Set.of("PROPERTY_TAX_DOUBLE"),
                              Map.of(TraitAxis.GENEROSITY, +0.6f, TraitAxis.COMPASSION, +0.3f)),

    // ── Crime & justice ────────────────────────────────────────────────────

    CURFEW                   (LawCategory.CRIME,   6,  -20,
                              Set.of(),
                              Map.of(TraitAxis.SOCIABILITY, -0.4f, TraitAxis.AMBITION, +0.3f)),
    DOUBLE_PUNISHMENT        (LawCategory.CRIME,   5,  -10,
                              Set.of("PARDON_FIRST_OFFENSE"),
                              Map.of(TraitAxis.COMPASSION, -0.4f, TraitAxis.AMBITION, +0.4f)),
    PARDON_FIRST_OFFENSE     (LawCategory.CRIME,   4,  +15,
                              Set.of("DOUBLE_PUNISHMENT"),
                              Map.of(TraitAxis.COMPASSION, +0.5f, TraitAxis.HONESTY, +0.2f)),
    BAN_EXECUTION            (LawCategory.CRIME,   7,  +10,
                              Set.of(),
                              Map.of(TraitAxis.COMPASSION, +0.6f)),
    QUARANTINE_VILLAGE       (LawCategory.CRIME,   5,  -25,
                              Set.of(),
                              Map.of(TraitAxis.COMPASSION, +0.4f, TraitAxis.SOCIABILITY, -0.3f)),

    // ── Social / cultural ──────────────────────────────────────────────────

    FOREIGN_TRADER_BAN       (LawCategory.SOCIAL,  6,  -15,
                              Set.of(),
                              Map.of(TraitAxis.SOCIABILITY, -0.4f, TraitAxis.GENEROSITY, -0.3f)),
    PILGRIM_WELCOME_BONUS    (LawCategory.SOCIAL,  4,  +5,
                              Set.of(),
                              Map.of(TraitAxis.COMPASSION, +0.3f, TraitAxis.SOCIABILITY, +0.3f)),
    COMPULSORY_SCHOOLING     (LawCategory.SOCIAL,  6,  -5,
                              Set.of(),
                              Map.of(TraitAxis.HONESTY, +0.3f, TraitAxis.AMBITION, +0.2f)),
    TEMPLE_ATTENDANCE_EXPECTED(LawCategory.SOCIAL, 5,  -5,
                              Set.of(),
                              Map.of(TraitAxis.SOCIABILITY, +0.2f, TraitAxis.HONESTY, +0.2f)),
    FESTIVAL_MANDATORY       (LawCategory.SOCIAL,  5,  +10,
                              Set.of(),
                              Map.of(TraitAxis.SOCIABILITY, +0.4f, TraitAxis.COMPASSION, +0.2f)),

    // ── Economic restrictions ──────────────────────────────────────────────

    GUILD_MEMBERSHIP_REQUIRED(LawCategory.ECONOMIC_RESTRICTION, 7, -10,
                              Set.of(),
                              Map.of(TraitAxis.AMBITION, +0.3f, TraitAxis.SOCIABILITY, -0.2f)),
    PRICE_CEILING_FOOD       (LawCategory.ECONOMIC_RESTRICTION, 5, +25,
                              Set.of("PRICE_FLOOR_FOOD"),
                              Map.of(TraitAxis.COMPASSION, +0.5f, TraitAxis.GENEROSITY, +0.4f)),
    PRICE_FLOOR_FOOD         (LawCategory.ECONOMIC_RESTRICTION, 5, -10,
                              Set.of("PRICE_CEILING_FOOD"),
                              Map.of(TraitAxis.AMBITION, +0.3f, TraitAxis.GENEROSITY, -0.3f));

    // ── Immutable metadata ─────────────────────────────────────────────────

    private final LawCategory category;
    private final int enactmentDifficulty;
    private final int popularityBase;
    /** Names of laws that conflict with this one. Stored as strings to
     *  avoid forward-reference issues during enum init. */
    private final Set<String> conflictNames;
    private final Map<TraitAxis, Float> traitFit;

    VillageLaw(LawCategory category, int enactmentDifficulty, int popularityBase,
               Set<String> conflictNames, Map<TraitAxis, Float> traitFit) {
        this.category            = category;
        this.enactmentDifficulty = enactmentDifficulty;
        this.popularityBase      = popularityBase;
        this.conflictNames       = conflictNames;
        this.traitFit            = traitFit;
    }

    public LawCategory category()        { return category; }
    public int enactmentDifficulty()     { return enactmentDifficulty; }
    public int popularityBase()          { return popularityBase; }

    /**
     * Resolved set of conflicting laws. Computed lazily on first call so
     * the enum class finishes initialising before resolution touches
     * other constants.
     */
    public Set<VillageLaw> conflicts() {
        if (conflictsResolved == null) {
            Set<VillageLaw> resolved = EnumSet.noneOf(VillageLaw.class);
            for (String n : conflictNames) {
                try { resolved.add(VillageLaw.valueOf(n)); }
                catch (IllegalArgumentException ignored) { /* removed law */ }
            }
            conflictsResolved = Collections.unmodifiableSet(resolved);
        }
        return conflictsResolved;
    }
    private transient Set<VillageLaw> conflictsResolved;

    /** Per-axis popularity fit; range −1..+1. Empty when the law is
     *  trait-neutral (no entries returned). */
    public Map<TraitAxis, Float> traitFit() { return traitFit; }

    /**
     * Convenience for {@link LawDecisionEngine} — short flavor text used
     * in announcements and the player-leader UI. Spec "Open decisions"
     * suggested "flavor name + one-sentence mechanical description".
     */
    public String description() {
        return switch (this) {
            case TOLL_ENTRY                -> "Outsiders pay a toll at the gate.";
            case MARKET_TAX_DOUBLE         -> "Market transactions taxed at double the usual rate.";
            case MARKET_TAX_REDUCED        -> "Market transactions taxed at half the usual rate.";
            case SUBSIDIZE_FARMER          -> "Treasury pays farmers a daily subsidy.";
            case SUBSIDIZE_BLACKSMITH      -> "Treasury pays blacksmiths a daily subsidy.";
            case SUBSIDIZE_SCHOLAR         -> "Treasury pays scholars a daily stipend.";
            case SUBSIDIZE_HEALER          -> "Treasury pays healers a daily stipend.";
            case PROFITS_TO_TREASURY       -> "A slice of every workshop's daily profit flows to the treasury.";
            case PROPERTY_TAX_DOUBLE       -> "Property tax doubled.";
            case PROPERTY_TAX_WAIVED       -> "Property tax suspended.";
            case CURFEW                    -> "Citizens must be home before nightfall.";
            case DOUBLE_PUNISHMENT         -> "All criminal punishments are increased.";
            case PARDON_FIRST_OFFENSE      -> "First-time minor offenders are pardoned.";
            case BAN_EXECUTION             -> "Capital punishment is forbidden.";
            case QUARANTINE_VILLAGE -> null;
            case FOREIGN_TRADER_BAN        -> "Caravans from outside this village may not trade here.";
            case PILGRIM_WELCOME_BONUS     -> "Pilgrim visitors gain reputation faster.";
            case COMPULSORY_SCHOOLING      -> "Children must attend daily lessons.";
            case TEMPLE_ATTENDANCE_EXPECTED -> "Adults are expected at temple rites.";
            case FESTIVAL_MANDATORY        -> "Attendance at announced festivals is required.";
            case GUILD_MEMBERSHIP_REQUIRED -> "Only guild members may produce for sale.";
            case PRICE_CEILING_FOOD        -> "A maximum price is set on food.";
            case PRICE_FLOOR_FOOD          -> "A minimum price is set on food.";
        };
    }

    /**
     * Numeric parameters this law accepts. Used by the player UI to render
     * editable fields and by {@link LawParams#withDefaults} to populate
     * sensible defaults at enact time. Empty list = no parameters.
     */
    public List<ParamSpec> paramSpecs() {
        return switch (this) {
            case TOLL_ENTRY ->         List.of(new ParamSpec("toll_amount",       5f, 1f, 50f));
            case SUBSIDIZE_FARMER,
                 SUBSIDIZE_BLACKSMITH,
                 SUBSIDIZE_SCHOLAR,
                 SUBSIDIZE_HEALER ->   List.of(new ParamSpec("subsidy_per_day",   3f, 1f, 25f));
            case PROFITS_TO_TREASURY ->List.of(new ParamSpec("profit_fraction",   0.10f, 0.01f, 0.5f));
            case PRICE_CEILING_FOOD -> List.of(new ParamSpec("max_bronze_per_unit",15f, 1f, 100f));
            case PRICE_FLOOR_FOOD ->   List.of(new ParamSpec("min_bronze_per_unit", 5f, 1f, 100f));
            default -> List.of();
        };
    }

    /** Looks up an enum value by name, swallowing typos. */
    public static Optional<VillageLaw> fromName(String name) {
        if (name == null) return Optional.empty();
        try { return Optional.of(VillageLaw.valueOf(name.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public static final Codec<VillageLaw> CODEC =
            Codec.STRING.xmap(VillageLaw::valueOf, VillageLaw::name);

    /** Numeric-parameter spec entry. */
    public record ParamSpec(String key, float defaultValue, float minValue, float maxValue) {}
}
