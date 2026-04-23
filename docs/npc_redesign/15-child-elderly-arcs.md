# 15 — Child & Elderly Arcs

## Purpose

The existing life-stage system scales NPC size by age (`CHILD`, `TEEN`,
`ADULT`, `ELDERLY`) but adds no distinct behavior. A child and an
adult do the same things at proportional speed. That's a missed
opportunity: children growing up and elders retiring are some of the
most narrative-rich parts of village life.

This subsystem adds distinct behavior, schedules, and game hooks for
children and elderly, turning age into real content.

Scope:
- **Children** (CHILD + TEEN life-stages): play, school, attachments,
  simple tasks, apprenticeship entry.
- **Elderly**: retirement, mentor role, slowing schedule, dying-with-
  unfinished-business hook.

Teen→adult transition and apprenticeship mechanics bridge into the
dedicated apprenticeship doc (`16-apprenticeship.md`).

## Data model

### Life stage (existing, extended)

The `LifeStage` enum already exists. Extend behavior via:

```java
public class LifeStageComponent {
    private LifeStage stage;
    private int ageDays;           // existing
    private long birthTick;        // existing

    // New behavioral hooks
    private ChildhoodState childhood;  // non-null for CHILD/TEEN
    private RetirementState retirement;// non-null for ELDERLY

    // ... save/load, queries
}
```

### ChildhoodState

```java
public class ChildhoodState {
    private UUID primaryCaregiverId;     // household head or assigned adult
    private UUID closestFriendId;        // another child, usually
    private List<ChildhoodSkill> interestedSkills;  // 2-3 future profession hints
    private boolean schoolingActive;     // has access to school/scholar
    private ProfessionPreference preferredProfession;  // when interviewed
    private int skillPoints;             // small budget to distribute

    public static final Codec<ChildhoodState> CODEC;
}

public record ChildhoodSkill(Skill skill, int interest) {}  // interest 1-10

public record ProfessionPreference(Profession preferred, int strength) {}
```

### RetirementState

```java
public class RetirementState {
    private long retirementStartTick;
    private boolean actingAsMentor;          // actively teaching
    private UUID mentorTargetId;             // current mentee NPC
    private List<UUID> mentoredInPast;      // former mentees
    private UnfinishedBusiness unfinished;   // goal-like; drives late life

    public static final Codec<RetirementState> CODEC;
}

public record UnfinishedBusiness(
    String description,
    UUID relatedId,             // target NPC (reconciliation, inheritance)
    long startTick,
    boolean resolved
) {}
```

## Child behavior

### Schedule

Children have their own weekly schedule variant, overriding the
profession-default:

- **WAKE_UP**: same as adult household
- **MORNING_PLAY** / **SCHOOLING**: playtime (if no school) or lessons
  (if school available)
- **MEAL**: with household
- **AFTERNOON_HELP**: minor tasks, hanging around parent's workplace
  observing, or playing
- **SOCIAL**: play with other children
- **LEISURE** / **HOME**: at home

Time-of-day windows similar to adult schedule but `WORK` phases are
replaced by play/schooling/help.

### Play behavior

`ChildPlayGoal` — low priority, active during play phases:

- Find other children in the village within 32 blocks.
- Approach; play-tag or sit-together behavior.
- If alone, wander near landmarks, watch adults work, return home
  occasionally.

Play between children with matching `interestedSkills` contributes to
slow skill XP accumulation (+0.5 per play session in the shared skill
area).

### Attachment to adults

Every child picks 1 primary caregiver (usually household head; random
if orphan) and 1-2 secondary attached adults. Attachments drive:

- Following behavior during certain windows.
- Relationship ledger seeded at high values (+50 with primary, +30
  with secondary).
- Mood response: primary caregiver's death produces a `FAMILY_DEATH`
  mood hit 1.5x normal magnitude.

### Schooling

If village has a library or scholar NPC, children attend a daily
schooling phase (~2000 ticks). During schooling:

- Assemble at library or scholar's building.
- LITERACY +1 per session.
- Related skill from scholar's expertise may also gain.
- If culture has high literacy baseline, schooling is more formalized
  (structured sessions). If low, ad-hoc.

No school → children skip to play/help phase.

### Coming-of-age event

At teen→adult transition (life-stage advances by age):

