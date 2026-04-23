# 01 — Trait Axes

## Purpose

Every NPC has a personality expressed as eight continuous values on
independent bipolar axes. Significant values surface as named traits
in the UI; everyone has values on all axes. This replaces the current
`PersonalityTrait` enum list on `AppearanceComponent`, which allows
mutually contradictory traits (generous + greedy at once) and has no
mechanical depth.

Traits drive behavior everywhere: schedule variation, dialogue tone,
office eligibility weighting, life-goal selection, trade pricing,
crime propensity, apprenticeship matching, gossip propagation, mood
responsiveness.

## Data model

### The eight axes

```java
public enum TraitAxis {
    INDUSTRY("Lazy", "Industrious"),
    COURAGE("Timid", "Bold"),
    SOCIABILITY("Solitary", "Gregarious"),
    GENEROSITY("Miserly", "Generous"),
    HONESTY("Deceitful", "Forthright"),
    AMBITION("Content", "Ambitious"),
    COMPASSION("Callous", "Compassionate"),
    TEMPERANCE("Impulsive", "Temperate");

    private final String negativePole;
    private final String positivePole;
    // getters, codec
}
```

### TraitVector

```java
public class TraitVector {
    private final float[] values = new float[TraitAxis.values().length];
    // all values default to 0.0f

    public float get(TraitAxis axis);
    public void set(TraitAxis axis, float value);  // clamped [-1, 1]
    public void adjust(TraitAxis axis, float delta);  // clamped

    // Display helpers
    public List<DisplayedTrait> significantTraits();
    public boolean hasTrait(TraitAxis axis, boolean positivePole);

    // Persistence
    public void save(CompoundTag tag);
    public void load(CompoundTag tag);

    // Codec (for saved-data contexts)
    public static final Codec<TraitVector> CODEC;
}
```

### DisplayedTrait

```java
public record DisplayedTrait(
    TraitAxis axis,
    boolean positivePole,     // true if value > 0
    TraitIntensity intensity
) {
    public String label() {
        String base = positivePole ? axis.positivePole() : axis.negativePole();
        return switch (intensity) {
            case EMPHATIC -> "Very " + base;  // or "incredibly", "pathologically"
            case NORMAL -> base;
        };
    }
}

public enum TraitIntensity { NORMAL, EMPHATIC }
```

## Display rules

- `|value| < 0.40` — not displayed, no named trait surfaces
- `0.40 ≤ |value| < 0.85` — `NORMAL` intensity, pole name displayed
- `|value| ≥ 0.85` — `EMPHATIC` intensity, "Very <pole>" label

`significantTraits()` returns all axes with `|value| ≥ 0.40`, sorted
by absolute value descending. UI typically shows top 3–4.

