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
        recipes.add(smelt("minecraft:raw_iron",   "minecraft:iron_ingot",   1, 200));
        recipes.add(smelt("minecraft:raw_gold",   "minecraft:gold_ingot",   1, 200));
        recipes.add(smelt("minecraft:raw_copper", "minecraft:copper_ingot", 1, 200));
        recipes.add(smelt("minecraft:iron_ore",   "minecraft:iron_ingot",   1, 200));
        recipes.add(smelt("minecraft:gold_ore",   "minecraft:gold_ingot",   1, 200));
        json.add("recipes", recipes);
        return json;
    }

    private JsonObject buildCrafting() {
        JsonObject json = new JsonObject();
        JsonArray recipes = new JsonArray();
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_sword",     1, 400));
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_pickaxe",   1, 400));
        recipes.add(craft("minecraft:iron_ingot", 3, "minecraft:iron_axe",       1, 400));
        recipes.add(craft("minecraft:iron_ingot", 5, "minecraft:iron_helmet",    1, 600));
        recipes.add(craft("minecraft:iron_ingot", 8, "minecraft:iron_chestplate",1, 800));
        recipes.add(craft("minecraft:iron_ingot", 7, "minecraft:iron_leggings",  1, 700));
        recipes.add(craft("minecraft:iron_ingot", 4, "minecraft:iron_boots",     1, 500));
        recipes.add(craft("minecraft:gold_ingot", 3, "minecraft:golden_sword",   1, 400));
        recipes.add(craft("minecraft:gold_ingot", 5, "minecraft:golden_helmet",  1, 600));
        json.add("recipes", recipes);
        return json;
    }

    private JsonObject smelt(String input, String output,
                             int count, int ticks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("input", input);
        obj.addProperty("output", output);
        obj.addProperty("count", count);
        obj.addProperty("ticks", ticks);
        return obj;
    }

    private JsonObject craft(String input, int inputCount,
                             String output, int count, int ticks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("input", input);
        obj.addProperty("input_count", inputCount);
        obj.addProperty("output", output);
        obj.addProperty("count", count);
        obj.addProperty("ticks", ticks);
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