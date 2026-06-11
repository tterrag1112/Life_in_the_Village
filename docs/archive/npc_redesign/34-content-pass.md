# 34 — Content Pass

## Purpose

Phases 0-4 defined the mechanical scaffolding. Many systems ship with
placeholder content that works but feels thin: ~15 dialogue trees,
minimal rumor slant templates, 3-4 textbooks, generic event text,
standard greeting barks. The content pass fills all of this in with
authored text, expanded templates, and tuning passes to give the
simulation its character.

This isn't a new system — it's a scoped authoring task that raises
the quality bar across the spec. It also catches rough edges that
only appear after full-phase integration testing.

## Scope

### Dialogue tree expansion

From Phase 1's 25 starter trees to ~80 total, covering:

- Greeting variants: 4+ per profession × mood × life-stage
  combination.
- Ask-life responses: per life-stage, per trait cluster.
- Verb response variants: 6+ per verb per trait extreme.
- Event-specific dialogue: greeting lines referencing recent events
  ("did you hear about the wedding?").
- Crisis dialogue: during plague, famine, fire events.
- Office-holder dialogue: leader speeches, priest homilies.
- Rite dialogue: priest rite scripts per rite type × culture.
- Trial dialogue: testimony, verdict, defense.
- Gossip content: slant templates per trait combination.

Dialogue writing style guide:

- Avoid modern slang; aim for timeless, slightly archaic register.
- Match speaker's LITERACY and trait profile (educated speaker uses
  longer sentences, low-Honesty speaker hedges).
- Use placeholders consistently: `{speaker.name}`, `{village.name}`,
  `{recent.event}`, `{subject.relation}`.
- Cap individual response length at ~280 characters to fit UI without
  wrapping awkwardly.

### Rumor slant templates

Phase 2 ships with ~15 slant patterns. Expand to ~60 covering:

- Character slant (positive, negative) across traits.
- Factual mutation templates per content type.
- Cultural flavor (different cultures phrase gossip differently).
- Source-attribution variations ("I heard from...", "Word is...",
  "Some say...").

### Memory descriptions

Every `MemoryType` needs a natural-language description generator for
profile display:

- `VICTIM_OF_CRIME_BY(Alric, theft)` → "Was robbed by Alric in the
  third month."
- `RESCUED_BY(player)` → "Saved by your hand during the caravan
  attack."
- `TAUGHT_BY(Master)` → "Learned the craft at Master's bench."

Phrasing varies with fidelity and recency: recent memories vivid,
old ones faded.

### Letter templates

Phase 2 ships with a basic template menu. Expand to ~30 templates:

- Formal business letters.
- Romantic correspondence (love letters).
- Family letters (home news).
- Commercial inquiry (trade offers).
- Apology letters.
- Threat letters (rare).
- Pilgrimage invitations.
- Scholarly correspondence.

Each template has slots for the player to customize.

### Book content

Textbook library from Phase 2's 3-4 to ~20:

- CRAFTING textbooks (smithing, carpentry, weaving, masonry).
- COMBAT textbooks (swordsmanship, archery).
- LITERACY textbooks (grammar, oratory).
- MEDICINE textbooks (herbal remedies, wound treatment).
- SURVIVAL textbooks (foraging, tracking).
- FARMING textbooks (crop rotation, animal husbandry).
- COMMERCE textbooks (pricing, negotiation).
- SOCIAL textbooks (etiquette, speech).

Plus cultural literature:
- 4 origin stories (one per culture).
- 4 religious scriptures (one per religion).
- 8 poetry collections.
- 12 fictional narratives.

