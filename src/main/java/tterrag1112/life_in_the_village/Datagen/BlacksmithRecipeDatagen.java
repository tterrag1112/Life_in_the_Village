package tterrag1112.life_in_the_village.Datagen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BlacksmithRecipeDatagen implements DataProvider {

    private final PackOutput output;

    public BlacksmithRecipeDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.add(save(cache, buildSmelting(), "smelting"));
        futures.add(save(cache, buildCrafting(), "crafting"));
        return CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
    }

    private JsonObject buildSmelting() {
        JsonObject json = new JsonObject();
        JsonArray recipes = new JsonArray();
        // No gates on basic smelting — iron/copper ingots are the
        // entry-level XP on-ramp every blacksmith does (gate 0). The gate
        // appears at gold tier and up.
        recipes.add(smelt("minecraft:raw_iron",   "minecraft:iron_ingot",   1, 200,  0));
        recipes.add(smelt("minecraft:raw_copper", "minecraft:copper_ingot", 1, 200,  0));
        recipes.add(smelt("minecraft:iron_ore",   "minecraft:iron_ingot",   1, 200,  0));
        // Blacksmith-skill-fix — gold smelt gated at BLACKSMITHING 10
        // (down from 15). A seeded smith (BLACKSMITHING 15-35) smelts gold
        // immediately; a low/fresh smith grows into it off iron/copper.
        recipes.add(smelt("minecraft:raw_gold",   "minecraft:gold_ingot",   1, 200, 10));
        recipes.add(smelt("minecraft:gold_ore",   "minecraft:gold_ingot",   1, 200, 10));
        json.add("recipes", recipes);
        return json;
    }

    private JsonObject buildCrafting() {
        JsonObject json = new JsonObject();
        JsonArray recipes = new JsonArray();
        // Blacksmith-skill-fix — recipes now GATE on the general
        // BLACKSMITHING axis (the seeded skill; see BlacksmithRecipeRegistry)
        // and the levels are lowered + tiered so a fresh/low smith always has
        // accessible work that grows with skill:
        //   iron tools/weapons/armor -> 0  (any smith, incl. fresh, XP on-ramp)
        //   gold gear                -> 10 (seeded smith immediately)
        //   diamond gear (JSON)      -> 20 (grows into it)
        // (Multi-input diamond/netherite masterpieces live inline in
        // SkillRecipes at 50/50/65, intentionally a high-mastery ceiling.)
        // XP from forging still ROUTES to the specialty (TOOLSMITHING /
        // WEAPONSMITHING / ARMORSMITHING) via BlacksmithSpec.craftPlan and
        // cascades 25% up to BLACKSMITHING, so specialties still develop and
        // unlock the masterpieces over time.
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_sword",     1, 400,  0));
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_pickaxe",   1, 400,  0));
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_axe",       1, 400,  0));
        recipes.add(craft("minecraft:iron_ingot", 5, "minecraft:iron_helmet",    1, 600,  0));
        recipes.add(craft("minecraft:iron_ingot", 8, "minecraft:iron_chestplate",1, 800,  0));
        recipes.add(craft("minecraft:iron_ingot", 7, "minecraft:iron_leggings",  1, 700,  0));
        recipes.add(craft("minecraft:iron_ingot", 4, "minecraft:iron_boots",     1, 500,  0));
        recipes.add(craft("minecraft:gold_ingot", 3, "minecraft:golden_sword",   1, 400, 10));
        recipes.add(craft("minecraft:gold_ingot", 5, "minecraft:golden_helmet",  1, 600, 10));

        // Phase 6.6.5.3 — single-input diamond_chestplate stays in JSON
        // (vanilla recipe is genuinely 8 diamonds, no second input).
        // Multi-input masterpieces (DIAMOND_PICKAXE, DIAMOND_SWORD,
        // NETHERITE_INGOT) moved inline to BlacksmithProductionBehavior
        // per the 6.6.5 Option A1 hybrid: single-input → JSON,
        // multi-input → inline ProductionRecipe.of(Map.of(...)).
        recipes.add(craft("minecraft:diamond", 8, "minecraft:diamond_chestplate",1,1000, 20));
        json.add("recipes", recipes);
        return json;
    }

    private JsonObject smelt(String input, String output,
                             int count, int ticks, int minSkillLevel) {
        JsonObject obj = new JsonObject();
        obj.addProperty("input", input);
        obj.addProperty("output", output);
        obj.addProperty("count", count);
        obj.addProperty("ticks", ticks);
        if (minSkillLevel > 0) obj.addProperty("min_skill_level", minSkillLevel);
        return obj;
    }

    private JsonObject craft(String input, int inputCount,
                             String output, int count, int ticks,
                             int minSkillLevel) {
        JsonObject obj = new JsonObject();
        obj.addProperty("input", input);
        obj.addProperty("input_count", inputCount);
        obj.addProperty("output", output);
        obj.addProperty("count", count);
        obj.addProperty("ticks", ticks);
        if (minSkillLevel > 0) obj.addProperty("min_skill_level", minSkillLevel);
        return obj;
    }

    private CompletableFuture<?> save(CachedOutput cache,
                                      JsonObject json, String name) {
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/blacksmith_recipes/" + name + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Life in the Village Blacksmith Recipes";
    }
}