
package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
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
import tterrag1112.life_in_the_village.World.WeatherContext;

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

    private static final Logger LOGGER = LogUtils.getLogger();

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
     *  as workers come and go.
     *  Phase 6.3.3.n.2 — sentinel changed from {@code Long.MIN_VALUE}
     *  to {@code 0L}. The previous sentinel made {@code now -
     *  Long.MIN_VALUE} overflow to a negative diff via two's
     *  complement (Java's {@code -Long.MIN_VALUE == Long.MIN_VALUE}
     *  quirk), so the cadence check {@code >= INTERVAL} was
     *  permanently false and role assignment never fired. Treated
     *  here as "never run yet"; first-call guard at the trigger site. */
    private long  lastRoleAssignTick = 0L;
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
    /** Phase 6.3.3.n.3 — one-shot guard so the "null role fallback"
     *  warning only fires once per behavior instance, not every tick. */
    private boolean warnedNullRole = false;
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
        TENDING_ANIMALS,
        /** Phase 6.3.3.h.3 — FERTILIZER role with manure in farmhouse
         *  storage walks to a low-soilQuality plot and composts it. */
        COMPOSTING,
        /** Phase 6.3.3.h.5 — farmer has no hoe; walk to market and
         *  purchase one (treasury debit, market stock add). */
        ACQUIRING_TOOL
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
            case COMPOSTING         -> compost(level);
            case ACQUIRING_TOOL     -> acquireTool(level);
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
        // Phase 6.3.3.n.2 — explicit first-call guard: lastRoleAssignTick
        // == 0L signals "never run yet" and fires immediately, then the
        // daily cadence takes over. Replaces the Long.MIN_VALUE sentinel
        // that two's-complement-overflowed the diff check to negative.
        long now = level.getGameTime();
        boolean firstAssign = lastRoleAssignTick == 0L;
        if (firstAssign || now - lastRoleAssignTick >= ROLE_ASSIGN_INTERVAL) {
            tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer
                    .FarmRoleAssigner.assignRoles(level, farmhouse);
            // Math.max(now, 1L) prevents accidentally re-pinning to the
            // 0L "never run" sentinel on tick 0 of a fresh world.
            lastRoleAssignTick = Math.max(now, 1L);
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
        // Phase 6.3.3.n.3 — defensive: if role is null (upstream assignment
        // failed for any reason — component reset, save bug, mod
        // interaction, etc.), the work loop used to dead-end because
        // canHarvest/canPlant returned false. The n.2 cadence fix makes
        // null-role rare, but the null-role fallback in canHarvest /
        // canPlant (treating null as GENERALIST) ensures the farmer
        // keeps working. Log once per behavior instance so the upstream
        // bug surfaces if it ever recurs.
        if (role == null && !warnedNullRole) {
            LOGGER.warn("[FarmerBehavior] {} has null FarmRole at FARMHOUSE {} — "
                    + "treating as GENERALIST. Upstream role assignment did not run "
                    + "(check FarmRoleAssigner cadence + ProfessionRoleManager state).",
                    entity.getNpcName(), farmhouse.getId());
            warnedNullRole = true;
        }

        // Phase 6.3.3.g.3 — animal-role workers bypass crop work entirely.
        // The roster cycle-tick handles production output passively; this
        // branch keeps the NPC at the animal facility for animation +
        // periodic XP awards.
        //
        // Phase 6.3.3.h.3 — FERTILIZER additionally checks for compost
        // work: when farmhouse has manure (BONE_MEAL) AND any assigned
        // crop plot is below SOIL_FALLOW_EXIT (and past its compost
        // cooldown), the worker routes to COMPOSTING instead. APPRENTICE
        // workers don't compost (no financial / planning authority).
        // Phase 6.3.3.i.4 — bias-not-gate: a GENERALIST-role FARMER with
        // animal_focus spec is treated as if their role were
        // ANIMAL_TENDER, so spec biases the routing without a gate. A
        // GENERALIST crop_focus FARMER stays on the crop path (already
        // the default). Specific animal roles always go to animal work
        // regardless of spec — spec is a tiebreaker, not an override.
        boolean specBiasAnimal = role == FarmRole.GENERALIST
                && entity.getSpecializationComponent().currentId()
                        .map(id -> id.equals(
                                tterrag1112.life_in_the_village.Npc.Specialization
                                        .NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                        .orElse(false);
        if (role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || role == FarmRole.FERTILIZER
                || specBiasAnimal) {
            if (role == FarmRole.FERTILIZER && !isApprenticeTier()
                    && tryRouteToCompost(level)) {
                return;
            }
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
            // Phase 6.3.3.m.3 — tool-gating philosophy shift. Tool
            // tier is now a productivity multiplier on yield, not a
            // hard prerequisite. A farmer with no hoe still harvests
            // (bare-hands at HOE_PRODUCTIVITY_NO_HOE = 0.5×). The
            // farmer attempts to pull a hoe from farmhouse storage
            // opportunistically — present-from-storage gives full
            // tier benefit — but storage absence no longer blocks
            // work. The ACQUIRING_TOOL phase is still reachable
            // through future opportunistic triggers but no longer
            // fires unconditionally on missing-hoe.
            if (!ToolUseSupport.hasUsableTool(entity, FarmerBehavior::isHoe)) {
                tryAcquireHoeFromFarmhouse(level);
            }
            phase = Phase.HARVESTING;
            return;
        }

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        if (!toReplant.isEmpty() && canPlant(role)) {
            // Phase 6.3.3.h.4 — weather-aware pause: skilled non-
            // APPRENTICE farmers wait out heavy rain before planting.
            // APPRENTICEs keep working regardless (they follow the
            // master's prior assignment without reading the sky).
            boolean pause = !isApprentice
                    && entity.getSkills().getLevel(
                            tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING)
                            >= ROTATION_SKILL_THRESHOLD
                    && WeatherContext.shouldPauseFarming(level);
            if (!pause) {
                phase = Phase.REPLANTING;
                return;
            }
        }

        // Phase 6.3.3.f.1 — APPRENTICE workers skip BUYING_SEEDS (no
        // financial responsibility) and the sell fall-through (they
        // don't sell). Their loop ends here with goIdle when there's
        // nothing harvestable / replantable on their plot.
        if (!isApprentice) {
            // Phase 6.3.3.n.4 — seed gate philosophy: BUYING_SEEDS only
            // fires when a market actually exists to buy from. A
            // no-market village (fresh world, distant outpost) skips
            // the phase entirely and falls through to tryHandOffToSell
            // / goIdle rather than entering buySeeds() just to
            // goIdle on the missing-market check. The plot stays
            // un-replanted this cycle and is picked up next cycle if
            // seeds become available; the farmer never deadlocks
            // pursuing seeds they can't acquire.
            if (needsSeeds(level) && canPlant(role)
                    && ProductionHelpers.findMarketInVillage(entity, level).isPresent()) {
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
        // Phase 6.3.3.n.3 — null role treated as GENERALIST (defensive
        // fallback paired with the analyze()-side warning). Prevents
        // the work loop from dead-ending when upstream role assignment
        // hasn't run; the assigner cadence fix in n.2 should make this
        // path cold, but the fallback is cheap insurance against
        // future regressions.
        if (role == null) return true;
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.HARVESTER;
    }

    private boolean canPlant(FarmRole role) {
        if (role == null) return true;
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
        // Phase 6.3.3.h.4 — weather contribution (rain bonus, thunder
        // penalty).
        float weatherMult = WeatherContext.yieldMultiplier(level);
        // Phase 6.3.3.k.2 — drought + frost layers. Drought is
        // village-scoped (×0.7 yield when active); frost is plot-
        // location-scoped and keyed by the crop's cold tolerance.
        UUID villageIdForYield = resolveVillageId(level);
        float droughtMult = WeatherContext.droughtYieldMultiplier(level, villageIdForYield);
        float frostMult = harvestedPlot != null
                ? WeatherContext.frostYieldMultiplier(level, cropPos,
                        harvestedPlot.getCropType().coldTolerance())
                : 1.0f;
        // Phase 6.3.3.k.4 — blight cuts yield in half regardless of
        // other modifiers.
        float blightMult = (harvestedPlot != null && harvestedPlot.isBlighted())
                ? FarmPlot.BLIGHT_YIELD_MULT : 1.0f;
        // Phase 6.3.3.m.3 — tool tier productivity ladder. Best hoe in
        // inventory scores its tier multiplier; no hoe at all falls
        // back to HOE_PRODUCTIVITY_NO_HOE (0.5×) — bare-hands farming
        // still produces, just at half rate. Composes alongside the
        // other yield modifiers without double-counting (single
        // multiplier per layer; specialty / mentee multipliers are
        // applied to XP grants, not crop yields).
        float hoeMult = ToolUseSupport.bestToolMultiplier(
                entity, FarmerBehavior::isHoe,
                FarmerBehavior::hoeProductivityMultiplier,
                HOE_PRODUCTIVITY_NO_HOE);
        float yieldMult = seasonMult * soilMult * weatherMult
                * droughtMult * frostMult * blightMult * hoeMult;

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
        // Phase 6.3.3.h.5 — equip + damage the durable hoe. If it
        // breaks mid-cycle, the next analyze() pass picks up the
        // missing-tool branch and routes to ACQUIRING_TOOL.
        ToolUseSupport.useToolFromInventory(entity, FarmerBehavior::isHoe,
                level, InteractionHand.MAIN_HAND);
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos, SoundEvents.CROP_BREAK,
                SoundSource.BLOCKS, 1.0f, 1.0f);
        // Phase 6.3.3.i.2 — XP routed to the sub-skill matching the
        // plot context: ORCHARD plots feed ORCHARDING; other crop
        // plots feed CROP_FARMING. Parent cascade (25%) fills FARMING.
        // The 6.3.3.i.4 specialty bonus is applied via awardWithBias.
        awardCropXp(level, harvestedPlot, 1);

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
            if (isHoe(s)) continue;
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

            // Don't deposit tools (h.5: keep the durable hoe with farmer).
            if (isHoe(stack)) continue;

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

        // Phase 6.3.3.k.3 — frost-aware crop swap. Skilled non-APPRENTICE
        // farmers refuse to plant warm-season crops where frost is
        // currently active at the plot. Swap to POTATOES (COOL_SEASON)
        // as a sensible cold-tolerant fallback. APPRENTICE workers
        // plant what they're told — they're learning, not deciding.
        if (shouldRotateCrops()
                && plot.getCropType().coldTolerance()
                        == FarmPlot.CropType.ColdTolerance.WARM_SEASON
                && WeatherContext.isFrost(level, plot.getOrigin())) {
            plot.setCropType(FarmPlot.CropType.POTATOES);
            VillageSavedData.get(level).setDirty();
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
            // Phase 6.3.3.i.2 — XP routed by crop context (ORCHARDING
            // for orchards, else CROP_FARMING). Parent cascade fills
            // FARMING at 25% via SkillComponent#addXp.
            awardCropXp(level, plot, 1);
            // Phase 6.3.3.h.1 — record the planting; soilQuality
            // decrements (with same-family penalty per CropFamily.of),
            // cropHistory trims to MAX_HISTORY, plot may auto-enter
            // fallow if quality fell past SOIL_FALLOW_ENTER.
            plot.onPlanted(plot.getCropType(), level.getGameTime());
            // Phase 6.3.3.k.2 — drought doubles soil-quality decay.
            // Apply an extra SOIL_DEC_PLANT chunk on top of the
            // normal planting decay when the village is in drought.
            if (WeatherContext.isDrought(level, resolveVillageId(level))) {
                plot.setSoilQuality(
                        plot.getSoilQuality() - FarmPlot.SOIL_DEC_PLANT);
            }
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
    // Phase: COMPOSTING (Phase 6.3.3.h.3)
    // =========================================================================

    /** Target plot for the active compost run. Set by tryRouteToCompost. */
    private FarmPlot compostTarget;
    /** Cooldown between compost applications on the same plot. */
    private static final long COMPOST_PLOT_COOLDOWN = 3L * FarmPlot.DAY_TICKS;

    /**
     * Phase 6.3.3.h.3 — FERTILIZER routing check. Returns true and
     * transitions to COMPOSTING when:
     * <ul>
     *   <li>Farmhouse storage contains at least 1 BONE_MEAL (manure)</li>
     *   <li>At least one assigned crop plot has soilQuality below
     *       SOIL_FALLOW_EXIT (0.7) AND past the per-plot cooldown</li>
     * </ul>
     * Picks the lowest-quality eligible plot as {@link #compostTarget}.
     */
    private boolean tryRouteToCompost(ServerLevel level) {
        if (farmhouse == null) return false;
        int manure = tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                .countItem(level, farmhouse, Items.BONE_MEAL);
        if (manure <= 0) return false;

        long now = level.getGameTime();
        VillageSavedData data = VillageSavedData.get(level);
        FarmPlot best = null;
        for (FarmPlot plot : data.getFarmPlotsForFarmhouse(farmhouse.getId())) {
            if (plot.getSubtype() != FarmPlot.PlotSubtype.CROP_FIELD) continue;
            if (plot.getSoilQuality() >= FarmPlot.SOIL_FALLOW_EXIT) continue;
            if (now - plot.getLastCompostedTick() < COMPOST_PLOT_COOLDOWN) continue;
            if (best == null || plot.getSoilQuality() < best.getSoilQuality()) {
                best = plot;
            }
        }
        if (best == null) return false;

        compostTarget = best;
        phase = Phase.COMPOSTING;
        entity.setCurrentActivity("Composting");
        return true;
    }

    private void compost(ServerLevel level) {
        if (farmhouse == null || compostTarget == null) { goIdle(); return; }

        // Walk to the target plot's origin before applying.
        BlockPos target = compostTarget.getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());
        if (distSq > INTERACT_RANGE_SQ * 4.0) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        // Pull 1 manure from farmhouse; abort if the stock vanished.
        boolean taken = BuildingStorageAccess.takeItem(
                level, farmhouse, Items.BONE_MEAL, 1);
        if (!taken) { compostTarget = null; phase = Phase.ANALYZING; return; }

        compostTarget.onComposted(level.getGameTime());
        VillageSavedData.get(level).setDirty();

        // Compost bridges both domains. FARMING parent stays direct
        // (the closer doesn't pick a side per spec i.2.C); the animal
        // half routes through awardAnimalXp so the specialty bonus
        // applies for animal_focus farmers.
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.FARMING,
                2, level.getGameTime());
        awardAnimalXp(level, 1);

        compostTarget = null;
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

        // Phase 6.3.3.h.6 — anchor selection:
        //   - Storm in progress → retreat to farmhouse anchor (livestock pen).
        //   - Skilled non-APPRENTICE with ANIMAL_PEN plots → walk to the
        //     active rotation pen (PastureRotation picks the freshest).
        //   - Else → default farmhouse anchor (single-pen / unskilled).
        BlockPos anchor = farmhouse.getShape().getOrigin();
        boolean storm = WeatherContext.isStorm(level);
        boolean canRotate = !isApprenticeTier()
                && entity.getSkills().getLevel(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY)
                        >= ROTATION_SKILL_THRESHOLD;
        FarmPlot activePen = null;
        if (!storm && canRotate) {
            activePen = tterrag1112.life_in_the_village.Village.Roster
                    .PastureRotation.chooseActivePen(level, farmhouse.getId())
                    .orElse(null);
            if (activePen != null) anchor = activePen.getOrigin();
        }

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

        // Mark grazing pressure on the active pen (drops grass quality
        // a tick; PastureRotation.chooseActivePen will pick a different
        // pen next cycle once this one falls behind). Skip when at the
        // farmhouse anchor (no pen actually grazed) or during storm.
        if (activePen != null) {
            activePen.onGrazed(level.getGameTime());
            VillageSavedData.get(level).setDirty();
        }

        // Phase 6.3.3.j.5 — plot-tag-aware target. When the farmhouse
        // has a BEES adjunct plot, some cycles award BEEKEEPING
        // directly (which propagates to ANIMAL_HUSBANDRY and FARMING
        // via the hierarchy). Otherwise stays in ANIMAL_HUSBANDRY.
        awardAnimalXp(level, 1, pickAnimalXpTarget(level));

        // Phase 6.3.3.k.6 — passive disease recovery. A skilled
        // farmer (ANIMAL_HUSBANDRY ≥ INTERMEDIATE = 40) tending
        // animals at this farmhouse reduces every roster's
        // diseaseLevel by 1 per tend cycle. Slow effect — HEALER
        // visits remain the big lever for moving severe outbreaks.
        if (entity.getSkills().getLevel(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY)
                        >= tterrag1112.life_in_the_village.Npc.Skills
                                .SkillThresholds.APPRENTICE_MILESTONE_INTERMEDIATE) {
            var rdata = tterrag1112.life_in_the_village.Village.Roster
                    .RosterSavedData.get(level);
            boolean changed = false;
            for (var roster : rdata.getRostersForBuilding(farmhouse.getId())) {
                if (roster.diseaseLevel() > 0) {
                    roster.adjustDiseaseLevel(-1);
                    changed = true;
                }
            }
            if (changed) rdata.markDirty();
        }

        phase = Phase.ANALYZING;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Phase 6.3.3.k.2 — resolves the FARMER's assigned village id for
     * climate / drought queries. Returns {@code null} when the FARMER
     * is unassigned or the village isn't loaded; downstream calls into
     * {@code WeatherContext.isDrought} tolerate null villageId
     * gracefully (drought reads as false).
     */
    private UUID resolveVillageId(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(v -> v.getId())
                .orElse(null);
    }



    private FarmPlot findPlotContaining(ServerLevel level, BlockPos pos) {
        for (FarmPlot plot : assignedPlots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (farmland.above().equals(pos)) return plot;
            }
        }
        return null;
    }



    /**
     * Phase 6.3.3.n.4 — seed-deficit probe. Returns true when at
     * least one assigned plot is short on seeds AND the farmer can
     * afford to buy the deficit. Accounts for seeds already in
     * personal inventory (a farmer carrying carrot seeds from a
     * previous trip doesn't trigger a redundant market run) in
     * addition to farmhouse storage.
     *
     * <p>The market-existence check is intentionally NOT inside this
     * probe — analyze() gates BUYING_SEEDS on
     * {@link ProductionHelpers#findMarketInVillage} so a no-market
     * village skips the phase cleanly without entering it just to
     * deadlock in buySeeds().</p>
     */
    private boolean needsSeeds(ServerLevel level) {
        if (farmhouse == null) return false;
        for (FarmPlot plot : assignedPlots) {
            Item seedItem = plot.getCropType().resolveSeedItem();
            int available = countSeedsInFarmhouse(level, seedItem)
                    + countSeedsInPersonalInventory(seedItem);
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

    /** Phase 6.3.3.n.4 — counts the farmer's personal-inventory
     *  seeds of {@code seedItem}. Saves a market trip when the
     *  farmer already carries enough seed from a previous purchase. */
    private int countSeedsInPersonalInventory(Item seedItem) {
        int total = 0;
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == seedItem) {
                total += stack.getCount();
            }
        }
        return total;
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
        compostTarget = null;
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

    // -------------------------------------------------------------------------
    // Phase 6.3.3.i.2 — XP routing helpers
    // -------------------------------------------------------------------------

    /**
     * Routes crop-work XP to the right sub-skill based on plot
     * context. ORCHARD plots feed ORCHARDING; all other crop types
     * feed CROP_FARMING. Parent cascade (25%) fills FARMING via
     * SkillComponent#addXp. The 6.3.3.i.4 specialty bonus is folded
     * in by {@link #specialtyMultiplier}.
     */
    private void awardCropXp(ServerLevel level, FarmPlot plot, float amount) {
        tterrag1112.life_in_the_village.Npc.Skills.Skill target =
                (plot != null && plot.getCropType() == FarmPlot.CropType.ORCHARD)
                        ? tterrag1112.life_in_the_village.Npc.Skills.Skill.ORCHARDING
                        : tterrag1112.life_in_the_village.Npc.Skills.Skill.CROP_FARMING;
        float boosted = amount * specialtyMultiplier(target);
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, target, boosted, level.getGameTime());
    }

    /**
     * Routes animal-work XP. Default target is ANIMAL_HUSBANDRY;
     * Phase 6.3.3.j.5 added the overload that takes an explicit
     * {@link tterrag1112.life_in_the_village.Npc.Skills.Skill} target
     * so tendAnimals can route to BEEKEEPING when the farmhouse has a
     * BEES-tagged adjunct plot. Specialty bonus applies in both forms.
     */
    private void awardAnimalXp(ServerLevel level, float amount) {
        awardAnimalXp(level, amount,
                tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY);
    }

    private void awardAnimalXp(ServerLevel level, float amount,
            tterrag1112.life_in_the_village.Npc.Skills.Skill target) {
        float boosted = amount * specialtyMultiplier(target);
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, target, boosted, level.getGameTime());
    }

    /**
     * Phase 6.3.3.j.5 — pick the animal-work XP target for the
     * current tendAnimals cycle. When the farmhouse building has a
     * BEES-tagged adjunct plot, 1-in-3 cycles route to BEEKEEPING so
     * the sub-skill surfaces in practice; the remaining cycles route
     * to ANIMAL_HUSBANDRY (the broader pen/coop work the farmer is
     * doing the rest of the time). No bees → always ANIMAL_HUSBANDRY.
     */
    private tterrag1112.life_in_the_village.Npc.Skills.Skill
            pickAnimalXpTarget(ServerLevel level) {
        if (farmhouse == null) {
            return tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY;
        }
        boolean hasBees = false;
        for (var plot : tterrag1112.life_in_the_village.Networking.VillageSavedData
                .get(level).getAdjunctPlotsForBuilding(farmhouse.getId())) {
            if (plot.type().activityTag() == tterrag1112.life_in_the_village
                    .Village.Decoration.Adjunct.ActivityTag.BEES) {
                hasBees = true;
                break;
            }
        }
        if (hasBees && entity.getRandom().nextInt(3) == 0) {
            return tterrag1112.life_in_the_village.Npc.Skills.Skill.BEEKEEPING;
        }
        return tterrag1112.life_in_the_village.Npc.Skills.Skill.ANIMAL_HUSBANDRY;
    }

    /**
     * Phase 6.3.3.i.4 — XP multiplier for spec-matched activity.
     * Mirrors the BlacksmithSpecialization +50% XP-on-match pattern:
     * crop_focus + crop activity → 1.5, animal_focus + animal activity
     * → 1.5, mixed / unset / mismatch → 1.0. Bonus is applied before
     * SkillXp.award so the mentee multiplier (6.3.2.b) composes on
     * top correctly.
     */
    private float specialtyMultiplier(
            tterrag1112.life_in_the_village.Npc.Skills.Skill target) {
        return tterrag1112.life_in_the_village.Npc.Specialization
                .FarmerSpecialtyMultiplier.of(entity, target);
    }

    // -------------------------------------------------------------------------
    // Phase 6.3.3.h.5 — hoe durability + acquisition
    // -------------------------------------------------------------------------

    private static final long HOE_PRICE_BRONZE = 30L;

    // Phase 6.3.3.m.3 — hoe productivity ladder. Tool tier scales the
    // harvest yield multiplier; villages without iron access still
    // produce food (bare-hands == 0.5× baseline). Composed into the
    // yield formula via bestToolMultiplier inside harvest().
    public static final float HOE_PRODUCTIVITY_NO_HOE     = 0.50f;
    public static final float HOE_PRODUCTIVITY_WOOD       = 0.70f;
    public static final float HOE_PRODUCTIVITY_STONE      = 0.85f;
    public static final float HOE_PRODUCTIVITY_IRON       = 1.00f;
    public static final float HOE_PRODUCTIVITY_DIAMOND    = 1.10f;
    public static final float HOE_PRODUCTIVITY_NETHERITE  = 1.10f;

    /** Predicate identifying farming hoes (wood/stone/iron/diamond/netherite). */
    static boolean isHoe(ItemStack s) {
        return s.is(Items.WOODEN_HOE) || s.is(Items.STONE_HOE)
                || s.is(Items.IRON_HOE) || s.is(Items.GOLDEN_HOE)
                || s.is(Items.DIAMOND_HOE) || s.is(Items.NETHERITE_HOE);
    }

    /**
     * Phase 6.3.3.m.3 — per-stack productivity score for the hoe
     * ladder. Used as the scoring lambda passed to
     * {@link ToolUseSupport#bestToolMultiplier} so the helper returns
     * the highest tier present in the entity's inventory. Golden hoes
     * score as iron-tier (same operational tier, worse durability).
     */
    static double hoeProductivityMultiplier(ItemStack s) {
        if (s.is(Items.NETHERITE_HOE)) return HOE_PRODUCTIVITY_NETHERITE;
        if (s.is(Items.DIAMOND_HOE))   return HOE_PRODUCTIVITY_DIAMOND;
        if (s.is(Items.IRON_HOE)
                || s.is(Items.GOLDEN_HOE)) return HOE_PRODUCTIVITY_IRON;
        if (s.is(Items.STONE_HOE))     return HOE_PRODUCTIVITY_STONE;
        if (s.is(Items.WOODEN_HOE))    return HOE_PRODUCTIVITY_WOOD;
        return HOE_PRODUCTIVITY_NO_HOE;
    }

    /** Transfers one hoe from farmhouse storage to personal inventory.
     *  Returns true on success. */
    private boolean tryAcquireHoeFromFarmhouse(ServerLevel level) {
        if (farmhouse == null) return false;
        for (var container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty() || !isHoe(s)) continue;
                ItemStack one = s.copy();
                one.setCount(1);
                s.shrink(1);
                entity.getPersonalInventory().addItem(one);
                return true;
            }
        }
        return false;
    }

    /**
     * Phase 6.3.3.h.5 — walk to market, debit farmhouse treasury, add
     * one IRON_HOE to personal inventory. Reuses the BUYING_SEEDS
     * shape (market lookup + treasury withdraw + dirty mark) without
     * the per-plot loop. APPRENTICEs never enter this phase.
     */
    private void acquireTool(ServerLevel level) {
        entity.setCurrentActivity("Buying tools");
        if (farmhouse == null) { goIdle(); return; }
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

        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId == null) { goIdle(); return; }
        VillageSavedData data = VillageSavedData.get(level);
        BuildingEconomy economy = data.getOrCreateBuildingEconomy(buildingId);
        if (!economy.canAfford(HOE_PRICE_BRONZE)) { goIdle(); return; }

        economy.withdraw(HOE_PRICE_BRONZE);
        entity.getPersonalInventory().addItem(new ItemStack(Items.IRON_HOE));
        data.setDirty();

        // Small COMMERCE bump for the buy interaction.
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, tterrag1112.life_in_the_village.Npc.Skills.Skill.COMMERCE,
                1, level.getGameTime());

        phase = Phase.ANALYZING;
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