package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionStep;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;

import javax.annotation.Nullable;
import java.util.*;

public abstract class AbstractWorkstationProductionGoal extends Goal {

    // ── Timing constants ─────────────────────────────────────────────────────
    protected static final int    IDLE_COOLDOWN_TICKS  = 600;
    protected static final double INTERACT_RANGE_SQ    = 9.0;
    protected static final int    WORK_PULSE_INTERVAL  = 20;
    private   static final int    MARKET_RETRY_TICKS   = 600;
    private   static final long   MIN_SELL_INTERVAL    = 20000L;
    private   static final long   ROLE_CHECK_INTERVAL  = 24000L; // ← new

    // ── Core state ───────────────────────────────────────────────────────────
    protected final TownspersonMob entity;

    protected Phase phase        = Phase.IDLE;
    protected int   idleCooldown = 0;
    protected int   workTimer    = 0;
    private   int   marketTimer  = 0;

    protected Building workBuilding;
    protected Building market;

    // ── Per-cycle production state ────────────────────────────────────────────
    protected ProductionRecipe currentRecipe;
    protected int                  currentBatchSize;
    protected List<ProductionStep> currentSteps     = List.of();
    protected int                  currentStepIndex = 0;

    // ── Market / role timing ─────────────────────────────────────────────────
    private long lastDailySellTick  = Long.MIN_VALUE;
    private long lastRoleCheckTick  = -ROLE_CHECK_INTERVAL; // ← new

    // =========================================================================
    // Phase enum
    // =========================================================================

    protected enum Phase {
        IDLE,
        GATHERING,
        WALKING_TO_STEP,
        WORKING_STEP,
        DEPOSITING,
        SELLING_PRODUCTS,
        MARKET_VISIT
    }

    protected AbstractWorkstationProductionGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Abstract — must implement
    // =========================================================================

    protected abstract BuildingType requiredBuildingType();

    protected abstract Optional<ProductionRecipe> chooseRecipe(ServerLevel level,
                                                               Building building);

    protected abstract int calculateBatchSize(ServerLevel level, ProductionRecipe recipe);

    // =========================================================================
    // Optional — override for custom behaviour
    // =========================================================================

