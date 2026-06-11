# 09 — Player Verbs

## Purpose

The current player-NPC interaction surface is thin: trade, give gift,
work-assign, open profile. For NPCs to feel like people, the player
needs more verbs — more ways to engage that are meaningful, context-
sensitive, and consequential.

Verbs are pluggable actions any NPC can receive. Each verb has an
eligibility check (when can this verb be offered?), a cost or risk, an
effect (dialogue trigger, memory creation, relationship delta, mood
change), and in some cases a player-side progression hook.

Phase 1 ships with 8 starter verbs; the framework supports adding more
in later phases as new systems come online.

## Data model

### PlayerVerb

```javapublic interface PlayerVerb {
String id();
Component label();                       // button text
Optional<Component> tooltip();boolean isAvailable(VerbContext ctx);    // eligibility check
VerbResult invoke(VerbContext ctx);      // executedefault int displayOrder() { return 100; }
}public record VerbContext(
ServerPlayer player,
TownspersonMob npc,
ServerLevel level,
long tick
) {}public record VerbResult(
String resultTreeId,        // dialogue tree to display after
List<DialogueEffect> additionalEffects,
boolean opensExternalScreen // if true, caller should handle screen open
) {}

### VerbRegistry

```javapublic class VerbRegistry {
public static void register(PlayerVerb verb);
public static List<PlayerVerb> availableFor(VerbContext ctx);
public static Optional<PlayerVerb> get(String id);
}

## The eight starter verbs

### 1. Greet

```javaid: "greet"
label: "Greet"

Eligibility: always available. Default verb when no specific context
demands another.

Effect: runs `greeting.*` dialogue tree. Establishes initial tone of
interaction. Repeated greeting within the same in-game day has no
effect (already greeted). First greeting of the day with an NPC you
haven't insulted or wronged produces a small memory refresh on any
positive existing memory.

### 2. Compliment

```javaid: "compliment"
label: "Compliment"
tooltip: "Praise their work, appearance, or character."

Eligibility: relationship ≥ −10 (very hostile NPCs reject compliments).

Effect:
- Player selects from sub-options based on context (their work, their
  appearance, their family, their bravery, etc.). Context offers
  2–3 relevant options.
- Specific-compliment quality depends on the player's SOCIAL skill and
  whether the compliment matches a trait the NPC actually has.
  - Matching compliment (praised Courage to a Courageous NPC): +5
    relationship, +3 mood, creates `COMPLIMENTED_BY` memory.
  - Mismatched compliment (praised Courage to a Timid NPC): +1
    relationship, 0 mood, small awkwardness.
  - Hollow compliment (low SOCIAL skill, random context): +1 rel, 0 mood.

Cooldown: once per in-game day per NPC.

### 3. Insult

```javaid: "insult"
label: "Insult"
tooltip: "Harsh words. This will be remembered."

Eligibility: always (risk is the point).

Effect:
- Runs insult-response dialogue tree.
- Creates `INSULTED_BY` memory (25 value; 50 if in public — other NPCs
  nearby).
- Relationship −15.
- NPC's mood `INSULT_RECEIVED` trigger.
- If NPC's Temperance is low (< −0.4), may trigger an aggressive
  response: attack, refuse service for a week, or gossip negatively.
- Witnessed by nearby NPCs → their opinion of the player drops slightly
  (reputation effect).

Cooldown: none; player can escalate as much as they like. But each
subsequent insult deepens the grudge.

### 4. Ask about their life

```javaid: "ask_life"
label: "Ask about their life"
tooltip: "Learn what they're working toward."

Eligibility: relationship ≥ +10 for a full answer; below that, vague
responses only.

Effect:
- NPC shares their primary active goal (via dialogue with
  `{goal.primary.narrative}` placeholder).
- If relationship ≥ +30, NPC shares all active goals.
- If relationship ≥ +50, NPC shares goal + why it matters to them
  (backstory text from life-goal templates).
- Creates no memory; this is information-gathering.
- Low-Honesty NPCs may lie or dodge — provide a surface answer that
  doesn't match their actual goal.

This verb is the player's primary discovery mechanism for helping NPCs.

### 5. Ask about someone

```javaid: "ask_about"
label: "Ask about…"
tooltip: "Inquire about another person."

Eligibility: relationship ≥ 0. Sub-choice: which other NPC to ask
about (limited to NPCs the player has met).

Effect:
- NPC draws from their knowledge ledger for the asked NPC.
- Shares what they know, degraded by 0.15 fidelity (knowledge retelling
  rule).
- If they have a strong relationship with the asked NPC (friend:
  positive tone; rival: negative tone), tone colors the response.
- Low-Honesty NPCs may fabricate. High-Sociability NPCs add color
  commentary.
- Creates knowledge entry in player (notionally; player knowledge not
  tracked in v1, so this is informational text only).

Cooldown: once per asked-about-NPC per day.

### 6. Give gift

```javaid: "give_gift"
label: "Give gift (held item)"
tooltip: "Offer the item in your main hand."

