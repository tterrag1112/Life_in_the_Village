package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRoleAssigner;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.ProfessionSpecialization;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.SpecializationManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.WorkPhase;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionStep;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.2.d.1 — Brain-side port of {@code AbstractWorkstationProductionGoal}.
 *
 * <p>State machine: IDLE → GATHERING → WALKING_TO_STEP → WORKING_STEP →
 * DEPOSITING → (if surplus) write CARGO_DESTINATION + WORK_PHASE=SELL,
 * stop; otherwise back to IDLE. SellToMarketBehavior owns the market
 * trip; when it erases CARGO_DESTINATION + sets WORK_PHASE=IDLE this
 * behavior re-enters and starts the next cycle.
 *
 * <p>Buy-side procurement for missing inputs uses the existing
 * ChannelRouter pipeline inline during GATHERING — the workshop's
 * multi-item buy is different in shape from BuyFromNpcBehavior's
 * single-item purchase, so we keep it co-located with the recipe
 * machinery here.
 *
 * <p>Subclasses override the same hooks as the original Goal:
 * {@link #requiredBuildingType}, {@link #chooseRecipe},
 * {@link #calculateBatchSize}, plus the optional seams.
 */
public abstract class AbstractProductionBehavior extends Behavior<TownspersonMob> {

    // ── Timing constants ─────────────────────────────────────────────────────
    protected static final int    IDLE_COOLDOWN_TICKS  = 600;
    protected static final double INTERACT_RANGE_SQ    = 9.0;
    /** XP awarded when an NPC completes one full production cycle.
     *  Replaces the deleted {@code NpcProfessionXp.XP_PER_PRODUCTION_CYCLE}. */
    protected static final int    XP_PER_PRODUCTION_CYCLE = 3;
    protected static final int    WORK_PULSE_INTERVAL  = 20;
    private   static final long   MIN_SELL_INTERVAL    = 20000L;
    private   static final long   ROLE_CHECK_INTERVAL  = 24000L;
    private   static final float  WALK_SPEED           = 1.0f;
    private   static final int    CLOSE_ENOUGH         = 1;
    private   static final int    MAX_RUN              = 24000;

    // ── Per-cycle state ──────────────────────────────────────────────────────
    protected Phase phase        = Phase.IDLE;
    protected int   workTimer    = 0;

    /** Entity bound at start(). Lets subclasses match Goal subclasses
     *  byte-for-byte (they reference {@code entity} as a field). */
    protected TownspersonMob entity;

    protected Building workBuilding;
    protected Building market;

    protected ProductionRecipe currentRecipe;
    protected int                  currentBatchSize;
    protected List<ProductionStep> currentSteps     = List.of();
    protected int                  currentStepIndex = 0;

    private long lastDailySellTick  = Long.MIN_VALUE;
    private long lastRoleCheckTick  = -ROLE_CHECK_INTERVAL;

    protected enum Phase {
        IDLE, GATHERING, WALKING_TO_STEP, WORKING_STEP, DEPOSITING, AWAITING_SELL
    }

    protected AbstractProductionBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CARGO_DESTINATION.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    // =========================================================================
    // Abstract — subclasses must implement
    // =========================================================================
    protected abstract BuildingType requiredBuildingType();
    protected abstract Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building);
    protected abstract int calculateBatchSize(ServerLevel level, ProductionRecipe recipe);

    // =========================================================================
    // Optional — override for per-profession variance
    // =========================================================================
    protected List<ProductionStep> buildSteps(ServerLevel level, Building building,
                                              ProductionRecipe recipe, int batchSize) {
        BlockPos station = findWorkstation(level, building).orElse(null);
        int scaledTicks = Math.max(20,
                (int)(recipe.ticks() * batchSize
                        * productionSpeedMultiplier()
                        * roleSpeedMultiplier()
                        / craftingSpeedMultiplier(entity)));
        Map<Item, Integer> consumes = new LinkedHashMap<>();
        recipe.inputs().forEach((item, count) -> consumes.put(item, count * batchSize));
        fuelPerBatch(recipe).forEach((item, count) ->
                consumes.merge(item, count * batchSize, Integer::sum));
        Map<Item, Integer> produces = new LinkedHashMap<>();
        produces.put(recipe.output(), recipe.outputCount() * batchSize);
        return List.of(new ProductionStep(station, scaledTicks, workSound(), workToolItem(),
                consumes, produces));
    }

    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) { return Optional.empty(); }
    protected SoundEvent workSound() { return SoundEvents.ANVIL_USE; }
    protected Item workToolItem() { return Items.AIR; }
    protected float productionSpeedMultiplier() { return 1.0f; }
    protected boolean canStartProduction(ServerLevel level, ProductionRecipe recipe) { return true; }
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) { return workBuilding; }
    protected Map<Item, Building> inputSourcePerItem(ServerLevel level, ProductionRecipe recipe) { return Map.of(); }
    protected Map<Item, Integer> fuelPerBatch(ProductionRecipe recipe) { return Map.of(); }
    protected Building fuelSource(ServerLevel level, Building workBuilding) { return resolveInputSource(level, workBuilding); }
    protected Building outputBuilding(ServerLevel level) { return workBuilding; }
    protected Map<Item, Integer> stockQuotas() { return Map.of(); }
    protected int sellSurplusThreshold() { return 8; }
    protected List<Item> sellableOutputs() { return List.of(); }
    protected int sellWindowDayTick() { return 10000; }
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) { return Map.of(); }
    protected boolean canProduceItem(Item item) { return false; }
    protected void onStepComplete(ServerLevel level, ProductionStep step, int stepIndex) {}
    protected void onProductionComplete(ServerLevel level, ProductionRecipe recipe, int batchSize) {}

    // =========================================================================
    // Role helpers (verbatim from AbstractWorkstationProductionGoal)
    // =========================================================================
    protected final boolean isBlockedByRole() {
        return ProfessionRoleManager.isMarketSeller(entity);
    }
    protected final void tickRoleCheck(ServerLevel level, Building building) {
        if (building == null) return;
        long tick = level.getGameTime();
        if (tick - lastRoleCheckTick >= ROLE_CHECK_INTERVAL) {
            lastRoleCheckTick = tick;
            WorkshopRoleAssigner.assignRoles(level, building);
        }
    }
    protected final float roleSpeedMultiplier() {
        if (entity == null) return 1.0f;
        WorkshopRole role = ProfessionRoleManager.getRole(entity, WorkshopRole.class);
        if (role == WorkshopRole.APPRENTICE) {
            return 1.0f / WorkshopRole.APPRENTICE.productionSpeedMultiplier();
        }
        return 1.0f;
    }

    protected final <T extends ProfessionSpecialization> T getSpecialization(Class<T> type) {
        return SpecializationManager.getSpecialization(entity, type);
    }

    // =========================================================================
    // Crafting-skill multipliers (replaces deleted NpcProfessionXp helpers).
    // Mapped from the SkillComponent 4-tier scheme (Amateur/Journeyman/Expert/
    // Master at 0/30/60/85). Curves chosen to land close to the previous
    // Novice/Journeyman/Expert/Master/Grandmaster numbers.
    // =========================================================================

    /** Production-step ticks are divided by this — higher = faster. */
    protected static float craftingSpeedMultiplier(TownspersonMob npc) {
        int level = npc.getSkills().getLevel(Skill.CRAFTING);
        if (level >= 85) return 1.55f;
        if (level >= 60) return 1.35f;
        if (level >= 30) return 1.15f;
        return 1.0f;
    }

    /** Probability (0-1) of producing one bonus output item per cycle. */
    protected static float craftingQualityChance(TownspersonMob npc) {
        int level = npc.getSkills().getLevel(Skill.CRAFTING);
        if (level >= 85) return 0.25f;
        if (level >= 60) return 0.10f;
        return 0.0f;
    }

    // =========================================================================
    // Behavior lifecycle
    // =========================================================================

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (isBlockedByRole()) return false;
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        workBuilding = findAssignedBuilding(entity, level, requiredBuildingType()).orElse(null);
        if (workBuilding == null) return false;
        tickRoleCheck(level, workBuilding);
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (phase == Phase.IDLE) return false;
        if (phase == Phase.AWAITING_SELL) return false; // hand off to SellToMarketBehavior
        return entity.isWorkTime();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.IDLE;
        analyze(level);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        switch (phase) {
            case GATHERING        -> gather(level);
            case WALKING_TO_STEP  -> walkToStep(level);
            case WORKING_STEP     -> workStep(level);
            case DEPOSITING       -> deposit(level);
            default               -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        goIdle();
    }

    // =========================================================================
    // ANALYZE
    // =========================================================================
    protected void analyze(ServerLevel level) {
        if (workBuilding == null) { goIdle(); return; }
        market = findMarket(level);

        // If surplus exists right now, immediately hand off to SellToMarketBehavior.
        if (isSellTime(level.getGameTime()) && market != null
                && !computeSurplusToSell(level).isEmpty()) {
            handOffToSell(level);
            return;
        }

        Optional<ProductionRecipe> recipe = chooseRecipe(level, workBuilding);
        if (recipe.isPresent()) {
            ProductionRecipe r = recipe.get();
            int batchSize = calculateBatchSize(level, r);
            if (batchSize > 0 && !isOutputAtCapacity(level, r)
                    && canStartProduction(level, r)) {
                List<ProductionStep> steps = buildSteps(level, workBuilding, r, batchSize);
                if (!steps.isEmpty()) {
                    currentRecipe = r;
                    currentBatchSize = batchSize;
                    currentSteps = steps;
                    currentStepIndex = 0;
                    phase = Phase.GATHERING;
                    return;
                }
            }
        }
        goIdle();
    }

    // =========================================================================
    // Phase: GATHERING (includes ChannelRouter-based fallback procurement)
    // =========================================================================
    protected void gather(ServerLevel level) {
        entity.setCurrentActivity("Gathering materials");
        if (currentRecipe == null) { goIdle(); return; }

        Map<Item, Integer> toGather = new LinkedHashMap<>();
        currentRecipe.inputs().forEach((item, count) ->
                toGather.put(item, count * currentBatchSize));
        fuelPerBatch(currentRecipe).forEach((item, count) ->
                toGather.merge(item, count * currentBatchSize, Integer::sum));
        if (toGather.isEmpty()) { transitionToFirstStep(); return; }

        Map<Item, Building> perItemSources = inputSourcePerItem(level, currentRecipe);
        Building defaultSource = resolveInputSource(level, workBuilding);

        boolean missingAny = false;
        for (Map.Entry<Item, Integer> entry : toGather.entrySet()) {
            Item item = entry.getKey();
            int qty = entry.getValue();
            Building source = perItemSources.getOrDefault(item, defaultSource);
            if (source == null) { missingAny = true; continue; }
            if (!BuildingStorageAccess.takeItem(level, source, item, qty)) {
                missingAny = true;
                continue;
            }
            entity.getPersonalInventory().addItem(new ItemStack(item, qty));
        }

        if (missingAny) {
            returnPersonalInventoryToBuilding(level, defaultSource);
            Map<Item, Integer> toBuy = resourcesToBuy(level, workBuilding);
            if (!toBuy.isEmpty()) executeBuy(level, toBuy);
            goIdle();
            return;
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

    protected void walkToStep(ServerLevel level) {
        if (currentStepIndex >= currentSteps.size()) { phase = Phase.DEPOSITING; return; }
        ProductionStep step = currentSteps.get(currentStepIndex);
        BlockPos target = stepTarget(step);
        if (moveToOrArrived(target)) {
            workTimer = 0;
            if (step.heldTool() != Items.AIR) {
                entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(step.heldTool()));
            } else {
                entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            phase = Phase.WORKING_STEP;
        }
    }

    protected void workStep(ServerLevel level) {
        if (currentStepIndex >= currentSteps.size()) { phase = Phase.DEPOSITING; return; }
        ProductionStep step = currentSteps.get(currentStepIndex);
        BlockPos target = stepTarget(step);

        entity.setCurrentActivity("Crafting " + currentRecipe.output().getDescriptionId());
        workTimer++;
        entity.getLookControl().setLookAt(target.getX(), target.getY(), target.getZ());
        if (workTimer % WORK_PULSE_INTERVAL == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, target, step.sound(), SoundSource.NEUTRAL,
                    0.5f, 0.8f + entity.getRandom().nextFloat() * 0.4f);
        }
        if (workTimer >= step.ticks()) {
            step.consumes().forEach((item, count) -> removeFromPersonalInventory(item, count));
            step.produces().forEach((item, count) ->
                    entity.getPersonalInventory().addItem(new ItemStack(item, count)));
            onStepComplete(level, step, currentStepIndex);
            workTimer = 0;
            currentStepIndex++;
            if (currentStepIndex >= currentSteps.size()) {
                for (ItemStack byproduct : currentRecipe.byproducts()) {
                    entity.getPersonalInventory().addItem(byproduct.copy());
                }
                entity.getSkills().addXp(Skill.CRAFTING,
                        XP_PER_PRODUCTION_CYCLE, level.getGameTime());
                float qualityChance = craftingQualityChance(entity);
                if (qualityChance > 0 && entity.getRandom().nextFloat() < qualityChance) {
                    entity.getPersonalInventory().addItem(
                            new ItemStack(currentRecipe.output(), currentBatchSize));
                }
                onProductionComplete(level, currentRecipe, currentBatchSize);
                phase = Phase.DEPOSITING;
            } else {
                phase = Phase.WALKING_TO_STEP;
            }
        }
    }

    protected void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing goods");
        Building dest = outputBuilding(level);
        if (dest == null) { goIdle(); return; }

        // Show arms-forward carry pose while the NPC is en route to / at the
        // deposit container — repeat-write each tick is cheap and keeps the
        // display synced even if a CORE behavior briefly clears it.
        ItemStack carried = firstNonEmptyStack(entity.getPersonalInventory());
        if (!carried.isEmpty()) {
            entity.getBrain().setMemory(
                    NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), carried.copy());
        }

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
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());

        if (market != null && isSellTime(level.getGameTime())
                && !computeSurplusToSell(level).isEmpty()) {
            handOffToSell(level);
        } else {
            goIdle();
        }
    }

    private void handOffToSell(ServerLevel level) {
        BlockPos marketOrigin = market.getShape().getOrigin();
        entity.getBrain().setMemory(NpcMemoryTypes.CARGO_DESTINATION.get(),
                GlobalPos.of(level.dimension(), marketOrigin));
        entity.getBrain().setMemory(NpcMemoryTypes.WORK_PHASE.get(), WorkPhase.SELL);
        phase = Phase.AWAITING_SELL;
    }

    // =========================================================================
    // Helpers — capacity / surplus / sell-time (verbatim)
    // =========================================================================
    private boolean isOutputAtCapacity(ServerLevel level, ProductionRecipe recipe) {
        Building dest = outputBuilding(level);
        if (dest == null) return false;
        int stock = BuildingStorageAccess.countItem(level, dest, recipe.output());
        int quota = stockQuotas().getOrDefault(recipe.output(), Integer.MAX_VALUE);
        return stock >= quota;
    }

    Map<Item, Integer> computeSurplusToSell(ServerLevel level) {
        List<Item> outputs = sellableOutputs();
        if (outputs.isEmpty()) return Map.of();
        Building dest = outputBuilding(level);
        if (dest == null) return Map.of();
        Map<Item, Integer> quotas = stockQuotas();
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (Item item : outputs) {
            int stock = BuildingStorageAccess.countItem(level, dest, item);
            int keep = quotas.getOrDefault(item, sellSurplusThreshold());
            if (stock > keep) result.put(item, stock - keep);
        }
        return result;
    }

    private boolean isSellTime(long gameTime) {
        long dayTime = gameTime % 24000;
        long todayBase = gameTime - dayTime;
        return dayTime >= sellWindowDayTick()
                && lastDailySellTick < todayBase
                && (gameTime - lastDailySellTick) >= MIN_SELL_INTERVAL;
    }

    // =========================================================================
    // ChannelRouter procurement (extracted from Goal's executeBuy)
    // =========================================================================
    private void executeBuy(ServerLevel level, Map<Item, Integer> toBuy) {
        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return;
        VillageSavedData data = VillageSavedData.get(level);
        tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy bEconomy =
                data.getOrCreateBuildingEconomy(buildingId);
        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) return;

        for (Map.Entry<Item, Integer> entry : toBuy.entrySet()) {
            Item item = entry.getKey();
            int wanted = entry.getValue();
            if (wanted <= 0) continue;
            long perUnitCeiling = Math.max(1L, getItemBuyPrice(level, item));
            tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent intent =
                    tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent.buy(
                            item, wanted, entity.getUUID(), buildingId,
                            village.getId(), perUnitCeiling,
                            tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency.NORMAL,
                            java.util.Set.of());
            var quote = tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter
                    .findBestChannel(intent, village, data, level).orElse(null);
            if (quote == null) continue;
            long total = quote.totalBronze();
            if (!bEconomy.canAfford(total)) continue;
            bEconomy.withdraw(total);
            entity.getWallet().receive(CurrencyValue.of(total));
            var channel = tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter
                    .registeredChannels().stream()
                    .filter(c -> c.type() == quote.channel())
                    .findFirst().orElse(null);
            var result = channel == null
                    ? tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult.fail("channel missing")
                    : channel.execute(quote, intent, level);
            if (!result.success()) {
                entity.getWallet().spend(CurrencyValue.of(total));
                bEconomy.depositRevenue(total);
                continue;
            }
            long leftover = total - result.totalBronze();
            if (leftover > 0) {
                entity.getWallet().spend(CurrencyValue.of(leftover));
                bEconomy.depositRevenue(leftover);
            }
            BuildingStorageAccess.storeItem(level, workBuilding,
                    new ItemStack(item, result.quantityTraded()));
            data.setDirty();
        }
    }

    // =========================================================================
    // Static sell helper — used by SellToMarketBehavior to execute the trip.
    // Lifted from the Goal's executeSell so the logic stays in one place.
    // =========================================================================
    public static long executeSellForWorkshop(ServerLevel level, TownspersonMob entity,
                                              Building outputBuilding, Building market,
                                              Map<Item, Integer> toSell) {
        if (toSell.isEmpty()) return 0L;
        if (outputBuilding == null || market == null) return 0L;

        VillageSavedData data = VillageSavedData.get(level);
        MarketStall ownStall = data.getStallByOwner(entity.getUUID()).orElse(null);
        boolean hasActiveStall = ownStall != null && ownStall.isActive()
                && !ownStall.getChestPos().equals(BlockPos.ZERO);
        long totalRevenue = 0;

        for (Map.Entry<Item, Integer> entry : toSell.entrySet()) {
            Item item = entry.getKey();
            int qty = entry.getValue();
            if (!BuildingStorageAccess.takeItem(level, outputBuilding, item, qty)) continue;
            if (hasActiveStall) {
                BlockEntity be = level.getBlockEntity(ownStall.getChestPos());
                if (be instanceof Container chest) {
                    addToContainer(chest, item, qty);
                } else {
                    BuildingStorageAccess.storeItem(level, market, new ItemStack(item, qty));
                }
            } else {
                BuildingStorageAccess.storeItem(level, market, new ItemStack(item, qty));
            }
            totalRevenue += Math.max(1L, Math.round(VillageEconomy.getBasePrice(item) * 0.8)) * qty;
            entity.getAssignedVillageName()
                    .flatMap(data::getVillageByName)
                    .ifPresent(v -> VillageEconomy.postListing(
                            level, v.getId(), entity, item, qty, level.getGameTime()));
        }

        if (totalRevenue > 0) {
            CurrencyValue earnings = CurrencyValue.of(totalRevenue);
            List<Container> containers = BuildingStorageAccess.findInventories(level, outputBuilding);
            if (!containers.isEmpty() && containers.get(0) instanceof SimpleContainer sc) {
                CoinHelper.giveCoins(sc, earnings);
            }
            WorkplaceAssignmentManager.onWorkplaceSale(
                    level, outputBuilding.getId(), (int) totalRevenue);
        }
        return totalRevenue;
    }

    private static void addToContainer(Container chest, Item item, int qty) {
        ItemStack toAdd = new ItemStack(item, qty);
        for (int i = 0; i < chest.getContainerSize() && !toAdd.isEmpty(); i++) {
            ItemStack ex = chest.getItem(i);
            if (ex.is(item) && ex.getCount() < ex.getMaxStackSize()) {
                int add = Math.min(ex.getMaxStackSize() - ex.getCount(), toAdd.getCount());
                ex.grow(add);
                toAdd.shrink(add);
            }
        }
        for (int i = 0; i < chest.getContainerSize() && !toAdd.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, toAdd.copy());
                toAdd.setCount(0);
            }
        }
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================
    private BlockPos stepTarget(ProductionStep step) {
        return step.station() != null ? step.station() : workBuilding.getShape().getOrigin();
    }

    /** Walks via WALK_TARGET memory. Returns true if already within INTERACT_RANGE_SQ. */
    protected boolean moveToOrArrived(BlockPos target) {
        double distSq = entity.distanceToSqr(target.getX(), entity.getY(), target.getZ());
        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            return true;
        }
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
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
        return ProductionHelpers.findMarketInVillage(entity, level).orElse(null);
    }

    protected Optional<Building> findAssignedBuilding(TownspersonMob npc, ServerLevel level, BuildingType type) {
        return ProductionHelpers.findAssignedBuilding(npc, level, type);
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
                    .map(h -> h.value()).orElse(null);
        } catch (Exception e) { return null; }
    }

    protected void goIdle() {
        entity.clearCurrentActivity();
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        phase = Phase.IDLE;
        workTimer = 0;
        currentRecipe = null;
        currentSteps = List.of();
        currentStepIndex = 0;
        market = null;
    }

    private static ItemStack firstNonEmptyStack(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

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

    // ── Specialization / order helpers (used by some subclasses) ───────────
    protected final Optional<Item> productionTarget(ServerLevel level, Building building) {
        Optional<Item> ordered = activeSpecialOrderItem(level);
        if (ordered.isPresent()) return ordered;
        Map<Item, Integer> quotas = stockQuotas();
        if (quotas.isEmpty()) return Optional.empty();
        Item lowestItem = null;
        double lowestRatio = Double.MAX_VALUE;
        for (Map.Entry<Item, Integer> e : quotas.entrySet()) {
            int stock = BuildingStorageAccess.countItem(level, outputBuilding(level), e.getKey());
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
                                .getOrdersForVillage(village.getId()).stream()
                                .filter(CraftingOrder::isOpen)
                                .filter(o -> { Item item = resolveItem(o.getItemId());
                                    return item != null && canProduceItem(item); })
                                .max(Comparator.comparingInt(CraftingOrder::getRemainingCount))
                                .map(o -> resolveItem(o.getItemId())));
    }
}
