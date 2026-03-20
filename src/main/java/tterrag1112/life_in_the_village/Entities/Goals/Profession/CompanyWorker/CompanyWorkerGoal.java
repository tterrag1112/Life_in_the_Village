package tterrag1112.life_in_the_village.Entities.Goals.Profession.CompanyWorker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeListing;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CompanyWorkerGoal extends Goal {

    // -------------------------------------------------------------------------
    // Phases shared across all roles
    // -------------------------------------------------------------------------

    private enum Phase {
        IDLE,
        WALKING_TO_BUILDING,
        WORKING,           // PRODUCER: simulating production
        DEPOSITING,        // depositing finished goods
        SELLING,           // SELLER: posting trade listings
        COURIERING         // COURIER: walking to source, then destination
    }

    // -------------------------------------------------------------------------
    // Timing constants
    // -------------------------------------------------------------------------

    private static final int IDLE_COOLDOWN       = 600;  // 30s before retry
    private static final int INTERACT_RANGE_SQ   = 9;    // 3 block radius
    // How many ticks it takes to produce one item (scales with daily target)
    // At target=8 and a 12-hour workday (12000 ticks), 1500 ticks/item
    private static final int BASE_TICKS_PER_ITEM = 1500;
    private static final int MAX_CARRY           = 16;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final TownspersonMob entity;

    private Phase phase        = Phase.IDLE;
    private int   idleCooldown = 0;
    private int   workTimer    = 0;
    private int   producedCount = 0;

    // Resolved from company data each activation
    private Company               company;
    private Company.CompanyWorker worker;
    private Item                  targetItem;
    private Building              assignedBuilding;

    // COURIER second destination
    private Building courierDestination;

    public CompanyWorkerGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isCompanyWorker()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        // Respect company work schedule
        CompanySavedData cdata = CompanySavedData.get(level);
        company = cdata.getCompanyForWorker(entity.getUUID()).orElse(null);
        if (company == null || !company.isActive()) return false;
        if (!company.getWorkSchedule().isWorkTime(level.getGameTime())) return false;

        worker = company.getWorker(entity.getUUID()).orElse(null);
        if (worker == null) return false;

        // Must have an assigned task to do meaningful work
        if (worker.assignedItemId().isEmpty()
                && worker.role() == Company.WorkerRole.PRODUCER) return false;

        // Resolve target item
        targetItem = resolveItem(worker.assignedItemId());

        // Resolve assigned building
        VillageSavedData vdata = VillageSavedData.get(level);
        assignedBuilding = vdata.getBuildingById(worker.assignedBuildingId())
                .orElse(null);
        if (assignedBuilding == null) return false;

        return true;
    }

    @Override
    public void start() {
        workTimer    = 0;
        producedCount = 0;
        phase = Phase.WALKING_TO_BUILDING;

        entity.setCurrentActivity(activityLabel());
        entity.setItemInHand(InteractionHand.MAIN_HAND,
                worker.role() == Company.WorkerRole.PRODUCER
                        ? new ItemStack(Items.IRON_AXE)
                        : ItemStack.EMPTY);
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isCompanyWorker()) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (!company.getWorkSchedule().isWorkTime(level.getGameTime())) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Re-sync worker data in case owner changed the task
        CompanySavedData cdata = CompanySavedData.get(level);
        company = cdata.getCompanyForWorker(entity.getUUID()).orElse(null);
        if (company == null) { goIdle(); return; }
        worker = company.getWorker(entity.getUUID()).orElse(null);
        if (worker == null) { goIdle(); return; }

        switch (phase) {
            case WALKING_TO_BUILDING -> walkToBuilding();
            case WORKING             -> work(level, cdata);
            case DEPOSITING          -> deposit(level);
            case SELLING             -> sell(level, cdata);
            case COURIERING          -> courier(level);
        }

        entity.setCurrentActivity(activityLabel());
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
        phase = Phase.IDLE;
    }

    // -------------------------------------------------------------------------
    // Phase handlers
    // -------------------------------------------------------------------------

    private void walkToBuilding() {
        if (assignedBuilding == null) { goIdle(); return; }

        BlockPos target = assignedBuilding.getShape().getOrigin();
        double distSq   = entity.distanceToSqr(
                target.getX(), entity.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            phase = switch (worker.role()) {
                case PRODUCER -> Phase.WORKING;
                case SELLER   -> Phase.SELLING;
                case COURIER  -> Phase.COURIERING;
            };
            workTimer = 0;
        }
    }

    /**
     * PRODUCER — simulates crafting/production over time.
     * Every BASE_TICKS_PER_ITEM ticks, one unit of the target item
     * is added to the NPC's personal inventory.
     * When carry limit is reached, transitions to DEPOSITING.
     */
    private void work(ServerLevel level, CompanySavedData cdata) {
        if (targetItem == null || worker.assignedItemId().isEmpty()) {
            goIdle();
            return;
        }

        workTimer++;

        // Face the building
        entity.getLookControl().setLookAt(
                assignedBuilding.getShape().getOrigin().getX() + 0.5,
                entity.getEyeY(),
                assignedBuilding.getShape().getOrigin().getZ() + 0.5);

        // Play a work animation cue every second
        if (workTimer % 20 == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
        }

        int ticksNeeded = ticksPerItem();
        if (workTimer < ticksNeeded) return;

        workTimer = 0;
        producedCount++;

        // Add to personal inventory
        entity.getPersonalInventory().addItem(new ItemStack(targetItem, 1));

        // Check if we've hit the daily target or carry limit
        int carried = countInPersonal(targetItem);
        boolean hitTarget  = producedCount >= worker.dailyTargetCount();
        boolean hitCarry   = carried >= MAX_CARRY;

        if (hitTarget || hitCarry) {
            phase = Phase.DEPOSITING;
        }
    }

    /**
     * Deposit produced items into the assigned building's storage.
     */
    private void deposit(ServerLevel level) {
        BlockPos target = assignedBuilding.getShape().getOrigin();
        double distSq   = entity.distanceToSqr(
                target.getX(), entity.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();
        SimpleContainer inv = entity.getPersonalInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            boolean stored = BuildingStorageAccess.storeItem(
                    level, assignedBuilding, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        producedCount = 0;
        goIdle();
    }

    /**
     * SELLER — posts trade listings for items in the building storage
     * using the company's price overrides, or market price as fallback.
     */
    private void sell(ServerLevel level, CompanySavedData cdata) {
        VillageSavedData vdata = VillageSavedData.get(level);

        // Find market building in the same village
        Building market = findBuildingOfType(level, vdata,
                tterrag1112.life_in_the_village.Village.Buildings.BuildingType.MARKET);

        if (market == null) {
            // No market — deposit items in assigned building and idle
            goIdle();
            return;
        }

        // Walk to market if not there yet
        BlockPos marketPos = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                marketPos.getX(), entity.getY(), marketPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    marketPos.getX(), marketPos.getY(), marketPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // For each item in the assigned building, post a listing
        // using company price override or 10% markup on market price
        UUID villageId = vdata.getVillageByName(
                        entity.getAssignedVillageName().orElse(""))
                .map(v -> v.getId()).orElse(null);

        if (villageId != null) {
            for (var priceOverride : company.getAllPriceOverrides()) {
                Item item = resolveItem(priceOverride.itemId());
                if (item == null) continue;

                int stock = BuildingStorageAccess.countItem(
                        level, assignedBuilding, item);
                if (stock <= 0) continue;

                // Post a trade listing with the company price
                TradeListing listing = new TradeListing(
                        entity.getUUID(),
                        assignedBuilding.getId(),
                        item,
                        stock,
                        priceOverride.pricePerUnit(),
                        TradeListing.SellerType.COMPANY,
                        level.getGameTime());

                VillageEconomy.addListing(villageId, listing);
            }
        }

        goIdle();
    }

    /**
     * COURIER — picks up items from the assigned building and
     * delivers them to the next company building.
     */
    private void courier(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);

        // Determine destination — the next building in the company list
        // that isn't the assigned building
        if (courierDestination == null) {
            courierDestination = company.getBuildingIds().stream()
                    .filter(id -> !id.equals(worker.assignedBuildingId()))
                    .map(vdata::getBuildingById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst()
                    .orElse(null);
        }

        if (courierDestination == null) { goIdle(); return; }

        SimpleContainer inv = entity.getPersonalInventory();
        boolean carrying = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) { carrying = true; break; }
        }

        if (!carrying) {
            // Phase A — pick up from source
            BlockPos src = assignedBuilding.getShape().getOrigin();
            double distSq = entity.distanceToSqr(src.getX(), entity.getY(), src.getZ());

            if (distSq > INTERACT_RANGE_SQ) {
                entity.getNavigation().moveTo(
                        src.getX(), src.getY(), src.getZ(), 1.0);
                return;
            }

            // Grab up to MAX_CARRY of whatever is in source
            entity.getNavigation().stop();
            for (int slot = 0; slot < MAX_CARRY; slot++) {
                // Try to take one item of each type stored
                for (Building.StoredItemInfo stored :
                        BuildingStorageAccess.listItems(level, assignedBuilding)) {
                    int take = Math.min(stored.count(), MAX_CARRY);
                    boolean taken = BuildingStorageAccess.takeItem(
                            level, assignedBuilding, stored.item(), take);
                    if (taken) inv.addItem(new ItemStack(stored.item(), take));
                    break; // take one type per courier trip
                }
                break;
            }

            if (countTotalInPersonal() == 0) { goIdle(); return; }

            entity.setCurrentActivity("Delivering goods...");

        } else {
            // Phase B — deliver to destination
            BlockPos dest = courierDestination.getShape().getOrigin();
            double distSq = entity.distanceToSqr(dest.getX(), entity.getY(), dest.getZ());

            if (distSq > INTERACT_RANGE_SQ) {
                entity.getNavigation().moveTo(
                        dest.getX(), dest.getY(), dest.getZ(), 1.0);
                return;
            }

            entity.getNavigation().stop();

            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                boolean stored = BuildingStorageAccess.storeItem(
                        level, courierDestination, stack.copy());
                if (stored) inv.setItem(i, ItemStack.EMPTY);
            }

            entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            courierDestination = null;
            goIdle();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void goIdle() {
        phase        = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        workTimer    = 0;
        producedCount = 0;
        entity.clearCurrentActivity();
        entity.getNavigation().stop();
    }

    private int ticksPerItem() {
        // Scale: higher daily target = faster production per item
        // so total output per shift stays proportional
        int target = Math.max(1, worker.dailyTargetCount());
        return Math.max(100, BASE_TICKS_PER_ITEM / target);
    }

    private int countInPersonal(Item item) {
        SimpleContainer inv = entity.getPersonalInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private int countTotalInPersonal() {
        SimpleContainer inv = entity.getPersonalInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++)
            count += inv.getItem(i).getCount();
        return count;
    }

    private static Item resolveItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        try {
            return BuiltInRegistries.ITEM
                    .get(Identifier.parse(itemId))
                    .map(h -> h.value())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Building findBuildingOfType(ServerLevel level, VillageSavedData vdata,
                                        tterrag1112.life_in_the_village.Village.Buildings.BuildingType type) {
        return entity.getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(vdata::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == type)
                        .findFirst())
                .orElse(null);
    }

    private String activityLabel() {
        return switch (worker.role()) {
            case PRODUCER -> targetItem != null
                    ? "Producing " + targetItem.getName().getString()
                    : "Waiting for task";
            case SELLER   -> "Managing sales";
            case COURIER  -> "Delivering goods";
        };
    }
}