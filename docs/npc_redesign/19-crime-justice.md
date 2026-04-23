# 19 — Crime & Justice

## Purpose

Crime ties together memory (WITNESSED_CRIME_BY), relationships (feuds
escalate), gossip (rumor about accusations), offices (constable,
bailiff, leader), and village laws (what counts as a crime, what the
punishment is). The player can commit, witness, accuse, be accused,
judge, defend, or exonerate.

Scope is village-interior only. Warfare-scale conflict is out of v1.

## Data model

### CrimeType

```java
public enum CrimeType {
    THEFT_MINOR,        // item < 20 bronze
    THEFT_MAJOR,        // item >= 20 bronze
    ASSAULT,
    MURDER,
    FRAUD,              // deception → material loss
    CONTRACT_BREACH,
    VANDALISM,
    TRESPASSING,
    SMUGGLING,          // contraband per village law
    SEAL_VIOLATION,     // breaking a sealed letter
    TAX_EVASION,
    PERJURY,
    BRIBERY;

    public CrimeSeverity severity() { ... }
}

public enum CrimeSeverity { MINOR, MODERATE, SERIOUS, CAPITAL }
```

### CrimeReport

```java
public record CrimeReport(
    UUID reportId,
    CrimeType type,
    UUID perpetratorId,          // null if unknown
    UUID victimId,               // null for victimless
    List<UUID> witnessIds,
    UUID reportedById,
    BlockPos location,
    long occurredTick,
    long reportedTick,
    List<String> evidenceNotes,
    ReportStatus status,
    Optional<UUID> investigatorId,
    Optional<UUID> trialId
) { ... }

public enum ReportStatus {
    FILED, INVESTIGATING, DISMISSED, TRIAL_SCHEDULED,
    TRIAL_COMPLETE, COLD;
}
```

### Trial

```java
public record Trial(
    UUID trialId,
    UUID reportId,
    UUID presidingOfficeHolderId,
    List<UUID> jurors,           // empty in v1
    List<TrialEvidence> evidence,
    List<TrialTestimony> testimonies,
    long scheduledTick,
    long completedTick,
    TrialVerdict verdict,
    Optional<Punishment> punishment
) { ... }

public record TrialEvidence(EvidenceType type, String description,
                            UUID providedBy, float weight) {}

public enum EvidenceType {
    WITNESS_TESTIMONY, PHYSICAL_ITEM, DOCUMENT,
    FORENSIC, CIRCUMSTANTIAL;
}

public record TrialTestimony(UUID witnessId, String statement,
                             float credibility, boolean corroborated) {}

public enum TrialVerdict { PENDING, GUILTY, NOT_GUILTY, MISTRIAL }
```

### Punishment

```java
public record Punishment(
    PunishmentType type,
    long bronzeAmount,
    long durationTicks,
    UUID confiscatedItemStackId,
    String details
) {}

public enum PunishmentType {
    WARNING, FINE, RESTITUTION, COMMUNITY_LABOR,
    DETENTION, EXILE, EXECUTION,
    ITEM_CONFISCATION, OFFICE_BAR;
}
```

### CrimeSavedData

Per-world saved data:

```java
public class CrimeSavedData extends SavedData {
    private final Map<UUID, CrimeReport> reports;
    private final Map<UUID, Trial> trials;
    private final Map<UUID, List<UUID>> reportsByVillage;
    private final Map<UUID, List<UUID>> reportsAgainst;
    // queries, mutations, codec
}
```

## Crime detection

### Theft

Existing theft detection extended: player opens container in a
building they don't own and removes items. Witnesses within line-of-
sight and 16 blocks logged. THEFT_MINOR / THEFT_MAJOR based on value.

NPC theft: rare; low-Honesty NPC with CRITICAL food need may attempt
theft from nearby stockpile.

### Assault / Murder

`LivingHurtEvent` → ASSAULT; `LivingDeathEvent` → MURDER. Self-
defense: if attacker was under attack first, no report.

### Fraud

Detected when:
- Trade completes with prices wildly off to player's disadvantage.
- A forged contract is identified.

### Vandalism

Block broken at a registered building or landmark by non-owner. Via
existing block-break event with witness detection.