Emphatic-intensity traits may use flavor overrides ("incredibly
generous", "pathologically honest") — see `TraitDisplayFlavor` table in
the implementation. Kept in code, not a data file in v1.

## Generation

### At spawn

When an NPC is created (existing birth/spawn paths in `TownspersonMob`):

1. Each axis starts at 0.0.
2. Apply culture pull vector: `value += culture.getPull(axis)`.
   Culture pulls are typically in the ±0.2 range. Phase 5 wires this
   up; Phase 0 stores an identity pull (all zeros) as placeholder.
3. Apply per-axis gaussian noise: `value += randomGaussian() * 0.3`.
4. Clamp to `[-1, 1]`.

The result: most NPCs have a couple of notable axes; most axes sit in
the middle. Extremes are rare but present.

### At child birth (for Phase 2 inheritance)

```java
childAxis = (motherAxis + fatherAxis) / 2 + gaussianNoise(0.2)
```

Child inherits the midpoint of parents ± noise. Implement when the
child system is built (Phase 2); Phase 0 just generates fresh.

## Life-event drift

Traits drift slowly from significant life events. Deferred to Phase 1
(needs memory system hooked up). Table:

| Event | Axis | Delta |
|---|---|---|
| Betrayed by friend | Honesty | −0.05 |
| Survived battle | Courage | +0.05 |
| Starved during famine | Generosity | −0.03 |
| Raised by generous parents | Generosity | +0.02 at age-up |
| Succeeded at ambitious goal | Ambition | +0.03 |
| Failed ambitious goal repeatedly | Ambition | −0.04 |
| Lost spouse | Compassion | +0.02, Sociability −0.02 |
| Long imprisonment | Temperance | +0.03, Honesty −0.03 |

Drifts are small by design — personality is stable, not fluid. Over a
full NPC lifetime, typical drift is <0.3 on any axis.

## Persistence

NBT keys (on entity tag):
- `npcTraits.industry` (float)
- `npcTraits.courage` (float)
- `npcTraits.sociability` (float)
- `npcTraits.generosity` (float)
- `npcTraits.honesty` (float)
- `npcTraits.ambition` (float)
- `npcTraits.compassion` (float)
- `npcTraits.temperance` (float)

Missing keys default to 0.0 (neutral) — ensures backward compatibility
with existing saves.

## Integration points

### Phase 0 integration

- `TownspersonMob` gets `private final TraitVector traits` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist it.
- Generation hooked in the existing spawn path (in `TownspersonMob`
  constructor or `finalizeSpawn`, matching how `randomize()` on
  `AppearanceComponent` is currently called).
- `NpcProfileSnapshot` gains a `List<String>` field for displayed
  trait labels; existing `traits` list becomes this list's source.
- `IdentityPanel` (in the NPC profile GUI) updated to render from the
  new list. Format matches current trait display.

### Existing PersonalityTrait enum

Keep existing `PersonalityTrait` enum and `AppearanceComponent.traits`
list **temporarily** as a read-only view derived from the new vector.
This avoids breaking other systems that query traits. A migration task
in Phase 0 rewrites consumers to query `TraitVector` directly, after
which the legacy enum can be deleted.

### Downstream consumers (deferred to later phases)

- Dialogue conditional branches (Phase 1)
- Life goal selection (Phase 1)
- Mood responsiveness (Phase 1)
- Trade price adjustment (Phase 3)
- Office eligibility weighting (Phase 3)
- Crime propensity (Phase 3)
- Apprenticeship matching (Phase 2)

## Behavior contract

### Does

- Store 8 float values per NPC, persisted across save/load.
- Generate values at NPC spawn via culture + noise.
- Display significant values as named traits in the NPC profile.
- Provide a stable API for other subsystems to query trait values.

### Does not

- Drive behavior directly in Phase 0 — storage and display only.
- Affect existing game systems until downstream consumers integrate.
- Support modding in v1 — the 8 axes are a fixed enum.
- Do life-event drift in Phase 0 (deferred to Phase 1).

## Edge cases

- **Loading a save with the legacy `PersonalityTrait` list.** The load
  code detects legacy traits and converts them: `GENEROUS` → set
  `GENEROSITY` to +0.5, `LAZY` → set `INDUSTRY` to −0.5, etc. A
  conversion table lives in the implementation. Legacy trait list is
  then cleared.
- **Two legacy contradictory traits.** If the save has both `GENEROUS`
  and `GREEDY`, they cancel. Clamp result to neutral for that axis.
- **Value exactly at boundary.** `|value| == 0.40` is considered
  displayed (use `>=`). `|value| == 0.85` is emphatic (use `>=`).

## Ordering dependencies

Phase 0 can implement this subsystem immediately; no dependencies on
other new subsystems. Culture-pull integration (Phase 5) and life-event
drift (Phase 1+) are added later without breaking the data model.

## Open decisions

None at spec time. All open design questions resolved in prior chat.

## Does-not-include

- JSON schema for traits (stays hardcoded; see project convention).
- Trait-based dialogue content (Phase 1 / Phase 5 content pass).
- Player-visible trait-axis bars in the profile — v1 displays labels
  only. Continuous-value bar visualization is a future polish task.
- Cross-NPC trait comparison tooling. Not needed for v1.

## Revision Notes

(changes recorded here as the spec evolves after testing)