package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class KingdomRulerBehavior extends Behavior<TownspersonMob> {

    private enum Phase {
        IDLE,
        HOLDING_COURT,   // standing in throne room receiving reports
        REVIEWING,       // reviewing kingdom state
        SENDING_GUARDS   // dispatching guards to threatened villages
    }

    private static final int IDLE_COOLDOWN = 2400;
    private static final int COURT_DURATION = 6000;
    private static final int CHECK_INTERVAL = 600;
    private static final int INTERACT_RANGE_SQ = 9;

    private TownspersonMob entity;

    public KingdomRulerBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int phaseTimer = 0;
    private int checkTimer = 0;

    private Kingdom kingdom = null;
    private Building throneRoom = null; // CASTLE or largest TOWN_HALL

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }

        VillageSavedData data = VillageSavedData.get(level);
        kingdom = findKingdom(level, data);
        if (kingdom == null) return false;

        throneRoom = findThroneRoom(level, data);
        return throneRoom != null;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phaseTimer = 0;
        phase = Phase.HOLDING_COURT;
        entity.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.GOLDEN_SWORD));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

        @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phaseTimer++;
        checkTimer++;

        if (checkTimer >= CHECK_INTERVAL) {
            checkTimer = 0;
            runKingdomChecks(level);
        }

        switch (phase) {
            case HOLDING_COURT  -> holdCourt(level);
            case REVIEWING      -> review(level);
            case SENDING_GUARDS -> sendGuards(level);
            default -> {}
        }
    }

    private void holdCourt(ServerLevel level) {
        if (phaseTimer >= COURT_DURATION) {
            phase = Phase.REVIEWING;
            phaseTimer = 0;
            return;
        }

        // Stand in throne room
        BlockPos target = throneRoom.getShape().getOrigin().offset(
                throneRoom.getShape().getWidth() / 2, 1,
                throneRoom.getShape().getLength() / 2
        );

        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

            // Look at visiting NPCs
            if (phaseTimer % 80 == 0) {
                List<TownspersonMob> visitors = level.getEntitiesOfClass(
                        TownspersonMob.class,
                        entity.getBoundingBox().inflate(16),
                        mob -> mob != entity
                );
                if (!visitors.isEmpty()) {
                    TownspersonMob visitor = visitors.get(
                            entity.getRandom().nextInt(visitors.size()));
                    entity.getLookControl().setLookAt(
                            visitor, 30f, 30f);

                    // Play trade sound for audience
                    if (entity.getRandom().nextInt(3) == 0) {
                        level.playSound(null, entity.blockPosition(),
                                SoundEvents.VILLAGER_TRADE,
                                SoundSource.NEUTRAL, 0.4f,
                                0.8f + entity.getRandom().nextFloat() * 0.2f);
                    }
                }
            }
        }
    }

    private void review(ServerLevel level) {
        if (phaseTimer >= 1200) {
            phase = Phase.HOLDING_COURT;
            phaseTimer = 0;
        }
        // Stand still, look thoughtful
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private void sendGuards(ServerLevel level) {
        if (!(entity.level() instanceof ServerLevel sl)) return;
        VillageSavedData data = VillageSavedData.get(sl);

        // Find threatened village
        Village threatened = findThreatenedVillage(sl, data);
        if (threatened == null) {
            phase = Phase.HOLDING_COURT;
            phaseTimer = 0;
            return;
        }

        // Signal nearby guards to move to threatened village
        level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(64),
                mob -> mob.getProfession() == Profession.GUARD
                        && kingdom.containsVillage(
                        mob.getAssignedVillageName()
                                .flatMap(data::getVillageByName)
                                .map(Village::getId)
                                .orElse(null))
        ).stream().limit(3).forEach(guard -> {
            // Set guard's assigned village to threatened village
            guard.setAssignedVillageName(threatened.getName());
            org.slf4j.LoggerFactory.getLogger(KingdomRulerBehavior.class).debug(
                    "Kingdom ruler sending guard "
                    + guard.getNpcName() + " to defend "
                    + threatened.getName());
        });

        level.playSound(null, entity.blockPosition(),
                SoundEvents.RAID_HORN.value(),
                SoundSource.NEUTRAL, 1.0f, 1.0f);

        phase = Phase.HOLDING_COURT;
        phaseTimer = 0;
    }

    private void runKingdomChecks(ServerLevel level) {
        if (kingdom == null) return;
        VillageSavedData data = VillageSavedData.get(level);

        // Check for threatened villages
        if (findThreatenedVillage(level, data) != null) {
            phase = Phase.SENDING_GUARDS;
            phaseTimer = 0;
        }
    }

    private Village findThreatenedVillage(ServerLevel level,
                                          VillageSavedData data) {
        // A village is threatened if it has hostile mobs nearby
        for (UUID villageId : kingdom.getVillageIds()) {
            Village village = data.getVillageById(villageId).orElse(null);
            if (village == null) continue;

            boolean hasThreats = village.getBounds(data)
                    .map(bounds -> !level.getEntitiesOfClass(
                            net.minecraft.world.entity.monster.Monster.class,
                            bounds.inflate(16)
                    ).isEmpty())
                    .orElse(false);

            if (hasThreats) return village;
        }
        return null;
    }

    private Kingdom findKingdom(ServerLevel level, VillageSavedData data) {
        // Find kingdom where this NPC is the ruler
        return data.getAllKingdoms().stream()
                .filter(k -> k.getRulerEntityId()
                        .map(id -> id.equals(entity.getUUID()))
                        .orElse(false))
                .findFirst()
                .orElse(null);
    }

    private Building findThroneRoom(ServerLevel level,
                                    VillageSavedData data) {
        if (kingdom == null) return null;

        // Prefer CASTLE, fall back to largest TOWN_HALL
        for (UUID villageId : kingdom.getVillageIds()) {
            Village village = data.getVillageById(villageId).orElse(null);
            if (village == null) continue;

            // Check for castle first
            Optional<Building> castle = village.getBuildingIds().stream()
                    .map(data::getBuildingById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(b -> b.getType() == BuildingType.CASTLE)
                    .findFirst();
            if (castle.isPresent()) return castle.get();
        }

        // Fall back to town hall of largest village
        return kingdom.getVillageIds().stream()
                .map(data::getVillageById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(v -> v.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.TOWN_HALL))
                .max(Comparator.comparingInt(b ->
                        b.getShape().getWidth() * b.getShape().getLength()))
                .orElse(null);
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        phaseTimer = 0;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) { this.entity = entity;
        goIdle(); }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}