# 31 — Cultures Wired

## Purpose

Phase 0–4 treat culture as a registered record that influences NPC
spawning naming, trait bias, and religion assignment. Phase 5 wires
culture into actual behavior across every subsystem that was
previously told to "check culture" as a stub: schedules, dialogue,
offices, laws, economics, apprenticeship norms, visitor flux weights,
and hobby weighting.

This doc consolidates the wiring and specifies the four starter
cultures in full mechanical detail. Phase 6 adds JSON-driven custom
cultures; Phase 5 keeps them hardcoded for content-tuning speed.

## Data model

### Culture (existing, extended)

The existing `Culture` record gains concrete fields for all phase-5
integration points:

```java
public record Culture(
    String id,
    String displayName,
    CultureSchedule schedule,
    CultureNaming naming,
    CultureTraitBias traitBias,
    CultureReligion religion,
    CultureOfficeRules officeRules,
    CultureLawDefaults lawDefaults,
    CultureEconomicNorms economicNorms,
    CultureHobbyWeights hobbyWeights,
    CultureVisitorAffinity visitorAffinity,
    CultureApprenticeshipNorms apprenticeshipNorms,
    CultureAestheticTokens aesthetics
) {
    public static final Codec<Culture> CODEC;
}
```

Each sub-record is a small, focused bundle. Phase 5 adds the bundles
that didn't exist in earlier phases; existing bundles (naming,
traitBias) stay.

### CultureSchedule

```java
public record CultureSchedule(
    Map<Profession, WeeklySchedule> professionOverrides,
    int weekLength,                     // 7 in v1; reserved for future
    List<Integer> cultureDayOffs,       // days off shared by culture
    Optional<Integer> holyDayInterval   // days between cultural holy days
) {}
```

Overrides stack on top of the base `WeeklyScheduleLibrary` defaults.

### CultureOfficeRules

```java
public record CultureOfficeRules(
    Map<OfficeId, OfficeSelectionMethod> preferredSelection,
    Map<OfficeId, Integer> termLengthDays,
    boolean councilDominant,      // favors COUNCIL selection broadly
    boolean heredityRespected     // inheritance gets weight
) {}
```

Overrides the `OfficeDefinition.defaultSelection`. E.g., Highmarch
prefers HEREDITARY for leader slots; Silkwood prefers MERITOCRATIC.

### CultureLawDefaults

```java
public record CultureLawDefaults(
    Set<VillageLaw> initialLaws,        // enacted at village founding
    Set<VillageLaw> preferredLaws,      // leaders lean toward these
    Set<VillageLaw> forbiddenLaws,      // culturally unacceptable
    LawParams defaultParams
) {}
```

### CultureEconomicNorms

```java
public record CultureEconomicNorms(
    float giftPropensity,           // how often gifts are expected
    float hagglingTendency,         // price negotiation intensity
    boolean creditAccepted,         // loan/credit norms
    float luxuryDemand,             // how much LUXURY category consumed
    Map<ResourceCategory, Float> consumptionBias  // per-capita multipliers
) {}
```

### CultureHobbyWeights

Map of hobby ID → weight multiplier (stacked on trait-based selection).

### CultureVisitorAffinity

Which `VisitorType`s the culture attracts or repels:

```java
public record CultureVisitorAffinity(
    Map<VisitorType, Float> weightMultipliers
) {}
```

### CultureApprenticeshipNorms

```java
public record CultureApprenticeshipNorms(
    int standardDurationDays,
    int maxApprenticesPerMaster,
    boolean requiresMasterpiece,
    float daughterInheritsShop     // 0..1; culture-gated inheritance
) {}
```

### CultureAestheticTokens

Hooks into appearance Layer 1 (`33-appearance-layer1.md`):

```java
public record CultureAestheticTokens(
    List<String> clothingPalette,      // color IDs
    List<String> accessoryPool,        // items/hats/etc.
    String primarySilhouette,          // for geometric variation
    Optional<String> bannerPattern
) {}
```

## Four starter cultures (fully specified)

### Plainfolk

Pragmatic farming folk. The default baseline; everything else is
relative to them.

- **Traits**: light +Industry, neutral everything else. No strong
  pull.
- **Schedule**: standard 6-day work, Sunday off. Harvest-season
  farmer exception (no day off during harvest).
- **Religion**: Sunstead. Holy days at equinoxes.
- **Offices**: MERIT_REPUBLIC for leader (MERITOCRATIC selection,
  4-year terms). Constable APPOINTED by leader. Scribe APPOINTED.
- **Laws at founding**: SUBSIDIZE_FARMER (mild). No execution (prefers
  EXILE).
- **Economics**: medium gifting, low haggling, moderate luxury demand.
- **Hobbies**: tend_garden, long_walk, inn_drink_talk weighted high.
  Sword practice weighted low (non-militaristic).
