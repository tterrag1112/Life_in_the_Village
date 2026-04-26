# 21 — Medicine & Healer Profession

## Purpose

NPCs currently never get sick or injured. This adds illness, injury,
recovery, and a HEALER profession. Interacts with plague events,
scribal system (medical books), religion (healing rites), and the
office framework (village_healer).

## Data model

### HealthCondition

Separate from Minecraft HP; mid-duration layers.

```java
public enum HealthCondition {
    // Injuries
    MINOR_WOUND, SERIOUS_WOUND, BROKEN_BONE, BURN, EXHAUSTION,

    // Illnesses
    FEVER, STOMACH_AILMENT, RESPIRATORY_ILLNESS, PLAGUE_CARRIER,

    // Age-related
    FRAILTY,

    // Mental (soft)
    MELANCHOLY, NERVOUS_BREAKDOWN;

    public boolean isContagious() {
        return this == RESPIRATORY_ILLNESS || this == PLAGUE_CARRIER
            || this == STOMACH_AILMENT;
    }

    public boolean requiresMedicine() {
        return this != EXHAUSTION && this != MELANCHOLY;
    }
}

public record ActiveCondition(
    HealthCondition type,
    long onsetTick,
    long expectedDurationTicks,
    int severity,            // 1..5
    boolean treated,
    UUID treatedById
) { ... }
```

### HealthComponent

```java
public class HealthComponent {
    private final List<ActiveCondition> conditions;
    private int constitution;           // 0..100; fades with age

    public boolean hasCondition(HealthCondition type);
    public List<ActiveCondition> active();
    public void add(ActiveCondition c);
    public void remove(HealthCondition type);
    public void tickConditions(long currentTick);

    public float workEfficiencyModifier();    // 0..1
    public boolean canWork();
    public boolean canTravel();
    public int moodPenalty();
}
```

## Onset

### Injury

- **Combat**: `LivingHurtEvent` → MINOR_WOUND/SERIOUS_WOUND/BROKEN_BONE
  per damage bracket.
- **Work accidents**: per production cycle. MINER → BROKEN_BONE
  0.01%/cycle; FARMER → MINOR_WOUND 0.005%/cycle; BLACKSMITH → BURN
  0.005%/cycle. Reduced at higher skill.
- **Exhaustion**: after 20+ consecutive work days without rest;
  forces reduced capacity until rest.

### Illness

- **Seasonal**: Winter 0.02/day RESPIRATORY; Summer 0.01/day STOMACH.
- **Contagion**: within 4 blocks of carrier, daily chance weighted by
  constitution.
- **Plague event**: rare village-wide; raises onset to 10%/day,
  reduces recovery.
- **Trauma**: NERVOUS_BREAKDOWN after sustained DISTRESSED mood
  30+ days; low-Temperance more prone.

### Age-related

- FRAILTY at ELDERLY + age > threshold; worsens slowly; contributes
  to natural death.
- Constitution drops 1/month past elderly threshold.

## Recovery

### Untreated

Naturally decay over expected duration:
- MINOR_WOUND ~3d
- SERIOUS_WOUND ~14d, 10% complication risk
- FEVER 7d (3-5 if treated)
- PLAGUE_CARRIER 14d, 20% mortality untreated
- EXHAUSTION 1-2d rest
- MELANCHOLY months; only mood improvement resolves

### Treated

- Duration cut 50%.
- Complications near zero.
- Mortality eliminated for treatable conditions.

## Healer profession

Requires adult + MEDICINE ≥ 40. Primary MEDICINE, secondary SURVIVAL
(herbs).

Building: HEALER_HUT (new) or CHURCH_ADJACENT.

`HealerWorkGoal`:
1. Check patient queue (village NPCs with treatable conditions).
2. Walk to patient; perform treatment.
3. Between: produce remedies from garden/stockpile ingredients.
4. Maintain supply for on-demand treatment.
5. During plague: triage and mass treatment.

### Remedies

```java
public record Remedy(
    UUID remedyId,
    RemedyType type,
    int potency,                  // 1..3
    long craftedTick,
    long expiresTick
) {}

public enum RemedyType {
    HERBAL_POULTICE,    // wounds
    FEVER_TEA,
    BONE_SALVE,
    BURN_OIL,
    STOMACH_BITTERS,
    RESPIRATORY_TONIC,
    PLAGUE_ANTIDOTE;    // rare
}
```

