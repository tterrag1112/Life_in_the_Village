package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Phase 6.3.3.d — descriptor for a kind of building roster. Content
 * passes register concrete instances in {@link RosterRegistry} at mod
 * init; {@link BuildingRoster} holds a reference to the definition's
 * id and resolves the metadata at tick time.
 *
 * <p>No definitions are registered in 6.3.3.d itself. 6.3.3.g (animal
 * husbandry content pass) populates the registry.
 *
 * @param id                    unique identifier (e.g. lit:roster/chicken)
 * @param entityType            Minecraft entity type for the realized form
 * @param defaultPopulation     starting count when a building first hosts
 *                              this roster
 * @param maxPopulation         soft cap; breeding suppressed beyond this
 * @param productionPeriodTicks how often production fires (e.g. 24000 for daily)
 * @param productionOutputs     items produced per adult per cycle
 * @param breedingRule          optional reproduction rule
 * @param slaughterOutputs      items dropped when an adult is slaughtered
 * @param growthPeriodTicks     baby → adult conversion time
 */
public record RosterDefinition(Identifier id,
                               EntityType<?> entityType,
                               int defaultPopulation,
                               int maxPopulation,
                               long productionPeriodTicks,
                               List<ItemStack> productionOutputs,
                               Optional<BreedingRule> breedingRule,
                               List<ItemStack> slaughterOutputs,
                               long growthPeriodTicks) {

    public RosterDefinition {
        productionOutputs = List.copyOf(productionOutputs == null ? List.of() : productionOutputs);
        slaughterOutputs  = List.copyOf(slaughterOutputs  == null ? List.of() : slaughterOutputs);
        if (defaultPopulation < 0) defaultPopulation = 0;
        if (maxPopulation < defaultPopulation) maxPopulation = defaultPopulation;
        if (productionPeriodTicks < 1L) productionPeriodTicks = 24000L;
        if (growthPeriodTicks < 0L) growthPeriodTicks = 0L;
        if (breedingRule == null) breedingRule = Optional.empty();
    }
}
