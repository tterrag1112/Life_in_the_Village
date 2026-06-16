package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Roster.PastureRotation;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;
import tterrag1112.life_in_the_village.World.WeatherContext;

import java.util.UUID;

/**
 * G2 — executor for a {@link FarmVerb#ANIMAL_TEND} task.
 *
 * <p>Behavior-faithful port of {@code FarmerBehavior.tendAnimals()}:
 * same rotation skill gate ({@link #ROTATION_SKILL_THRESHOLD}=40, non-apprentice),
 * same storm-retreat to farmhouse anchor, same pen walk (PastureRotation),
 * same {@code onGrazed} call, same ANIMAL_HUSBANDRY XP path + FarmerSpecialtyMultiplier,
 * same passive disease recovery (ANIMAL_HUSBANDRY ≥ APPRENTICE_MILESTONE_INTERMEDIATE=40).</p>
 *
 * <p>The executor runs for {@link #MAX_TEND_CYCLES} tend cycles then returns
 * DONE so the dusk-yield in {@code DoTaskBehavior} still applies cleanly.
 * Each cycle is gated on {@link #TICKS_PER_ACTION}, matching the legacy cadence.</p>
 *
 * <p>Animal PRODUCTS (wool/honey/eggs/milk/beef/manure) are generated passively
 * by {@code BuildingRoster.tick} — this executor does NOT produce items.</p>
 */
public final class AnimalTendExecutor implements TaskExecutor {

    /** Mirrors FarmerBehavior.TICKS_PER_ACTION. */
    private static final int    TICKS_PER_ACTION        = 20;
    /** Mirrors FarmerBehavior.INTERACT_RANGE_SQ. */
    private static final double INTERACT_RANGE_SQ       = 4.0;
    /** Mirrors FarmerBehavior.ROTATION_SKILL_THRESHOLD / canRotate gate. */
    private static final int    ROTATION_SKILL_THRESHOLD = 40;
    /** After this many tend cycles the executor returns DONE (dusk-yield backstop). */
    private static final int    MAX_TEND_CYCLES         = 8;

    private Building farmhouse;
    private int      actionTimer;
    private int      tendCycles;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;
        UUID farmhouseId = ps.ref().map(UUID::fromString).orElse(null);
        if (farmhouseId == null) return Result.FAILED;

        // Lazily resolve farmhouse on first tick
        if (farmhouse == null) {
            farmhouse = VillageSavedData.get(level).getBuildingById(farmhouseId)
                    .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                    .orElse(null);
            if (farmhouse == null) return Result.FAILED;
        }

        // ── Anchor selection (mirrors FarmerBehavior.tendAnimals :1418-1434) ──
        // Storm → retreat to farmhouse origin.
        // Skilled non-apprentice with ANIMAL_PEN plots → walk to chosen pen.
        // Else → farmhouse origin (single-pen / unskilled).
        BlockPos anchor = farmhouse.getShape().getOrigin();
        boolean storm = WeatherContext.isStorm(level);
        boolean canRotate = !isApprenticeTier(level, npc, farmhouse)
                && npc.getSkills().getLevel(Skill.ANIMAL_HUSBANDRY) >= ROTATION_SKILL_THRESHOLD;
        FarmPlot activePen = null;
        if (!storm && canRotate) {
            activePen = PastureRotation.chooseActivePen(level, farmhouse.getId()).orElse(null);
            if (activePen != null) anchor = activePen.getOrigin();
        }

        // ── Walk to anchor (mirrors tendAnimals walk block :1431-1436) ────────
        double distSq = npc.distanceToSqr(anchor.getX(), anchor.getY(), anchor.getZ());
        if (distSq > INTERACT_RANGE_SQ * 4.0) {
            NpcBehaviorHelpers.walkTo(npc, anchor, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // ── Tick gate (mirrors FarmerBehavior.tendAnimals :1439-1441) ─────────
        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return Result.RUNNING;
        actionTimer = 0;

        // ── onGrazed (mirrors tendAnimals :1451-1454) ─────────────────────────
        // Skip when at the farmhouse anchor (no pen was actually grazed).
        if (activePen != null) {
            activePen.onGrazed(level.getGameTime());
            VillageSavedData.get(level).setDirty();
        }

        // ── ANIMAL_HUSBANDRY XP (mirrors awardAnimalXp + specialtyMultiplier) ─
        // pickAnimalXpTarget always returns ANIMAL_HUSBANDRY (beekeeping
        // sub-route retired with adjunct system; resurfaces in G2b).
        float xp = 1f * FarmerSpecialtyMultiplier.of(npc, Skill.ANIMAL_HUSBANDRY);
        SkillXp.award(npc, Skill.ANIMAL_HUSBANDRY, xp, level.getGameTime());

        // ── Passive disease recovery (mirrors tendAnimals :1463-1476) ─────────
        if (npc.getSkills().getLevel(Skill.ANIMAL_HUSBANDRY)
                >= SkillThresholds.APPRENTICE_MILESTONE_INTERMEDIATE) {
            RosterSavedData rdata = RosterSavedData.get(level);
            boolean changed = false;
            for (var roster : rdata.getRostersForBuilding(farmhouse.getId())) {
                if (roster.diseaseLevel() > 0) {
                    roster.adjustDiseaseLevel(-1);
                    changed = true;
                }
            }
            if (changed) rdata.markDirty();
        }

        // ── Cycle bound — yield after MAX_TEND_CYCLES so dusk-yield fires ─────
        tendCycles++;
        if (tendCycles >= MAX_TEND_CYCLES) return Result.DONE;
        return Result.RUNNING;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Mirrors the apprentice check used in FarmTaskSource and ReplantExecutor. */
    private static boolean isApprenticeTier(ServerLevel level,
                                             TownspersonMob npc, Building farmhouse) {
        var bdata = tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData.get(level);
        for (var business : bdata.getAllBusinesses()) {
            if (!business.getBuildingIds().contains(farmhouse.getId())) continue;
            return business.getWorkerTier(npc.getUUID())
                    .map(t -> t == tterrag1112.life_in_the_village.Guilds.Companies.EmploymentTier.APPRENTICE)
                    .orElse(false);
        }
        return false;
    }
}
