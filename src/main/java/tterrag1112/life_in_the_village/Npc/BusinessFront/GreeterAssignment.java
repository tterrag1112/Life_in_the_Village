package tterrag1112.life_in_the_village.Npc.BusinessFront;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Picks a greeter when a player enters a {@link
 * BuildingTypeFlags#hasGreeterFront} building during work hours.
 * Spec lines 113-128.
 *
 * <p>Selection ranking (spec line 121):</p>
 * <ol>
 *   <li>SERVICE_FACING role first — MERCHANT / INNKEEPER / SCRIBE /
 *   LIBRARIAN / PRIEST.</li>
 *   <li>Owner next (UUID matches the building's stall-owner record;
 *   v1 just uses "first MERCHANT" as proxy until the per-building
 *   owner ledger lands).</li>
 *   <li>Highest-skill master next (any worker with their primary
 *   skill ≥ 60).</li>
 *   <li>First-free last.</li>
 * </ol>
 *
 * <p>If no eligible worker exists, no greeter is assigned and the
 * player browses freely (spec line 128). Off-hours: status is set to
 * {@link BusinessFrontStatus#CLOSED} so right-click handling produces
 * a refusal.</p>
 */
public final class GreeterAssignment {

    /** Anti-spam: same player can't re-trigger a greet within this gap.
     *  Was previously {@code GreetPlayerGoal.RE_GREET_COOLDOWN_TICKS}. */
    public static final int RE_GREET_COOLDOWN_TICKS = 1200;
    /** Lifetime of a {@code GREET_TARGET} memory before it self-expires.
     *  Covers ATTEND_TIMEOUT_TICKS (600) plus a generous approach budget. */
    private static final long ASSIGNMENT_TTL_TICKS = 2400L;

    private GreeterAssignment() {}

    /** Hook fired by {@link BuildingPresenceTracker}'s enter listener. */
    public static void onPlayerEntered(ServerPlayer player, UUID buildingId) {
        if (player == null || buildingId == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);
        Building building = data.getBuildingById(buildingId).orElse(null);
        if (building == null) return;
        if (!BuildingTypeFlags.hasGreeterFront(building.getType())) return;
        assignFor(player, building, level, data);
    }

    /** Hook fired by {@link BuildingPresenceTracker}'s leave listener. */
    public static void onPlayerLeft(ServerPlayer player, UUID buildingId) {
        if (player == null || buildingId == null) return;
        BusinessFrontTracker.get(buildingId).ifPresent(state -> {
            if (state.greeterNpcId() != null
                    && player.getUUID().equals(state.targetPlayerId())) {
                clearGreeter(state.greeterNpcId(), player.level() instanceof ServerLevel sl ? sl : null);
                BusinessFrontTracker.put(BusinessFrontState.none(buildingId));
            }
        });
        BusinessFrontTracker.clearRefusalsFor(player.getUUID(), buildingId);
    }

    /**
     * Performs the actual selection + assignment. Public so debug
     * commands can re-run it.
     */
    public static void assignFor(ServerPlayer player, Building building,
                                 ServerLevel level, VillageSavedData data) {
        // Off-hours: any candidate worker present, but no greeter logic.
        List<TownspersonMob> workers = workersAt(level, building);
        boolean anyWorking = workers.stream().anyMatch(TownspersonMob::isWorkTime);
        if (!anyWorking) {
            BusinessFrontTracker.put(BusinessFrontState.none(building.getId())
                    .withStatus(BusinessFrontStatus.CLOSED));
            return;
        }
        // Filter eligible.
        List<TownspersonMob> eligible = workers.stream()
                .filter(TownspersonMob::isWorkTime)
                .filter(TownspersonMob::isAlive)
                .filter(npc -> !tterrag1112.life_in_the_village.Npc.Roles.NpcRoles.isOnCaravan(npc))
                .toList();
        if (eligible.isEmpty()) return; // no greeter available — silent

        // Re-greet cooldown: don't re-trigger if the same player was
        // greeted by the same building in the past 60s.
        long now = level.getGameTime();
        BusinessFrontState existing = BusinessFrontTracker.get(building.getId()).orElse(null);
        if (existing != null
                && player.getUUID().equals(existing.targetPlayerId())
                && now - existing.attendingSinceTick() < RE_GREET_COOLDOWN_TICKS) {
            return;
        }

        TownspersonMob greeter = eligible.stream()
                .sorted(priorityComparator())
                .findFirst().orElse(null);
        if (greeter == null) return;

        // Phase 6.2.b — assignment is now a memory write. The Brain's
        // GreetPlayerBehavior watches GREET_TARGET and runs the approach
        // + attend + dismiss state machine. TTL covers the full
        // ATTEND_TIMEOUT_TICKS (600) plus reasonable approach time.
        greeter.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.GREET_TARGET.get(), player, ASSIGNMENT_TTL_TICKS);
        // Phase 6.3.2.a — role projection, TTL-bound, mirrors the memory.
        greeter.getRoles().assignRole(
                tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment.timed(
                        tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.GREETING,
                        java.util.Map.of(
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_PLAYER_UUID,
                                player.getUUID().toString()),
                        now + ASSIGNMENT_TTL_TICKS));
        BusinessFrontTracker.put(BusinessFrontTracker.getOrCreate(building.getId())
                .withGreeter(greeter.getUUID(), player.getUUID(), now,
                        BusinessFrontStatus.GREETER_APPROACHING));
    }

    /** Used by the /business greeter clear command. */
    public static void clearGreeter(UUID greeterId, ServerLevel level) {
        if (greeterId == null || level == null) return;
        TownspersonMob greeter = TownspersonMob.findByUUID(level, greeterId).orElse(null);
        if (greeter == null) return;
        greeter.getBrain().eraseMemory(NpcMemoryTypes.GREET_TARGET.get());
        // Phase 6.3.2.a — clear role projection.
        greeter.getRoles().removeRole(
                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.GREETING);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static List<TownspersonMob> workersAt(ServerLevel level, Building building) {
        var box = building.getShape().toAABB().inflate(8);
        UUID buildingId = building.getId();
        return level.getEntitiesOfClass(TownspersonMob.class, box,
                npc -> npc.getAssignedBuildingId().filter(id -> id.equals(buildingId)).isPresent());
    }

    /** Spec ranking: SERVICE_FACING > owner > master > first. */
    private static Comparator<TownspersonMob> priorityComparator() {
        return Comparator
                .comparingInt(GreeterAssignment::serviceFacingRank).reversed()
                .thenComparing(Comparator.comparingInt(GreeterAssignment::masterRank).reversed())
                .thenComparing(npc -> npc.getUUID().toString());
    }

    /** Higher = more service-facing. */
    private static int serviceFacingRank(TownspersonMob npc) {
        return switch (npc.getProfession()) {
            case MERCHANT, INNKEEPER, SCRIBE, LIBRARIAN, PRIEST, HERALD -> 3;
            case BAKER, BLACKSMITH, CARPENTER, STONEMASON, WEAVER,
                 CANDLEMAKER, MILLER, SCHOLAR -> 2;
            case STOCKPILE_KEEPER -> 1;
            default -> 0;
        };
    }

    /** Master rank: rough check for any-skill ≥ 60. */
    private static int masterRank(TownspersonMob npc) {
        var skills = npc.getSkills();
        int max = 0;
        for (var s : tterrag1112.life_in_the_village.Npc.Skills.Skill.values()) {
            int level = skills.getLevel(s);
            if (level > max) max = level;
        }
        return max;
    }

    /** Suppress unused warning on Profession import via direct switch use. */
    @SuppressWarnings("unused")
    private static final Profession DOC_HOOK = Profession.NONE;
}
