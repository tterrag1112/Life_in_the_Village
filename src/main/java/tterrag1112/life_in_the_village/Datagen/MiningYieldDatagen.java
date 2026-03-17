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

public class MiningYieldDatagen implements DataProvider {

    private final PackOutput output;

    public MiningYieldDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.add(saveYields(cache, buildDefault()));
        return CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
    }

    private JsonObject buildDefault() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "default");

        JsonArray yields = new JsonArray();
        yields.add(entry("minecraft:cobblestone",  "COMMON",   2, 8));
        yields.add(entry("minecraft:gravel",        "COMMON",   1, 4));
        yields.add(entry("minecraft:flint",         "COMMON",   1, 3));
        yields.add(entry("minecraft:coal",          "COMMON",   1, 4));
        yields.add(entry("minecraft:raw_iron",      "UNCOMMON", 1, 3));
        yields.add(entry("minecraft:raw_copper",    "UNCOMMON", 1, 3));
        yields.add(entry("minecraft:raw_gold",      "RARE",     1, 2));
        yields.add(entry("minecraft:redstone",      "RARE",     1, 4));
        yields.add(entry("minecraft:lapis_lazuli",  "RARE",     1, 3));
        yields.add(entry("minecraft:diamond",       "EPIC",     1, 1));
        yields.add(entry("minecraft:emerald",       "EPIC",     1, 1));
        json.add("yields", yields);

        return json;
    }

    private JsonObject entry(String item, String rarity, int min, int max) {
        JsonObject obj = new JsonObject();
        obj.addProperty("item", item);
        obj.addProperty("rarity", rarity);
        obj.addProperty("min", min);
        obj.addProperty("max", max);
        return obj;
    }

    private CompletableFuture<?> saveYields(CachedOutput cache,
                                            JsonObject json) {
        String type = json.get("type").getAsString();
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/mining_yields/" + type + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Life in the Village Mining Yields";
    }
}