Eligibility: player has an item in main hand.

Effect:
- Checks gift appropriateness: culture + trait + profession + personal
  preferences weight how the NPC feels about this item.
- Appropriateness determines memory value and relationship delta:
  - Favorite gift: memory value 35, relationship +15, mood +20.
  - Normal gift: memory value 20, relationship +8, mood +10.
  - Off-base gift: memory value 12, relationship +4, mood +5.
  - Insulting gift (pork to a culture that views it as unclean):
    memory value 15 negative, relationship −10, mood −8.
- Item is consumed from player inventory.
- Memory entry created (`RECEIVED_GIFT`).

Cooldown: once per NPC per day (daily-stack-cap in mood system also
applies).

Phase 5 content pass fills in gift-appropriateness tables per
culture/profession/trait.

### 7. Commission work

```javaid: "commission"
label: "Commission work"
tooltip: "Pay them to craft or produce something specific."

Eligibility: NPC has a profession that can produce something; NPC is
at their workplace or during work time; player has enough coin.

Effect:
- Opens a small commission UI: select item, quantity, due date.
- Creates a `CRAFTING_ORDER` on the existing crafting-order system
  (extend with commission type if needed).
- Payment upfront (or escrow — Phase 3 decision).
- Memory created: `COMMISSIONED_WORK` value 20.
- On fulfillment: relationship +5, possible skill XP for NPC.
- On missed deadline: relationship −10, refund.

Reuses existing `CraftingOrder` infrastructure. Verb is the player-
facing entry point.

### 8. Challenge

```javaid: "challenge"
label: "Challenge"
tooltip: "Propose a contest of skill."

Eligibility: relationship ≥ −30 (even rivals can accept a duel);
NPC has Courage > −0.4 (very timid NPCs decline all challenges).

Effect:
- Sub-choice: challenge to duel (COMBAT), wager (COMMERCE), contest
  of strength (varies by culture), drinking contest (culture-gated).
- NPC's acceptance depends on: trait match (Ambitious / Courageous
  accept more), current mood (distressed NPCs decline), relative
  skill (too one-sided → decline).
- On acceptance: mini-event runs (duel mechanic, coin-flip wager,
  etc. — details Phase 5 content).
- Win/loss affects relationship (±5), creates memory, awards
  skill XP.

Some professions and cultures expand this — a Highmarch guard
welcomes combat challenges; a Silkwood scholar might accept a
debate contest.

## Verb display

In the NPC profile / business-front GUI, available verbs appear as
buttons. Unavailable verbs are hidden (not greyed) to reduce clutter.
Sort by `displayOrder()`; default order is:

1. Greet (only if nothing else applies)
2. Trade (existing; has display order 10)
3. Give gift (20)
4. Ask about their life (30)
5. Ask about someone (40)
6. Compliment (50)
7. Commission work (60)
8. Challenge (80)
9. Insult (99) — last, visually separated

Work-related verbs (assign work, etc.) keep their existing order.

## Verb cooldowns

Per-verb cooldowns prevent spam. Tracked on the NPC side (cheaper than
per-player):

