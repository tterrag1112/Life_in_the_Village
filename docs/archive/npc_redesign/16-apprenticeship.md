# 16 — Apprenticeship

## Purpose

The existing `JobPosting` system has an `APPRENTICE` role, but it's
just a hiring keyword — no arc, no progression, no graduation. For a
crafts-heavy village, apprenticeship is one of the richest narrative
spaces available: a master takes a student, teaches them for years,
the student produces a masterpiece, becomes a journeyman, eventually
a master themselves elsewhere or inherits the shop.

Apprenticeship connects life-goals, skills, NPC relationships, and the
guild system into a real career arc. The player can enter as
apprentice to an NPC master, or (as a master themselves) take NPC
apprentices.

## Data model

### ApprenticeRank

```java
public enum ApprenticeRank {
    APPRENTICE,       // entry: learning basics; skill 0..40
    JOURNEYMAN,       // mid-career: competent, not master; skill 40..75
    MASTER;           // accomplished: can run own shop, take apprentices; skill 75+

    public static ApprenticeRank fromSkillLevel(int skill) {
        if (skill >= 75) return MASTER;
        if (skill >= 40) return JOURNEYMAN;
        return APPRENTICE;
    }
}
```

### ApprenticeshipContract

Formal relationship between master and apprentice:

```java
public record ApprenticeshipContract(
    UUID contractId,
    UUID masterId,              // player or NPC
    UUID apprenticeId,          // player or NPC
    UUID buildingId,            // workplace
    Profession profession,
    long startTick,
    long expectedDurationTicks, // typical 730*24000 = 2 years in-game
    ContractStatus status,
    int progressMilestones,     // 0..4
    int masterpieceSubmitted,   // 0/1
    long lastProgressTick,
    String contractTerms        // brief text for scribal records
) {
    public static final Codec<ApprenticeshipContract> CODEC;
}

public enum ContractStatus {
    ACTIVE,
    COMPLETED,      // apprentice passed masterpiece
    TERMINATED,     // master released apprentice or vice versa
    BROKEN;         // master died or apprentice abandoned
}
```

### ApprenticeshipRegistry

Per-world saved data:

```java
public class ApprenticeshipSavedData {
    private final Map<UUID, ApprenticeshipContract> byContractId;
    private final Map<UUID, UUID> apprenticeToContract;  // apprentice → contractId
    private final Map<UUID, List<UUID>> masterToContracts; // master → contractIds

    public Optional<ApprenticeshipContract> getByApprentice(UUID apprenticeId);
    public List<ApprenticeshipContract> getByMaster(UUID masterId);
    public void add(ApprenticeshipContract contract);
    public void update(ApprenticeshipContract contract);
    public void remove(UUID contractId);

    // Standard SavedData codec pattern
}
```

Attaches to `VillageSavedData` or standalone; recommend standalone
given cross-village possibilities.

## Apprenticeship lifecycle

### Discovery

Candidate apprentice (teen or young adult) identifies a master:

1. NPC reaches ADULT life-stage with `preferredProfession` set.
2. Village scan: find NPCs with that profession at skill ≥ 70 who
   are workshop owners and don't already have max apprentices.
3. Relationship weighting: favor masters with positive relationship,
   preferentially family-friend connections.
4. If candidate found, contract offer is made (via dialogue or
   automatic proposal).

Player path: player can apply to any master via new "Apprentice under
me" verb on NPC master, or "Offer apprenticeship" verb when player is
themselves a master.

### Master-side decision

Master evaluates the apprentice candidate:

- **Relationship**: positive bias for friend, strong bias against
  rival.
- **Skill interest match**: check the candidate's interested skills
  align with profession.
- **Existing apprentices**: max 2 apprentices per master in v1.
- **Master's traits**: high Ambition masters are pickier; high
  Compassion masters accept more candidates.

If accepted, `ApprenticeshipContract` created. Master-apprentice
relationship seeded at +30.

