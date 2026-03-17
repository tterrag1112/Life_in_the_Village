package tterrag1112.life_in_the_village.Entities.Goals.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerQuestTracker;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guild.Quest;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public class AdventurerExploreGoal extends Goal {

    private static final double MOVE_SPEED       = 0.7;
    private static final int    RECHECK_INTERVAL = 100;
    private static final int    STEP_DISTANCE    = 48;

    private final TownspersonMob entity;
    private BlockPos currentWaypoint;
    private int recheckTimer = 0;

    public AdventurerExploreGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isAdventurerGroupMember()) return false;

        Quest quest = getActiveQuest();
        if (quest == null) return false;
        if (quest.getType() != Quest.QuestType.EXPLORE) return false;
        if (quest.getCurrentCount() >= quest.getTargetCount()) return false;

        return getGroup().map(g ->
                        g.getState() == AdventurerGroup.GroupState.EXPLORING
                                || g.getState() == AdventurerGroup.GroupState.TRAVELLING)
                .orElse(false);
    }

    @Override
    public boolean canContinueToUse() {
        Quest quest = getActiveQuest();
        if (quest == null) return false;
        return quest.getCurrentCount() < quest.getTargetCount();
    }

    @Override
    public void start() {
        updateWaypoint();
        entity.setCurrentActivity("Exploring...");
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        currentWaypoint = null;
        recheckTimer = 0;
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        recheckTimer++;

        // Check biome every 20 ticks
        if (recheckTimer % 20 == 0) {
            checkCurrentBiome(level);
        }

        // Recheck waypoint periodically or when reached
        if (recheckTimer >= RECHECK_INTERVAL
                || entity.getNavigation().isDone()) {
            recheckTimer = 0;
            updateWaypoint();
        }
    }

    // -------------------------------------------------------------------------
    // Biome checking
    // -------------------------------------------------------------------------

    private void checkCurrentBiome(ServerLevel level) {
        Quest quest = getActiveQuest();
        if (quest == null || quest.getTargetBiome() == null) return;

        var biomeHolder = level.getBiome(entity.blockPosition());
        String currentBiome = biomeHolder.unwrapKey()
                .map(k -> k.registry().toString())
                .orElse("");

        if (currentBiome.equals(quest.getTargetBiome())) {
            // Found the biome — complete the quest
            AdventurerSavedData data = AdventurerSavedData.get(level);
            getGroup().ifPresent(group ->
                    AdventurerQuestTracker.completeQuestSpawned(
                            group, data, level));

            entity.setCurrentActivity("Biome found! Returning...");
        } else {
            // Update label to show what they're looking for
            String biomeName = quest.getTargetBiome().contains(":")
                    ? quest.getTargetBiome().split(":")[1]
                    .replace("_", " ")
                    : quest.getTargetBiome();
            entity.setCurrentActivity("Searching for " + biomeName);
        }
    }

    // -------------------------------------------------------------------------
    // Waypoint navigation
    // -------------------------------------------------------------------------

    private void updateWaypoint() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        AdventurerGroup group = getGroup().orElse(null);
        if (group == null) return;

        Quest quest = getActiveQuest();
        if (quest == null) return;

        // Prefer the quest's specific biome position over the
        // group's general target position
        BlockPos target = quest.getQuestTargetPos() != null
                ? quest.getQuestTargetPos()
                : group.getTargetPos();

        BlockPos current = entity.blockPosition();

        if (current.closerThan(target, STEP_DISTANCE)) {
            currentWaypoint = target.offset(
                    level.getRandom().nextIntBetweenInclusive(-16, 16),
                    0,
                    level.getRandom().nextIntBetweenInclusive(-16, 16)
            );
        } else {
            double dx = target.getX() - current.getX();
            double dz = target.getZ() - current.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double scale = Math.min(STEP_DISTANCE, dist) / dist;

            currentWaypoint = new BlockPos(
                    (int)(current.getX() + dx * scale),
                    current.getY(),
                    (int)(current.getZ() + dz * scale)
            );
        }

        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                currentWaypoint.getX(),
                currentWaypoint.getZ());

        currentWaypoint = new BlockPos(
                currentWaypoint.getX(),
                surfaceY,
                currentWaypoint.getZ());

        entity.getNavigation().moveTo(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5,
                MOVE_SPEED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Quest getActiveQuest() {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        return getGroup()
                .map(AdventurerGroup::getActiveQuest)
                .orElse(null);
    }

    private Optional<AdventurerGroup> getGroup() {
        if (!(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return entity.getGroupId()
                .flatMap(id -> AdventurerSavedData.get(level).getGroup(id));
    }
}