### Trespassing

Player enters private building during HOME phase or while owner
flagged privacy. NPCs generally don't trespass. Uses
`BuildingPresenceTracker` (from `24-business-greeting.md`).

### Seal violation

Sealed letter broken by non-recipient (see `18-letters-and-books.md`).

### Contract breach

Apprentice/master breaks contract in bad-faith way (from
`16-apprenticeship.md`). Physical contract is evidence.

## Investigation

### Constable workflow

`ConstableInvestigationGoal`:

1. Pick highest-severity oldest FILED report.
2. Walk to crime scene.
3. Verify witnesses via memory log (corroborating `WITNESSED_CRIME_BY`
   entries).
4. Check perpetrator's recent memories and inventory for evidence.
5. Mark INVESTIGATING; fill TrialEvidence list.
6. If ≥ 2 corroborating witnesses OR physical item → TRIAL_SCHEDULED.
7. Else DISMISSED.

Vacant constable: detection rate ~50%; reports accumulate.

### Evidence scoring

| Evidence | Weight |
|---|---|
| Physical item (stolen good on perp) | 1.0 |
| Signed contract | 1.0 |
| Sealed-letter violation | 0.9 |
| Witness, high credibility | 0.8 |
| Witness, neutral | 0.5 |
| Witness, biased (rival) | 0.2 |
| Circumstantial | 0.3 |

Conviction threshold: 1.5 MINOR, 2.5 MODERATE, 3.5 SERIOUS, 4.5
CAPITAL.

## Trials

### Scheduling

- MINOR/MODERATE: village_bailiff presides.
- SERIOUS/CAPITAL: village_leader presides.
- Scheduled 1-3 days out.
- Gossip seed generated.

### Execution

Trial runs as scheduled event:

1. Presiding officer, accused, witnesses gather.
2. Evidence presented.
3. Testimonies given with credibility = `0.5 + Honesty × 0.3 - abs(rel_to_accused × 0.003)`.
4. Accused speaks (NPC Honesty drives; player selects plea).
5. Verdict computed (sum weights + judge skill + Compassion bias).
6. If GUILTY, punishment selected.

### Punishment selection

| Crime | First offense | Repeat upgrade |
|---|---|---|
| THEFT_MINOR | FINE 2x value + RESTITUTION | COMMUNITY_LABOR 7d |
| THEFT_MAJOR | FINE 3x + RESTITUTION | DETENTION 14d |
| ASSAULT | FINE 20 + rep penalty | DETENTION 14d |
| MURDER | EXILE or EXECUTION (culture) | — |
| FRAUD | RESTITUTION + FINE | OFFICE_BAR 60d |
| CONTRACT_BREACH | RESTITUTION | OFFICE_BAR |
| VANDALISM | FINE + RESTITUTION | COMMUNITY_LABOR |
| TRESPASSING | WARNING → FINE | DETENTION |
| SMUGGLING | ITEM_CONFISCATION + FINE | EXILE |
| SEAL_VIOLATION | WARNING + rep | FINE |
| TAX_EVASION | FINE 2x owed | property forfeit |
| PERJURY | FINE + OFFICE_BAR | EXILE |
| BRIBERY | FINE + OFFICE_BAR | EXILE |

Repeat-offender: ≥ 3 prior convictions → category auto-escalated.

### Execution of punishment

- WARNING: logged only.
- FINE: bronze deducted; if insufficient → DETENTION.
- RESTITUTION: transfer to victim.
- COMMUNITY_LABOR: public-works `JobPosting` at low/zero wage.
- DETENTION: confined to jail area via `DetentionGoal`.
- EXILE: migrate out of village permanently.
- EXECUTION: NPC death, kingdom history, culture-gated.
- ITEM_CONFISCATION: returned to victim or village.
- OFFICE_BAR: office vacated, can't hold for duration.

## Effects

- Victim → perpetrator relationship delta −60.
- Mood triggers on victim and witnesses.
- Rumor seed about crime propagates.
- Reputation drop for perpetrator.
- `VICTIM_OF_CRIME_BY` memory pinned if serious.
- SERIOUS/CAPITAL guilty → kingdom history.