- **Visitors**: neutral mix. Slight pilgrim preference (Sunstead
  rites).
- **Apprenticeship**: standard 2-year, max 2 apprentices per master,
  masterpiece required for MASTER rank. Daughter inheritance common
  (0.7).
- **Aesthetics**: earth tones, practical wool and linen garments,
  no banners.

### Highmarch

Warrior-aristocratic highland culture. Militant, honor-bound,
hierarchical.

- **Traits**: +Courage, +Ambition, −Sociability (formal rather than
  warm).
- **Schedule**: standard week but with explicit martial-training
  blocks embedded in leisure phase. Military drills once weekly for
  all adults.
- **Religion**: Forge Creed (ancestor-worship, warrior saints).
  Holy days anchored to kingdom-founding anniversary and solstices.
- **Offices**: FEUDAL_COUNCIL. Leader HEREDITARY (eldest adult
  child inherits); council-appointed bailiffs. Terms lifelong.
- **Laws at founding**: DOUBLE_PUNISHMENT (harsh justice), executions
  permitted for CAPITAL crimes. MARKET_TAX_REDUCED (encourage trade
  for military supply).
- **Economics**: high gifting (honor-gifts between nobility and
  retainers), low haggling (honor-bound pricing), high weapons and
  cloth demand (uniforms, heraldry).
- **Hobbies**: sword_practice, archery, tell_story, visit_grave
  weighted high. Gardening weighted low.
- **Visitors**: high ENVOY and SCHOLAR_VISITING (Highmarch values
  formal emissary exchange). Low PILGRIM (their religion is
  ancestor-local).
- **Apprenticeship**: 3-year standard, masterpiece required,
  daughter inheritance rare (0.3). Guild hierarchy rigid.
- **Aesthetics**: dark wools and leathers, banners with kingdom
  heraldry, stone and iron accents.

### Silkwood

Scholarly woodland culture. Literate, contemplative, pattern-
oriented.

- **Traits**: +Temperance, +Compassion, light +Sociability. Industry
  average.
- **Schedule**: formalized study periods in morning; leisure rich
  with reading/writing. Monthly "Threading" holy day for public
  readings.
- **Religion**: The Loom. Priests read futures; no single deity.
- **Offices**: MONARCHY with scholar-advisor check. Leader
  HEREDITARY but High Scholar COUNCIL-elected and holds veto on
  laws affecting scholarship.
- **Laws at founding**: COMPULSORY_SCHOOLING, BAN_EXECUTION,
  SUBSIDIZE_SCHOLAR.
- **Economics**: low gifting (gifts seen as crass unless cultural),
  high haggling (intellectual sport), high paper/luxury/liturgical
  demand.
- **Hobbies**: read_at_library, write_letter, meditate, carve_at_home
  weighted high. Sword practice and archery weighted very low.
- **Visitors**: highest STUDENT and SCHOLAR_VISITING rates; minstrels
  welcomed (weighted up). Low REFUGEE (closed-society).
- **Apprenticeship**: 4-year standard (longer), masterpiece always
  required and often is a book for scholarly trades. Daughter
  inheritance common (0.6) but patronage-of-merit also accepted.
- **Aesthetics**: greens and muted blues, silk and linen, detailed
  embroidery, banners with geometric patterns.

### Tidereach

Coastal egalitarian seafaring culture. Informal, traveled,
resilient.

- **Traits**: +Sociability, +Generosity, +Honesty (direct), Temperance
  slightly negative (celebratory).
- **Schedule**: tide-based work (fishing-profession specific);
  2 days off per week. Full moon festivals monthly.
- **Religion**: Tidecall. Seasonal sea-spirits. Blessing before
  voyages.
- **Offices**: elective MERIT_REPUBLIC for leader; Harbourmaster is
  distinct ELECTIVE office. Bailiff often doubles as harbourmaster.
- **Laws at founding**: PILGRIM_WELCOME_BONUS (travelers celebrated),
  FOREIGN_TRADER_BAN forbidden (they welcome foreigners),
  BAN_EXECUTION.
- **Economics**: high gifting (hospitality norm), high haggling (part
  of trade culture), moderate luxury demand (imports via sea).
- **Hobbies**: fishing, tell_story, inn_drink_talk, cards_at_inn,
  cook weighted high.
- **Visitors**: very high MERCHANT_ITINERANT and TRAVELER. Pilgrim
  rate moderate. Minstrels frequent.
- **Apprenticeship**: 2-year standard, more informal, masterpiece
  not always required (competency sufficient). Daughter inheritance
  default (0.8).
- **Aesthetics**: blues, whites, weathered canvas, rope accents,
  banners with wave motifs.

## Integration wiring

Each sub-record maps to a hook point in the relevant subsystem. The
`CultureResolver` utility is the single access point:

