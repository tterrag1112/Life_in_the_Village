# 05 — Skill System

## Purpose

Skills are cross-profession proficiencies that persist when an NPC
changes professions. They drive speed and quality multipliers for work,
gate Office eligibility, enable apprenticeship matching, and provide a
substrate for player progression beyond profession tenure.

Eight skills cover the full professional/life space without being so
fine-grained that most skills sit unused on most NPCs.

## Data model

### The eight skills

```java
public enum Skill {
    FARMING,     // crops, animals, food production
    CRAFTING,    // smithing, carpentry, weaving, masonry
    COMBAT,      // swords, bows, unarmed
    COMMERCE,    // trading, negotiation, accounting
    SOCIAL,      // persuasion, leadership, public speaking
    LITERACY,    // reading, writing, scholarship
    SURVIVAL,    // foraging, hunting, wilderness travel
    MEDICINE;    // healing, herbs, illness diagnosis

    public static final Codec CODEC =
            Codec.STRING.xmap(Skill::valueOf, Skill::name);
}
```

These map to the plan's professional vocabulary:
- Farmers, herders, foresters lean on FARMING + SURVIVAL
- Blacksmiths, carpenters, weavers lean on CRAFTING
- Guards, adventurers lean on COMBAT + SURVIVAL
- Merchants lean on COMMERCE + SOCIAL
- Village leaders, priests lean on SOCIAL + LITERACY
- Scribes, scholars lean on LITERACY (+ COMMERCE for scribes)
- Healers, herbalists lean on MEDICINE + SURVIVAL

### SkillComponent

```java
public class SkillComponent {
    // Each skill is stored as a short (0..100 + small overflow buffer)
    private final short[] levels = new short[Skill.values().length];

    public int get(Skill skill);
    public void set(Skill skill, int level);        // clamped 0..100
    public void addXp(Skill skill, float xp);       // see progression
    public void decay(Skill skill, float amount);   // for unused skills

    public int primary();                           // returns highest-level skill
    public List topN(int n);

    public void save(CompoundTag tag);
    public void load(CompoundTag tag);
}
```

Internally, XP accumulates as a float and converts to integer levels
at thresholds. Store both the level (short) and sub-level XP progress
(float, optional) in NBT.

## Progression

### XP thresholds
Level  1 →  50 XP
Level  5 →  300 XP
Level 10 →  900 XP
Level 20 →  2500 XP
Level 40 →  7500 XP
Level 60 → 15000 XP
Level 80 → 26000 XP
Level 100 → 40000 XP

Intermediate levels interpolated logarithmically. Skills 0–30 represent
amateur, 30–60 journeyman, 60–85 expert, 85–100 master and above.

### XP sources

Per source, typical XP amount per occurrence:

| Source | Affected skill | XP |
|---|---|---|
| Production cycle (existing NPC goals) | Primary skill of profession | 3 |
| Production cycle | Secondary skill | 1 |
| Crafting order fulfilled | Primary | 10 |
| Combat hit landed | COMBAT | 1 |
| Combat mob killed | COMBAT | 5 |
| Read a book (if literate) | LITERACY | 15 |
| Read a book (skill-book with matching skill) | That skill | 25 + LITERACY 5 |
| Teaching another NPC | Skill taught, for both | Teacher: 2; Student: 20 |
| Successful trade (NPC merchant) | COMMERCE | 2 |
| Successful persuasion (dialogue branch) | SOCIAL | 3 |
| Healing another NPC | MEDICINE | 8 |
| Harvesting from wild | SURVIVAL | 2 |

Profession ↔ skill mapping lives in a static table:

```java
public static Map<Profession, ProfessionSkills> table = Map.of(
    Profession.FARMER, new ProfessionSkills(FARMING, SURVIVAL),
    Profession.BLACKSMITH, new ProfessionSkills(CRAFTING, COMMERCE),
    Profession.GUARD, new ProfessionSkills(COMBAT, SURVIVAL),
    Profession.MERCHANT, new ProfessionSkills(COMMERCE, SOCIAL),
    Profession.SCRIBE, new ProfessionSkills(LITERACY, SOCIAL),
    Profession.SCHOLAR, new ProfessionSkills(LITERACY, MEDICINE),
    Profession.HEALER, new ProfessionSkills(MEDICINE, SURVIVAL),
    // ...
);
```

### Decay

Unused skills decay slowly. Once per in-game day, for each skill:
if last XP gain > 30 days ago:
level -= 0.02   // ~0.6 levels per month of disuse

Tracked per-skill via a separate `lastXpTick` array (Phase 0 stores it
even if decay is deferred to Phase 1).

Decay floors at 10 — NPCs never forget skills entirely. An old farmer
who hasn't touched a field in years is still a better farmer than a
novice.

## Effect multipliers

Existing `NpcProfessionXp.getSpeedMultiplier` and `getQualityChance`
get updated to read from skill levels instead of per-profession XP:

```java
public static float getSpeedMultiplier(TownspersonMob npc) {
    int primary = npc.getSkills().get(primarySkill(npc.getProfession()));
    int secondary = npc.getSkills().get(secondarySkill(npc.getProfession()));
    int effective = primary + secondary / 2;

    if (effective < 10)  return 1.00f;
    if (effective < 30)  return 1.10f;
    if (effective < 60)  return 1.25f;
    if (effective < 85)  return 1.45f;
    return 1.70f;
}
```

Effect is continuous-in-intent but bucketed for simplicity. Tuning
values after testing.

## Office competence

Offices require a minimum skill level (defined per office in
`06-office-framework.md`). The office's "competence multiplier"
scales from the minimum to 100:
competence = (level - requiredMin) / (100 - requiredMin)
// clamped 0..1
// multiplied by office's contribution effect

Competent office holders grant bonuses to their organization;
incompetent ones (level just above minimum) grant only a small bonus
and may introduce wasted resources (see office framework doc).

## Persistence

NBT structure on entity tag, rooted at `npcSkill`:
npcSkill: {
farming: 42,
crafting: 12,
combat: 5,
commerce: 30,
social: 22,
literacy: 8,
survival: 35,
medicine: 2,
xpProgress: [12.5, 3.2, 0.8, 18.4, 4.0, 1.1, 22.7, 0.0],
lastXpTick: [123456L, 100000L, 50000L, ...]
}

Backward compat: missing skill keys default to 0. Existing saves load
with all skills at 0; skills accumulate from first XP grant.

## Integration points

### Phase 0 integration

- `TownspersonMob` gets `private final SkillComponent skills` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist it.
- Spawn-time initialization:
    - Adults get starting skills based on profession: primary skill at
      randomized value in [15..35], secondary in [5..20], others in
      [0..10]. Culture baseline (Phase 5) will modify these.
    - Children start with all skills at 0 (or with a small Literacy
      bump if raised in a scholarly culture).
- `NpcProfessionXp` (existing) continues to work; skill system is
  additive. Eventually XP routes through skills, but Phase 0 keeps
  both systems parallel to avoid breaking existing behavior.
- `/npc skills <uuid>` command prints all 8 skill levels.

### Phase 2 consumer

- LITERACY skill gates book reading and writing (see
  `17-letters-and-books.md`).

### Phase 3 consumer

- Office competence calculations.
- Skill-based dialogue options: a high-SOCIAL player can persuade
  where a low-SOCIAL one cannot.

### Integration with existing `NpcProfessionXp`

The existing `NpcProfessionXp` tracks per-profession XP and multipliers.
Phase 0 leaves it alone. Phase 1+ migrates `getSpeedMultiplier` and
`getQualityChance` to consult skills. Eventually `NpcProfessionXp`
becomes a thin wrapper or gets deprecated — deferred to avoid
destabilizing existing production goals.

## Behavior contract

### Does

- Store 8 skill levels per NPC, persisted across save/load.
- Initialize skills at spawn based on profession.
- Expose read/write API for other subsystems.
- Route XP via `addXp` with automatic level recompute.
- Track last-XP-tick per skill for decay eligibility.

### Does not

- Award XP automatically in Phase 0 — storage only.
- Replace `NpcProfessionXp` in Phase 0. Coexist.
- Decay skills in Phase 0 (deferred to Phase 1).
- Support skill prerequisites or trees. Flat.

## Edge cases

- **NPC changes profession.** Skills persist. Profession-XP resets
  (existing `NpcProfessionXp.reset`). Skills are the reason an
  experienced NPC retraining feels different from a novice.
- **Skill level 0 with no XP.** Valid state; `get()` returns 0.
- **Overflow above 100.** Clamp to 100. Master tier.
- **Negative decay accumulates.** Floor at 10; never below.

## Ordering dependencies

Phase 0 scope depends on: nothing new. The existing `Profession` enum
is referenced for initial skill assignment.

Phase 1+ consumers require this subsystem stable.

## Open decisions

- Should Literacy be age-gated? A 5-year-old literate NPC is weird
  unless they're in a scholarly culture. **Proposed: children under
  teen stage cap Literacy at 20 regardless of XP.** Implement as clamp
  in `addXp` for children.
- Should skill decay be uniform across all skills or differ per skill?
  Combat decays quickly without practice, Literacy almost never decays
  once learned. **Proposed: uniform in v1; per-skill decay rates in a
  later tuning pass.**

## Does-not-include

- Player skills UI panel in v1. Add when player progression becomes
  visible in Phase 3+.
- Skill-based crafting unlocks ("can't craft iron sword without
  CRAFTING 40"). Plausible future feature; not in v1.
- Skill-based combat damage scaling for NPCs beyond what the existing
  system does.
- Per-skill prestige titles ("Master Smith"). Could add in Phase 5.

## Revision Notes

(changes recorded here as the spec evolves after testing)