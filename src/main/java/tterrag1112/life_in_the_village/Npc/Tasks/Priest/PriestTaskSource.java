package tterrag1112.life_in_the_village.Npc.Tasks.Priest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteCapability;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecutor;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteTier;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskIds;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSource;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.EventCategory;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * G4 — task source for priest rite-officiation tasks.
 *
 * <p>On each refresh it:
 * <ol>
 *   <li>Resolves the priest's assigned sacred building (TEMPLE/CHAPEL/SHRINE)
 *       and the village that building belongs to.</li>
 *   <li>Queries {@link RiteSavedData#dueRites} for PENDING rites in this
 *       village whose presider is either vacant or already this priest, and
 *       that this priest {@link RiteCapability#canOfficiate} can perform.</li>
 *   <li>Excludes rites linked to an active RELIGIOUS_RITE gathering (festival
 *       fronting path in {@code PriestBehavior} handles those; see disjointness
 *       note below).</li>
 *   <li>Emits one {@code officiate_rite} task per claimable rite, with stable
 *       id keyed by rite UUID, priority derived from tier + lateness urgency,
 *       and {@code at} = rite location (guarded against BlockPos.ZERO).</li>
 *   <li>Prunes stale unclaimed officiate_rite tasks not in the live set.</li>
 * </ol>
 *
 * <h3>Fronting disjointness</h3>
 * {@code PriestBehavior.tryStartFronting} claims the BLESSING rite of an active
 * RELIGIOUS_RITE gathering (category check on {@code VillageEvent.EventType}).
 * This source excludes any rite whose id appears as the {@code riteId} eventData
 * of an active RELIGIOUS_RITE gathering. That is the precise linkage: a fronted
 * rite and a task-sourced rite are provably disjoint.
 *
 * <h3>Priority</h3>
 * Tier maps to base priority (GRAND→HIGH, STANDARD→NORMAL, MINOR→LOW).
 * Urgency scales by {@code clamp((now−scheduledTick)/OFFICIATE_GRACE_TICKS, 0, 1)}
 * so rites closer to the abstract-fallback grace window bubble up.
 */
public final class PriestTaskSource implements TaskSource {

    private final IssuerRef issuer;
    private final Building  sacredBuilding;
    private final TownspersonMob priest;

    private PriestTaskSource(IssuerRef issuer, Building sacredBuilding, TownspersonMob priest) {
        this.issuer         = issuer;
        this.sacredBuilding = sacredBuilding;
        this.priest         = priest;
    }

    /**
     * Resolve the source for {@code npc}, or empty if not a PRIEST or has no
     * assigned TEMPLE/CHAPEL/SHRINE building.
     */
    public static Optional<PriestTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.PRIEST) return Optional.empty();
        Building b = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(PriestTaskSource::isReligiousBuilding)
                .orElse(null);
        if (b == null) return Optional.empty();
        return Optional.of(new PriestTaskSource(resolveIssuer(npc), b, npc));
    }

    /** Convenience entry point used by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** BUSINESS if the priest has one, else NPC. Mirrors FarmTaskSource. */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    //@Override
    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        // Resolve the priest's village via assigned village name.
        Village village = priest.getAssignedVillageName()
                .flatMap(n -> VillageSavedData.get(level).getVillageByName(n))
                .orElse(null);
        if (village == null) return;

        UUID villageId  = village.getId();
        UUID me         = priest.getUUID();
        BlockPos origin = sacredBuilding.getShape().getOrigin();
        GlobalPos originGpos = GlobalPos.of(level.dimension(),
                origin != null ? origin : BlockPos.ZERO);

        // Collect the rite ids of active RELIGIOUS_RITE gathering blessings so
        // we can exclude them (those are owned by PriestBehavior.tryStartFronting).
        Set<UUID> frontedRiteIds = collectFrontedRiteIds(level, villageId, now);

        RiteSavedData rdata = RiteSavedData.get(level);
        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        TaskFilter priestFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.PRIEST), Optional.empty(), false);

        Set<TaskId> live = new HashSet<>();

        for (RiteExecution rite : rdata.dueRites(now)) {
            if (!villageId.equals(rite.villageId())) continue;

            // Presider check: vacant or already me
            UUID presider = rite.presidingPriestId().orElse(null);
            if (presider != null && !presider.equals(me)) continue;

            // Capability gate (same as PriestBehavior.findClaimableRite)
            if (!RiteCapability.canOfficiate(priest, rite.type())) continue;

            // Disjointness: exclude rites linked to an active festival gathering
            if (frontedRiteIds.contains(rite.riteId())) continue;

            // Stable id keyed by rite UUID
            TaskId id = ProductionTaskIds.stable(issuer, PriestVerb.OFFICIATE_RITE + ":" + rite.riteId());

            // Location: guard BlockPos.ZERO (unset sentinel); fall back to building origin
            GlobalPos gpos;
            if (BlockPos.ZERO.equals(rite.location())) {
                gpos = originGpos;
            } else {
                gpos = GlobalPos.of(level.dimension(), rite.location());
            }

            // Priority: tier→base + lateness urgency
            TaskPriority base = tierToPriority(RiteTier.tierOf(rite.type()));
            float urgency = net.minecraft.util.Mth.clamp(
                    (float)(now - rite.scheduledTick()) / RiteExecutor.OFFICIATE_GRACE_TICKS,
                    0f, 1f);

            live.add(id);
            upsert(board, taskData, id,
                    new Objective.PerformService(PriestVerb.OFFICIATE_RITE,
                            Optional.of(rite.riteId().toString()),
                            Optional.of(gpos)),
                    new Priority(base, urgency),
                    priestFilter);
        }

        // Prune stale unclaimed officiate_rite tasks not in live set
        for (Task t : List.copyOf(board.all())) {
            if (!(t.objective() instanceof Objective.PerformService ps)) continue;
            if (!PriestVerb.isPriestVerb(ps.kind())) continue;
            if (live.contains(t.id())) continue;
            if (!t.assignment().claimants().isEmpty()) continue;
            board.remove(t.id());
            taskData.markChanged();
        }
    }

    /**
     * Collects the rite UUIDs that are blessing-extensions of active
     * RELIGIOUS_RITE gatherings in {@code villageId}. PriestBehavior's
     * festival-fronting path exclusively handles those; the task source
     * excludes them so the two claim systems are disjoint.
     *
     * <p>The linkage: a {@code VillageEvent} with category RELIGIOUS_RITE
     * stores its linked rite's UUID under {@link CeremonyBlessings#RITE_ID_KEY}
     * in its eventData. We collect those ids for active (isActiveAt) events.</p>
     */
    private static Set<UUID> collectFrontedRiteIds(ServerLevel level, UUID villageId, long now) {
        Set<UUID> ids = new HashSet<>();
        for (VillageEvent ve : VillageSavedData.get(level).getAllEvents()) {
            if (!villageId.equals(ve.getVillageId())) continue;
            if (ve.getType().category() != EventCategory.RELIGIOUS_RITE) continue;
            if (!ve.isActiveAt(now)) continue;
            String riteIdStr = ve.getEventData().get(CeremonyBlessings.RITE_ID_KEY);
            if (riteIdStr == null) continue;
            try { ids.add(UUID.fromString(riteIdStr)); }
            catch (IllegalArgumentException ignored) {}
        }
        return ids;
    }

    // ── Priority helpers ──────────────────────────────────────────────────────

    private static TaskPriority tierToPriority(RiteTier tier) {
        return switch (tier) {
            case GRAND    -> TaskPriority.HIGH;
            case STANDARD -> TaskPriority.NORMAL;
            case MINOR    -> TaskPriority.LOW;
        };
    }

    // ── Building predicate (mirrors RiteScheduler.isReligiousBuilding) ────────

    static boolean isReligiousBuilding(Building b) {
        return isReligiousBuilding(b.getType());
    }

    public static boolean isReligiousBuilding(BuildingType type) {
        return type == BuildingType.TEMPLE
                || type == BuildingType.CHAPEL
                || type == BuildingType.SHRINE;
    }

    // ── Board mutation helpers (mirrors FarmTaskSource) ───────────────────────

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority, TaskFilter filter) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            if (t.assignment().isTerminal()) {
                board.remove(id);
                // fall through to create fresh
            } else {
                t.setPriority(priority);
                data.markChanged();
                return;
            }
        }
        Task t = new Task(id, issuer, obj, priority, filter,
                new Assignment(), List.of(), 0L, null);
        data.addTask(issuer, t);
    }

}