Recipes require herbs, honey, cloth. Shelf life 30-60 days; expired
reduces potency.

### Diagnosis

Auto-success for visible conditions. Skill-based roll for illnesses
(MEDICINE ≥ 50 identifies any; below that may misdiagnose). Misdiag
applies wrong treatment; no benefit, possible minor adverse effect.

### Training

Via apprenticeship system (`16-apprenticeship.md`). Herbal knowledge
also via books (`18-letters-and-books.md` medical category).

## Plague events

New `VillageEvent` type PLAGUE_OUTBREAK:
- Random low-probability per village per year.
- Higher probability if visitor from plague-affected region (Phase 4
  visitor flux).
- Daily infection chance 10% susceptible villagers.
- Work halved, trade halted, visitors avoid.
- **Quarantine** law: reduces spread 60%.
- Ends when infected count reaches 0 or 30 days.
- Post-plague: village mood low 2 weeks; kingdom history; orphans.

Healer during plague:
- Schedule overridden; around-the-clock work.
- Mass remedy production.
- Triage: higher-constitution prioritized.
- Healer death risk: 5%/day of plague exposure.

## Offices

- **village_healer** (new): appointed by leader. Powers: quarantine
  order, remedy requisitioning, village medical leadership.
- **kingdom_physician** (Phase 4 stub).

## Player participation

- **Request treatment**: pay for treatment.
- **Donate herbs**: gift ingredients.
- **Study under healer**: apprenticeship → HEALER player profession.
- **Buy remedy**: purchase for personal use.

Player as healer: treat NPCs, produce remedies, respond to plague,
possibly become village_healer.

## Integration points

### Phase 3 integration

- HEALER profession + HEALER_HUT building.
- `HealthComponent` on TownspersonMob.
- `LivingHurtEvent` and work-accident hooks.
- Seasonal illness ticker (daily).
- Contagion propagation (daily proximity scan).
- PLAGUE_OUTBREAK event type; scheduler rolls weekly.
- `HealerWorkGoal` registered.
- `Remedy` item + recipes.
- Player verbs.
- `/health <npc>` debug.
- `/plague start <village>` debug.

### Phase 4+ integration

- Inter-village healer expertise (letters, visits).
- Kingdom physician office.
- Medical book trade.

## Behavior contract

### Does

- Add active conditions affecting behavior and productivity.
- Trigger injuries from combat and work accidents.
- Seasonal illness with contagion.
- Plague as village-wide crisis.
- HEALER profession diagnosing/treating/crafting.
- Player medical verbs.

### Does not

- Replace Minecraft HP.
- Simulate complex biology (enum-based).
- Magical healing.
- Require diagnosis minigames.

## Edge cases

- **Healer dies of plague.** Village left without; plague worsens.
  Phase 4: neighbor-village healer travels.
- **No remedy ingredients.** Production halts; conditions worsen.
  Phase 4 request board resolves.
- **Misdiagnosis.** Wrong remedy, no improvement, condition runs
  course.
- **Player claims fake condition.** Healer diagnoses none; no
  treatment.
- **Child illness.** Lower constitution, higher mortality; caregiver
  distress.

## Ordering dependencies

Phase 3 depends on:
- Office framework (Phase 3).
- Memory system — treatment memories.
- Mood system — conditions apply penalties.
- Scribal (Phase 2) — medical books.
- Skill component — MEDICINE gating.
- Existing event system — plague.

## Open decisions

- Constitution visible? **Proposed: hidden; revealed via healer
  examination dialogue.**
- Natural death timing. **Proposed: age + FRAILTY + random roll;
  65-80 range.**
- Child mortality rate. **Proposed: low (5% illness per child per
  year).**
- Mental conditions visibility. **Proposed: visible via DISTRESSED
  mood + dialogue cues; treatable by priest (CONFESSION) or time.**

## Does-not-include

- Full pharmacology.
- Chronic conditions lasting years.
- Disability mechanics.
- Player character getting sick (player uses vanilla HP only).

## Revision Notes

