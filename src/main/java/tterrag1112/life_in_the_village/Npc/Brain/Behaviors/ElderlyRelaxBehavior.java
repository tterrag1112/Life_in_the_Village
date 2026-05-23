package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.2.c — migrated from {@code ElderlyRelaxGoal}. Universal IDLE
 * @ low priority, ELDERLY-only.
 *
 * <p>Inspection verdict: NOT redundant with the 6.1 ambient behaviors.
 * Preserves the elderly-specific bundle as a dedicated behavior:
 * - long sitting duration (2400t vs SitAtFurniture's 200-600t),
 * - slow walk speed (0.5 / 0.4),
 * - ambient {@code VILLAGER_AMBIENT} sound every ~200t while sitting,
 * - social gaze (look at random nearby NPC every 60t while sitting).
 *
 * <p>The richer 6.1 SitAtFurniture / IdleGesture / Wander behaviors
 * still apply to elderly NPCs in parallel — this behavior layers
 * additional elderly flavor, it doesn't replace them.
 */
public class ElderlyRelaxBehavior extends Behavior<TownspersonMob> {

    private enum Mode { WALKING_TO_SPOT, SITTING, WANDERING }

    private static final int SIT_DURATION = 2400;
    private static final int WANDER_RADIUS = 8;
    private static final double ARRIVAL_DIST_SQ = 9.0;
    private static final long COOLDOWN_TICKS = 600L;
    private static final float SIT_WALK_SPEED = 0.5f;
    private static final float WANDER_SPEED = 0.4f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = SIT_DURATION + 600;
    /** Phase 6.3.3.j.4.A — advise gesture cadence while the elder is
     *  sitting. Resolves {@code RetirementState.mentorTargetId} and,
     *  if the apprentice is in range, grants them XP in the elder's
     *  profession primary skill. */
    private static final int ADVISE_INTERVAL = 120;
    private static final double ADVISE_SCAN_RADIUS = 16.0;
    private static final int APPRENTICE_XP_PER_ADVISE = 1;

    private Mode mode = Mode.WANDERING;
    private int sitTimer;
    private BlockPos sitPos;

    public ElderlyRelaxBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.ELDERLY_RELAX_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!entity.isElderly()) return false;
        if (entity.shouldBeHome()) return false;
        return BrainNavGuard.canSteerNavigation(entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (entity.shouldBeHome()) return false;
        return mode != Mode.SITTING || sitTimer < SIT_DURATION;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        sitTimer = 0;
        sitPos = null;
        if (entity.getRandom().nextFloat() < 0.6f) {
            sitPos = findSitSpot(level, entity);
            if (sitPos != null) {
                mode = Mode.WALKING_TO_SPOT;
                entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(sitPos, SIT_WALK_SPEED, CLOSE_ENOUGH));
                return;
            }
        }
        mode = Mode.WANDERING;
        pickWanderTarget(level, entity);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        switch (mode) {
            case WALKING_TO_SPOT -> tickWalkToSpot(entity);
            case SITTING -> tickSit(level, entity);
            case WANDERING -> tickWander(level, entity);
        }
    }

    private void tickWalkToSpot(TownspersonMob entity) {
        if (sitPos == null) { mode = Mode.WANDERING; return; }
        double distSq = entity.distanceToSqr(sitPos.getX(), sitPos.getY(), sitPos.getZ());
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            sitTimer = 0;
            mode = Mode.SITTING;
        }
    }

    private void tickSit(ServerLevel level, TownspersonMob entity) {
        sitTimer++;
        // Social gaze — look at random nearby NPC every 60 ticks.
        if (sitTimer % 60 == 0) {
            List<TownspersonMob> nearby = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    entity.getBoundingBox().inflate(12),
                    mob -> mob != entity);
            if (!nearby.isEmpty()) {
                TownspersonMob watch = nearby.get(entity.getRandom().nextInt(nearby.size()));
                entity.getLookControl().setLookAt(watch, 30f, 30f);
            }
        }
        // Phase 6.3.3.j.4.A — advise gesture. If this elder has set
        // a mentorTargetId via a prior MentorBehavior session and the
        // apprentice is in range, fire a visible look-at + chirp +
        // grant the apprentice XP in the elder's profession primary
        // skill. Mentee multiplier composes inside SkillXp.award.
        if (sitTimer % ADVISE_INTERVAL == 0) {
            tryAdviseApprentice(level, entity);
        }
        // Occasional ambient vocalization.
        if (sitTimer % 200 == 0 && entity.getRandom().nextInt(3) == 0) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.3f,
                    0.7f + entity.getRandom().nextFloat() * 0.2f);
        }
        if (sitTimer >= SIT_DURATION) {
            mode = Mode.WANDERING;
            pickWanderTarget(level, entity);
        }
    }

    /**
     * Phase 6.3.3.j.4.A — resolve the elder's mentor target via
     * RetirementState, locate the apprentice within ADVISE_SCAN_RADIUS,
     * and grant a small XP bump. Routes through SkillXp.award so the
     * mentee multiplier (which itself is 1.5× when this elder is the
     * matched mentor) composes — the apprentice gets a real on-spec
     * boost while the elder is in the same room.
     */
    private void tryAdviseApprentice(ServerLevel level, TownspersonMob entity) {
        Optional<UUID> targetId = entity.getRetirementState().mentorTargetId();
        if (targetId.isEmpty()) return;
        UUID id = targetId.get();
        Skill skill = elderProfessionSkill(entity).orElse(null);
        if (skill == null) return;
        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(ADVISE_SCAN_RADIUS),
                mob -> mob != entity && id.equals(mob.getUUID()));
        if (nearby.isEmpty()) return;
        TownspersonMob apprentice = nearby.get(0);
        entity.getLookControl().setLookAt(apprentice, 30f, 30f);
        level.playSound(null, entity.blockPosition(),
                SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.35f,
                0.7f + entity.getRandom().nextFloat() * 0.2f);
        SkillXp.award(apprentice, skill,
                APPRENTICE_XP_PER_ADVISE, level.getGameTime());
    }

    private static Optional<Skill> elderProfessionSkill(TownspersonMob entity) {
        Profession p = entity.getProfession();
        if (p == null || p == Profession.NONE) return Optional.empty();
        return ProfessionSkills.of(p).map(ProfessionSkills::primary);
    }

    private void tickWander(ServerLevel level, TownspersonMob entity) {
        if (!entity.getNavigation().isInProgress()) {
            pickWanderTarget(level, entity);
        }
    }

    private void pickWanderTarget(ServerLevel level, TownspersonMob entity) {
        BlockPos home = entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(entity.blockPosition());
        BlockPos target = home.offset(
                entity.getRandom().nextInt(WANDER_RADIUS * 2) - WANDER_RADIUS, 0,
                entity.getRandom().nextInt(WANDER_RADIUS * 2) - WANDER_RADIUS);
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WANDER_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.ELDERLY_RELAX_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        mode = Mode.WANDERING;
        sitTimer = 0;
        sitPos = null;
    }

    private static BlockPos findSitSpot(ServerLevel level, TownspersonMob entity) {
        BlockPos home = entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(entity.blockPosition());
        int radius = 12;
        for (BlockPos pos : BlockPos.betweenClosed(
                home.offset(-radius, -2, -radius), home.offset(radius, 2, radius))) {
            var state = level.getBlockState(pos);
            if ((state.getBlock() instanceof SlabBlock
                    || state.getBlock() instanceof StairBlock
                    || state.is(BlockTags.WOODEN_SLABS)
                    || state.is(BlockTags.WOODEN_STAIRS))
                    && level.getBlockState(pos.above()).isAir()) {
                return pos.immutable().above();
            }
        }
        return null;
    }
}
