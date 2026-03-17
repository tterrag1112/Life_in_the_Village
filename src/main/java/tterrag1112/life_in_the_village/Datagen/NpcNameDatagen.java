package tterrag1112.life_in_the_village.Datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NpcNameDatagen implements DataProvider {

    private final PackOutput output;

    public NpcNameDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(net.minecraft.data.CachedOutput cache) {
        List<CompletableFuture<?>> futures = new java.util.ArrayList<>();

        // Default culture
        futures.add(saveFirstNames(cache, "default",
                List.of("William", "Thomas", "Henry", "Edmund", "Walter",
                        "Richard", "Robert", "John", "Geoffrey", "Hugh",
                        "Alfred", "Roger", "Simon", "Stephen", "Nicholas"),
                List.of("Alice", "Margaret", "Agnes", "Eleanor", "Matilda",
                        "Joan", "Isabel", "Emma", "Cecily", "Beatrice",
                        "Maud", "Anne", "Catherine", "Elizabeth", "Rose")
        ));

        futures.add(saveSurnames(cache, "default",
                List.of("Miller", "Smith", "Cooper", "Fletcher", "Thatcher",
                        "Baker", "Fisher", "Mason", "Turner", "Walker",
                        "Carter", "Wright", "Potter", "Weaver", "Farmer",
                        "Hunter", "Tanner", "Fuller", "Dyer", "Shepherd")
        ));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<?> saveFirstNames(
            net.minecraft.data.CachedOutput cache,
            String culture, List<String> male, List<String> female) {

        JsonObject json = new JsonObject();

        JsonArray maleArray = new JsonArray();
        male.forEach(maleArray::add);
        json.add("male", maleArray);

        JsonArray femaleArray = new JsonArray();
        female.forEach(femaleArray::add);
        json.add("female", femaleArray);

        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/npc_names/" + culture + "/first_names.json");

        return DataProvider.saveStable(cache, json, path);
    }

    private CompletableFuture<?> saveSurnames(
            net.minecraft.data.CachedOutput cache,
            String culture, List<String> surnames) {

        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        surnames.forEach(array::add);
        json.add("surnames", array);

        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/npc_names/" + culture + "/surnames.json");

        return DataProvider.saveStable(cache, json, path);
    }

    @Override
    public String getName() {
        return "Life in the Village NPC Names";
    }
}
