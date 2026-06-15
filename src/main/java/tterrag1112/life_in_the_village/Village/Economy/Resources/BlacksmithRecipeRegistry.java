package tterrag1112.life_in_the_village.Village.Economy.Resources;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.io.InputStreamReader;
import java.util.*;

public class BlacksmithRecipeRegistry extends
        SimplePreparableReloadListener<BlacksmithRecipeData> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BlacksmithRecipeRegistry.class);
    private static final Gson GSON = new Gson();

    public static final BlacksmithRecipeRegistry INSTANCE =
            new BlacksmithRecipeRegistry();

    private BlacksmithRecipeData data = new BlacksmithRecipeData(
            List.of(), List.of());

    private BlacksmithRecipeRegistry() {}

    @Override
    protected BlacksmithRecipeData prepare(ResourceManager manager,
                                           ProfilerFiller profiler) {
        // Phase 6.6.5.2b — JSON parser now produces ProductionRecipe
        // instances directly. Per the 6.6.5 sign-off (Option A1 hybrid),
        // JSON files retain the single-input schema; multi-input
        // masterpieces live inline in BlacksmithProductionBehavior.
        // Skill gating via .withSkillRequirement on the GENERAL
        // BLACKSMITHING axis (Blacksmith-skill-fix); XP routing to the
        // specialty is set later in BlacksmithSpec.craftPlan.
        List<ProductionRecipe> smelting = new ArrayList<>();
        List<ProductionRecipe> crafting = new ArrayList<>();

        // Load smelting
        loadFile(manager, "blacksmith_recipes/smelting.json",
                json -> {
                    for (var el : json.getAsJsonArray("recipes")) {
                        JsonObject r = el.getAsJsonObject();
                        Item input  = getItem(r.get("input").getAsString());
                        Item output = getItem(r.get("output").getAsString());
                        if (input == null || output == null) return;
                        int minSkill = r.has("min_skill_level")
                                ? r.get("min_skill_level").getAsInt() : 0;
                        ProductionRecipe recipe = ProductionRecipe.of(
                                input, 1, output,
                                r.get("count").getAsInt(),
                                r.get("ticks").getAsInt());
                        if (minSkill > 0) {
                            // Blacksmith-skill-fix — gate on the GENERAL
                            // BLACKSMITHING axis (the seeded skill), not the
                            // per-output specialty. XP ROUTING is unchanged:
                            // it lives in BlacksmithSpec.craftPlan /
                            // intermediatePlan (categorize -> specialty / SMELTING),
                            // which still cascades up to BLACKSMITHING.
                            recipe = recipe.withSkillRequirement(Skill.BLACKSMITHING, minSkill);
                        }
                        smelting.add(recipe);
                    }
                });

        // Load crafting
        loadFile(manager, "blacksmith_recipes/crafting.json",
                json -> {
                    for (var el : json.getAsJsonArray("recipes")) {
                        JsonObject r = el.getAsJsonObject();
                        Item input  = getItem(r.get("input").getAsString());
                        Item output = getItem(r.get("output").getAsString());
                        if (input == null || output == null) return;
                        int minSkill = r.has("min_skill_level")
                                ? r.get("min_skill_level").getAsInt() : 0;
                        ProductionRecipe recipe = ProductionRecipe.of(
                                input,
                                r.get("input_count").getAsInt(),
                                output,
                                r.get("count").getAsInt(),
                                r.get("ticks").getAsInt());
                        if (minSkill > 0) {
                            // Blacksmith-skill-fix — gate on the GENERAL
                            // BLACKSMITHING axis (the seeded skill), not the
                            // per-output specialty. XP ROUTING is unchanged:
                            // BlacksmithSpec.craftPlan sets the plan skill to
                            // categorize(output) (TOOLSMITHING / WEAPONSMITHING /
                            // ARMORSMITHING), which cascades 25% -> BLACKSMITHING.
                            recipe = recipe.withSkillRequirement(Skill.BLACKSMITHING, minSkill);
                        }
                        crafting.add(recipe);
                    }
                });

        LOGGER.info("Loaded {} smelting and {} crafting recipes",
                smelting.size(), crafting.size());
        return new BlacksmithRecipeData(smelting, crafting);
    }

    @Override
    protected void apply(BlacksmithRecipeData prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.data = prepared;
    }

    public void loadFromServer(ResourceManager manager) {
        apply(prepare(manager, null), manager, null);
    }

    public BlacksmithRecipeData getData() { return data; }

    private void loadFile(ResourceManager manager, String path,
                          java.util.function.Consumer<JsonObject> consumer) {
        Identifier loc = Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID, path);
        manager.getResource(loc).ifPresent(resource -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class);
                consumer.accept(json);
            } catch (Exception e) {
                LOGGER.error("Failed to load {}: {}", path, e.getMessage());
            }
        });
    }

    private Item getItem(String id) {
        return BuiltInRegistries.ITEM
                .getOptional(Identifier.parse(id))
                .orElse(null);
    }
}