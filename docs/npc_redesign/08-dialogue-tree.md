# 08 — Dialogue Tree

## Purpose

Dialogue is the primary channel through which NPCs express personhood.
Without conditional, memory-aware, mood-aware dialogue, every other
personhood system (traits, memories, goals, moods, knowledge) becomes
invisible to the player.

The dialogue tree system lets an NPC speak differently based on:
- Who they're talking to (player, specific NPC)
- Their current mood
- Their traits
- Their memories of the listener
- Their life goals
- Their knowledge
- Time of day, weather, season
- Recent village/kingdom events
- Their profession, family, household state

Dialogue is structured as branching trees with conditional nodes. The
tree walker picks the most specific applicable branch; line pools
provide variety so the same branch isn't monotonous.

## Data model

### DialogueNode

A node is either a line pool (leaf) or a conditional branch (interior):

```javapublic sealed interface DialogueNode
permits DialogueNode.Branch, DialogueNode.Lines, DialogueNode.Ref {record Branch(
    DialoguePredicate predicate,
    DialogueNode trueChild,
    DialogueNode falseChild
) implements DialogueNode {}record Lines(
    List<DialogueLine> pool
) implements DialogueNode {}record Ref(
    String treeId   // pointer to another tree (for reuse)
) implements DialogueNode {}
}

### DialogueLine

One spoken line plus effects:

```javapublic record DialogueLine(
String text,                       // with {placeholders}
List<DialogueEffect> effects,      // fire when line spoken
List<DialogueOption> playerOptions // for branching conversations
) { ... }public record DialogueOption(
String label,                      // what the player says
String leadsTo                     // tree ID or node ID
) {}

### DialogueEffect

Side effects when a line is delivered:

```javapublic enum DialogueEffectType {
MOOD_APPLY,          // apply a MoodTrigger to the speaker
MEMORY_REFRESH,      // refresh a memory by ID
MEMORY_CREATE,       // create a new memory
KNOWLEDGE_SHARE,     // transfer knowledge to listener
RELATIONSHIP_DELTA,  // personal relationship adjust
GOAL_PROGRESS,       // advance a goal
TRAIT_DRIFT,         // tiny trait nudge (rare)
OPEN_SCREEN,         // trigger an external UI (trade, quest, etc.)
END_CONVERSATION;public static final Codec<DialogueEffectType> CODEC = ...;
}public record DialogueEffect(
DialogueEffectType type,
Map<String, String> params
) {}

### DialoguePredicate

Boolean checks that drive branching:

```javapublic sealed interface DialoguePredicate
permits TraitGreater, TraitLess, MoodCategory, HasMemoryOf,
RelationshipAtLeast, KnowsTopic, HasGoal, IsDayPhase,
IsSeason, IsWeather, HasProfession, IsFamilyRelated,
EventActive, HasOffice, And, Or, Not {boolean test(DialogueContext ctx);record TraitGreater(TraitAxis axis, float threshold) implements DialoguePredicate {
    public boolean test(DialogueContext ctx) {
        return ctx.speaker().getTraits().get(axis) > threshold;
    }
}record HasMemoryOf(MemoryType type, Target target) implements DialoguePredicate {
    public boolean test(DialogueContext ctx) {
        UUID targetId = target.resolve(ctx);
        return ctx.speaker().getMemory().hasMemoryOf(type, targetId);
    }
}record RelationshipAtLeast(Target target, int minScore) implements DialoguePredicate {
    public boolean test(DialogueContext ctx) { ... }
}// ... similar for all predicate typesrecord And(List<DialoguePredicate> all) implements DialoguePredicate {
    public boolean test(DialogueContext ctx) {
        return all.stream().allMatch(p -> p.test(ctx));
    }
}
record Or(List<DialoguePredicate> any) implements DialoguePredicate { ... }
record Not(DialoguePredicate inner) implements DialoguePredicate { ... }public static final Codec<DialoguePredicate> CODEC = ...; // polymorphic
}public enum Target { SELF, LISTENER, PLAYER, HOUSEHOLD_HEAD, SPOUSE }

### DialogueContext

Context object passed to predicates and effects:

```javapublic record DialogueContext(
TownspersonMob speaker,
@Nullable ServerPlayer player,
@Nullable TownspersonMob listener,  // for NPC-to-NPC
ServerLevel level,
long tick,
Map<String, String> scratch         // values passed between nodes
) {
public UUID resolveTarget(Target t) { ... }
}

### DialogueTree

Named, registered trees:

```javapublic record DialogueTree(
String treeId,                       // e.g. "greeting.player.default"
DialogueNode root
) { ... }public class DialogueRegistry {
// Static registration at mod init
public static void register(DialogueTree tree);
public static Optional<DialogueTree> get(String treeId);
public static List<DialogueTree> all();
}

## Tree selection

When dialogue is invoked, a lookup resolves which tree to use:

1. **Specific context** first: `greeting.player.shopkeeper.morning`
2. Fall back to more general: `greeting.player.shopkeeper.any`
3. Fall back further: `greeting.player.default`

