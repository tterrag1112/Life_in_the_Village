# Conventions

Cross-cutting rules every subsystem doc assumes. Read this once; other
docs reference it rather than restating.

## Package structure

New code lives under
`tterrag1112.life_in_the_village.Npc.<subsystem>` where `<subsystem>`
matches the doc name roughly:

- `Npc.Traits` — trait axes
- `Npc.Memory` — memory log
- `Npc.Knowledge` — knowledge ledger
- `Npc.Mood` — mood state
- `Npc.Skills` — skill system
- `Npc.Office` — office framework
- `Npc.LifeGoal` — life goals
- `Npc.Dialogue` — dialogue trees
- `Npc.Verbs` — player verbs
- `Npc.Relationship` — NPC↔NPC relationships
- `Npc.Rumor` — gossip propagation
- `Npc.Culture` — culture definitions

Existing classes stay where they are; new code joins the new tree.
`TownspersonMob` gains component references to new subsystems, same
pattern as `FamilyComponent` / `EconomyComponent` / `AppearanceComponent`
today.

## Naming

- Classes: `PascalCase`, no abbreviation unless already established
  (`Npc`, `Ai`, `Xp`, `Id`, `Uuid`).
- Records over classes when immutable data makes sense.
- Enums for closed sets (trait axes, office types, memory types,
  mood categories, culture IDs).
- Codec field names match field names, lowercase with underscores where
  needed for readability.

## Persistence

Every persistent state class defines:
1. A `Codec<T>` for Mojang's data codec system, matching existing
   patterns (see `VillageTreasury`, `Company`, `Quest` for references).
2. Save/load methods on the owning entity or saved-data when stored
   separately from the codec.
3. NBT keys scoped to avoid collision. Use the subsystem prefix:
   `npcMemory.*`, `npcMood.*`, `npcSkill.*`, `npcTraits.*`.
4. `optionalFieldOf` with defaults for any field added after v1 — never
   break save compatibility. Existing saves must load cleanly.

## Component pattern

New per-NPC state attaches to `TownspersonMob` as a component field
following the existing `FamilyComponent` / `EconomyComponent` pattern:

```java
private final TraitVector traits = new TraitVector();
private final NpcMoodState mood = new NpcMoodState();
private final NpcMemoryLog memory = new NpcMemoryLog();
private final NpcKnowledgeLedger knowledge = new NpcKnowledgeLedger();
private final SkillComponent skills = new SkillComponent();
```

Components expose their own save/load methods called from
`addAdditionalSaveData` / `readAdditionalSaveData`. They do not access
the parent mob directly — callers orchestrate.

Cross-component queries go through the mob's delegation methods, not
component-to-component calls.

## Tick budget philosophy

Not everything runs every tick. The rough contract:

- **Every tick**: combat, pathfinding, goal selection (existing).
- **Every 20 ticks (1 second)**: mood decay checks, memory decay
  checks, knowledge fidelity checks. Split NPCs across 20 buckets so
  each tick updates ~5% of loaded NPCs.
- **Every 6000 ticks (5 minutes)**: life goal progress evaluation,
  relationship decay, gossip spread.
- **Once per day (24000 ticks)**: memory eviction pass, skill decay
  for unused skills, office competence re-evaluation, trait drift
  from life events, schedule regeneration.
- **On event**: dialogue, trade, gift, crime, rescue, letter received,
  rumor heard, office-election, festival attended.

Never loop all loaded NPCs every tick for any expensive operation.
Use the 20-bucket pattern.

## Save-size philosophy

Per-NPC data cost matters. Rough per-NPC budget for new state:

- Traits: 8 floats = 32 bytes
- Memory log: up to 32 entries × ~60 bytes = ~2KB
- Knowledge ledger: up to 64 entries × ~50 bytes = ~3KB
- Mood: 1 float + small event list = ~200 bytes
- Skills: 8 shorts = 16 bytes
- Life goals: up to 3 × ~100 bytes = ~300 bytes
- Relationship ledger: up to 15 entries × ~30 bytes = ~450 bytes

Total per NPC: ~6KB. At 200 NPCs × 50 villages = 60MB uncompressed. NBT
compresses well; real cost will be lower. Still — hard caps matter.
Don't let any list grow unbounded.

## UUID handling

All cross-reference IDs use `UUID`. Store via `UUIDUtil.STRING_CODEC`
or `UUIDUtil.CODEC` as appropriate. Prefer the string codec for
human-readable saves unless size is critical.

## Optional fields and null

Prefer `Optional<T>` over nullable references in public APIs. Internal
fields can be `@Nullable` with the annotation.

## Codec backward compatibility

When adding fields to an existing record with a codec:

1. Use `optionalFieldOf("name", defaultValue).forGetter(...)`.
2. Test loading an existing save before committing.
3. Document the default value in the doc's Revision Notes section.

Never reorder existing codec fields.

## Performance quick checks

When touching a hot path:
- Use `AABB.inflate` sparingly; prefer cached neighbor lookups where
  possible.
- Don't call `level.getEntitiesOfClass` in a tick handler — cache
  results or restrict to lower-frequency ticks.
- String comparisons on profession / village names: prefer UUID
  comparisons where the UUID is already at hand.

## Error handling

NPC-state errors log a warning and fall back to safe defaults. Never
crash the save load over a corrupted trait value; clamp to 0. Every
component's load method must be defensive.

## Testing strategy

Each subsystem ships with at least:
- An in-world `/npc` command subcommand to inspect state
  (`/npc traits <uuid>`, `/npc memory <uuid>`, etc.).
- One scripted test scenario via `/npc test <subsystem>` that sets up
  a known state and triggers the behavior.

No JUnit tests required for this rework — the NeoForge runtime
doesn't sandbox well enough. Commands are the test harness.

## Don't-do list

- **Don't** cache entity references across ticks; store UUIDs and
  resolve on demand.
- **Don't** add fields to `TownspersonMob` directly; route them
  through components.
- **Don't** create new saved-data classes when existing ones can host
  the field (check `VillageSavedData`, `PlayerGuildData`, etc. first).
- **Don't** write JSON-driven content in v1; cultures/dialogue/events
  stay hardcoded until explicitly migrated in Phase 6.
- **Don't** add new packets when existing ones can carry the data.
  `NpcProfileSnapshot` is the preferred delivery vehicle for display
  state.

## Revision Notes

(changes recorded here as conventions evolve)