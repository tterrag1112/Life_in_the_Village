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

public class VillageTypeDatagen implements DataProvider {

    private final PackOutput output;

    public VillageTypeDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        futures.add(saveVillageType(cache, buildDefault()));
        futures.add(saveVillageType(cache, buildFarmingVillage()));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private JsonObject buildDefault() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "default");
        json.addProperty("culture", "default");

        JsonArray buildings = new JsonArray();
        buildings.add(building("TOWN_HALL",  "town_hall/level_1",  0,  0,  0));
        buildings.add(building("STOCKPILE",  "stockpile/level_1",  20, 0,  0));
        buildings.add(building("FARMHOUSE",  "farmhouse/level_1",  0,  0,  20));
        buildings.add(building("HOUSE",      "house/level_1",      -20, 0, 0));
        buildings.add(building("GUILD_HALL", "house/level_1", 20, 0, 40));

        json.add("starter_buildings", buildings);

        JsonArray npcs = new JsonArray();
        npcs.add(npc("BUILDER",          "TOWN_HALL",  "HEAD"));
        npcs.add(npc("VILLAGE_LEADER", "TOWN_HALL", "HEAD"));
        npcs.add(npc("FARMER",           "FARMHOUSE",  "HEAD"));
        npcs.add(npc("GUARD",            "TOWN_HALL",  "UNASSIGNED"));
        npcs.add(npc("STOCKPILE_KEEPER", "STOCKPILE",  "HEAD"));
        npcs.add(npc("NONE",             "HOUSE",      "HEAD"));
        npcs.add(npc("GUILDWORKER", "GUILD_HALL", "UNASSIGNED"));
        npcs.add(npc("GUILDMASTER", "GUILD_HALL", "HEAD"));
        json.add("starter_npcs", npcs);

        JsonArray items = new JsonArray();
        items.add(item("STOCKPILE", "minecraft:wheat_seeds", 64));
        items.add(item("STOCKPILE", "minecraft:oak_log",     64));
        items.add(item("STOCKPILE", "minecraft:cobblestone", 128));
        json.add("starter_items", items);

        return json;
    }

    private JsonObject buildFarmingVillage() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "farming_village");
        json.addProperty("culture", "default");

        JsonArray buildings = new JsonArray();
        buildings.add(building("TOWN_HALL",  "town_hall/level_1",  0,   0, 0));
        buildings.add(building("STOCKPILE",  "stockpile/level_1",  20,  0, 0));
        buildings.add(building("FARMHOUSE",  "farmhouse/level_1",  0,   0, 20));
        buildings.add(building("FARMHOUSE",  "farmhouse/level_1",  20,  0, 20));
        buildings.add(building("HOUSE",      "house/level_1",      -20, 0, 0));
        buildings.add(building("MARKET", "market/level_1", 40, 0, 0));
        json.add("starter_buildings", buildings);

        JsonArray npcs = new JsonArray();
        npcs.add(npc("BUILDER",          "TOWN_HALL",  "HEAD"));
        npcs.add(npc("FARMER",           "FARMHOUSE",  "HEAD"));
        npcs.add(npc("FARMER",           "FARMHOUSE",  "HEAD"));
        npcs.add(npc("STOCKPILE_KEEPER", "STOCKPILE",  "HEAD"));
        npcs.add(npc("NONE",             "HOUSE",      "HEAD"));
        npcs.add(npc("MERCHANT", "MARKET", "HEAD"));
        json.add("starter_npcs", npcs);

        JsonArray items = new JsonArray();
        items.add(item("STOCKPILE", "minecraft:wheat_seeds",  128));
        items.add(item("STOCKPILE", "minecraft:carrot",       64));
        items.add(item("STOCKPILE", "minecraft:potato",       64));
        items.add(item("STOCKPILE", "minecraft:oak_log",      64));
        items.add(item("STOCKPILE", "minecraft:cobblestone",  128));
        json.add("starter_items", items);

        return json;
    }

    // --- Helpers ---

    private JsonObject building(String type, String structure,
                                int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("structure", structure);
        JsonArray offset = new JsonArray();
        offset.add(x); offset.add(y); offset.add(z);
        obj.add("offset", offset);
        return obj;
    }

    private JsonObject npc(String profession, String buildingType,
                           String familyRole) {
        JsonObject obj = new JsonObject();
        obj.addProperty("profession", profession);
        obj.addProperty("building_type", buildingType);
        obj.addProperty("family_role", familyRole);
        return obj;
    }

    private JsonObject item(String buildingType, String itemId, int count) {
        JsonObject obj = new JsonObject();
        obj.addProperty("building_type", buildingType);
        obj.addProperty("item", itemId);
        obj.addProperty("count", count);
        return obj;
    }

    private CompletableFuture<?> saveVillageType(CachedOutput cache,
                                                 JsonObject json) {
        String type = json.get("type").getAsString();
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/village_types/" + type + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Life in the Village Village Types";
    }
}