Trees register under composite IDs following this pattern:<intent>.<listenerType>.<role>.<modifier>

- `intent` — greeting, farewell, trade_open, tell_gossip, etc.
- `listenerType` — player, npc, child
- `role` — shopkeeper, innkeeper, guard, scribe, leader, default
- `modifier` — morning, evening, angry, drunk, mourning, etc.

Lookup tries progressively shorter IDs by dropping modifier first, then
role, then listenerType. Always falls through to a universal default.

## Tree walker

Walks from root, evaluating branches:

```javapublic class DialogueWalker {
public static DialogueLine select(DialogueTree tree, DialogueContext ctx) {
DialogueNode node = tree.root();
while (!(node instanceof DialogueNode.Lines leaf)) {
if (node instanceof DialogueNode.Branch b) {
node = b.predicate().test(ctx) ? b.trueChild() : b.falseChild();
} else if (node instanceof DialogueNode.Ref ref) {
DialogueTree sub = DialogueRegistry.get(ref.treeId()).orElse(null);
if (sub == null) return fallback();
node = sub.root();
}
}
return pickFromPool(leaf.pool(), ctx);
}private static DialogueLine pickFromPool(List<DialogueLine> pool, DialogueContext ctx) {
    // Weighted random; recently spoken lines by this NPC are down-weighted
    // to avoid repetition within a session.
}
}

## Placeholder substitution

Lines use `{placeholder}` tokens filled from context:

- `{speaker.name}`, `{speaker.profession}`
- `{listener.name}`, `{listener.profession}`
- `{village.name}`, `{kingdom.name}`
- `{weather}`, `{season}`, `{time_of_day}`
- `{memory.X}` — latest memory of type X involving listener
- `{goal.primary.narrative}` — the primary goal's narrative text
- `{relationship.tier}` — "stranger", "acquaintance", "friend", etc.

Substitution is a post-selection pass applied to the final line's text.
Fallback: missing tokens render as empty string with a logged warning.

## Sample trees (illustrative)

### Greeting the player (default)

```javaDialogueTree greetingPlayerDefault = new DialogueTree(
"greeting.player.default",
new Branch(
new HasMemoryOf(MemoryType.SAVED_BY, Target.LISTENER),
new Lines(List.of(
new DialogueLine("You again, {listener.name}. I owe you my life.",
List.of(new DialogueEffect(MEMORY_REFRESH, ...)), options()),
new DialogueLine("My savior returns. What brings you?",
List.of(new DialogueEffect(MEMORY_REFRESH, ...)), options())
)),
new Branch(
new HasMemoryOf(MemoryType.INSULTED_BY, Target.LISTENER),
new Lines(List.of(
new DialogueLine("What do you want now?",
List.of(new DialogueEffect(MOOD_APPLY, ...)), options()),
new DialogueLine("Haven't you said enough?",
List.of(), options())
)),
new Branch(
new RelationshipAtLeast(Target.LISTENER, 40),
/* friend lines /,
new Branch(
new MoodCategory(DISTRESSED),
/ distressed lines /,
/ generic lines */
)
)
)
)
);

Phase 1 content starts with stubs like the above — 3–5 lines per pool,
roughly 20 trees covering common contexts. Phase 5 content pass expands
every pool and adds specialized trees.

### Shopkeeper greeting in their shop during work timegreeting.player.shopkeeper.work
Branch: relationship >= 40
Lines: ["Ah, {listener.name}! Back for more?", ...]
false → Branch: mood == DISTRESSED
Lines: ["What can I do for you.", ...]  (curt)
false → Lines: ["Welcome to {speaker.shop_name}. What'll it be?", ...]

## Dialogue-driven actions

Line effects can trigger interactions beyond text. Common patterns:

- **Offer trade**: `OPEN_SCREEN` effect with trade UI
- **Share a rumor**: `KNOWLEDGE_SHARE` effect transfers a low-fidelity
  copy of one of the speaker's recent knowledge entries
- **Ask for help**: line surfaces a player option that accepts a goal
  as a quest
- **Give a gift spontaneously**: `MEMORY_CREATE(GAVE_GIFT)` effect
- **Become hostile**: multiple negative exchanges trigger
  `END_CONVERSATION` effect and a relationship decrement

## NPC-to-NPC dialogue

Dialogue isn't only player-facing. NPCs chat during SOCIAL phase and
while working near each other. NPC-to-NPC dialogue runs a simplified
walker — no player options, effects apply, but no text is rendered
(or rendered as ambient chat bubbles for nearby players).

Purpose of NPC-NPC dialogue is gossip propagation (knowledge share),
relationship growth, and memory refresh. This runs in the existing
`SocialWalkGoal` / `EatMealGoal` — each tick during these goals has
a small chance to trigger a chat interaction.

## Registration and content

Phase 1 ships with ~25 registered trees covering:

- 3 greeting trees (default, shopkeeper, leader)
- 3 farewell trees (default, friend, distressed)
- 4 trade-related (open, refuse, thank, haggle)
- 3 gossip (tell, hear, deny)
- 3 goal-related (ask-about-life, offer-help, accept-help)
- 3 verb-triggered (compliment-response, insult-response, ask-about-other)
- 2 event-related (festival-greeting, mourning)
- 4 job-related (accept-work, refuse-work, deliver-work, fire)

