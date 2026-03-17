package tterrag1112.life_in_the_village.Entities.Goals.Profession.Leader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class VillageLeaderGoal extends Goal {

    private enum Phase {
        IDLE,
        INSPECTING,      // walking around village
        MANAGING,        // standing at town hall managing affairs
        COLLECTING_TAX,  // collecting coins from treasury chest
        MEETING          // meeting with another village leader
    }

    private static final int IDLE_COOLDOWN = 1200;
    private static final int INSPECT_DURATION = 3000;
    private static final int MANAGE_DURATION = 2400;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int CHECK_INTERVAL = 200;

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int phaseTimer = 0;
    private int checkTimer = 0;

    private Village village = null;
    private Building townHall = null;
    private List<BlockPos> inspectPoints = new ArrayList<>();
    private int inspectIndex = 0;

    public VillageLeaderGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);
        village = findVillage(level, data);
        if (village == null) return false;

        townHall = findTownHall(level, data);
        return townHall != null;
    }

    @Override
    public void start() {
        phaseTimer = 0;
        // Alternate between inspecting and managing
        if (entity.getRandom().nextBoolean()) {
            buildInspectRoute();
            phase = Phase.INSPECTING;
        } else {
            phase = Phase.MANAGING;
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        phaseTimer++;
        checkTimer++;

        // Run management checks periodically regardless of phase
        if (checkTimer >= CHECK_INTERVAL) {
            checkTimer = 0;
            runManagementChecks(level);
        }

        switch (phase) {
            case INSPECTING      -> inspect(level);
            case MANAGING        -> manage(level);
            case COLLECTING_TAX  -> collectTax(level);
            case MEETING         -> meeting(level);
            default -> {}
        }
    }

    // --- Phase handlers ---

    private void inspect(ServerLevel level) {
        if (phaseTimer >= INSPECT_DURATION) {
            phase = Phase.MANAGING;
            phaseTimer = 0;
            return;
        }

        if (inspectPoints.isEmpty()) { goIdle(); return; }

        BlockPos target = inspectPoints.get(
                inspectIndex % inspectPoints.size());
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq <= INTERACT_RANGE_SQ) {
            // Arrived at inspection point — look around
            entity.getNavigation().stop();
            if (phaseTimer % 40 == 0) {
                entity.getLookControl().setLookAt(
                        target.getX() + entity.getRandom().nextInt(6) - 3,
                        target.getY(),
                        target.getZ() + entity.getRandom().nextInt(6) - 3
                );
            }
            // Move to next point after a pause
            if (phaseTimer % 120 == 0) {
                inspectIndex++;
                if (inspectIndex < inspectPoints.size()) {
                    BlockPos next = inspectPoints.get(inspectIndex);
                    entity.getNavigation().moveTo(
                            next.getX(), next.getY(), next.getZ(), 0.8);
                }
            }
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 0.8);
        }
    }

    private void manage(ServerLevel level) {
        if (phaseTimer >= MANAGE_DURATION) {
            goIdle();
            return;
        }

        // Walk to town hall
        BlockPos target = townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 1,
                townHall.getShape().getLength() / 2
        );

        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            // Hold a book to show managing
            if (phaseTimer == 1) {
                entity.setItemInHand(
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(Items.BOOK));
            }
            // Look around periodically
            if (phaseTimer % 60 == 0) {
                entity.getLookControl().setLookAt(
                        target.getX() + entity.getRandom().nextInt(8) - 4,
                        target.getY(),
                        target.getZ() + entity.getRandom().nextInt(8) - 4
                );
            }
        }
    }

    private void collectTax(ServerLevel level) {
        if (townHall == null) { phase = Phase.MANAGING; return; }

        BlockPos target = townHall.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Collect coins from town hall chest into treasury
        long collected = 0;
        for (var container : BuildingStorageAccess.findInventories(
                level, townHall)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                int value = CurrencyValue.valuePerCoin(stack.getItem());
                if (value > 0) {
                    collected += (long) value * stack.getCount();
                    container.setItem(i, ItemStack.EMPTY);
                }
            }
        }

        if (collected > 0) {
            village.depositToTreasury(collected);
            VillageSavedData.get(level).setDirty();
            System.out.println("Village leader collected "
                    + CurrencyValue.of(collected)
                    + " into " + village.getName() + " treasury. "
                    + "Total: " + village.getTreasury());
        }

        phase = Phase.MANAGING;
    }

    private void meeting(ServerLevel level) {
        // Find another village leader to meet with
        List<TownspersonMob> leaders = level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(32),
                mob -> mob != entity
                        && mob.getProfession() ==
                        Profession.VILLAGE_LEADER
        );

        if (leaders.isEmpty() || phaseTimer > 600) {
            phase = Phase.MANAGING;
            entity.setItemInHand(
                    net.minecraft.world.InteractionHand.MAIN_HAND,
                    ItemStack.EMPTY);
            return;
        }

        TownspersonMob other = leaders.get(0);
        entity.getLookControl().setLookAt(other, 30f, 30f);

        if (phaseTimer % 100 == 0) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_TRADE,
                    SoundSource.NEUTRAL, 0.5f,
                    0.9f + entity.getRandom().nextFloat() * 0.2f);
        }
    }

    // --- Management checks ---

    private void runManagementChecks(ServerLevel level) {
        if (village == null) return;
        VillageSavedData data = VillageSavedData.get(level);

        // 1. Assign professions to unemployed NPCs
        assignProfessions(level, data);

        // 2. Check if treasury needs collecting
        if (hasTreasuryCoins(level)) {
            phase = Phase.COLLECTING_TAX;
            phaseTimer = 0;
        }

        // 3. Pay village upkeep to kingdom if applicable
        payKingdomUpkeep(level, data);

        // 4. Check for nearby village leaders to meet
        checkForMeetings(level);
    }

    private void assignProfessions(ServerLevel level,
                                   VillageSavedData data) {
        // Find unemployed NPCs
        List<TownspersonMob> unemployed = level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
                mob -> mob.getProfession() == Profession.NONE
                        && mob.isAdult()
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        );

        if (unemployed.isEmpty()) return;

        // Find buildings that need an NPC
        for (Building building : village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList()) {

            // Check if building already has an assigned NPC
            boolean hasNpc = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    building.getShape().toAABB().inflate(16),
                    mob -> building.getId().equals(
                            mob.getAssignedBuildingId().orElse(null))
            ).size() > 0;

            if (hasNpc) continue;
            if (unemployed.isEmpty()) break;

            // Assign first unemployed NPC
            TownspersonMob npc = unemployed.remove(0);
            Profession prof =
                    getProfessionForBuilding(building.getType());
            if (prof == Profession.NONE) continue;

            npc.setProfession(prof);
            npc.setAssignedBuildingId(building.getId());
            npc.setAssignedVillageName(village.getName());

            System.out.println("Village leader assigned "
                    + npc.getNpcName() + " as " + prof
                    + " to " + building.getName());
        }
    }

    private Profession getProfessionForBuilding(
            BuildingType type) {
        return switch (type) {
            case BLACKSMITH      -> Profession.BLACKSMITH;
            case FARMHOUSE       -> Profession.FARMER;
            case MINE            -> Profession.MINER;
            case MARKET          -> Profession.MERCHANT;
            case INN             -> Profession.INNKEEPER;
            case STOCKPILE       -> Profession.STOCKPILE_KEEPER;
            case GUARD_TOWER     -> Profession.GUARD;
            default              -> Profession.NONE;
        };
    }

    private boolean hasTreasuryCoins(ServerLevel level) {
        if (townHall == null) return false;
        for (var container : BuildingStorageAccess
                .findInventories(level, townHall)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (CurrencyValue.valuePerCoin(
                        container.getItem(i).getItem()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void payKingdomUpkeep(ServerLevel level,
                                  VillageSavedData data) {
        data.getKingdomForVillage(village.getId()).ifPresent(kingdom -> {
            long upkeep = kingdom.getFlatUpkeepBronze();
            if (village.withdrawFromTreasury(upkeep)) {
                kingdom.depositToTreasury(upkeep);
                data.setDirty();
            }
        });
    }

    private void checkForMeetings(ServerLevel level) {
        if (phase != Phase.MANAGING) return;
        if (entity.getRandom().nextInt(20) != 0) return;

        List<TownspersonMob> nearbyLeaders = level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(24),
                mob -> mob != entity
                        && mob.getProfession() ==
                        Profession.VILLAGE_LEADER
        );

        if (!nearbyLeaders.isEmpty()) {
            phase = Phase.MEETING;
            phaseTimer = 0;
        }
    }

    // --- Helpers ---

    private void buildInspectRoute() {
        if (village == null || townHall == null) return;
        inspectPoints.clear();
        inspectIndex = 0;

        // Add town hall center
        inspectPoints.add(townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 1,
                townHall.getShape().getLength() / 2
        ));

        // Add each building origin
        if (!(entity.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);

        village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(b -> inspectPoints.add(b.getShape().getOrigin()));

        // Shuffle for variety
        Collections.shuffle(inspectPoints,
                new Random(entity.getRandom().nextLong()));
    }

    private Village findVillage(ServerLevel level, VillageSavedData data) {
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
    }

    private Building findTownHall(ServerLevel level, VillageSavedData data) {
        if (village == null) return null;
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                .findFirst()
                .orElse(null);
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        phaseTimer = 0;
        entity.getNavigation().stop();
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY);
    }

    @Override
    public void stop() { goIdle(); }
}