### Phase 3 task 21 implementation pass

Things-to-flag responses:
1. **Healer death rate during plague.** Implemented at the spec'd
   5%/day per `HealthTicker.applyHealerOverwork`. There is no fallback
   priest yet — the village just runs without medical leadership when
   the healer dies. Phase 4 inter-village healer travel covers this
   per `21-medicine-healer.md` Edge Cases.
2. **Constitution reveal.** Hidden value, surfaced via
   `/health show <npc>` debug only. Healer-examination dialogue cue
   is Phase 5 polish.
3. **Plague-carrier visitor source.** Stub: `PlagueScheduler` rolls
   weekly per village at 0.4% baseline scaled by village size. The
   visitor-flux integration is left for Phase 4 — see
   `12-inter-village-visitor-flux.md`.
4. **Remedy expiry handling.** Expired remedies are pruned by the
   healer's work goal (`HealerInventory.pruneExpired`) at the start
   of each `canUse()` cycle. Player stashes are not scanned by the
   daily sweep; expiry pruning happens lazily when a verb opens the
   stash. Future work: a per-day sweep over all open player stashes.

Spec deviations:
- **Remedy is a record, not a vanilla Item with NBT.** The spec gives
  the {@code Remedy} record shape directly; v1 stores it on
  {@code HealthSavedData}'s player-stash map and on each healer's
  {@code HealerInventory}. The "Buy remedy" verb hands a record
  between stashes rather than spawning an item entity.
- **Recipe ingredient consumption is abstract.** Producing a remedy
  in {@link HealerWorkGoal} doesn't yet pull herbs / honey / cloth
  from a workshop chest. The 200-tick "produce" phase is a stand-in
  until the apprentice / workshop-inventory hooks ship.
- **Player diagnosis stub.** Player has no HealthComponent (vanilla
  HP only), so {@link RequestTreatmentVerb} defaults to MINOR_WOUND
  and falls back to any-on-hand remedy. Phase 4 may add an opt-in
  player HealthComponent for the curious-player path.
- **Misdiagnosis** (spec line 158) is deferred. v1 always picks the
  patient's highest-severity treatable condition and a remedy that
  targets it; misdiagnosis at MEDICINE < 50 lands in Phase 5.
- **Confession heals MELANCHOLY directly.** Per spec "Open decisions"
  #4 ("treatable by priest (CONFESSION) or time"),
  {@link RiteExecutor#handleConfession} now removes a MELANCHOLY
  condition outright and fires a HEALED mood blip.
- **Calendar.** 384-day year split into 4×96-day seasons; the
  health subsystem uses {@code (gameTime / 24000) % 384} to derive
  the day-of-year. Vanilla doesn't ship seasons, and the religion
  subsystem already uses 365 — the medical calendar uses 384 because
  it lines up cleanly with the 4-season split. Phase 4 may unify the
  two if the discrepancy starts mattering.
- **Quarantine law** added as `VillageLaw.QUARANTINE_VILLAGE`
  (CRIME category, popularity -25, Compassion +0.4 / Sociability
  -0.3 trait fit). Office power `QUARANTINE_VILLAGE` and
  `REQUISITION_REMEDIES` added to `OfficePower`. The actual law-
  popularity decay and quarantine-detection enforcement leans on the
  existing law machinery from doc 22.

Deferrals:
- Healer apprenticeship — Phase 2 task 16 must ship first.
- Player as healer profession — placeholder; v1 only handles NPC
  healers + the three player verbs (request, donate, buy).
- COMMUNITY_TRIAGE / inter-village healer travel — Phase 4.
- Kingdom physician office — Phase 4+.
- Magical healing, religious persecution, food restrictions —
  per Does-not-include section.

### Audit-discovered fixes
- `HealerWorkGoal.canUse` originally consumed the remedy before
  `start()` was guaranteed; reworked to check availability only
  (`hasRemedyFor`) and call `takeFor` in `start()` with a fallback.
- `RequestTreatmentVerb` had a redundant double-`takeFor` in the
  fallback path; collapsed to a clean if-else.
- `BuyRemedyVerb` could mismatch the highest-potency lookup vs. the
  remedy actually returned by `takeFor`; now uses the `takeFor`
  return value directly.