- One-time rite of passage: event triggered in village if a priest
  or leader is present (ceremony, small gift from parents).
- Generate life goals (Phase 1 adulthood hook).
- Generate personal schedule override.
- `PreferredProfession` determines which apprenticeship openings
  the young adult applies to (see `16-apprenticeship.md`).
- If no apprenticeship available immediately, default to a basic
  profession (FARMHAND, LABORER) temporarily.

### Teen stage (existing)

Teens straddle child and adult. Schedule shifts toward more adult work
help, less play. Teens can start formal apprenticeships. Teens can be
assigned light workplace roles.

## Elderly behavior

### Retirement trigger

At ELDERLY life-stage:

- NPC's profession workload halves: they still come to workplace but
  perform fewer production cycles, or they fully retire.
- Decision based on: profession (some like priest don't retire;
  physical like MINER retire fully), Industry trait (high Industry
  delays retirement), and presence of a junior replacement.

### Mentor role

Elderly NPCs with high primary-skill (level ≥ 60) become mentors:

- Identify young apprentices or journeymen nearby at the same
  workplace or profession.
- Set `mentorTargetId` and hang around their workplace during
  WORK_PRIMARY.
- While mentoring: target NPC gains +50% skill XP from production
  cycles.
- The mentor gains +1 SOCIAL XP per session (teaching is social).
- Target NPC fires `TAUGHT_BY(mentor)` memory on skill milestones.

Mentoring is a hobby-adjacent activity but specifically shaped for
elderly. Runs during WORK phases, not LEISURE.

### Slowing schedule

Elderly schedule shifts:

- `WAKE_UP` earlier (+500 ticks earlier).
- `WORK_PRIMARY` shortened (50% duration).
- `LEISURE` extended (60% longer).
- `HOME_PREP` earlier (earlier bedtime).

Shift magnitude scales with age-beyond-threshold: the older the NPC,
the more slowed.

### Unfinished Business

When an NPC enters ELDERLY life-stage, there's a chance (weighted by
`Ambition` trait and presence of unresolved memories) that they form
a piece of `UnfinishedBusiness`:

Types (v1):
- **RECONCILIATION**: wants to repair a negative relationship before
  death. Target: an NPC with grudge or feud score, preferably family.
- **LAST_PILGRIMAGE**: visit a specific shrine/temple before death.
- **INHERITANCE_ARRANGEMENT**: ensure a specific inheritance passes
  to a chosen heir.
- **APPRENTICE_COMPLETION**: train one more apprentice to journeyman.
- **UNKEPT_PROMISE**: fulfill a long-standing `PROMISED_BY` memory.

