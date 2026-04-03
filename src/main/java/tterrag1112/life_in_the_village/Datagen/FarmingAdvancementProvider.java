// New file: src/main/java/tterrag1112/life_in_the_village/Datagen/FarmingAdvancementProvider.java

package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.criterion.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.advancements.AdvancementProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Farming progression advancement tree.
 */
/*public class FarmingAdvancementProvider extends AdvancementProvider {

    public FarmingAdvancementProvider(PackOutput output,
                                      CompletableFuture<HolderLookup.Provider> registries,
                                      ExistingFileHelper existingFileHelper) {
        super(output, registries, existingFileHelper, List.of(new FarmingAdvancements()));
    }

    public static class FarmingAdvancements implements AdvancementGenerator {

        @Override
        public void generate(HolderLookup.Provider registries, Consumer<AdvancementHolder> consumer, ExistingFileHelper existingFileHelper) {
            // Root advancement
            AdvancementHolder root = Advancement.Builder.advancement()
                    .display(
                            Items.WHEAT,
                            Component.literal("First Harvest"),
                            Component.literal("Harvest your first crop as a farmhand"),
                            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID,
                                    "textures/gui/advancements/backgrounds/farming.png"),
                            AdvancementType.TASK,
                            true, true, false
                    )
                    .addCriterion("harvest_crop",
                            ItemUsedOnLocationTrigger.TriggerInstance.itemUsedOnBlock(
                                    LocationPredicate.Builder.location(),
                                    ItemPredicate.Builder.item().of(Items.WHEAT)
                            ))
                    .save(consumer, id("farming/root"));

            // Farmhand wages
            AdvancementHolder wages = Advancement.Builder.advancement()
                    .parent(root)
                    .display(
                            Items.GOLD_INGOT,
                            Component.literal("Farmhand Wages"),
                            Component.literal("Complete your first farming task and receive payment"),
                            null,
                            AdvancementType.TASK,
                            true, true, false
                    )
                    .addCriterion("complete_task",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/first_wage"));

            // Animal husbandry
            AdvancementHolder animals = Advancement.Builder.advancement()
                    .parent(root)
                    .display(
                            Items.WHEAT_SEEDS,
                            Component.literal("Animal Husbandry"),
                            Component.literal("Breed your first animal"),
                            null,
                            AdvancementType.TASK,
                            true, true, false
                    )
                    .addCriterion("breed_animal",
                            BredAnimalsTrigger.TriggerInstance.bredAnimals())
                    .save(consumer, id("farming/breed_animal"));

            // Market maven
            AdvancementHolder market = Advancement.Builder.advancement()
                    .parent(wages)
                    .display(
                            Items.EMERALD,
                            Component.literal("Market Maven"),
                            Component.literal("Sell 500 coins worth of farm goods"),
                            null,
                            AdvancementType.CHALLENGE,
                            true, true, false
                    )
                    .addCriterion("sell_goods",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/market_maven"));

            // Business owner
            AdvancementHolder owner = Advancement.Builder.advancement()
                    .parent(market)
                    .display(
                            Items.OAK_DOOR,
                            Component.literal("Business Owner"),
                            Component.literal("Purchase your first farm"),
                            null,
                            AdvancementType.GOAL,
                            true, true, false
                    )
                    .addCriterion("buy_farm",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/buy_farm"));

            // Agricultural empire
            AdvancementHolder empire = Advancement.Builder.advancement()
                    .parent(owner)
                    .display(
                            Items.GOLDEN_HOE,
                            Component.literal("Agricultural Empire"),
                            Component.literal("Reach Business Level 5 on a farm"),
                            null,
                            AdvancementType.CHALLENGE,
                            true, true, true
                    )
                    .addCriterion("level_5",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/empire"));

            // Quality farmer
            AdvancementHolder quality = Advancement.Builder.advancement()
                    .parent(owner)
                    .display(
                            Items.BONE_MEAL,
                            Component.literal("Quality Farmer"),
                            Component.literal("Achieve 'Exceptional' quality on a crop"),
                            null,
                            AdvancementType.TASK,
                            true, true, false
                    )
                    .addCriterion("exceptional_quality",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/quality_farmer"));

            // Master breeder
            AdvancementHolder breeder = Advancement.Builder.advancement()
                    .parent(animals)
                    .display(
                            Items.LEAD,
                            Component.literal("Master Breeder"),
                            Component.literal("Have 10 animals in a single pen"),
                            null,
                            AdvancementType.TASK,
                            true, true, false
                    )
                    .addCriterion("ten_animals",
                            PlayerTrigger.TriggerInstance.located(
                                    LocationPredicate.Builder.location()
                            ))
                    .save(consumer, id("farming/master_breeder"));
        }

        private static Identifier id(String path) {
            return Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
        }
    }
}

 */