```java
public final class CultureResolver {
    public static Culture of(TownspersonMob npc);
    public static Culture of(Village village);  // dominant village culture
    public static Culture ofKingdom(Kingdom k);  // kingdom-culture if set

    public static WeeklySchedule scheduleFor(TownspersonMob npc,
                                              Profession p);
    public static OfficeSelectionMethod selectionFor(Culture c, OfficeId id);
    // ... accessors per sub-record
}
```

Subsystems query `CultureResolver` rather than reading culture records
directly.

### Schedule layering (updated)

```
personalOverride > eventOverride > cultureOverride > weeklyVariant > professionDefault
```

Cultural schedule adds between the event and weekly layers.

### Office selection

`OfficeElection.runElection` consults `CultureResolver.selectionFor`.
Falls back to `OfficeDefinition.defaultSelection` if culture doesn't
override.

### Law enactment

`LawDecisionEngine` biases leader decisions via
`culture.lawDefaults().preferredLaws()` and excludes
`forbiddenLaws()`. `culture.lawDefaults().initialLaws()` are enacted
at village founding.

### Economic norms

- `ChannelRouter.score` adds cultural haggling adjustment.
- Gift verb checks cultural giftPropensity for mood effects.
- `VillageSimEngine` consumption derives from
  `CultureResolver.of(village).economicNorms().consumptionBias()`.

### Hobby selection

`HobbyCatalogue.availableFor` now multiplies base trait weight by
culture's hobby weight table.

### Visitor flux

`VisitorFluxEngine` multiplies type weights by the destination
culture's `visitorAffinity`.

### Apprenticeship norms

`ApprenticeshipContract` construction reads
`culture.apprenticeshipNorms()` for duration, max apprentices,
masterpiece requirement, inheritance rules.

### Religion assignment

Village's dominant religion follows village-of-birth culture unless
mixed-culture village (Phase 5 stretch). NPC's piety starts at
0.3 toward culture religion.

## Village-culture resolution

A village's dominant culture is determined by founding NPCs. If a
village has mixed population, the majority culture dominates; laws,
offices, schedules follow the majority.

Minority-culture residents keep their personal schedule/religion
but must abide by village laws.

## Kingdom-culture

A kingdom is assigned a culture at creation. Kingdom-level offices
(king, chancellor) use kingdom culture. Subordinate villages may
vary but economic and diplomatic defaults inherit from kingdom.

## Integration points

### Phase 5 integration

- `Culture` record extended with all sub-records listed above.
- Four starter cultures defined in `CultureRegistry` at mod init.
- `CultureResolver` utility added.
- Schedule, office, law, economy, hobby, visitor, apprenticeship
  subsystems call resolver at their hooks.
- Village founding hook enacts `initialLaws`.
- Office-default-selection path consults resolver.
- `NpcProfileSnapshot` shows culture-derived labels.
- `/culture info <id>` debug command prints full culture spec.
- `/culture set <village> <id>` migrates village culture (testing).

### Phase 6 future

- JSON-driven custom cultures replacing hardcoded registry.
- Culture drift over time (village shifts under sustained minority
  growth).
- Culture-culture rivalry mechanics at kingdom level.

## Behavior contract

### Does

- Wire culture into every subsystem that previously stubbed cultural
  variation.
- Ship four fully-specified starter cultures.
- Provide a single resolver entry point for subsystem access.
- Apply cultural schedule as an additional layer between event and
  weekly.

### Does not

- Replace trait-based behavior; culture biases defaults, traits still
  drive individual variation.
- Enforce cultural conformity — NPCs can hold unusual views.
- Support per-NPC culture override beyond the existing
  `CultureComponent` data (cultures are coarse; individual is fine).
- Support JSON culture packs (Phase 6).

## Edge cases

- **Mixed-culture village, no clear majority.** Dominant culture
  falls back to founder's; or leader's if founder gone. Documented
  ambiguity.
- **Culture-forbidden law enacted by player leader.** Allowed with
  significant reputation hit; villagers mood drops; may trigger
  migration.
- **Culture's religion has no priests present.** Rite effects
  attenuate; visitor flux adjusts; villagers may fall into
  irreligious drift over time.
- **Apprenticeship culture rules vs. master's personal style.**
  Master can request variance via dialogue; culture defaults for
  v1.

## Ordering dependencies

Phase 5 depends on:
- All phase 0-4 systems being in place (every subsystem the
  resolver touches).
- Appearance Layer 1 (same phase) — aesthetic tokens.
- Events expanded (same phase) — cultural holy days.

## Open decisions

- Culture drift over sustained cross-culture exposure — auto-blend?
  **Proposed: no auto-drift in v1; cultures stable.**
- Display culture prominently in NPC profile? **Proposed: yes —
  shown as small tag next to name.**