All with author attribution (assigned to NPC scholars or "Unknown
Hand" for legendary works).

### Life goal flavor

25 life-goal types from Phase 1. Expand flavor per goal:

- 3+ variants of completion dialogue.
- 3+ variants of failure dialogue.
- Trait-flavored commentary during pursuit.

### Event content

For each of ~35 event types:

- Announcement dialogue (gossip seed text).
- Event-during-active dialogue (what attendees say).
- Completion summary.
- Villager mood commentary.

### Profession flavor

Each profession gets:

- Recruitment dialogue (hiring).
- Daily work commentary.
- Promotion / demotion reaction.
- Retirement sentiment.

### Cultural expressions

Each culture gets:

- ~15 proverbs / sayings used in dialogue.
- 5-10 song titles / lullabies (referenced, not played).
- Wedding / funeral / holiday-specific dialogue.

## Tuning pass

Alongside content, the pass tunes numerical parameters:

### Mood system tuning

- Default mood baseline values.
- Mood trigger magnitudes per event type.
- Decay rates.
- Trait-modulation curves.

Test scenario: a positive event shouldn't push an already-Content NPC
into Euphoric (saturation); a Distressed NPC should still meaningfully
recover from small wins.

### Memory formation tuning

- Initial values per memory type.
- Refresh rates.
- Pin thresholds.
- Decay curves.

Test: key life events (marriage, first child, master's masterpiece)
should remain pinned indefinitely; minor interactions should fade in
weeks.

### Relationship ledger tuning

- Proximity growth per SOCIAL phase day.
- Event-based deltas (gift, compliment, trade, betrayal).
- Decay rates.
- Mode transition thresholds.

Test: regular daily contact should maintain friendships; a single
insult shouldn't cause permanent feud.

### Economic tuning

- Per-building resource category production/consumption values.
- Market markup percentages.
- Visitor coin-flux averages.
- Wage ranges per profession.

Test: specialized villages should be economically viable with realistic
trade flows; no village should be permanently deficit with no path to
balance.

### Event scheduling tuning

- Calendar event frequency.
- Crisis event probabilities.
- Attendance probabilities per event type.

Test: a village should feel like events happen regularly (weekly-ish)
without overwhelming the player.

### Crime detection tuning

- Witness probabilities.
- Evidence weights.
- Conviction thresholds.

Test: guilty NPCs mostly get convicted; innocent mostly acquitted;
player can commit crimes with realistic consequences.

## QA scenarios

Specific scenario tests to run at end of phase:

1. **Year-one village**: start a village, simulate 365 days, verify
   all systems trigger appropriately without crashes or extreme
   states.
2. **Plague response**: trigger plague, verify healers respond, village
   quarantines if law in place, recovery happens.
3. **Wedding flow**: courtship → proposal → wedding event → marriage
   memory → household formation → child → coming-of-age → apprenticeship.
4. **Trial flow**: witnessed crime → investigation → trial → verdict
   → punishment → rumor propagation.
5. **Trade network**: 3 villages with different specializations —
   agricultural, craft, scholarly — verify caravans move appropriate
   goods, requests post and fulfill.
6. **Player as master**: player takes apprentice, runs through full
   arc to graduation.
7. **Player as leader**: player elected leader, enacts law, faces
   consequences.
8. **Elderly death arc**: NPC reaches elderly, forms unfinished
   business, resolves or dies with regret, village history records.

Each scenario documented as a repeatable test case with expected
outcomes.

## Deliverables

### Content files

- `dialogue/` YAML or JSON trees — 80 trees.
- `rumors/slants.json` — slant template catalogue.
- `books/` — 20 textbooks + 28 cultural works.
- `letters/templates/` — 30 templates.
- `events/content/` — per-event text and announcement.
- `lifegoals/flavor/` — per-goal flavor text.

### Tuning changes

Applied as constant updates in the relevant subsystem files, documented
in the Revision Notes sections of affected specs (`02-memory-system.md`,
`04-mood-system.md`, `11-npc-relationship-ledger.md`, etc.).

### Test scenarios

`tests/scenarios/` directory with documented repeatable tests.

## Integration points

### Phase 5 integration

- Content files loaded at mod init via existing registry paths.
- Tuning constants updated in-place.
- QA scenarios documented in `tests/` — not auto-running, but
  reproducible by human testing.

### Ongoing

Post-Phase-5, content expansion is ongoing. Each user-reported content
gap becomes a small content pass update.

## Behavior contract

### Does

- Fill content gaps left by phases 0-4.
- Apply numerical tuning across subsystems.
- Document QA scenarios for regression testing.

### Does not

- Add new mechanical systems — this is content and tuning only.
- Complete every possible flavor piece — aims for "rich enough",
  not exhaustive.
- Replace Phase 6 content packs — those add JSON-driven user content
  on top of this baseline.

## Edge cases

- **Content lookup miss.** Fall back to generic template;
  log warning; add to next-pass backlog.
- **Tuning value causes unexpected behavior** in production.
  Configurable via server config or data pack; easy to revert per-world.
- **Cultural sensitivity in written content.** Review pass before
  ship; avoid real-world religious/ethnic mappings.

## Ordering dependencies

Phase 5 depends on:
- All phase 0-4 systems implemented.
- Cultures (same phase).
- Events expanded (same phase).
- Appearance Layer 1 (same phase).

## Open decisions

- Content authoring tool — direct JSON/YAML or a data-gen authoring
  step? **Proposed: start with hand-authored YAML under
  `src/main/resources/data/{modid}/npc/`; revisit tooling if content
  volume grows past Phase 5.**
- Localization — ship with English only and i18n framework ready, or
  multi-language in v1? **Proposed: English only, i18n keys used
  throughout so future localization is mechanical.**
- Content diff review — how thoroughly before ship? **Proposed:
  dialogue/rumor slants reviewed sample-size ~20 per tree; letters
  and books proof-read fully.**

## Does-not-include

- Voice acting.
- Music / SFX.
- Procedural narrative generators.
- Full localization to languages other than English in v1.
- Expansion packs or subscription content.

## Revision Notes

(changes recorded here as the spec evolves after testing)
