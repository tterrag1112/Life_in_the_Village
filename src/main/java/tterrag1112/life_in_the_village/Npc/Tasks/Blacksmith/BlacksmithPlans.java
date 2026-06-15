package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.List;
import java.util.Optional;

/**
 * T1 — builds a {@link Plan} for the EXACT blacksmith recipe a fulfillment
 * chose, reusing {@link BlacksmithCrafts} for batch sizing / XP / fuel and
 * {@link ContextProductionBehavior}'s deposit machinery for execution.
 *
 * <p>This is the bridge between a Task-System fulfillment (which picks
 * <i>what</i> to make) and the shared production state machine (which
 * knows <i>how</i> to make it). The plan it returns is identical in shape
 * to what {@code BlacksmithProductionBehavior.selectPlan} would build for
 * that recipe — same workstation (furnace for smelt, anvil for craft),
 * same fuel hook, same XP routing — so behavior parity is preserved.</p>
 *
 * <p><b>SMELTING binding (T1):</b> a smelt plan awards {@link Skill#SMELTING}
 * (the smelt fulfillment's skill), wiring the SMELTING enum to the smelt
 * recipes as the prompt requires. A craft plan awards the output's
 * category sub-skill (TOOLSMITHING / WEAPONSMITHING / ARMORSMITHING),
 * matching the legacy cascade.</p>
 */
public final class BlacksmithPlans {

    private BlacksmithPlans() {}

    /** Build a smelt plan for {@code recipe} (furnace + coal fuel, SMELTING XP),
     *  or empty if it can't run right now (no batch / no furnace). */
    public static Optional<Plan> smeltPlan(ServerLevel level, TownspersonMob npc,
                                           Building building, ProductionRecipe recipe) {
        int batch = BlacksmithCrafts.batchSize(level, building, recipe, BlacksmithCrafts.SMELT_FUEL);
        if (batch <= 0) return Optional.empty();
        BlockPos station = AmenityType.firstPresent(level, building, List.of(AmenityType.FURNACE));
        if (station == null) return Optional.empty();
        int xp = BlacksmithCrafts.BASE_XP; // smelting is unbiased (intermediate good)
        return Optional.of(new Plan(building, station, recipe, Skill.SMELTING, xp,
                "Smelting metal", batch, /*applyMultipliers*/ true, /*recordLedger*/ true,
                BlacksmithCrafts.SMELT_FUEL, /*fuelSource*/ building));
    }

    /** Build a craft plan for {@code recipe} (anvil, category-skill XP with the
     *  specialty bonus folded in), or empty if it can't run right now. */
    public static Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                           Building building, ProductionRecipe recipe) {
        int batch = BlacksmithCrafts.batchSize(level, building, recipe);
        if (batch <= 0) return Optional.empty();
        BlockPos station = AmenityType.firstPresent(level, building, List.of(AmenityType.ANVIL));
        if (station == null) return Optional.empty();
        Skill skill = tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith
                .BlacksmithSpecialization.categorize(recipe.output());
        int xp = BlacksmithCrafts.xpFor(recipe.output(), npc);
        return Optional.of(new Plan(building, station, recipe, skill, xp,
                "Forging at the anvil", batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
    }

    /** Resolve the smith's assigned BLACKSMITH building, if any. */
    public static Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, BuildingType.BLACKSMITH);
    }

    /** The first crafting-bin recipe whose output is {@code item}, gated by the
     *  NPC's skills. Empty if none / under-skilled. */
    public static Optional<ProductionRecipe> craftRecipeFor(Item item, TownspersonMob npc) {
        for (ProductionRecipe r : BlacksmithCrafts.crafting()) {
            if (r.output() != item) continue;
            if (!BlacksmithCrafts.meetsSkillGate(r, npc)) continue;
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The first smelting-bin recipe whose output is {@code item}, gated by the
     *  NPC's skills. Empty if none / under-skilled. */
    public static Optional<ProductionRecipe> smeltRecipeFor(Item item, TownspersonMob npc) {
        for (ProductionRecipe r : BlacksmithCrafts.smelting()) {
            if (r.output() != item) continue;
            if (!BlacksmithCrafts.meetsSkillGate(r, npc)) continue;
            return Optional.of(r);
        }
        return Optional.empty();
    }
}
