package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryLending;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;

/**
 * Spec line 197. Curates the LIBRARY: stocks books, runs an
 * overdue-loan sweep, and assists any NPC standing inside the
 * library during open hours. Schooling sessions (spec line 202) are
 * stubbed — the dependent child-arc system lives in doc 15 and lands
 * in a later session.
 */
public class LibrarianBehavior extends Behavior<TownspersonMob> {

    /** Tick interval between overdue-loan sweeps. */
    public static final int OVERDUE_SWEEP_INTERVAL = 1200;
    /** XP per resolved overdue loan. */
    public static final int XP_PER_RECOVERED_LOAN = 1;
    /** XP per schooling stub tick (rate-limited via STUDENT_XP_INTERVAL). */
    public static final int XP_PER_SCHOOLING_TICK = 1;
    public static final int STUDENT_XP_INTERVAL = 24000;
    public static final double WALK_RADIUS_SQ = 36.0;

    private TownspersonMob entity;

    public LibrarianBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Building library;
    private BlockPos deskPos;
    private int sweepTimer;
    private int schoolingTimer;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        library = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.LIBRARY)
                .orElse(null);
        if (library == null) return false;
        deskPos = library.getShape().getOrigin();
        return deskPos != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return library != null && entity.isWorkTime();
    }

        @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        sweepTimer = 0;
        schoolingTimer = 0;
        entity.setCurrentActivity("Curating the library");
        if (deskPos != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5, 0.8));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        sweepTimer++;
        schoolingTimer++;

        // Overdue-loan sweep — auto-recovers books left out too long.
        if (sweepTimer >= OVERDUE_SWEEP_INTERVAL) {
            sweepTimer = 0;
            sweepOverdueLoans(level);
        }

        // Schooling stub: any nearby child gets a small LITERACY trickle.
        if (schoolingTimer >= STUDENT_XP_INTERVAL) {
            schoolingTimer = 0;
            grantSchoolingTrickle(level);
        }

        // Drift back to desk if we wandered too far.
        if (deskPos != null && entity.distanceToSqr(
                deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5) > WALK_RADIUS_SQ
                && !entity.getNavigation().isInProgress()) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5, 0.8));
        }
    }

    private void sweepOverdueLoans(ServerLevel level) {
        LibraryCatalogue cat = VillageSavedData.get(level)
                .getOrCreateLibraryCatalogue(library.getId());
        List<LibraryLending> overdue = cat.overdueLoans(level.getGameTime());
        if (overdue.isEmpty()) return;
        for (LibraryLending l : overdue) {
            // v1 auto-recovers on a sweep; doc 18's letter system will
            // upgrade this to send a "return your book" letter via the
            // local scribe before forcing the return.
            cat.returnBook(l.bookId(), l.borrowerId());
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(entity, Skill.LITERACY, XP_PER_RECOVERED_LOAN, level.getGameTime());
        }
        VillageSavedData.get(level).setDirty();
    }

    private void grantSchoolingTrickle(ServerLevel level) {
        // Spec lines 304-305: while LIBRARY+LIBRARIAN active, children
        // gain +1 LITERACY/month via schooling. v1 grants on the
        // librarian's sweep tick to any nearby child.
        var children = level.getEntitiesOfClass(TownspersonMob.class,
                entity.getBoundingBox().inflate(12),
                m -> m != entity && m.isChild());
        for (TownspersonMob child : children) {
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(child, Skill.LITERACY, XP_PER_SCHOOLING_TICK, level.getGameTime());
        }
        if (!children.isEmpty()) {
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(entity, Skill.SOCIAL, 1, level.getGameTime());
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        library = null;
        deskPos = null;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