    protected List<ProductionStep> buildSteps(ServerLevel level, Building building,
                                              ProductionRecipe recipe, int batchSize) {
        BlockPos station = findWorkstation(level, building).orElse(null);

        // ── Role speed factor applied on top of profession speed multiplier ──
        int scaledTicks = Math.max(20,
                (int)(recipe.ticks() * batchSize
                        * productionSpeedMultiplier()
                        * roleSpeedMultiplier()));        // ← new factor

        Map<Item, Integer> consumes = new LinkedHashMap<>();
        recipe.inputs().forEach((item, count) ->
                consumes.put(item, count * batchSize));
        fuelPerBatch(recipe).forEach((item, count) ->
                consumes.merge(item, count * batchSize, Integer::sum));

        Map<Item, Integer> produces = new LinkedHashMap<>();
        produces.put(recipe.output(), recipe.outputCount() * batchSize);

        return List.of(new ProductionStep(
                station, scaledTicks, workSound(), workToolItem(),
                consumes, produces));
    }

    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return Optional.empty();
    }

    protected SoundEvent workSound() { return SoundEvents.ANVIL_USE; }

    protected Item workToolItem() { return Items.AIR; }

    protected float productionSpeedMultiplier() { return 1.0f; }

    protected boolean canStartProduction(ServerLevel level, ProductionRecipe recipe) {
        return true;
    }

    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        return workBuilding;
    }

    protected Map<Item, Building> inputSourcePerItem(ServerLevel level,
                                                     ProductionRecipe recipe) {
        return Map.of();
    }

    protected Map<Item, Integer> fuelPerBatch(ProductionRecipe recipe) {
        return Map.of();
    }

    protected Building fuelSource(ServerLevel level, Building workBuilding) {
        return resolveInputSource(level, workBuilding);
    }

    protected Building outputBuilding(ServerLevel level) { return workBuilding; }

    protected Map<Item, Integer> stockQuotas() { return Map.of(); }

    protected int sellSurplusThreshold() { return 8; }

    protected List<Item> sellableOutputs() { return List.of(); }

    protected int sellWindowDayTick() { return 10000; }

    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        return Map.of();
    }

    protected boolean canProduceItem(Item item) { return false; }

    protected void onStepComplete(ServerLevel level, ProductionStep step,
                                  int stepIndex) {}

    protected void onProductionComplete(ServerLevel level,
                                        ProductionRecipe recipe,
                                        int batchSize) {}

    // =========================================================================
    // Market / role helpers                                          ← new section
    // =========================================================================

    /**
     * True if this NPC's role is MARKET_SELLER. MARKET_SELLER workers
     * never use the workstation — their work day is spent at their stall.
     */
    protected final boolean isBlockedByRole() {
        return ProfessionRoleManager.isMarketSeller(entity);
    }

    /**
     * Once per in-game day, triggers WorkshopRoleAssigner to reassign
     * roles based on current worker count at the building.
     */
    protected final void tickRoleCheck(ServerLevel level, Building building) {
        if (building == null) return;
        long tick = level.getGameTime();
        if (tick - lastRoleCheckTick >= ROLE_CHECK_INTERVAL) {
            lastRoleCheckTick = tick;
            WorkshopRoleAssigner.assignRoles(level, building);
        }
    }

    /**
     * Speed multiplier derived from the NPC's WorkshopRole.
     * APPRENTICE workers take 1/0.6 ≈ 1.67× longer.
     * All other roles return 1.0 (no change).
     * Multiplied into step ticks inside {@link #buildSteps}.
     */
    protected final float roleSpeedMultiplier() {
        WorkshopRole role = ProfessionRoleManager.getRole(entity, WorkshopRole.class);
        if (role == WorkshopRole.APPRENTICE) {
            return 1.0f / WorkshopRole.APPRENTICE.productionSpeedMultiplier();
        }
        return 1.0f;
    }

    // =========================================================================
    // Production target helper
    // =========================================================================

    protected final Optional<Item> productionTarget(ServerLevel level,
                                                    Building building) {
        Optional<Item> ordered = activeSpecialOrderItem(level);
        if (ordered.isPresent()) return ordered;

        Map<Item, Integer> quotas = stockQuotas();
        if (quotas.isEmpty()) return Optional.empty();

        Item   lowestItem  = null;
        double lowestRatio = Double.MAX_VALUE;

        for (Map.Entry<Item, Integer> e : quotas.entrySet()) {
            int stock = BuildingStorageAccess.countItem(
                    level, outputBuilding(level), e.getKey());
            if (stock >= e.getValue()) continue;
            double ratio = (double) stock / e.getValue();
            if (ratio < lowestRatio) { lowestRatio = ratio; lowestItem = e.getKey(); }
        }
        return Optional.ofNullable(lowestItem);
    }

    private Optional<Item> activeSpecialOrderItem(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village ->
                        VillageSavedData.get(level)
                                .getOrdersForVillage(village.getId())
                                .stream()
                                .filter(CraftingOrder::isOpen)
                                .filter(o -> {
                                    Item item = resolveItem(o.getItemId());
                                    return item != null && canProduceItem(item);
                                })
                                .max(Comparator.comparingInt(CraftingOrder::getRemainingCount))
                                .map(o -> resolveItem(o.getItemId()))
                );
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (isBlockedByRole()) return false;                          // ← new
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        workBuilding = findAssignedBuilding(entity, level, requiredBuildingType())
                .orElse(null);
        if (workBuilding == null) return false;

        tickRoleCheck(level, workBuilding);                           // ← new
        return true;
    }

    @Override
    public void start() {
        phase = Phase.IDLE;
        analyze();
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE) return false;
        if (phase == Phase.SELLING_PRODUCTS) return true;
        return entity.isWorkTime();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case GATHERING        -> gather(level);
            case WALKING_TO_STEP  -> walkToStep(level);
            case WORKING_STEP     -> workStep(level);
            case DEPOSITING       -> deposit(level);
            case SELLING_PRODUCTS -> sellProducts(level);
            case MARKET_VISIT     -> marketVisit(level);
            default               -> {}
        }
    }

    @Override
    public void stop() { goIdle(); }

    // =========================================================================
    // ANALYZE
    // =========================================================================

    protected void analyze() {
        if (!(entity.level() instanceof ServerLevel level)) { goIdle(); return; }
        if (workBuilding == null) { goIdle(); return; }

        market = findMarket(level);

        if (isSellTime(level.getGameTime()) && market != null
                && !computeSurplusToSell(level).isEmpty()) {
            phase = Phase.SELLING_PRODUCTS;
            return;
        }

        Optional<ProductionRecipe> recipe = chooseRecipe(level, workBuilding);
        if (recipe.isPresent()) {
            ProductionRecipe r = recipe.get();
            int batchSize = calculateBatchSize(level, r);

            if (batchSize > 0
                    && !isOutputAtCapacity(level, r)
                    && canStartProduction(level, r)) {

                List<ProductionStep> steps = buildSteps(level, workBuilding, r, batchSize);
                if (!steps.isEmpty()) {
                    currentRecipe    = r;
                    currentBatchSize = batchSize;
                    currentSteps     = steps;
                    currentStepIndex = 0;
                    phase = Phase.GATHERING;
                    return;
                }
            }
        }

        if (market != null) {
            marketTimer = 0;
            phase = Phase.MARKET_VISIT;
        } else {
            goIdle();
        }
    }

    // =========================================================================
    // Phase: GATHERING
    // =========================================================================

    protected void gather(ServerLevel level) {
        entity.setCurrentActivity("Gathering materials");
        if (currentRecipe == null) { goIdle(); return; }

        Map<Item, Integer> toGather = new LinkedHashMap<>();
        currentRecipe.inputs().forEach((item, count) ->
                toGather.put(item, count * currentBatchSize));
        fuelPerBatch(currentRecipe).forEach((item, count) ->
                toGather.merge(item, count * currentBatchSize, Integer::sum));

        if (toGather.isEmpty()) {
            transitionToFirstStep();
            return;
        }

        Map<Item, Building> perItemSources = inputSourcePerItem(level, currentRecipe);
        Building defaultSource = resolveInputSource(level, workBuilding);

        for (Map.Entry<Item, Integer> entry : toGather.entrySet()) {
            Item item = entry.getKey();
            int  qty  = entry.getValue();

            Building source = perItemSources.getOrDefault(item, defaultSource);
            if (source == null) { goIdle(); return; }

            if (!BuildingStorageAccess.takeItem(level, source, item, qty)) {
                returnPersonalInventoryToBuilding(level, defaultSource);
                if (market != null) { marketTimer = 0; phase = Phase.MARKET_VISIT; }
                else { goIdle(); }
                return;
            }

            entity.getPersonalInventory().addItem(new ItemStack(item, qty));
        }

        transitionToFirstStep();
    }

    private void transitionToFirstStep() {
        currentStepIndex = 0;
        if (!currentSteps.isEmpty() && currentSteps.get(0).heldTool() != Items.AIR) {
            entity.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(currentSteps.get(0).heldTool()));
        }
        phase = Phase.WALKING_TO_STEP;
    }

    // =========================================================================
    // Phase: WALKING_TO_STEP
    // =========================================================================

    protected void walkToStep(ServerLevel level) {
        if (currentStepIndex >= currentSteps.size()) {
            phase = Phase.DEPOSITING;
            return;
        }

        ProductionStep step   = currentSteps.get(currentStepIndex);
        BlockPos       target = stepTarget(step);

        if (moveToOrArrived(target)) {
            workTimer = 0;
            if (step.heldTool() != Items.AIR) {
                entity.setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(step.heldTool()));
            } else {
                entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            phase = Phase.WORKING_STEP;
        }
    }

    // =========================================================================
    // Phase: WORKING_STEP
    // =========================================================================

    protected void workStep(ServerLevel level) {
        if (currentStepIndex >= currentSteps.size()) {
            phase = Phase.DEPOSITING;
            return;
        }

        ProductionStep step   = currentSteps.get(currentStepIndex);
        BlockPos       target = stepTarget(step);

        entity.setCurrentActivity("Crafting " + currentRecipe.output().getDescriptionId());
        workTimer++;

        entity.getLookControl().setLookAt(
                target.getX(), target.getY(), target.getZ());

        if (workTimer % WORK_PULSE_INTERVAL == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, target, step.sound(),
                    SoundSource.NEUTRAL,
                    0.5f, 0.8f + entity.getRandom().nextFloat() * 0.4f);
        }

        if (workTimer >= step.ticks()) {
            step.consumes().forEach((item, count) ->
                    removeFromPersonalInventory(item, count));
            step.produces().forEach((item, count) ->
                    entity.getPersonalInventory().addItem(new ItemStack(item, count)));

            onStepComplete(level, step, currentStepIndex);

            workTimer = 0;
            currentStepIndex++;

            if (currentStepIndex >= currentSteps.size()) {
                for (ItemStack byproduct : currentRecipe.byproducts()) {
                    entity.getPersonalInventory().addItem(byproduct.copy());
                }
                onProductionComplete(level, currentRecipe, currentBatchSize);
                phase = Phase.DEPOSITING;
            } else {
                phase = Phase.WALKING_TO_STEP;
            }
        }
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    protected void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing goods");

        Building dest = outputBuilding(level);
        if (dest == null) { goIdle(); return; }

        if (!moveToOrArrived(dest.getShape().getOrigin())) return;

        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (BuildingStorageAccess.storeItem(level, dest, stack.copy())) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        if (market != null && isSellTime(level.getGameTime())
                && !computeSurplusToSell(level).isEmpty()) {
            phase = Phase.SELLING_PRODUCTS;
        } else {
            goIdle();
        }
    }

    // =========================================================================
    // Phase: SELLING_PRODUCTS
    // =========================================================================

    protected void sellProducts(ServerLevel level) {
        entity.setCurrentActivity("Selling goods");
        if (market == null) { goIdle(); return; }

        if (!moveToOrArrived(market.getShape().getOrigin())) return;

        executeSell(level, computeSurplusToSell(level));
        lastDailySellTick = level.getGameTime();
        goIdle();
    }

    // =========================================================================
    // Phase: MARKET_VISIT
    // =========================================================================

    protected void marketVisit(ServerLevel level) {
        entity.setCurrentActivity("Waiting at market");
        if (market == null) { goIdle(); return; }

        if (!moveToOrArrived(market.getShape().getOrigin())) return;

        if (marketTimer == 0) {
            Map<Item, Integer> toBuy = resourcesToBuy(level, workBuilding);
            if (!toBuy.isEmpty()) executeBuy(level, toBuy);

            Map<Item, Integer> toSell = computeSurplusToSell(level);
            if (!toSell.isEmpty()) {
                executeSell(level, toSell);
                lastDailySellTick = level.getGameTime();
            }
        }

        marketTimer++;

        if (isSellTime(level.getGameTime()) && !computeSurplusToSell(level).isEmpty()) {
            phase = Phase.SELLING_PRODUCTS;
            return;
        }

        Optional<ProductionRecipe> recipe = chooseRecipe(level, workBuilding);
        if (recipe.isPresent()) {
            ProductionRecipe r   = recipe.get();
            int batchSize        = calculateBatchSize(level, r);
            if (batchSize > 0 && !isOutputAtCapacity(level, r)
                    && canStartProduction(level, r)) {
                List<ProductionStep> steps = buildSteps(level, workBuilding, r, batchSize);
                if (!steps.isEmpty()) {
                    currentRecipe    = r;
                    currentBatchSize = batchSize;
                    currentSteps     = steps;
                    currentStepIndex = 0;
                    marketTimer      = 0;
                    phase = Phase.GATHERING;
                    return;
                }
            }
        }

        if (!entity.isWorkTime()) { goIdle(); return; }

        if (marketTimer >= MARKET_RETRY_TICKS) marketTimer = 0;
    }

    // =========================================================================
    // Sell / buy execution
    // =========================================================================

    private void executeSell(ServerLevel level, Map<Item, Integer> toSell) {
        if (toSell.isEmpty()) return;
        Building dest = outputBuilding(level);
        if (dest == null) return;

        // ── Check if this NPC owns a stall — route there instead ────────────
        VillageSavedData data = VillageSavedData.get(level);
        MarketStall ownStall = data.getStallByOwner(entity.getUUID()).orElse(null);
        boolean hasActiveStall = ownStall != null
                && ownStall.isActive()
                && !ownStall.getChestPos().equals(BlockPos.ZERO);

        long totalRevenue = 0;

        for (Map.Entry<Item, Integer> entry : toSell.entrySet()) {
            Item item = entry.getKey();
            int  qty  = entry.getValue();

            if (!BuildingStorageAccess.takeItem(level, dest, item, qty)) continue;

            if (hasActiveStall) {
                // ── Stall path: deposit into stall chest directly ─────────
                BlockEntity be = level.getBlockEntity(ownStall.getChestPos());
                if (be instanceof Container chest) {
                    addToContainer(chest, item, qty);
                } else {
                    // Stall chest missing — fall back to market chest
                    BuildingStorageAccess.storeItem(level, market,
                            new ItemStack(item, qty));
                }
            } else {
                // ── Normal path: deposit into market chest ────────────────
                BuildingStorageAccess.storeItem(level, market,
                        new ItemStack(item, qty));
            }

            totalRevenue += getItemSellPrice(level, item) * qty;

            entity.getAssignedVillageName()
                    .flatMap(name -> data.getVillageByName(name))
                    .ifPresent(v -> VillageEconomy.postListing(
                            level, v.getId(), entity, item, qty,
                            level.getGameTime()));
        }

        if (totalRevenue > 0) {
            CurrencyValue earnings = CurrencyValue.of(totalRevenue);
            List<Container> containers = BuildingStorageAccess.findInventories(
                    level, dest);
            if (!containers.isEmpty()
                    && containers.get(0) instanceof SimpleContainer sc) {
                CoinHelper.giveCoins(sc, earnings);
            }
            WorkplaceAssignmentManager.onWorkplaceSale(
                    level, workBuilding.getId(), (int) totalRevenue);
        }
    }

    private static void addToContainer(Container chest, Item item, int qty) {
        ItemStack toAdd = new ItemStack(item, qty);
        // Merge with existing stacks first
        for (int i = 0; i < chest.getContainerSize() && !toAdd.isEmpty(); i++) {
            ItemStack ex = chest.getItem(i);
            if (ex.is(item) && ex.getCount() < ex.getMaxStackSize()) {
                int add = Math.min(ex.getMaxStackSize() - ex.getCount(), toAdd.getCount());
                ex.grow(add);
                toAdd.shrink(add);
            }
        }
        // Then empty slots
        for (int i = 0; i < chest.getContainerSize() && !toAdd.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, toAdd.copy());
                toAdd.setCount(0);
            }
        }
    }

    private void executeBuy(ServerLevel level, Map<Item, Integer> toBuy) {
        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return;

        VillageSavedData data = VillageSavedData.get(level);
        tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy bEconomy =
                data.getOrCreateBuildingEconomy(buildingId);

        for (Map.Entry<Item, Integer> entry : toBuy.entrySet()) {
            Item item   = entry.getKey();
            int  wanted = entry.getValue();

            int inMarket = BuildingStorageAccess.countItem(level, market, item);
            int buyNow   = Math.min(wanted, inMarket);
            if (buyNow <= 0) continue;

            long cost = getItemBuyPrice(level, item) * buyNow;
            if (!bEconomy.canAfford(cost)) continue;

            if (!BuildingStorageAccess.takeItem(level, market, item, buyNow)) continue;

            // Deduct from building treasury and pay the market merchant
            bEconomy.withdraw(cost);
            data.setDirty();

            // Route payment to stall owner or merchant NPC
            CurrencyValue costValue = CurrencyValue.of(cost);
            tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall stall =
                    data.getStallsForMarket(market.getId()).stream()
                            .filter(s -> s.isActive()
                                    && !s.getChestPos().equals(
                                    net.minecraft.core.BlockPos.ZERO))
                            .filter(s -> {
                                var be = level.getBlockEntity(s.getChestPos());
                                if (!(be instanceof net.minecraft.world.Container chest))
                                    return false;
                                for (int i = 0; i < chest.getContainerSize(); i++)
                                    if (chest.getItem(i).is(item)) return true;
                                return false;
                            })
                            .findFirst().orElse(null);

            if (stall != null && stall.getOwnerType()
                    == tterrag1112.life_in_the_village.Village.Economy.Market
                    .MarketStall.OwnerType.NPC) {
                level.getEntitiesOfClass(
                                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                                entity.getBoundingBox().inflate(128),
                                mob -> mob.getUUID().equals(stall.getOwnerUUID()))
                        .stream().findFirst()
                        .ifPresent(owner -> owner.getWallet().receive(costValue));
            } else {
                // No stall — pay the merchant NPC if present
                level.getEntitiesOfClass(
                                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                                market.getShape().toAABB().inflate(16),
                                mob -> mob.getProfession()
                                        == tterrag1112.life_in_the_village.Profession.Profession.MERCHANT)
                        .stream().findFirst()
                        .ifPresent(merchant -> merchant.getWallet().receive(costValue));
            }

            BuildingStorageAccess.storeItem(level, workBuilding,
                    new ItemStack(item, buyNow));
        }
    }

    // =========================================================================
    // Capacity gate / surplus
    // =========================================================================

    private boolean isOutputAtCapacity(ServerLevel level, ProductionRecipe recipe) {
        Building dest = outputBuilding(level);
        if (dest == null) return false;
        int stock = BuildingStorageAccess.countItem(level, dest, recipe.output());
        int quota = stockQuotas().getOrDefault(recipe.output(), Integer.MAX_VALUE);
        return stock >= quota;
    }

    private Map<Item, Integer> computeSurplusToSell(ServerLevel level) {
        List<Item> outputs = sellableOutputs();
        if (outputs.isEmpty()) return Map.of();

        Building dest = outputBuilding(level);
        if (dest == null) return Map.of();

        Map<Item, Integer> quotas = stockQuotas();
        Map<Item, Integer> result = new LinkedHashMap<>();

        for (Item item : outputs) {
            int stock = BuildingStorageAccess.countItem(level, dest, item);
            int keep  = quotas.getOrDefault(item, sellSurplusThreshold());
            if (stock > keep) result.put(item, stock - keep);
        }
        return result;
    }

    // =========================================================================
    // Sell window
    // =========================================================================

    private boolean isSellTime(long gameTime) {
        long dayTime   = gameTime % 24000;
        long todayBase = gameTime - dayTime;
        return dayTime >= sellWindowDayTick()
                && lastDailySellTick < todayBase
                && (gameTime - lastDailySellTick) >= MIN_SELL_INTERVAL;
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private BlockPos stepTarget(ProductionStep step) {
        return step.station() != null
                ? step.station()
                : workBuilding.getShape().getOrigin();
    }

    protected boolean moveToOrArrived(BlockPos target) {
        double distSq = entity.distanceToSqr(
                target.getX(), entity.getY(), target.getZ());
        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            return true;
        }
        entity.getNavigation().moveTo(
                target.getX(), target.getY(), target.getZ(), 1.0);
        return false;
    }

    protected void removeFromPersonalInventory(Item item, int count) {
        SimpleContainer inv = entity.getPersonalInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    private void returnPersonalInventoryToBuilding(ServerLevel level, Building dest) {
        if (dest == null) return;
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            BuildingStorageAccess.storeItem(level, dest, stack.copy());
            inv.setItem(i, ItemStack.EMPTY);
        }
    }

    protected Building findMarket(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.MARKET)
                        .findFirst())
                .orElse(null);
    }

    protected Optional<Building> findAssignedBuilding(TownspersonMob npc,
                                                      ServerLevel level,
                                                      BuildingType type) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == type);
    }

    protected long getItemBuyPrice(ServerLevel level, Item item) {
        return Math.max(1L, VillageEconomy.getBasePrice(item));
    }

    protected long getItemSellPrice(ServerLevel level, Item item) {
        return Math.max(1L, Math.round(VillageEconomy.getBasePrice(item) * 0.8));
    }

    protected static @Nullable Item resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM.get(Identifier.parse(itemId))
                    .map(h -> h.value())
                    .orElse(null);
        } catch (Exception e) { return null; }
    }

    protected void goIdle() {
        entity.clearCurrentActivity();
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        phase            = Phase.IDLE;
        idleCooldown     = IDLE_COOLDOWN_TICKS;
        workTimer        = 0;
        marketTimer      = 0;
        currentRecipe    = null;
        currentSteps     = List.of();
        currentStepIndex = 0;
        market           = null;
    }
    /**
     * Scan the building's bounding box for the first block matching the
     * given predicate. Used by subclasses to locate their workstation.
     */
    protected Optional<BlockPos> findBlock(ServerLevel level, Building building,
                                           java.util.function.Predicate<net.minecraft.world.level.block.Block> test) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (test.test(level.getBlockState(pos).getBlock())) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }
}