```java// In NpcMemoryLog or a dedicated NpcVerbLog
public class NpcVerbCooldowns {
private final Map<String, Long> cooldowns;  // verbId → expiry tick
public boolean isOnCooldown(String verbId, UUID playerId, long now);
public void setCooldown(String verbId, UUID playerId, long expiry);
}

Persists per-NPC. Keyed by verbId and playerId to allow multiple
players.

Cooldowns default:
- compliment: 24000 ticks (1 day)
- insult: 0 (no cooldown)
- ask_life, ask_about: 24000
- give_gift: 24000
- commission: 0 (player pays each time)
- challenge: 72000 (3 days)

## Persistence

Verb system itself is stateless (registry). Per-NPC cooldowns persist
on entity tag under `npcVerbCooldowns`:npcVerbCooldowns: {
"compliment": {
"uuid-player-1": 130000L,
"uuid-player-2": 128500L
},
"give_gift": { ... }
}

Cleared lazily when read (expired entries removed).

## Integration points

### Phase 1 integration

- `VerbRegistry` populated at mod init with the 8 starter verbs.
- `NpcProfileHub` (existing) extended to list available verbs.
- `ActionBarPanel` (existing) rebuilt to source from `VerbRegistry`
  instead of hardcoded buttons. Existing actions (trade, guild,
  assign-work) reimplemented as verbs or kept as specialized buttons
  coexisting with the verb list.
- `NpcProfileActionPacket` extended to carry verb ID; server-side
  dispatches to `VerbRegistry.get(id).invoke(ctx)`.
- Each verb implements the 8 listed behaviors.

### Phase 2+ integration

- Letter-related verb: "Send letter" — unlocks with Literacy ≥ 30 and
  scribe access.
- Gossip-related verb: "Tell a rumor" — Phase 2.

### Phase 3 integration

- Political verbs: "Endorse for office", "Petition", "Accuse of crime",
  "Testify" — Phase 3 crime/office integration.

### Phase 4 integration

- Trade-expansion verbs: "Offer partnership", "Request caravan".

## Behavior contract

### Does

- Register a set of player verbs with eligibility and effect logic.
- Surface available verbs in the NPC profile GUI.
- Dispatch verb invocations to their effect handlers.
- Track per-NPC per-player cooldowns.

### Does not

- Replace existing specialized interactions in Phase 1. Trade, guild,
  assign-work coexist during migration.
- Support player-authored verbs.
- Allow verbs outside the profile GUI (no mid-world hotkeys to
  compliment). Interaction requires opening the profile.
- Apply any effect beyond what subsystems expose (memory, mood,
  relationship, dialogue, etc.).

## Edge cases

- **NPC is busy** (e.g. in conversation with another player). Verbs
  unavailable until released.
- **Verb invocation fails** (e.g. commission UI has no valid items).
  Return with an error line via dialogue; no cooldown consumed.
- **Player targets themselves via "Ask about someone".** Filter self
  out of choice list.
- **Insult during greeting tree.** Insult interrupts; runs
  insult-response tree immediately.

## Ordering dependencies

Phase 1 depends on:
- Dialogue tree (`08-dialogue-tree.md`) — verbs trigger trees.
- Memory system (Phase 0 + producers) — verbs create memories.
- Mood state — verbs apply triggers.
- Skill component — SOCIAL skill gates compliment quality, etc.
- Life goals — "ask about their life" surfaces goals.
- Knowledge ledger — "ask about someone" queries knowledge.
- Existing `NpcProfileHub` and related GUI infrastructure.

## Open decisions

- Should Greet be an explicit verb or implicit (happens on profile open
  without needing a button)? **Proposed: implicit. The first line
  shown when profile opens runs the greeting tree automatically.
  The Greet button is unnecessary in v1.**
- Challenge mechanic — full mini-game in Phase 1 or stub? **Proposed:
  stub in Phase 1 (dialogue + skill roll, no combat mechanic); full
  duel mini-game in Phase 5.**
- Commission — how does the existing crafting-order system handle
  player→NPC commissions? Existing flow is NPC→player (assignments).
  Commission may need new path. **Proposed: add a `PlayerCommission`
  variant of the crafting order, with player as client; implement as
  part of Phase 1 commission verb.**

## Does-not-include

- Player trading verbs (already exists).
- Violence verbs (attack). Existing vanilla combat handles this.
- Follow / lead verbs. Existing adventurer-party system handles
  following behavior.
- Verbs that require kingdom/guild offices (endorse, accuse). Deferred.

## Revision Notes

### 2026-04-23 — Phase 1 implementation (task 09)

Implementation landed in `tterrag1112.life_in_the_village.Npc.Verbs`:
core types (`PlayerVerb` interface, `VerbContext` record,
`VerbResult` record, `NpcVerbCooldowns` per-NPC component),
`PlayerVerbRegistry` static catalogue + `VerbInvocation` server-
side glue, and 8 verb implementations under `Impl/`. New packet
`Networking.PlayerVerbInvokePacket` carries the verb id + arg map
client→server. `ActionBarPanel` renders one button per available
verb pulled from `NpcProfileSnapshot.availableVerbIds`.

**Locked decisions:**

- **Compliment-topic mapping** (spec line 75-80 ambiguous on
  exact topic→trait list). Implemented in
  `ComplimentVerb.TOPIC_TRAIT`:
  work/craft → INDUSTRY, bravery → COURAGE,
  honor/honesty → HONESTY, kindness → COMPASSION,
  wit → SOCIABILITY, patience → TEMPERANCE, ambition → AMBITION,
  generosity → GENEROSITY. Topics not in the map (appearance,
  family, etc.) never trait-match — always hollow per spec.
  Default topic when none supplied is "kindness".

- **Public-insult radius**: 8 blocks (spec line 100 says "other
  NPCs nearby" without a radius). Closer than the 16-block
  death-witness scan since "earshot" is more localised.
  `InsultVerb.PUBLIC_RADIUS_BLOCKS` constant. Open to retuning.

- **Verb cooldowns**: spec defaults applied verbatim (line 253):
  compliment / ask_life / ask_about / give_gift = 1 day,
  challenge = 3 days, insult / commission / greet = none.
  Stamped on `NpcVerbCooldowns` only when the verb returns
  success.

- **Favorite-gift detection** (spec line 173 explicitly defers to
  Phase 5): `GiveGiftVerb.appropriatenessOf` returns
  `APPROPRIATE` for everything as a Phase 1 stub. The bus event
  carries the appropriateness; memory + mood producers respond
  correctly when Phase 5 supplies a real table — no further code
  changes needed.

**GreetVerb is explicit** despite spec line 347 ("Open decisions")
suggesting it be implicit. Spec preference noted on the class —
if testing decides the auto-greeting from
`NpcDialogue.getGreeting` is sufficient, the verb can be removed
without other downstream changes (UI just renders one fewer
button). Display order is 5 so it sits at the front of the verb
list rather than crowding specialised verbs.

**Network shape — separate packet, not extended.** Spec line 285
suggests extending `NpcProfileActionPacket` with a verb id.
Implementation ships a new `PlayerVerbInvokePacket` instead.
Reasoning: the legacy `Action` enum is hardcoded across UI and
server; extending it risks a serialisation collision with old
clients. The new packet is additive, the legacy packet keeps
working untouched, and the migration window stays clean. Phase 6
or a later refactor can fold the legacy actions into verbs and
retire `NpcProfileActionPacket`.

**Cooldown enforcement** is centralised in `VerbInvocation.invoke`
(used by both the network packet handler and `/verb fire`). Verbs
return `VerbResult` and the central glue stamps cooldowns / routes
the dialogue tree / sends chat feedback — verbs don't do their own
cooldown bookkeeping.

**Persistent component** `NpcVerbCooldowns` ships as the second
new persistent field on `TownspersonMob` after Phase 1's
`TraitDriftLog`. Saved under `npcVerbCooldowns` per spec NBT
shape (line 264). Lazy-eviction on read keeps the map from
accumulating expired entries.

**ActionBarPanel coexistence.** Existing legacy buttons (Trade,
Guild Board, Assign Work, etc.) keep their original packet path.
Verb buttons render below them, sourced from
`NpcProfileSnapshot.availableVerbIds`. The legacy "Give Gift
(hand)" button is suppressed when the give_gift verb is in the
list to avoid duplicates; it remains as a fallback if the verb
registry is empty (e.g. tests that bypass init).

**`CommissionVerb` is a stub** (spec line 357 explicitly defers
the proper PlayerCommission implementation). Phase 1
implementation eligibility-gates by profession + work-time, then
returns a screen-open result that the host should route to the
existing crafting-orders UI. Real PlayerCommission lands in a
follow-up.

**`ChallengeVerb` is a stub** (spec line 351). Phase 1 ships
eligibility (relationship ≥ −30, Courage > −0.4) + a placeholder
dialogue result. The actual contest mechanic (duel / wager /
debate) lands in Phase 5 content pass.

**Spec↔prompt deviations:**

- Prompt says verbs implement
  `boolean isAvailable(player, npc, context)`. Spec uses a
  single `VerbContext` record (player + npc + level + tick) and
  `boolean isAvailable(VerbContext)`. Used spec.
- Prompt's `VerbResult` fields differ from spec — used spec
  (success, resultTreeId, additionalEffects, opensExternalScreen,
  statusText) plus a couple of factory constants for readability.
- Prompt says "GreetVerb posts no event"; spec confirms greeting
  is not an event source. Implementation matches.
- Prompt mentions `ChallengeIssued` as a bus event — not in spec
  event list and not in Phase 1 task 10. Skipped.

**Not implemented in this session (deferred per prompt's DO NOT
list and standing UI-deferral pattern):**

- Verb-flavored response variation (Phase 5 content pass).
- New verbs beyond the 8 starters (later phases).
- Player profession gating (player professions integrate later).
- Commission UI proper (Phase 1.5 / Phase 3).
- Challenge contest mechanic (Phase 5).
- Sub-option pickers for compliment topic / ask-about target /
  challenge type (Phase 1 ships defaults; richer pickers can be
  added without changing the verb pipeline — args map already
  carries the data).