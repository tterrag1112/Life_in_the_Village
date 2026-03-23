// src/main/java/tterrag1112/life_in_the_village/Datagen/KingdomTitleDatagen.java
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
        futures.add(save(cache, buildImperial()));
        futures.add(save(cache, buildHighland()));
        futures.add(save(cache, buildBookworm()));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // ── default — classic feudal monarchy ────────────────────────────────────

    private JsonObject buildDefault() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "default");
        JsonObject t = new JsonObject();
        t.addProperty("ruler_male",    "King");
        t.addProperty("ruler_female",  "Queen");
        t.addProperty("lord_male",     "Lord");
        t.addProperty("lord_female",   "Lady");
        t.addProperty("knight_male",   "Sir");
        t.addProperty("knight_female", "Dame");
        t.addProperty("citizen",       "Citizen");
        json.add("titles", t);
        return json;
    }

    // ── imperial — Roman/Byzantine inspired ──────────────────────────────────
    // The Emperor rules from a grand palace complex. Consuls administer
    // provinces. Legates command the armies. Citizens owe loyalty to the
    // Empire above all else.

    private JsonObject buildImperial() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "imperial");
        JsonObject t = new JsonObject();
        t.addProperty("ruler_male",    "Emperor");
        t.addProperty("ruler_female",  "Empress");
        t.addProperty("lord_male",     "Consul");
        t.addProperty("lord_female",   "Consul");
        t.addProperty("knight_male",   "Legate");
        t.addProperty("knight_female", "Legate");
        t.addProperty("citizen",       "Subject");
        json.add("titles", t);
        return json;
    }

    // ── highland — Celtic/Norse clan society ─────────────────────────────────
    // The High Chieftain rules by strength and bloodline. Thanes control
    // the clan territories. Champions prove themselves in battle.
    // Every clansman owes loyalty to their Thane.

    private JsonObject buildHighland() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "highland");
        JsonObject t = new JsonObject();
        t.addProperty("ruler_male",    "High Chieftain");
        t.addProperty("ruler_female",  "High Chieftain");
        t.addProperty("lord_male",     "Thane");
        t.addProperty("lord_female",   "Thane");
        t.addProperty("knight_male",   "Champion");
        t.addProperty("knight_female", "Champion");
        t.addProperty("citizen",       "Clansman");
        json.add("titles", t);
        return json;
    }

    // ── bookworm — scholar-ruled enlightened republic ─────────────────────────

    private JsonObject buildBookworm() {
        JsonObject json = new JsonObject();
        json.addProperty("culture", "bookworm");
        JsonObject t = new JsonObject();
        t.addProperty("ruler_male",    "Archduke");
        t.addProperty("ruler_female",  "Archduchess");
        t.addProperty("lord_male",     "Magister");
        t.addProperty("lord_female",   "Magistra");
        t.addProperty("knight_male",   "Warden");
        t.addProperty("knight_female", "Warden");
        t.addProperty("citizen",       "Scholar");
        json.add("titles", t);
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