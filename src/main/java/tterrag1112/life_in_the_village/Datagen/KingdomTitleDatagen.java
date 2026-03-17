package tterrag1112.life_in_the_village.Datagen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class KingdomTitleDatagen implements DataProvider {

    private final PackOutput output;

    public KingdomTitleDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        futures.add(save(cache, buildDefault()));
        futures.add(save(cache, buildBookworm()));


        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private JsonObject buildDefault() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "default");
        JsonObject titles = new JsonObject();
        titles.addProperty("ruler_male",   "King");
        titles.addProperty("ruler_female", "Queen");
        titles.addProperty("lord_male",    "Lord");
        titles.addProperty("lord_female",  "Lady");
        titles.addProperty("knight_male",  "Sir");
        titles.addProperty("knight_female","Dame");
        titles.addProperty("citizen",      "Citizen");
        json.add("titles", titles);
        return json;
    }
    private JsonObject buildBookworm() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "bookworm");
        JsonObject titles = new JsonObject();
        titles.addProperty("ruler_male",   "Kind");
        titles.addProperty("ruler_female", "Queen");
        titles.addProperty("lord_male",    "Archduke");
        titles.addProperty("lord_female",  "Lady");
        titles.addProperty("knight_male",  "Sir");
        titles.addProperty("knight_female","Dame");
        titles.addProperty("citizen",      "Citizen");
        json.add("titles", titles);
        return json;
    }

    private CompletableFuture<?> save(CachedOutput cache, JsonObject json) {
        String culture = json.get("culture").getAsString();
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/kingdom_titles/" + culture + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Life in the Village Kingdom Titles";
    }
}