### Active phase

During apprenticeship:

- Apprentice's `assignedBuildingId` set to master's workshop.
- Weekly schedule: follow master's schedule closely; mostly WORK
  phases at the workshop, minimal SOCIAL variance.
- Skill XP: apprentice gains +50% XP in profession's primary skill
  from production cycles (mentor bonus from master being present at
  same building).
- Fires `TAUGHT_BY(master)` memory occasionally (every 30 days).
- Apprentice's daily wage is reduced (half normal for profession,
  since master is providing training).
- Master receives +2 SOCIAL XP per session teaching.

### Milestones

Apprenticeship progresses through 4 invisible milestones, each
triggering narrative events:

| Milestone | Trigger | Event |
|---|---|---|
| 1 | Primary skill level ≥ 20 | First-week completion; apprentice given workshop access |
| 2 | Primary skill level ≥ 40 | Journeyman-readiness approached; apprentice allowed to take basic jobs |
| 3 | Primary skill level ≥ 55 | Master starts demanding independent work; minor conflict possible |
| 4 | Primary skill level ≥ 70 | Masterpiece phase unlocked |

Milestone progression is checked weekly and fires a dialogue event
between master and apprentice (optional ambient flavor).

### Masterpiece phase

When the apprentice reaches milestone 4:

- Master assigns a specific `MasterpieceCommission` — a high-grade
  item production target (e.g. a fine iron sword, a master carpenter's
  armoire, a scholar's original treatise).
- Commission is a special `CraftingOrder` with:
  - High material requirements
  - Long deadline (30 in-game days)
  - Evaluation by master on submission
- Apprentice works on it during WORK phases, drawing from workshop
  materials.
- On submission: master evaluates based on:
  - Material quality used
  - Apprentice's current skill level vs. commission difficulty
  - Master's own quality expectation (their skill level)
  - Small random roll
- **Pass**: apprentice promoted to MASTER (if skill ≥ 75; else
  JOURNEYMAN). Contract completes. Published masterpiece becomes a
  village-history artifact (Phase 4).
- **Fail**: apprentice tries again (up to 2 retries). Hard fail:
  apprentice stuck at JOURNEYMAN rank until leaving master and trying
  elsewhere, or master relents.

### Completion

Contract status → COMPLETED.

Consequences:

- Graduated apprentice gains `ApprenticeRank.MASTER` or
  `JOURNEYMAN`.
- Master's life-goal `TEACH_APPRENTICE` (if active) completes.
- Graduation event in village history.
- Graduated NPC may:
  - Stay at master's workshop as senior journeyman (some professions).
  - Leave to start their own business (via new Company founding,
    Phase 4).
  - Migrate to another village seeking a position.
- Mood boost for both: `GOAL_COMPLETED` + relationship +20.
- Memory: apprentice gets `TAUGHT_BY(master)` pinned high-value entry.

### Termination

Either party can end the contract:

- **Apprentice leaves**: moves to ABANDONED status. Skill gains lost
  going forward; apprentice must restart at another master. Memory
  bruise.
- **Master dismisses**: TERMINATED status. Relationship hit for
  apprentice (shame). Possible rumor seed.
- **Master dies mid-contract**: BROKEN status. Apprentice seeks new
  master (automatic). If master was close to completing, apprentice
  may be granted honorary journeyman status by guild (Phase 4
  integration).

## Apprenticeship contract as a physical document

With the scribal system (`17-scribal-professions.md`, same phase),
contracts are issued as physical items — a `WrittenLetter` with
special metadata. Contents:

> "Apprenticeship Contract: [Master Name], [Profession]
> takes [Apprentice Name] as apprentice for [duration].
> Terms: [standard terms text].
> Witnessed by: [village scribe or leader]."

Signed at creation. Possession of the physical contract is evidence
in disputes (Phase 3 crime/justice when contracts are broken).

Producing contracts requires a scribe. Villages without a scribe fall
back to verbal (in-memory) contracts — still enforceable, just
un-physical.

## Integration with guilds

Phase 4's guild refactor hooks into apprenticeship:

- `master_of_apprentices` office in craftsmen guild approves/tracks
  apprenticeships.
- Guild records completed apprenticeships for its profession.
- Inter-village apprenticeships (apprentice moves villages to study
  under renowned master) are tracked by guild.

Phase 2 apprenticeship works without guild (implicit guild still
exists at level 0). Phase 4 formalizes.

## Player as apprentice

Player takes "Apprentice under me" verb with a master NPC:

- Opens an acceptance dialogue; master evaluates as above.
- On acceptance: player gains PLAYER_PROFESSION matching the
  apprenticeship.
- Existing player-professions system handles XP; apprenticeship adds
  a *mentorship bonus* (+10% XP from any source while within 32 blocks
  of master at their workshop).
- Milestones trigger in-world events and dialogue for player.
- Masterpiece commission appears as a standard player quest.
- On completion, player reaches the profession's master tier.

Player can leave the apprenticeship at any time. Consequences match
NPC-apprentice leaving.

## Player as master

Player-owned business can take NPC apprentices via "Take apprentice"
verb on an eligible candidate (adult NPC applying for work at
player's building).

- Same contract mechanics.
- Player as master: evaluates candidate's skill and relationship
  score shown in dialogue.
- Player collects a daily trickle of master-wage (since apprentice
  produces net value minus training cost).
- Master-player must be physically present at workshop periodically
  for apprentice to gain mentorship bonus.

## Persistence

`ApprenticeshipSavedData` saves all contracts. Per-NPC data (rank,
mentorship target) goes on entity as part of existing profession
data.

```
apprenticeships: {
    contracts: [
        {
            contractId: "uuid",
            masterId: "uuid-or-player-uuid",
            apprenticeId: "uuid",
            buildingId: "uuid",
            profession: "BLACKSMITH",
            startTick: 100000L,
            expectedDurationTicks: 17520000L,
            status: "ACTIVE",
            progressMilestones: 2,
            masterpieceSubmitted: 0,
            lastProgressTick: 120000L,
            contractTerms: "Standard. 2 years."
        }
    ]
}
```

## Integration points

### Phase 2 integration

- `ApprenticeshipSavedData` registered as saved data.
- Apprenticeship discovery runs on ADULT life-stage transition.
- Milestone check runs on skill level-up events.
- Masterpiece commission uses existing `CraftingOrder` infrastructure
  with a new `COMMISSION_MASTERPIECE` type.
- New player verbs: "Apprentice under me" (on master NPC), "Take
  apprentice" (on candidate NPC), "Release from apprenticeship"
  (on own apprentice).
- New NPC dialogue trees:
  - `apprentice.accept`, `apprentice.reject`
  - `apprentice.milestone1` ... `apprentice.milestone4`
  - `apprentice.masterpiece.assign`, `.submit`, `.pass`, `.fail`
  - `apprentice.graduation`
  - `apprentice.terminate`
- `NpcProfileSnapshot` shows apprenticeship status if active.
- Contract items produced by scribes (see
  `17-scribal-professions.md`).
- `/apprentice` commands for debug inspection and forced transitions.

### Phase 3+ integration

- Crime: broken contracts can be disputed (Phase 3 justice).
- Office: `master_of_apprentices` recognizes completed apprenticeships.
- Guild: Phase 4 guild formalization.

## Behavior contract

### Does

- Formalize master-apprentice relationships with a contract.
- Drive apprentice skill growth with mentorship bonus.
- Progress through milestones and masterpiece phase.
- Complete or terminate with consequences for both parties.
- Produce physical contracts via scribes when available.
- Support both NPC-NPC and player-NPC apprenticeships in both
  directions.

### Does not

- Replace the existing `JobPosting` system — apprenticeship sits
  beside it as a specialized contract type.
- Guarantee apprenticeship availability — some villages have no
  eligible masters.
- Model multi-master apprenticeship (tandem training). One master
  per apprentice.
- Award profession XP outside the existing skill/XP paths. The +50%
  mentorship bonus is the entire mechanical boost.

## Edge cases

- **No masters in village for preferred profession.** Adult NPC takes
  a basic profession (LABORER, FARMHAND) and the apprenticeship
  goal remains on life-goal list, reattempted when new masters appear.
- **Master's workshop destroyed mid-apprenticeship.** Contract goes
  BROKEN; apprentice floats until a new master found.
- **Apprentice skill exceeds master's.** Possible with trait/drift;
  apprenticeship still completes on milestone progression, but
  masterpiece evaluation is subjective and master may resent.
  Resentment-leads-to-termination scenario generates a juicy memory
  and rumor.
- **Multiple apprentices at same workshop.** Both gain mentorship
  bonus when master present; rivalry may develop (they compete for
  master's attention).
- **Player leaves apprenticeship during masterpiece phase.** Loss of
  current progress; must restart if trying again.

## Ordering dependencies

Phase 2 depends on:
- Skill component (Phase 0) — progression tracking.
- Life goals (Phase 1) — `TEACH_APPRENTICE`, `SEE_CHILD_APPRENTICED`
  hooks.
- Child/elderly arcs (Phase 2, same phase) — teen→adult handoff.
- NPC relationship ledger (Phase 2, same phase) — master preference.
- Scribal professions (Phase 2, same phase) — contract documents.
- Existing `CraftingOrder` / `JobPosting` infrastructure.

## Open decisions

- Should there be a guild-level "apprenticeship rules" (standard
  duration, standard terms)? **Proposed: yes — each profession has a
  default contract template. Culture/guild can override. Phase 4.**
- Player-player apprenticeships (one player teaches another)?
  **Proposed: out of scope for v1 (deprioritized player-to-player
  interactions).**
- Masterpiece examples — what item per profession? **Proposed:
  profession-specific table:
  - BLACKSMITH: diamond sword or high-tier tool
  - CARPENTER: ornate chest or specific decorated bookshelf
  - WEAVER: banner or special cloth item
  - SCHOLAR: authored book with minimum page count
  - MEDIC/HEALER: master potion
  Phase 5 content pass elaborates.**

## Does-not-include

- Guild-run formal certification (master's test administered by
  guild). Phase 4.
- Apprenticeship contests between apprentices of rival masters.
  Narrative flavor; Phase 5+.
- Paid-up-front apprenticeship fees (reverse wage). Simple daily
  reduced-wage in v1.
- Apprentice "running away" to bigger city. Apprentice can leave;
  migration to new village is a Phase 4+ feature.

## Revision Notes

(changes recorded here as the spec evolves after testing)

### Phase 2 implementation notes

- **Masterpiece state lives on the contract record, not on a
  new CraftingOrder type.** Spec line 158 describes the
  masterpiece as "a special CraftingOrder with
  COMMISSION_MASTERPIECE type". The existing
  `CraftingOrder` ships without a type field; adding one is a
  larger surface change than this task warrants. Phase 2 rolls
  the masterpiece-phase fields (target item id, deadline,
  attempts) directly into `ApprenticeshipContract`. The
  user-visible mechanics are identical; if the type-on-CraftingOrder
  framing becomes important in Phase 3 (e.g. for player-quest
  surfacing), the data is already populated and a thin
  CraftingOrder wrapper can be added without re-doing the
  contract record.
- **`preferredProfession` field doesn't exist yet.** Spec line
  94 reads from a candidate's `preferredProfession`; that
  field has no implementation in the NPC data model.
  `ApprenticeshipMatcher.preferredProfessionFor` proxies via
  the candidate's current profession (when non-NONE) or by
  reverse-mapping from their highest-XP skill via
  `ProfessionSkills`. Phase 5 culture pass adds the proper
  preferredProfession field; this proxy keeps the discovery
  path working until then.
- **Master-side dialogue evaluation is loose.** Spec line 243
  calls for an "acceptance dialogue" when the player asks to
  apprentice. v1's `ApprenticeUnderMeVerb` always accepts
  (provided the master meets capacity / skill / building gates);
  the Phase 5 dialogue tree replaces this with the spec's
  full evaluation flow.
- **Apprentice profession + workplace inheritance.** When a
  contract starts, the apprentice's profession is set to the
  master's, and `assignedBuildingId` is rerouted to the
  master's workshop. This means the apprentice's existing
  profession work-goal automatically targets the right
  building — no apprentice-only work-goal is needed.
- **Mentorship bonus surface.** Spec line 130's "+50% XP from
  production cycles when master is at workshop" is exposed
  via `ApprenticeshipManager.mentorshipMultiplierFor` for
  NPCs (16-block co-location) and via
  `MentorshipBonus.scalePlayerXp` for players (32-block radius
  per spec line 248). The helper exists and is callable; the
  systematic deployment to every existing `addXp` call site
  is a Phase 5 polish wave. Production-cycle XP grants in the
  current goals are reachable, and starter wiring lands here.
- **Wage halving applies to player-as-apprentice path.** The
  visible wage-paying surface is `WorkplaceAssignmentManager.tickWeeklyPay`
  (player path). NPC wages are accounting-only via household
  wealth; halving for NPC apprentices doesn't surface
  anywhere user-visible in v1. The halving is applied to
  player wages when they're an active apprentice; NPC-side
  halving lands when there's a visible NPC wage surface.
- **Master-death triggers BROKEN cleanup.**
  `ApprenticeshipManager.onMasterDeath` is called from
  `TownspersonMob.onNpcDeath`. Apprentices left behind are
  not auto-rematched in v1 (spec line 204); they sit in
  BROKEN state until a future tick or debug command finds
  them a new master.
- **Contract document via existing scribe path.** Contracts
  produce a `WrittenLetterItem` with `LetterSpecial.CONTRACT`
  via `ScribalItems.contract`. When a `SCRIBE_WORKSHOP`
  exists in the village, the contract is queued via the
  scribe's `CommissionQueue` so the actual writing routes
  through the existing `ScribeWorkGoal` flow. When no scribe
  is in the village, the contract is direct-minted and dropped
  at the master's feet (spec line 222 fallback).
- **Inheritance / shop transfer on master death** — explicitly
  deferred per spec line 200-206. v1 marks contracts BROKEN
  and stops there; the spec leaves shop transfer to a later
  phase.
- **`NpcProfileSnapshot` apprenticeship status field deferred.**
  Spec line 315 mentions adding it; not added in v1 to avoid
  expanding the codec for a field whose UI panel isn't
  authored. Active contracts are visible via
  `/apprentice list` and `/apprentice info` for now; the
  profile-panel surfacing lands with the Phase 5 polish wave.
- **Masterpiece pass is skill-driven rather than item-submitted.**
  Spec line 169 says "On submission: master evaluates based on
  material quality, apprentice's current skill level vs.
  commission difficulty, master's own quality expectation,
  small random roll." v1 abstracts the submission step: when
  the apprentice's primary skill reaches
  `MASTERPIECE_PASS_SKILL`, the contract auto-completes as
  MASTER. Hard-fail after 2 retries → JOURNEYMAN. This keeps
  the v1 mechanic predictable without a full submission UI;
  the dialogue trees `apprentice.masterpiece.assign / .submit
  / .pass / .fail` surface in Phase 5 content pass.
- **Mentorship-presence radius confirmed at 32 blocks** (spec
  line 248) for the player path; NPC-NPC uses 16 blocks as a
  building-bounds approximation since the spec phrasing is
  "at the same building" rather than a hard radius. Both
  values are constants and easy to retune.
