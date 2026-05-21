
package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Client.FarmingVisualEffects;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Head farmer goal — manages crop operations on assigned plots.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Harvest mature crops from all assigned plots</li>
 *   <li>Deposit harvested goods in the farmhouse</li>
 *   <li>Replant farmland</li>
 *   <li>Purchase seeds from the market when short</li>
 *   <li>Coordinate farmhand roles via {@link FarmRoleAssigner}</li>
 * </ul>
 *
 * <h3>Selling</h3>
 * Selling surplus crops is NOT handled here — {@code SellToMarketGoal}
 * (registered alongside this goal in {@code ProfessionGoalFactory})
 * owns that flow. It uses dynamic pricing, pays the merchant correctly,
 * and hooks {@code WorkplaceAssignmentManager.onWorkplaceSale()}.
 */
public class FarmerBehavior extends Behavior<TownspersonMob> {

    // =========================================================================
    // Constants
    // =========================================================================

    private static final int    TICKS_PER_ACTION   = 40;
    private static final int    IDLE_COOLDOWN      = 200;
    private static final double INTERACT_RANGE_SQ  = 4.0;

    // =========================================================================
    // State
    // =========================================================================

    private TownspersonMob entity;

    public FarmerBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }

    private Phase phase;
    private int   actionTimer;
    private int   idleCooldown;
    /** Phase 6.3.3.e.3 — tick of the most recent FarmRoleAssigner run.
     *  The assigner was orphan (no caller) pre-6.3.3.e; this gates a
     *  daily reassignment cadence so role distribution stays current
     *  as workers come and go. */
    private long  lastRoleAssignTick = Long.MIN_VALUE;
    private static final long ROLE_ASSIGN_INTERVAL = 24000L;

    private Building            farmhouse;
    private List<FarmPlot>      assignedPlots;
    // Phase 6.3.3.e.0 — pre-flight fix: these were declared final but
    // never initialized in the no-arg constructor (compile blocker
    // surfaced by the 6.3.3.0.A inspection). Initialize inline.
    private final List<BlockPos> toHarvest = new java.util.ArrayList<>();
    private final List<BlockPos> toReplant = new java.util.ArrayList<>();
    private final Map<Item, Integer> harvestedThisCycle = new java.util.LinkedHashMap<>();
    /** Phase 6.3.3.h.2 — per-cycle dedup so a plot's cropType is only
     *  rotated once even though replant() runs per BlockPos. */
    private final java.util.Set<UUID> rotatedThisCycle = new java.util.HashSet<>();
    /** Phase 6.3.3.h.2 — CROP_FARMING skill threshold at which a
     *  non-APPRENTICE farmer starts proactively rotating. Matches the
     *  "intermediate apprentice" milestone (level 40). */
    private static final int ROTATION_SKILL_THRESHOLD = 40;

    private enum Phase {
        IDLE,
        ANALYZING,
        HARVESTING,
        WALKING_TO_FARMHOUSE,
        DEPOSITING,
        REPLANTING,
        BUYING_SEEDS,
        /** Phase 6.3.3.e.1 — writes CARGO_DESTINATION + WORK_PHASE=SELL and
         *  hands off to the universal SellToMarketBehavior. */
        AWAITING_SELL,
        /** Phase 6.3.3.g — animal-tending mode for ANIMAL_SPECIALIST /
         *  ANIMAL_TENDER / FERTILIZER roles. Roster cycle-tick handles
         *  production output; this phase keeps the NPC at the animal
         *  facility and awards ANIMAL_HUSBANDRY XP periodically. */
        TENDING_ANIMALS
    }

    

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!entity.isWorkTime()) return false;
        return entity.getAssignedBuildingId().isPresent();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return phase != Phase.IDLE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.ANALYZING;
        actionTimer = 0;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        switch (phase) {
            case IDLE               -> { /* no-op */ }
            case ANALYZING          -> analyze(level);
            case HARVESTING         -> harvest(level);
            case WALKING_TO_FARMHOUSE -> walkToFarmhouse();
            case DEPOSITING         -> deposit(level);
            case REPLANTING         -> replant(level);
            case BUYING_SEEDS       -> buySeeds(level);
            case AWAITING_SELL      -> { /* SellToMarketBehavior owns the loop */ }
            case TENDING_ANIMALS    -> tendAnimals(level);
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) { this.entity = entity;
        goIdle(); }

    // =========================================================================
    // Phase: ANALYZING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Planning farm work...");

        VillageSavedData data = VillageSavedData.get(level);

        // FIX #10: resolve farmhouse BEFORE the null check
        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) { goIdle(); return; }

        // Phase 6.3.3.e.3 — periodic FarmRoleAssigner run (every in-game
        // day). The assigner was orphan pre-6.3.3.e; this is the single
        // call site that brings role distribution to life.
        long now = level.getGameTime();
        if (now - lastRoleAssignTick >= ROLE_ASSIGN_INTERVAL) {
            tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer
                    .FarmRoleAssigner.assignRoles(level, farmhouse);
            lastRoleAssignTick = now;
        }

        // Gather crop plots assigned to this farmhouse.
        // Phase 6.3.3.h.1 — tick fallow recovery for every plot before
        // filtering. Fallow plots are excluded from the planting/harvest
        // candidate set; recovery is per-plot so it advances even when
        // the plot isn't actively worked.
        List<FarmPlot> allPlots = data.getFarmPlotsForFarmhouse(farmhouse.getId()).stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .peek(p -> p.tickFallowRecovery(level.getGameTime()))
                .filter(p -> !p.isFallow())
                .collect(Collectors.toList());

        if (allPlots.isEmpty()) { goIdle(); return; }

        // Role-based task filtering
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);

        // Phase 6.3.3.g.3 — animal-role workers bypass crop work entirely.
        // The roster cycle-tick handles production output passively; this
        // branch keeps the NPC at the animal facility for animation +
        // periodic XP awards.
        if (role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || role == FarmRole.FERTILIZER) {
            phase = Phase.TENDING_ANIMALS;
            entity.setCurrentActivity("Tending animals");
            return;
        }

        // Phase 6.3.3.f.1 — tier-aware constraints. APPRENTICE workers
        // operate on their assigned plot only (single-plot mode, matches
        // pre-consolidation FarmhandBehavior semantics), skip BUYING_SEEDS
        // (they don't manage finances), and skip MARKET_SELLER bias
        // (they don't sell). JOURNEYMAN / MASTER use the full work loop.
        boolean isApprentice = isApprenticeTier();
        if (isApprentice) {
            java.util.UUID assignedPlotId = entity.getAssignedPlotId().orElse(null);
            if (assignedPlotId == null) { goIdle(); return; }
            assignedPlots = allPlots.stream()
                    .filter(p -> p.getId().equals(assignedPlotId))
                    .collect(Collectors.toList());
            if (assignedPlots.isEmpty()) { goIdle(); return; }
        } else {
            assignedPlots = allPlots;
        }

        // Phase 6.3.3.e.3 — sell pipeline: if farmhouse storage exceeds
        // stockQuotas for any crop AND a market exists, hand off to
        // SellToMarketBehavior. MARKET_SELLER role biases strongly toward
        // this path (checked first); other roles try sell only when no
        // farming work is available. APPRENTICE workers don't sell.
        if (!isApprentice && role == FarmRole.MARKET_SELLER) {
            if (tryHandOffToSell(level)) return;
        }

        toHarvest.clear();
        toReplant.clear();
        harvestedThisCycle.clear();

        for (FarmPlot plot : assignedPlots) {
            scanPlotForTasks(level, plot);
        }

        // Decide next phase
        if (!toHarvest.isEmpty() && canHarvest(role)) {
            phase = Phase.HARVESTING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
            return;
        }

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        if (!toReplant.isEmpty() && canPlant(role)) {
            phase = Phase.REPLANTING;
            return;
        }

        // Phase 6.3.3.f.1 — APPRENTICE workers skip BUYING_SEEDS (no
        // financial responsibility) and the sell fall-through (they
        // don't sell). Their loop ends here with goIdle when there's
        // nothing harvestable / replantable on their plot.
        if (!isApprentice) {
            if (needsSeeds(level) && canPlant(role)) {
                phase = Phase.BUYING_SEEDS;
                return;
            }
            // Phase 6.3.3.e.3 — non-MARKET_SELLER fall-through: still try
            // sell if surplus exists and no farming work is available.
            if (tryHandOffToSell(level)) return;
        }

        goIdle();
    }

    /**
     * Phase 6.3.3.f.1 — true if this NPC is an APPRENTICE-tier worker
     * at the farm Business hosted on the farmhouse. Looks up the
     * Business via BusinessSavedData, finds the worker entry by NPC
     * UUID, reads the tier. Returns false when no Business / no entry
     * (default tier = JOURNEYMAN-equivalent for non-business farmers,
     * which gets full work loop).
     */
    private boolean isApprenticeTier() {
        if (farmhouse == null) return false;
        var bdata = tterrag1112.life_in_the_village.Guilds.Companies
                .BusinessSavedData.get((ServerLevel) entity.level());
        for (var business : bdata.getAllBusinesses()) {
            if (!business.getBuildingIds().contains(farmhouse.getId())) continue;
            return business.getWorkerTier(entity.getUUID())
                    .map(t -> t == tterrag1112.life_in_the_village.Guilds.Companies
                            .EmploymentTier.APPRENTICE)
                    .orElse(false);
        }
        return false;
    }

    private void scanPlotForTasks(ServerLevel level, FarmPlot plot) {
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);

            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                toHarvest.add(cropPos);
            } else if (state.isAir()
                    && level.getBlockState(farmland).getBlock() instanceof FarmBlock) {
                toReplant.add(cropPos);
            }
        }
    }

    private boolean canHarvest(FarmRole role) {
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.HARVESTER;
    }

    private boolean canPlant(FarmRole role) {
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.PLANTER;
    }

    // =========================================================================
    // Phase: HARVESTING
    // =========================================================================

    private void harvest(ServerLevel level) {
        entity.setCurrentActivity("Harvesting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toHarvest.isEmpty()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        BlockPos cropPos = toHarvest.get(0);
        double distSq = entity.distanceToSqr(
                cropPos.getX(), cropPos.getY(), cropPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            toHarvest.remove(0);
            return;
        }
        if (!crop.isMaxAge(state)) {
            toHarvest.remove(0);
            return;
        }

        FarmingVisualEffects.showHarvestEffect(level, cropPos, state);
        FarmingVisualEffects.playHarvestSound(level, cropPos);

        List<ItemStack> drops = Block.getDrops(state, level, cropPos, null);
        float seasonMult = SeasonTracker.getYieldMultiplier(level);
        // Phase 6.3.3.h.1 — soil quality factor composited into the
        // existing seasonMult formula. soilQuality ranges 0.1–1.5.
        FarmPlot harvestedPlot = findPlotContaining(level, cropPos);
        float soilMult = harvestedPlot != null ? harvestedPlot.getSoilQuality() : 1.0f;
        float yieldMult = seasonMult * soilMult;

        for (ItemStack drop : drops) {
            int scaledCount = 0;
            for (int i = 0; i < drop.getCount(); i++) {
                if (entity.getRandom().nextFloat() < yieldMult) scaledCount++;
            }
            scaledCount = Math.max(1, scaledCount);

            ItemStack scaled = drop.copy();
            scaled.setCount(scaledCount);
            entity.getPersonalInventory().addItem(scaled);
            harvestedThisCycle.merge(scaled.getItem(), scaledCount, Integer::sum);
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos, SoundEvents.CROP_BREAK,
                SoundSource.BLOCKS, 1.0f, 1.0f);
        // Phase 6.3.3.e.2 — FARMING XP for a successful harvest. The
        // mentee mentorship multiplier (6.3.2.b) flows through SkillXp.
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING,
                1, level.getGameTime());

        toHarvest.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
        }
    }

    // =========================================================================
    // Phase: WALKING TO FARMHOUSE
    // =========================================================================

    private void walkToFarmhouse() {
        if (farmhouse == null) { goIdle(); return; }

        // Carry-pose: arms-forward holding the freshest crop while
        // ferrying the harvest back to the farmhouse.
        ItemStack carried = firstHarvestStack();
        if (!carried.isEmpty()) {
            entity.getBrain().setMemory(
                    NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), carried.copy());
        }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.DEPOSITING;
        }
    }

    private ItemStack firstHarvestStack() {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(Items.IRON_HOE) || s.is(Items.DIAMOND_HOE)) continue;
            return s;
        }
        return ItemStack.EMPTY;
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        if (farmhouse == null) { goIdle(); return; }

        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Don't deposit tools
            if (stack.is(Items.IRON_HOE) || stack.is(Items.DIAMOND_HOE)) continue;

            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }

        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        // Phase 6.3.3.e.2 — production-cycle XP bonus on deposit completion
        // (the "you actually closed the loop" reward).
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING,
                2, level.getGameTime());
        phase = toHarvest.isEmpty() ? Phase.ANALYZING : Phase.HARVESTING;
    }

    // =========================================================================
    // Phase: REPLANTING
    // =========================================================================

    private void replant(ServerLevel level) {
        entity.setCurrentActivity("Replanting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toReplant.isEmpty()) {
            phase = Phase.ANALYZING;
            return;
        }

        BlockPos targetPos = toReplant.get(0);
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        if (!(level.getBlockState(targetPos.below()).getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        FarmPlot plot = findPlotContaining(level, targetPos);
        if (plot == null) { toReplant.remove(0); return; }

        // Phase 6.3.3.h.2 — rotation decision: once per plot per
        // analyze-cycle. Non-APPRENTICE workers with FARMING ≥
        // ROTATION_SKILL_THRESHOLD pick a different family from
        // recent history; APPRENTICEs plant what the master assigned.
        if (rotatedThisCycle.add(plot.getId()) && shouldRotateCrops()) {
            FarmPlot.CropType rotated = FarmPlot.CropFamily.suggestRotation(
                    plot.getCropType(), plot.getCropHistory(), entity.getRandom());
            if (rotated != plot.getCropType()) {
                plot.setCropType(rotated);
                VillageSavedData.get(level).setDirty();
            }
        }

        Block cropBlock = plot.getCropType().resolveCropBlock();
        Item  seedItem  = plot.getCropType().resolveSeedItem();
        if (cropBlock == null) { toReplant.remove(0); return; }

        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, seedItem, 1);
        if (taken) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos, SoundEvents.CROP_PLANTED,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
            // Phase 6.3.3.e.2 — FARMING XP for a successful replant.
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                    entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING,
                    1, level.getGameTime());
            // Phase 6.3.3.h.1 — record the planting; soilQuality
            // decrements (with same-family penalty per CropFamily.of),
            // cropHistory trims to MAX_HISTORY, plot may auto-enter
            // fallow if quality fell past SOIL_FALLOW_ENTER.
            plot.onPlanted(plot.getCropType(), level.getGameTime());
            VillageSavedData.get(level).setDirty();
        }

        toReplant.remove(0);
    }

    // =========================================================================
    // Phase: BUYING SEEDS
    // =========================================================================

    private void buySeeds(ServerLevel level) {
        entity.setCurrentActivity("Buying seeds");

        Building market = ProductionHelpers.findMarketInVillage(entity, level).orElse(null);

        if (market == null) { goIdle(); return; }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        VillageSavedData data = VillageSavedData.get(level);
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name))
                .map(v -> v.getId())
                .orElse(null);

        // Find merchant to pay (if any)
        TownspersonMob merchant = level.getEntitiesOfClass(
                TownspersonMob.class,
                market.getShape().toAABB().inflate(16),
                mob -> mob.getProfession() == Profession.MERCHANT
        ).stream().findFirst().orElse(null);

        // FIX #3: use entity.payWithBuilding and dynamic pricing, no broken .get() calls
        for (FarmPlot plot : assignedPlots) {
            int plotSize = plot.getFarmlandBlocks(level).size();
            if (plotSize == 0) continue;

            Item seedItem = plot.getCropType().resolveSeedItem();
            int currentSeeds = countSeedsInFarmhouse(level, seedItem);
            int needed = Math.max(0, plotSize - currentSeeds);
            if (needed <= 0) continue;

            long pricePerSeed = EconomicBalance.SEED_PRICES.getOrDefault(seedItem, 1L);
            CurrencyValue cost = CurrencyValue.of((long) needed * pricePerSeed);
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId == null) continue;

            // Business purchase: farmhouse treasury pays, not personal wallet
            BuildingEconomy economy = data.getOrCreateBuildingEconomy(buildingId);
            if (!economy.canAfford(cost.toBronze())) continue;

            if (villageId != null) {
                var seller = VillageEconomy.findCheapestSeller(
                        level, villageId, seedItem,
                        entity.getX(), entity.getZ(),
                        level.getGameTime()).orElse(null);

                if (seller != null) {
                    long actualCost = seller.listing().getPricePerItem() * needed;
                    if (economy.canAfford(actualCost)) {
                        boolean taken = BuildingStorageAccess.takeItem(
                                level,
                                data.getBuildingById(
                                                seller.listing().getSellerBuildingId())
                                        .orElse(null),
                                seedItem, needed);
                        if (taken) {
                            // businessPay: building treasury → seller wallet + visual
                            NpcEconomy.businessPay(
                                    buildingId, seller.seller(),
                                    CurrencyValue.of(actualCost), level, data);
                            BuildingStorageAccess.storeItem(level, farmhouse,
                                    new ItemStack(seedItem, needed));
                        }
                    }
                    continue;
                }
            }

            // Fallback: spend from building treasury silently (no seller NPC found)
            economy.withdraw(cost.toBronze());
            BuildingStorageAccess.storeItem(level, farmhouse,
                    new ItemStack(seedItem, needed));
            data.setDirty();
            // Phase 6.3.3.e.2 — small COMMERCE bonus for the buy interaction.
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                    entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.COMMERCE,
                    1, level.getGameTime());
        }

        phase = Phase.ANALYZING;
    }

    // =========================================================================
    // Phase: TENDING ANIMALS
    // =========================================================================

    /**
     * Phase 6.3.3.g.3 — animal-tending scaffold for ANIMAL_SPECIALIST /
     * ANIMAL_TENDER / FERTILIZER roles. The actual production output is
     * driven passively by the {@link tterrag1112.life_in_the_village.Village.Roster.BuildingRoster}
     * cycle tick; this method just:
     * <ul>
     *   <li>Keeps the worker near the farmhouse anchor (animation /
     *       grounding — animal facilities resolve via adjunct plots in
     *       later content phases)</li>
     *   <li>Awards {@link tterrag1112.life_in_the_village.Npc.Skills.Skill#ANIMAL_HUSBANDRY}
     *       XP on a {@link #TICKS_PER_ACTION} cadence (cascades 25% →
     *       FARMING via the hierarchical Skill tree)</li>
     *   <li>Returns to ANALYZING once the loop expires so role / phase
     *       routing can re-evaluate</li>
     * </ul>
     *
     * <p>BEEKEEPING-specific XP routing and per-facility navigation are
     * deferred — they need adjunct-plot ActivityTag queries that land in
     * later phases of this content pass.
     */
    private void tendAnimals(ServerLevel level) {
        if (farmhouse == null) { goIdle(); return; }

        entity.setCurrentActivity("Tending animals");

        BlockPos anchor = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                anchor.getX(), anchor.getY(), anchor.getZ());
        if (distSq > INTERACT_RANGE_SQ * 4.0) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    anchor.getX(), anchor.getY(), anchor.getZ(), 1.0));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity,
                tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY,
                1, level.getGameTime());

        phase = Phase.ANALYZING;
    }

    // =========================================================================
    // Helpers
    // =========================================================================



    private FarmPlot findPlotContaining(ServerLevel level, BlockPos pos) {
        for (FarmPlot plot : assignedPlots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (farmland.above().equals(pos)) return plot;
            }
        }
        return null;
    }



    private boolean needsSeeds(ServerLevel level) {
        if (farmhouse == null) return false;
        for (FarmPlot plot : assignedPlots) {
            Item seedItem = plot.getCropType().resolveSeedItem();
            int available = countSeedsInFarmhouse(level, seedItem);
            int needed = plot.getFarmlandBlocks(level).size();
            if (available < needed) {
                long pricePerSeed = VillageEconomy.getBasePrice(seedItem);
                CurrencyValue cost = CurrencyValue.of(
                        (long)(needed - available) * pricePerSeed);
                return entity.canAffordWithBuilding(cost, level);
            }
        }
        return false;
    }

    private int countSeedsInFarmhouse(ServerLevel level, Item seedItem) {
        int total = 0;
        for (var container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.is(seedItem)) total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isPersonalInventoryNearlyFull() {
        SimpleContainer inv = entity.getPersonalInventory();
        int emptySlots = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 3;
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        toHarvest.clear();
        toReplant.clear();
        rotatedThisCycle.clear();
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    /**
     * Phase 6.3.3.h.2 — true when this farmer is allowed and skilled
     * enough to actively rotate crops. APPRENTICEs follow the master's
     * cropType assignment; JOURNEYMAN/MASTER with FARMING ≥
     * {@link #ROTATION_SKILL_THRESHOLD} rotate proactively.
     */
    private boolean shouldRotateCrops() {
        if (isApprenticeTier()) return false;
        return entity.getSkills().getLevel(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING)
                >= ROTATION_SKILL_THRESHOLD;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

    // -------------------------------------------------------------------------
    // Phase 6.3.3.e — sell pipeline integration
    // -------------------------------------------------------------------------

    /**
     * Phase 6.3.3.e.3 — per-output stockpile quotas for the farmhouse.
     * When stock exceeds quota for an item, the excess is sellable.
     * Mirrors {@code AbstractProductionBehavior.stockQuotas} pattern;
     * FARMER doesn't inherit AbstractProductionBehavior but the
     * SellToMarketBehavior pipeline reads this through composition.
     */
    private Map<Item, Integer> stockQuotas() {
        return Map.of(
                Items.WHEAT,    32,   // staple — high quota
                Items.CARROT,   16,
                Items.POTATO,   16,
                Items.BEETROOT, 16);
    }

    /** Phase 6.3.3.e.3 — items the FARMER may sell. Mirrors workshop
     *  {@code sellableOutputs} surface. */
    private List<Item> sellableOutputs() {
        return java.util.List.of(Items.WHEAT, Items.CARROT, Items.POTATO,
                Items.BEETROOT, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS);
    }

    /** Phase 6.3.3.e.3 — surplus for sale: stock at farmhouse minus
     *  per-item quota. Empty map = nothing to sell. */
    private Map<Item, Integer> computeSurplusToSell(ServerLevel level) {
        if (farmhouse == null) return Map.of();
        Map<Item, Integer> quotas = stockQuotas();
        Map<Item, Integer> result = new java.util.LinkedHashMap<>();
        for (Item item : sellableOutputs()) {
            int stock = tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                    .countItem(level, farmhouse, item);
            int keep = quotas.getOrDefault(item, 8);
            if (stock > keep) result.put(item, stock - keep);
        }
        return result;
    }

    /**
     * Phase 6.3.3.e.3 — if a market exists and farmhouse storage has
     * sellable surplus, write CARGO_DESTINATION + WORK_PHASE=SELL and
     * transition to AWAITING_SELL. The universal SellToMarketBehavior
     * takes over from there (walk to market, execute sell, clear
     * memory, return). Returns true if the hand-off fired.
     */
    private boolean tryHandOffToSell(ServerLevel level) {
        if (farmhouse == null) return false;
        Building market = tterrag1112.life_in_the_village.Village.Economy.Resources
                .ProductionHelpers.findMarketInVillage(entity, level).orElse(null);
        if (market == null) return false;
        if (computeSurplusToSell(level).isEmpty()) return false;
        BlockPos marketOrigin = market.getShape().getOrigin();
        entity.getBrain().setMemory(NpcMemoryTypes.CARGO_DESTINATION.get(),
                net.minecraft.core.GlobalPos.of(level.dimension(), marketOrigin));
        entity.getBrain().setMemory(NpcMemoryTypes.WORK_PHASE.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.WorkPhase.SELL);
        phase = Phase.AWAITING_SELL;
        return true;
    }
}