All trees are registered in code at mod init. Phase 6 migrates to JSON
for modding; not in v1.

## Player-facing conversation flow

When the player interacts with an NPC:

1. `DialogueWalker.select` runs on the greeting tree.
2. The line renders in chat or a dialogue UI (Phase 1 uses chat; a
   dedicated UI could come in a later polish pass).
3. If the line has `DialogueOption` entries, player sees option
   buttons (chat-command-style in v1, like vanilla villager trades).
4. Selecting an option runs another tree or ends the conversation.

For Phase 1 simplicity, most dialogue is single-line responses. Multi-
turn branching conversations are reserved for specific high-value
interactions (accepting quests, haggling).

## Persistence

Dialogue state is mostly static (trees registered at init). Per-NPC
dialogue history (recently spoken lines) lives in a small in-memory
cache, cleared on server restart — not persisted.

Per-player conversation state (active conversation, current tree,
current node) is session-only.

Effects persist via their target subsystems (memory, mood, etc.).

## Integration points

### Phase 1 integration

- `DialogueRegistry` populated at mod init with 25 starter trees.
- `NpcInteractionHandler` extended: when player interacts, run
  `DialogueWalker.select` for greeting tree and display the line.
  This becomes the first line seen in the NPC profile screen.
- `NpcDialogue` (existing) becomes a simple shim over the registry.
- Effects dispatch through an `EffectDispatcher` that routes to each
  subsystem.
- NPC-to-NPC chat hook added to `SocialWalkGoal` and `EatMealGoal`.
- `/dialogue test <treeId> <npcUuid>` command to preview trees.

### Phase 2+ integration

- Gossip system (Phase 2) uses `KNOWLEDGE_SHARE` effects.
- Rumor content sourced from speaker's memories via `{memory.X}`
  placeholder.
- Player verbs (see `09-player-verbs.md`) each fire specific trees.

### Phase 5 content pass

- Expand all pools (5 lines → 15-20 lines per pool).
- Write culture-specific variants: `greeting.player.default.highmarch`
  for militant culture, etc.
- Write mood-modifier trees for each major mood state.

## Behavior contract

### Does

- Select a context-appropriate line for any NPC-player or NPC-NPC
  interaction.
- Dispatch line effects to memory, mood, knowledge, relationship,
  goal systems.
- Substitute placeholders from context.
- Avoid immediate repetition via pool weighting.
- Fall through progressively general trees when specific ones absent.

### Does not

- Persist per-NPC dialogue history across saves.
- Provide a dialogue editor. Trees are code-defined in v1.
- Handle text-to-speech or any audio. Text only.
- Support player-authored dialogue. Fixed content.
- Model NPC-specific speech impediments, accents, or language
  variations in v1 — though placeholders give hooks for future work.

## Edge cases

- **No tree found after fallback.** Return a hardcoded ultimate
  fallback line ("…"). Log warning. Never crash.
- **Effect references a missing memory/goal/etc.** Fail silently;
  line still displays.
- **Player interrupts mid-line.** Effects from the current line
  still fire; conversation ends cleanly.
- **Two players interact with same NPC simultaneously.** Existing NPC
  lock prevents this; second player sees "busy" message.
- **Placeholder resolution fails.** Render empty string; log.
- **Pool is empty.** Return fallback line; log.

## Ordering dependencies

Phase 1 depends on:
- Trait vector (Phase 0) — for TraitGreater/Less predicates.
- Mood state (Phase 0 storage + decay) — for MoodCategory predicate.
- Memory log (Phase 0 storage + Phase 1 producers) — for HasMemoryOf.
- Knowledge ledger (Phase 0 storage) — for KnowsTopic.
- Life goals (Phase 1) — for HasGoal predicate and goal placeholders.

Has circular-ish relationship with memory producers: dialogue can
create/refresh memories, and memories gate dialogue branches. Both
must work; neither blocks the other.

## Open decisions

- Dialogue UI: chat messages vs. dedicated UI panel? **Proposed:
  chat for v1 (matches existing mod style); dedicated UI as a Phase 5
  polish task.**
- Should NPC-NPC chat be audible/visible to nearby players? **Proposed:
  small overhead bubble with first few words when within 16 blocks;
  full text not shown. Adds life to villages without cluttering.**
- Haggling and multi-turn trade negotiation: simple option-tree or
  richer mechanic? **Proposed: simple 3-option tree in v1
  (accept / counter / walk away); deeper haggling as a skill-gated
  Phase 3 feature.**

## Does-not-include

- Voiced dialogue.
- Dynamic NPC-authored dialogue (NPCs generating their own lines).
- Cross-language dialogue. Culture language families are Phase 5+.
- Dialogue replay / history UI. Session only.
- Auto-translation of player chat for NPC understanding. N/A for v1.

## Revision Notes

(changes recorded here as the spec evolves after testing)