The business is a soft life-goal (higher importance than normal) that
drives behavior in the elderly's final years. Resolution fires a
mood+memory+rumor event ("Old Martin finally made peace with his
brother before he passed").

If unresolved at death, a `DIE_WITH_REGRET` hook fires — kingdom
history notes it, nearby NPCs feel slight mood drop, a gossip seed
propagates about the unresolved matter.

### Death arc

Elderly NPCs nearing end-of-life (age > profession-specific threshold)
have a decreasing-HP simulation: at the extreme, they may die in their
sleep. The death fires a standard NPC death event, but a funeral is
scheduled automatically (Phase 3 funeral content) if at least one
household member survives.

Natural death from age is separate from combat death; the existing
NPC death path adds an "age-natural" flag for the memory/gossip
system to distinguish.

## Persistence

Child/elderly state on entity tag under existing life-stage NBT
subtree, extended:

```
lifeStage: {
    stage: "CHILD",
    ageDays: 7,
    birthTick: 80000L,
    childhood: {
        primaryCaregiverId: "uuid",
        closestFriendId: "uuid",
        interestedSkills: [
            { skill: "CRAFTING", interest: 7 },
            { skill: "SURVIVAL", interest: 4 }
        ],
        schoolingActive: true,
        preferredProfession: { preferred: "BLACKSMITH", strength: 6 }
    }
}
```

Or for elderly:

```
lifeStage: {
    stage: "ELDERLY",
    ageDays: 62,
    retirement: {
        retirementStartTick: 1500000L,
        actingAsMentor: true,
        mentorTargetId: "uuid",
        mentoredInPast: ["uuid1", "uuid2"],
        unfinished: {
            description: "Reconcile with Aldric",
            relatedId: "uuid",
            startTick: 1500000L,
            resolved: false
        }
    }
}
```

## Integration points

### Phase 2 integration

- `LifeStageComponent` extended with `ChildhoodState` and
  `RetirementState`.
- Existing life-stage transition code (in `TownspersonMob` age-up
  path) gains initialization for new stage-specific state.
- Coming-of-age event hook fires on TEEN→ADULT transition.
- `ChildPlayGoal` registered for CHILD life-stage NPCs.
- `MentorGoal` registered for ELDERLY NPCs meeting criteria.
- Children's schedule overrides profession-default schedule
  (`ScheduleResolver` already stacks; add child-stage as highest
  post-event layer).
- Elderly schedule slowdown applied post-personal-override.
- New life-goal types (where applicable) route `UnfinishedBusiness`
  through the goal system with special priority.
- `/npc children <village>` command lists children and their states.
- `/npc elderly <village>` command lists elderly and retirement/
  mentor state.
- `NpcProfileSnapshot` shows life-stage specifics:
  - Children: caregiver name, schooling status, preferred profession.
  - Elderly: mentor target (if any), unfinished business summary.

### Phase 3 integration

- Funeral events for age-natural deaths.
- Priest officiates coming-of-age rites.
- Schooling formalized via scholar profession presence.

## Behavior contract

### Does

- Give children distinct schedule, play behavior, caregiver
  attachment, schooling access, professional interest profile.
- Give elderly retirement triggers, mentor role, slowed schedule,
  unfinished-business life arc.
- Fire coming-of-age transition on TEEN→ADULT.
- Fire death-with-regret hook on ELDERLY death with unresolved
  business.

### Does not

- Model pregnancy or gestation (existing child-birth is instant).
- Model education-style progression with grades/levels. Schooling is
  a single ambient-XP activity.
- Enforce retirement. Elderly NPCs can keep working if industrious.
- Create art for children/elderly (existing scale-based visual).
  Full-model differences come in `33-appearance-layer1.md`.

## Edge cases

- **Orphan child.** `primaryCaregiverId` set to any adult in same
  household, or village leader if no household. Low-probability edge
  case for stability.
- **Teen with no profession preference.** Default to generic LABORER
  entry-level role until adulthood re-prompts.
- **Elderly with no junior to mentor.** `actingAsMentor = false`;
  retirement behavior without mentor duty. Normal LEISURE fills the
  time (enhanced hobbies).
- **Unfinished business target dies.** Business auto-resolves as
  "cannot be completed" with moderate regret event.
- **Child with no children to play with.** Falls back to watching
  adults, visiting caregiver's workplace, solo play near home.

## Ordering dependencies

Phase 2 depends on:
- Weekly schedule (Phase 2, same phase) — child schedule variants.
- Hobby system (Phase 2, same phase) — elderly leisure content.
- Trait vector (Phase 0) — for preference generation.
- Life goal system (Phase 1) — unfinished business as soft goal.
- Relationship ledger (Phase 2, same phase) — caregiver bonds.
- Memory system (Phase 1) — death-with-regret events.
- Apprenticeship (Phase 2, same phase) — coming-of-age handoff.

## Open decisions

- How many in-game days between life-stage transitions? Existing mod
  has defaults; this spec doesn't override. **Proposed: reuse
  existing thresholds without change.**
- Should player be able to "tutor" children (alternative to scholar
  schooling)? **Proposed: yes, via a new Phase 2 verb "Teach" (child
  only). Grants LITERACY / primary skill XP based on player skill.
  Defer to verb expansion later in Phase 2.**
- Forced retirement age? **Proposed: no hard cap — industry trait
  can keep elderly working indefinitely. But physical professions
  (MINER, GUARD, FARMER physical-heavy) have skill-based caps: past
  ELDERLY + skill below 50, forced retirement.**

## Does-not-include

- Custom art for children/elderly faces (scale only, v1).
- School buildings (repurposes library).
- Coming-of-age minigames for players at ADULT transition. Player
  progression is separate.
- Grandchild-grandparent relationship mechanics. Existing family
  component suffices.

## Revision Notes

(changes recorded here as the spec evolves after testing)
