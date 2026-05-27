package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CandlemakerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

/**
 * Phase 6.6.6 — opportunistic homestead-skill behavior, sibling to
 * HomeBakingBehavior. Any NPC (not just CANDLEMAKER) with non-zero
 * CANDLEMAKING + sticks + coal in their house can craft torches when
 * family lighting stock runs low.
 *
 * <h3>No workstation</h3>
 * Mirrors {@link CandlemakerProductionBehavior}: torch-making uses
 * no specific block (works at building origin). No
 * WALKING_TO_WORKSTATION phase needed — the NPC stays at the house
 * and goes straight to PRODUCING. Distinguishes this behavior from
 * HomeBaking/HomeMilling/HomeWeaving which all require a workstation
 * amenity.
 *
 * <h3>Conditions</h3>
 * <ul>
 *   <li>Not a child, nav available.</li>
 *   <li>CANDLEMAKING ≥ 1.</li>
 *   <li>House has STICK + COAL (both inputs).</li>
 *   <li>Family torch stock &lt; {@code TORCH_PER_FAMILY_MEMBER} × member count.</li>
 *   <li>Economic OR hobby motive active.</li>
 * </ul>
 *
 * <h3>Recipe</h3>
 * Reuses {@link CandlemakerProductionBehavior#MAKE_TORCH}
 * (1 stick + 1 coal → 4 torches, 40t) verbatim. The candle and
 * lantern recipes stay profession-exclusive — homestead-tier handles
 * basic lighting only.
 */
public class HomeCandlemakingBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String HOBBY_HOME_CHANDLERY = "home_chandlery";
    /** Torch count below which family-economic motive can fire. Torches
     *  are cheap and useful in bulk; threshold is high. */
    private static final int TORCH_PER_FAMILY_MEMBER = 4;
    private static final long ECONOMIC_WALLET_THRESHOLD = 150L;
    private static final int CANDLEMAKING_XP_PER_BATCH = 2;

    private static final int MAX_RUN = 24000;

    private enum Phase { PRODUCING, DEPOSITING, DONE }

    private Phase phase;
    private Building house;
    private long startTick;
    private int subTimer;

    public HomeCandlemakingBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getSkills().getLevel(Skill.CANDLEMAKING) < 1) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return false;

        ProductionRecipe recipe = CandlemakerProductionBehavior.MAKE_TORCH;
        // Multi-input check — stick + coal both required (1 each).
        for (var e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, h, e.getKey()) < e.getValue()) {
                return false;
            }
        }

        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        int torchThreshold = familySize * TORCH_PER_FAMILY_MEMBER;
        int torchStock = BuildingStorageAccess.countItem(level, h, Items.TORCH);
        if (torchStock >= torchThreshold) return false;

        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        boolean economicMotive = (wallet + pool) < ECONOMIC_WALLET_THRESHOLD;

        NpcHobbyPreference pref = entity.getHobbyPreference();
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        boolean hobbyMotive = curPhase == DayPhase.LEISURE
                && pref.hasCurrent()
                && HOBBY_HOME_CHANDLERY.equals(pref.currentHobby());

        if (!economicMotive && !hobbyMotive) return false;

        this.house = h;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != null && phase != Phase.DONE
                && (gameTime - startTick) < MAX_RUN;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        startTick = gameTime;
        subTimer = 0;
        phase = Phase.PRODUCING;
        entity.setCurrentActivity("Making torches");
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case PRODUCING  -> tickProducing();
            case DEPOSITING -> tickDepositing(level, entity, gameTime);
            case DONE       -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickProducing() {
        if (subTimer >= CandlemakerProductionBehavior.MAKE_TORCH.ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = CandlemakerProductionBehavior.MAKE_TORCH;
        // Consume all inputs — multi-input via takeItem per entry.
        for (var e : recipe.inputs().entrySet()) {
            if (!BuildingStorageAccess.takeItem(level, house, e.getKey(), e.getValue())) {
                phase = Phase.DONE;
                return;
            }
        }
        ItemStack torches = new ItemStack(Items.TORCH, recipe.outputCount());
        BuildingStorageAccess.storeWithFallback(level, house, torches,
                entity.getPersonalInventory());
        SkillXp.award(entity, Skill.CANDLEMAKING, CANDLEMAKING_XP_PER_BATCH, gameTime);

        LOGGER.info("[HomeCandlemakingBehavior] {} (CANDLEMAKING {}) made {} torches; " +
                "house stock now {}, stick remaining {}, coal remaining {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.CANDLEMAKING),
                recipe.outputCount(),
                BuildingStorageAccess.countItem(level, house, Items.TORCH),
                BuildingStorageAccess.countItem(level, house, Items.STICK),
                BuildingStorageAccess.countItem(level, house, Items.COAL));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.clearCurrentActivity();
    }
}