Not-guilty when crime was real:
- Accused rep partially restored.
- Victim's memory/grudge persists.
- Accuser may face PERJURY suspicion.

## Village law integration

`22-village-laws.md` provides modifiers:
- CURFEW → time-gated TRESPASSING variant.
- FOREIGN_TRADER_BAN → items become contraband → SMUGGLING.
- DOUBLE_PUNISHMENT → severity + 1.
- PARDON_FIRST_OFFENSE → WARNING on first minor.
- BAN_EXECUTION → EXECUTION substitutes to EXILE.

`PunishmentSelector` consults `VillagePolicy` before finalizing.

## Player offices

- **village_constable**: `INVESTIGATE_CRIME` power. Investigation
  screen, active reports, priority assignment.
- **village_bailiff / village_leader**: `TRY_ACCUSED` power. Preside
  over trials.

## Integration points

### Phase 3 integration

- `CrimeSavedData` registered.
- Detection hooks:
  - `LivingHurtEvent` for ASSAULT/MURDER.
  - `BlockEvent.BreakEvent` for VANDALISM.
  - Existing theft detection creates CrimeReport.
  - `onLetterReceived` detects broken seals.
  - `BuildingPresenceTracker` for TRESPASSING.
- `ConstableInvestigationGoal` on constables.
- Trial scheduler runs daily.
- `PunishmentExecutor` applies punishments.
- New verbs: "Accuse of crime", "Testify at trial", "Defend in
  trial", "Pardon".
- Dialogue trees: constable-investigation, trial-testimony,
  verdict-announcement.
- `/crime list|report|trial` debug commands.

### Phase 4+ integration

- Cross-village fugitive pursuit.
- Kingdom court appeals.

## Behavior contract

### Does

- Detect crimes via event hooks with witness logging.
- Create CrimeReports with evidence and witnesses.
- Run constable investigations.
- Schedule and execute trials with evidence-weighted verdicts.
- Apply punishments per crime and repeat status.
- Integrate with offices.

### Does not

- Model appeals, retrials.
- Support multi-perpetrator conspiracies.
- Do forensics beyond simple weights.
- Handle extradition.
- Prevent player from committing crimes.

## Edge cases

- **No witnesses.** No report unless victim discovers loss and files
  circumstantial.
- **Victim is witness.** Credibility penalty for bias.
- **Accused dies before trial.** Trial cancelled; report resolved.
- **Constable is accused.** Leader presides, bailiff investigates, or
  delay until replacement.
- **No eligible presider.** Report stuck FILED 30d → COLD (rep/
  relationship effects apply without formal trial).
- **Multiple reports of same incident.** Consolidate by location +
  tick window.

## Ordering dependencies

Phase 3 depends on:
- Office framework (Phase 3) — constable, bailiff, leader.
- Memory system (Phase 1) — witness memories.
- NPC relationships (Phase 2) — credibility.
- Gossip (Phase 2) — rumor seeds.
- Letters (Phase 2) — seal evidence, documents.
- Apprenticeship (Phase 2) — contract breach.
- BuildingPresenceTracker (Phase 3) — trespassing.
- Village laws (Phase 3) — punishment overrides.

## Open decisions

- Execution availability by culture. **Proposed: Highmarch allows;
  Silkwood bans; Plainfolk rarely; Tidereach bans. Phase 3 defaults
  all to EXILE; Phase 5 wires culture gate.**
- NPC-witnessed NPC crimes auto-prosecute, or victim-required?
  **Proposed: ASSAULT/MURDER auto-file; minor requires victim
  report.**
- Jury trials? **Proposed: no jury v1; structure supports future.**
- Player kingdom-level rep: should cross-village crimes stack?
  **Proposed: yes; hooks into existing reputation system.**

## Does-not-include

- Private revenge vs. state justice framework. Personal feud
  escalation uses relationship state.
- Organized crime / criminal guilds. Phase 4 stretch.
- Player lawyers.
- Bribery-as-gameplay. Bribery is a crime, attempting doesn't
  guarantee outcome.

## Revision Notes

(changes recorded here as the spec evolves after testing)