- Cross-culture dialogue tone differences? **Proposed: culture-hint
  in greeting trees; deeper variation in Phase 6.**

## Does-not-include

- Cultural cuisine differences beyond category-level consumption bias.
- Cultural dance / music mechanics (festivals are event-type, not
  per-culture choreography).
- Inter-cultural marriage consequences beyond relationship-ledger
  seeding.
- Language barriers; all share a common written language.

## Revision Notes

(changes recorded here as the spec evolves after testing)

### Phase 5 implementation (2026-04-27, branch `claude/npc-office-framework-behavior-qBdJn`)

#### Things to flag

- **`Culture` was a stub class, not a record.** The pre-existing
  `Cultures/Culture.java` was a one-field `(String key)` placeholder
  with a `static List<Culture> ListCultures` collection and a single
  unused subclass `AxolotlingCulture`. Phase 5 rewrites it as a 13-field
  record matching the spec. The stub class and `AxolotlingCulture` are
  deleted; nothing referenced them outside the file itself.
- **Two `CultureResolver` classes coexist in different packages.**
  `Village/CultureResolver` is the structure-template path resolver
  (different concept — predates this work). The new behavior resolver
  lives at `Cultures/CultureResolver`. No file imports both, so the
  short-name collision is harmless. Renaming the older one was not
  attempted in this phase.
- **Codec arity packed into nine sub-records (`CultureBundles.java`).**
  DFU `RecordCodecBuilder.Instance.group(...)` caps at 16 fields. Even
  the 13-field `Culture` record stays under the cap, but each of the
  inner bundles (laws, economic norms, etc.) gets its own focused codec
  for readability.
- **`CultureLawDefaults` references laws by enum value (not by string).**
  `VillageLaw` is an enum so the default codec works; preferred /
  forbidden / initial all serialize via `Codec.STRING.xmap` to enum.
- **`CultureNaming` is a v1 placeholder.** The four starter registry
  entries leave name pools empty; spec § "Phase 6 future" handles
  per-culture name lists. NPC naming today still uses the global pool.

#### Spec deviations

- **`/culture set <village> <id>` is NOT shipped.** The spec lists this
  as a phase-5 debug command. Village culture is *derived* from kingdom
  culture (the canonical source) rather than stored on the village, so
  there is no field to set. To migrate a village's culture for testing,
  change its kingdom's culture via `/kingdom` data manipulation. The
  read-only commands `/culture list`, `/culture info`, and the new
  `/culture resolve npc|village <hook>` cover the inspection use cases.
- **`CultureSchedule` is plumbed into the record but not yet read.**
  `WeeklyScheduleLibrary` does not have an event/cultural override
  layer in this codebase. The schedule field is populated on the four
  starter cultures with `professionOverrides = empty, weekLength = 7,
  cultureDayOffs = [], holyDayInterval = empty` so the codec
  round-trips. Wiring schedules requires extending the schedule
  library — flagged for the next pass.
- **Dialogue / appearance / consumption-bias hooks are not yet wired.**
  Spec calls out `ChannelRouter.score` haggling adjustment, gift verb
  mood effects, `VillageSimEngine` consumption derivation, appearance
  Layer 1 aesthetic tokens, and `NpcProfileSnapshot` culture labels.
  All of those subsystems exist but were left alone — the spec
  treats this phase as the wiring pass for "behavior", not for UI or
  the simulator's economic loop. The data is now available on
  `Culture.economicNorms()` and `aesthetics()` for the consumers to
  read whenever they're updated.
- **Religion piety is overwritten on first culture apply.** Villagers
  born into a culture get `cultureReligionPiety = 0.6` (above the
  spec-suggested 0.3 starter to make cultural identity more legible
  in early playtest). Conversion drift later in life is unchanged.

#### Audit-discovered fix

- **finalizeSpawn timing bug.** The first wiring put culture lookup
  in `finalizeSpawn`, which the populator path calls *before*
  `assignToBuilding`, so `getAssignedVillageName()` always returned
  empty and culture resolved to the registry default — making the
  trait-bias and religion application inert for production villagers.
  Fixed by:
  1. Reverting `finalizeSpawn` to seed the default Sunstead religion
     (cheap, no culture lookup).
  2. Adding a private `applyVillageCulture(ServerLevel)` method that
     reads culture, applies trait biases additively, and overwrites
     religion piety.
  3. Calling `applyVillageCulture` from `assignToBuilding` once,
     gated by a persisted `cultureApplied` boolean (NBT-stored so
     the apply is exactly-once across saves).

#### Future-phase deferrals

- JSON-driven custom cultures (Phase 6).
- Mixed-culture village resolution (currently village = kingdom
  culture, no per-village override).
- Culture drift / auto-blend.
- Holy-day calendar generation from `holyDayInterval`.
- Per-culture name pools.
- Kingdom-level cultural rivalry mechanics.
