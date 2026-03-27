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

public class CastleStyleDatagen implements DataProvider {

    private final PackOutput output;

    public CastleStyleDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        futures.add(save(cache, buildNorman()));
       // futures.add(save(cache, buildMoorish()));
        //futures.add(save(cache, buildFantasy()));
        //futures.add(save(cache, buildRuin()));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // -------------------------------------------------------------------------
    // Norman — solid square towers, thick walls, stone brick
    // -------------------------------------------------------------------------

   private JsonObject buildNorman() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "norman");

        JsonObject layout = new JsonObject();
        layout.addProperty("plan_type", "square");
        layout.addProperty("min_radius", 24);
        layout.addProperty("max_radius", 32);
        json.add("layout", layout);

        JsonObject walls = new JsonObject();
        walls.addProperty("min_wall_height", 10);
        walls.addProperty("max_wall_height", 14);
        walls.addProperty("wall_thickness", 3);
        json.add("walls", walls);

        JsonObject towers = new JsonObject();
        towers.addProperty("tower_shape", "square");
        towers.addProperty("min_tower_radius", 4);
        towers.addProperty("max_tower_radius", 6);
        towers.addProperty("tower_height_bonus", 4);
        towers.addProperty("tower_frequency", 0.6f);
        json.add("towers", towers);

        JsonObject donjon = new JsonObject();
        donjon.addProperty("has_donjon", true);
        donjon.addProperty("donjon_min_size", 5);
        donjon.addProperty("donjon_max_size", 7);
        donjon.addProperty("donjon_height_bonus", 6);
        donjon.addProperty("has_inner_ward", false);
        donjon.addProperty("inner_ward_scale", 0.5f);
        json.add("donjon", donjon);

        JsonObject features = new JsonObject();
        features.addProperty("has_moat", true);
        features.addProperty("moat_width", 4);
        features.addProperty("moat_depth", 3);
        features.addProperty("has_portcullis", true);
        features.addProperty("has_drawbridge", true);
        features.addProperty("add_torches", true);
        features.addProperty("add_flags", true);
        json.add("features", features);

        JsonObject pools = new JsonObject();
        pools.addProperty("battlement_pool",  Life_in_the_village.MODID + ":castle/battlements/norman");
        pools.addProperty("gatehouse_pool",   Life_in_the_village.MODID + ":castle/gatehouses/norman");
        pools.addProperty("window_pool",      Life_in_the_village.MODID + ":castle/windows/norman");
        pools.addProperty("tower_roof_pool",  Life_in_the_village.MODID + ":castle/tower_roofs/norman");
        pools.addProperty("donjon_roof_pool", Life_in_the_village.MODID + ":castle/donjon_roofs/norman");
        pools.addProperty("interior_pool",    Life_in_the_village.MODID + ":castle/interiors/barracks");
        json.add("pools", pools);

        json.add("primary_material", stoneBrickPalette());
        json.add("accent_material",  stoneBrickPalette());
        json.add("floor_material",   stoneBrickPalette());
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1001L);

        return json;
    }

    // -------------------------------------------------------------------------
    // Moorish — polygonal towers, sandstone, inner ward, no moat
    // -------------------------------------------------------------------------

    /*
    private JsonObject buildMoorish() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "moorish");
        json.addProperty("plan_type", "polygon");
        json.addProperty("min_radius", 28);
        json.addProperty("max_radius", 38);
        json.addProperty("min_wall_height", 8);
        json.addProperty("max_wall_height", 12);
        json.addProperty("wall_thickness", 2);
        json.addProperty("tower_shape", "polygonal");
        json.addProperty("min_tower_radius", 3);
        json.addProperty("max_tower_radius", 5);
        json.addProperty("tower_height_bonus", 5);
        json.addProperty("tower_frequency", 0.8f);
        json.addProperty("has_donjon", true);
        json.addProperty("donjon_min_size", 4);
        json.addProperty("donjon_max_size", 6);
        json.addProperty("donjon_height_bonus", 4);
        json.addProperty("has_inner_ward", true);
        json.addProperty("inner_ward_scale", 0.5f);
        json.addProperty("has_moat", false);
        json.addProperty("moat_width", 0);
        json.addProperty("moat_depth", 0);
        json.addProperty("has_portcullis", false);
        json.addProperty("has_drawbridge", false);
        json.add("primary_material",  sandstonePalette());
        json.add("accent_material",   sandstoneAccentPalette());
        json.add("floor_material",    sandstonePalette());
        json.addProperty("battlement_pool",  Life_in_the_village.MODID + ":castle/battlements/moorish");
        json.addProperty("gatehouse_pool",   Life_in_the_village.MODID + ":castle/gatehouses/moorish");
        json.addProperty("window_pool",      Life_in_the_village.MODID + ":castle/windows/moorish");
        json.addProperty("tower_roof_pool",  Life_in_the_village.MODID + ":castle/tower_roofs/moorish");
        json.addProperty("donjon_roof_pool", Life_in_the_village.MODID + ":castle/donjon_roofs/moorish");
        json.addProperty("interior_pool",    Life_in_the_village.MODID + ":castle/interiors/empty");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("add_torches", true);
        json.addProperty("add_flags", true);
        json.addProperty("style_seed", 2002L);
        return json;
    }

    // -------------------------------------------------------------------------
    // Fantasy — round towers, deep slate, tall spires, inner ward + moat
    // -------------------------------------------------------------------------

    private JsonObject buildFantasy() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "fantasy");
        json.addProperty("plan_type", "irregular");
        json.addProperty("min_radius", 30);
        json.addProperty("max_radius", 42);
        json.addProperty("min_wall_height", 12);
        json.addProperty("max_wall_height", 18);
        json.addProperty("wall_thickness", 2);
        json.addProperty("tower_shape", "round");
        json.addProperty("min_tower_radius", 4);
        json.addProperty("max_tower_radius", 7);
        json.addProperty("tower_height_bonus", 8);
        json.addProperty("tower_frequency", 0.9f);
        json.addProperty("has_donjon", true);
        json.addProperty("donjon_min_size", 6);
        json.addProperty("donjon_max_size", 9);
        json.addProperty("donjon_height_bonus", 10);
        json.addProperty("has_inner_ward", true);
        json.addProperty("inner_ward_scale", 0.45f);
        json.addProperty("has_moat", true);
        json.addProperty("moat_width", 5);
        json.addProperty("moat_depth", 4);
        json.addProperty("has_portcullis", true);
        json.addProperty("has_drawbridge", true);
        json.add("primary_material",  deepslatePalette());
        json.add("accent_material",   deepslatePalette());
        json.add("floor_material",    deepslatePalette());
        json.addProperty("battlement_pool",  Life_in_the_village.MODID + ":castle/battlements/fantasy");
        json.addProperty("gatehouse_pool",   Life_in_the_village.MODID + ":castle/gatehouses/fantasy");
        json.addProperty("window_pool",      Life_in_the_village.MODID + ":castle/windows/fantasy");
        json.addProperty("tower_roof_pool",  Life_in_the_village.MODID + ":castle/tower_roofs/fantasy");
        json.addProperty("donjon_roof_pool", Life_in_the_village.MODID + ":castle/donjon_roofs/fantasy");
        json.addProperty("interior_pool",    Life_in_the_village.MODID + ":castle/interiors/storage");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("add_torches", true);
        json.addProperty("add_flags", true);
        json.addProperty("style_seed", 3003L);
        return json;
    }

    // -------------------------------------------------------------------------
    // Ruin — heavily collapsed, no moat, no portcullis
    // -------------------------------------------------------------------------

    private JsonObject buildRuin() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "ruin");
        json.addProperty("plan_type", "irregular");
        json.addProperty("min_radius", 20);
        json.addProperty("max_radius", 30);
        json.addProperty("min_wall_height", 6);
        json.addProperty("max_wall_height", 10);
        json.addProperty("wall_thickness", 2);
        json.addProperty("tower_shape", "round");
        json.addProperty("min_tower_radius", 3);
        json.addProperty("max_tower_radius", 5);
        json.addProperty("tower_height_bonus", 2);
        json.addProperty("tower_frequency", 0.5f);
        json.addProperty("has_donjon", false);
        json.addProperty("donjon_min_size", 4);
        json.addProperty("donjon_max_size", 6);
        json.addProperty("donjon_height_bonus", 4);
        json.addProperty("has_inner_ward", false);
        json.addProperty("inner_ward_scale", 0.5f);
        json.addProperty("has_moat", false);
        json.addProperty("moat_width", 0);
        json.addProperty("moat_depth", 0);
        json.addProperty("has_portcullis", false);
        json.addProperty("has_drawbridge", false);
        json.add("primary_material",  stoneBrickPalette());
        json.add("accent_material",   stoneBrickPalette());
        json.add("floor_material",    stoneBrickPalette());
        json.addProperty("battlement_pool",  Life_in_the_village.MODID + ":castle/battlements/ruin");
        json.addProperty("gatehouse_pool",   Life_in_the_village.MODID + ":castle/gatehouses/ruin");
        json.addProperty("window_pool",      Life_in_the_village.MODID + ":castle/windows/norman");
        json.addProperty("tower_roof_pool",  Life_in_the_village.MODID + ":castle/tower_roofs/ruin");
        json.addProperty("donjon_roof_pool", Life_in_the_village.MODID + ":castle/donjon_roofs/ruin");
        json.addProperty("interior_pool",    Life_in_the_village.MODID + ":castle/interiors/empty");
        json.addProperty("ruination_level", 0.65f);
        json.addProperty("add_torches", false);
        json.addProperty("add_flags", false);
        json.addProperty("style_seed", 4004L);
        return json;
    }

     */

    // -------------------------------------------------------------------------
    // Material palette helpers
    // -------------------------------------------------------------------------

    private JsonObject stoneBrickPalette() {
        JsonObject p = new JsonObject();
        p.add("main_blocks", weightedBlocks(
                block("minecraft:stone_bricks",         70),
                block("minecraft:mossy_stone_bricks",   20),
                block("minecraft:cracked_stone_bricks", 10)
        ));
        p.add("cracked_variants", weightedBlocks(
                block("minecraft:cracked_stone_bricks", 60),
                block("minecraft:cobblestone",          40)
        ));
        p.addProperty("stair_block", "minecraft:stone_brick_stairs");
        p.addProperty("slab_block",  "minecraft:stone_brick_slab");
        p.addProperty("wall_block",  "minecraft:stone_brick_wall");
        return p;
    }

    private JsonObject sandstonePalette() {
        JsonObject p = new JsonObject();
        p.add("main_blocks", weightedBlocks(
                block("minecraft:sandstone",         60),
                block("minecraft:smooth_sandstone",  30),
                block("minecraft:chiseled_sandstone",10)
        ));
        p.add("cracked_variants", weightedBlocks(
                block("minecraft:sandstone", 80),
                block("minecraft:sand",      20)
        ));
        p.addProperty("stair_block", "minecraft:sandstone_stairs");
        p.addProperty("slab_block",  "minecraft:sandstone_slab");
        p.addProperty("wall_block",  "minecraft:sandstone_wall");
        return p;
    }

    private JsonObject sandstoneAccentPalette() {
        JsonObject p = new JsonObject();
        p.add("main_blocks", weightedBlocks(
                block("minecraft:chiseled_sandstone", 100)
        ));
        p.add("cracked_variants", weightedBlocks(
                block("minecraft:sandstone", 100)
        ));
        p.addProperty("stair_block", "minecraft:sandstone_stairs");
        p.addProperty("slab_block",  "minecraft:sandstone_slab");
        p.addProperty("wall_block",  "minecraft:sandstone_wall");
        return p;
    }

    private JsonObject deepslatePalette() {
        JsonObject p = new JsonObject();
        p.add("main_blocks", weightedBlocks(
                block("minecraft:deepslate_bricks",         65),
                block("minecraft:deepslate_tiles",          25),
                block("minecraft:cracked_deepslate_bricks", 10)
        ));
        p.add("cracked_variants", weightedBlocks(
                block("minecraft:cracked_deepslate_bricks", 50),
                block("minecraft:cobbled_deepslate",        50)
        ));
        p.addProperty("stair_block", "minecraft:deepslate_brick_stairs");
        p.addProperty("slab_block",  "minecraft:deepslate_brick_slab");
        p.addProperty("wall_block",  "minecraft:deepslate_brick_wall");
        return p;
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    /** Build a weighted block entry: { "block": "...", "weight": n } */
    private JsonObject block(String id, int weight) {
        JsonObject o = new JsonObject();
        o.addProperty("block", id);
        o.addProperty("weight", weight);
        return o;
    }

    /** Wrap multiple weighted block entries into a JsonArray. */
    private JsonArray weightedBlocks(JsonObject... entries) {
        JsonArray arr = new JsonArray();
        for (JsonObject e : entries) arr.add(e);
        return arr;
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private CompletableFuture<?> save(CachedOutput cache, JsonObject json) {
        String id = json.get("style_id").getAsString();
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID + "/castle_styles/" + id + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Castle Styles";
    }
}