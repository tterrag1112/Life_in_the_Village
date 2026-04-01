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

/**
 * Generates JSON files for all built-in CastleStyle presets.
 * Output: data/life_in_the_village/castle_styles/<style_id>.json
 *
 * All style definitions live here — there is no CastleStyles.java constant file.
 * Adding a new style means adding a build*() method and registering it in run().
 */
public class CastleStyleDatagen implements DataProvider {

    private final PackOutput output;

    public CastleStyleDatagen(PackOutput output) {
        this.output = output;
    }

    // =========================================================================
    // Registration
    // =========================================================================

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // --- Original four ---
        futures.add(save(cache, buildNorman()));
        futures.add(save(cache, buildMoorish()));
        futures.add(save(cache, buildFantasy()));
        futures.add(save(cache, buildRuin()));

        // --- Historical ---
        futures.add(save(cache, buildJapanese()));
        futures.add(save(cache, buildFrenchGothic()));
        futures.add(save(cache, buildByzantine()));
        futures.add(save(cache, buildEdwardian()));
        futures.add(save(cache, buildMughal()));

        // --- Fantasy ---
        futures.add(save(cache, buildDarkLord()));
        futures.add(save(cache, buildElvish()));
        futures.add(save(cache, buildDwarven()));
        futures.add(save(cache, buildSeaFort()));
        futures.add(save(cache, buildAncientRuin()));
        futures.add(save(cache, buildUndead()));
        futures.add(save(cache, buildCrystalSpire()));
        // In run():
        futures.add(save(cache, buildCattingham()));
        futures.add(save(cache, buildGrandPalace()));
        futures.add(save(cache, buildRusticManor()));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public String getName() {
        return "Castle Styles";
    }

    // =========================================================================
    // Original four styles
    // =========================================================================

    private JsonObject buildNorman() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "norman");
        json.add("layout",   buildLayout("square", 24, 32, 0, true));
        json.add("walls",    buildWalls(10, 14, 3));
        json.add("towers",   buildTowers("square", 4, 6, 4, 0.6f));
        json.add("donjon",   buildDonjon(true, 5, 7, 6, false, 0.5f));
        json.add("features", buildFeatures(
                true, 4, 3, "water",
                true, true, true, false, 0.0f,
                true, true, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "norman", "norman", "barracks"));
        json.addProperty("theme_id",   "norman");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1001L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 2, 1, 0, 16, 6, 2, 2, 2, 2, 0, 0, 0),
                buildNormanSubbuildings(),
                true, true, true, false, false, true));
        json.add("style_detail", buildStyleDetail(
                "crenellated", "pyramidal", "round",
                "arrow_slit", "none", 0,
                true, false, "paved", 1, 1));
        return json;
    }

    private JsonObject buildMoorish() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "moorish");
        json.add("layout",   buildLayout("polygon", 28, 38, 8, false));
        json.add("walls",    buildWalls(8, 12, 2));
        json.add("towers",   buildTowers("polygonal", 3, 5, 5, 0.8f));
        json.add("donjon",   buildDonjon(true, 4, 6, 4, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.5f,
                true, true, false, false));
        json.add("pools", buildPools(
                "moorish", "moorish", "moorish",
                "moorish", "moorish", "empty"));
        json.addProperty("theme_id",   "moorish");
        json.addProperty("palette_id", "sandstone");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 2002L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 1, 1, 6, 4, 1, 4, 1, 0, 0, 4, 2, 1),
                buildMoorishSubbuildings(),
                false, false, true, true, true, false));
        json.add("style_detail", buildStyleDetail(
                "stepped", "pyramidal", "horseshoe",
                "circular", "banded", 4,
                false, true, "fountain", 1, 2));

        return json;
    }

    private JsonObject buildFantasy() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "fantasy");
        json.add("layout",   buildLayout("irregular", 30, 42, 0, false));
        json.add("walls",    buildWalls(12, 18, 2));
        json.add("towers",   buildTowers("round", 4, 7, 8, 0.9f));
        json.add("donjon",   buildDonjon(true, 6, 9, 10, true, 0.45f));
        json.add("features", buildFeatures(
                true, 5, 4, "lava",
                true, true, true, true, 0.6f,
                true, true, false, false));
        json.add("pools", buildPools(
                "fantasy", "fantasy", "fantasy",
                "fantasy", "fantasy", "storage"));
        json.addProperty("theme_id",   "fantasy");
        json.addProperty("palette_id", "deepslate");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 3003L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 3, 2, 4, 6, 4, 4, 1, 1, 0, 2, 2, 1),
                buildFantasySubbuildings(),
                true, true, false, true, true, true));
        json.add("style_detail", buildStyleDetail(
                "pointed", "spire", "pointed",
                "wide_arch", "string_course", 5,
                false, false, "paved", 1, 2));
        return json;
    }

    private JsonObject buildRuin() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "ruin");
        json.add("layout",   buildLayout("irregular", 20, 30, 0, false));
        json.add("walls",    buildWalls(6, 10, 2));
        json.add("towers",   buildTowers("round", 3, 5, 2, 0.5f));
        json.add("donjon",   buildDonjon(false, 4, 6, 4, false, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, false, 0.0f,
                false, false, true, true));
        json.add("pools", buildPools(
                "ruin", "ruin", "norman",
                "ruin", "ruin", "empty"));
        json.addProperty("theme_id",   "ruin");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.65f);
        json.addProperty("style_seed", 4004L);
        json.add("function", buildFunction(
                buildRoster(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                buildRuinSubbuildings(),
                false, false, false, false, false, false));
        json.add("style_detail", buildStyleDetail(
                "ruined", "ruined", "round",
                "arrow_slit", "none", 0,
                false, false, "bare", 1, 1));
        return json;
    }

    // =========================================================================
    // Historical styles
    // =========================================================================

    private JsonObject buildJapanese() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "japanese");
        json.add("layout",   buildLayout("concentric", 26, 34, 0, true));
        json.add("walls",    buildWalls(8, 10, 2));
        json.add("towers",   buildTowers("round", 3, 5, 6, 0.7f));
        json.add("donjon",   buildDonjon(true, 5, 7, 14, true, 0.55f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.6f,
                true, true, false, false));
        json.add("pools", buildPools(
                "japanese", "japanese", "japanese",
                "japanese", "japanese", "empty"));
        json.addProperty("theme_id",   "japanese");
        json.addProperty("palette_id", "quartz");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 5005L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 2, 2, 4, 6, 3, 4, 1, 0, 0, 2, 0, 1),
                buildJapaneseSubbuildings(),
                true, false, true, true, false, true));
        json.add("style_detail", buildStyleDetail(
                "parapet", "pagoda", "flat",
                "wide_arch", "string_course", 3,
                false, false, "garden", 2, 1));
        return json;
    }

    private JsonObject buildFrenchGothic() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "french_gothic");
        json.add("layout",   buildLayout("concentric", 28, 36, 0, true));
        json.add("walls",    buildWalls(12, 16, 2));
        json.add("towers",   buildTowers("round", 4, 6, 10, 0.8f));
        json.add("donjon",   buildDonjon(true, 6, 8, 12, true, 0.5f));
        json.add("features", buildFeatures(
                true, 5, 4, "water",
                true, true, true, true, 0.5f,
                true, true, false, false));
        json.add("pools", buildPools(
                "gothic", "gothic", "gothic",
                "gothic", "gothic", "empty"));
        json.addProperty("theme_id",   "french_gothic");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 6006L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 2, 2, 4, 6, 2, 4, 1, 0, 0, 2, 0, 2),
                buildFrenchGothicSubbuildings(),
                false, false, true, true, false, false));
        json.add("style_detail", buildStyleDetail(
                "pointed", "spire", "pointed",
                "wide_arch", "pilasters", 4,
                false, true, "garden", 1, 2));
        return json;
    }

    private JsonObject buildByzantine() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "byzantine");
        json.add("layout",   buildLayout("polygon", 26, 36, 8, false));
        json.add("walls",    buildWalls(9, 13, 3));
        json.add("towers",   buildTowers("polygonal", 4, 6, 6, 0.7f));
        json.add("donjon",   buildDonjon(true, 5, 7, 8, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.4f,
                true, true, false, false));
        json.add("pools", buildPools(
                "byzantine", "byzantine", "byzantine",
                "byzantine", "byzantine", "empty"));
        json.addProperty("theme_id",   "byzantine");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 7007L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 1, 1, 6, 4, 1, 4, 1, 0, 0, 2, 1, 2),
                buildByzantineSubbuildings(),
                false, false, true, true, true, false));
        json.add("style_detail", buildStyleDetail(
                "stepped", "dome", "round",
                "circular", "banded", 3,
                false, true, "fountain", 2, 1));
        return json;
    }

    private JsonObject buildEdwardian() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "edwardian");
        json.add("layout",   buildLayout("concentric", 30, 40, 0, true));
        json.add("walls",    buildWalls(10, 14, 3));
        json.add("towers",   buildTowers("round", 5, 7, 4, 0.9f));
        json.add("donjon",   buildDonjon(false, 4, 6, 0, true, 0.45f));
        json.add("features", buildFeatures(
                true, 5, 3, "water",
                true, true, true, false, 0.0f,
                true, true, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "norman", "norman", "barracks"));
        json.addProperty("theme_id",   "edwardian");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 8008L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 1, 0, 0, 16, 6, 2, 2, 2, 2, 0, 0, 0),
                buildEdwardianSubbuildings(),
                true, false, false, false, false, true));
        json.add("style_detail", buildStyleDetail(
                "crenellated", "flat", "round",
                "arrow_slit", "none", 0,
                true, false, "paved", 1, 1));

        return json;
    }

    private JsonObject buildMughal() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "mughal");
        json.add("layout",   buildLayout("polygon", 28, 38, 8, false));
        json.add("walls",    buildWalls(9, 12, 2));
        json.add("towers",   buildTowers("polygonal", 3, 5, 8, 0.8f));
        json.add("donjon",   buildDonjon(true, 5, 7, 10, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.7f,
                true, true, false, false));
        json.add("pools", buildPools(
                "moorish", "moorish", "moorish",
                "moorish", "moorish", "empty"));
        json.addProperty("theme_id",   "mughal");
        json.addProperty("palette_id", "red_sandstone");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 9009L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 1, 1, 6, 4, 1, 4, 1, 0, 0, 2, 1, 1),
                buildMughalSubbuildings(),
                false, false, true, true, true, false));
        json.add("style_detail", buildStyleDetail(
                "crenellated", "flat", "round",
                "arrow_slit", "none", 0,
                true, false, "paved", 1, 1));

        return json;
    }

    // =========================================================================
    // Fantasy styles
    // =========================================================================

    private JsonObject buildDarkLord() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "dark_lord");
        json.add("layout",   buildLayout("irregular", 32, 44, 0, false));
        json.add("walls",    buildWalls(14, 20, 3));
        json.add("towers",   buildTowers("round", 5, 8, 12, 1.0f));
        json.add("donjon",   buildDonjon(true, 7, 10, 16, true, 0.4f));
        json.add("features", buildFeatures(
                true, 6, 5, "lava",
                true, true, true, true, 0.8f,
                false, true, false, false));
        json.add("pools", buildPools(
                "fantasy", "fantasy", "fantasy",
                "fantasy", "fantasy", "storage"));
        json.addProperty("theme_id",   "dark_lord");
        json.addProperty("palette_id", "blackstone");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1010L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 3, 1, 2, 12, 6, 3, 1, 2, 0, 1, 2, 0),
                buildDarkLordSubbuildings(),
                false, true, false, true, true, true));
        json.add("style_detail", buildStyleDetail(
                "wide", "spire", "pointed",
                "none", "string_course", 6,
                true, false, "paved", 2, 1));
        return json;
    }

    private JsonObject buildElvish() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "elvish");
        json.add("layout",   buildLayout("irregular", 28, 40, 0, false));
        json.add("walls",    buildWalls(10, 14, 2));
        json.add("towers",   buildTowers("round", 3, 5, 10, 0.8f));
        json.add("donjon",   buildDonjon(true, 5, 7, 12, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.6f,
                true, true, false, false));
        json.add("pools", buildPools(
                "elvish", "elvish", "elvish",
                "elvish", "elvish", "empty"));
        json.addProperty("theme_id",   "elvish");
        json.addProperty("palette_id", "oak_wood");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1111L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 1, 1, 6, 4, 1, 4, 1, 0, 0, 4, 2, 1),
                buildElvishSubbuildings(),
                false, false, true, true, true, false));
        json.add("style_detail", buildStyleDetail(
                "parapet", "conical", "tudor",
                "wide_arch", "string_course", 4,
                false, false, "garden", 2, 1));
        return json;
    }

    private JsonObject buildDwarven() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "dwarven");
        json.add("layout",   buildLayout("rectangle", 24, 32, 0,false));
        json.add("walls",    buildWalls(8, 12, 4));
        json.add("towers",   buildTowers("square", 4, 6, 2, 0.4f));
        json.add("donjon",   buildDonjon(true, 6, 8, 4, false, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                true, false, false, false, 0.0f,
                true, false, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "norman", "norman", "storage"));
        json.addProperty("theme_id",   "dwarven");
        json.addProperty("palette_id", "deepslate");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1212L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 2, 1, 3, 10, 4, 4, 2, 3, 1, 2, 1, 0),
                buildDwarvenSubbuildings(),
                true, true, false, true, false, true));
        json.add("style_detail", buildStyleDetail(
                "wide", "flat", "flat",
                "none", "quoins", 0,
                true, true, "paved", 2, 1));
        return json;
    }

    private JsonObject buildSeaFort() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "sea_fort");
        json.add("layout",   buildLayout("polygon", 22, 30, 6, false));
        json.add("walls",    buildWalls(8, 12, 3));
        json.add("towers",   buildTowers("round", 4, 6, 3, 0.9f));
        json.add("donjon",   buildDonjon(true, 5, 7, 6, false, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                true, false, false, false, 0.0f,
                true, true, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "norman", "norman", "barracks"));
        json.addProperty("theme_id",   "sea_fort");
        json.addProperty("palette_id", "prismarine");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1313L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 1, 0, 0, 16, 6, 2, 2, 2, 2, 0, 0, 0),
                buildSeaFortSubbuildings(),
                false, false, false, false, false, true));
        json.add("style_detail", buildStyleDetail(
                "crenellated", "flat", "flat",
                "cross_slit", "none", 0,
                true, false, "paved", 1, 1));

        return json;
    }

    private JsonObject buildAncientRuin() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "ancient_ruin");
        json.add("layout",   buildLayout("irregular", 24, 36, 0, false));
        json.add("walls",    buildWalls(4, 8, 2));
        json.add("towers",   buildTowers("round", 3, 6, 1, 0.6f));
        json.add("donjon",   buildDonjon(true, 4, 7, 2, false, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, false, 0.0f,
                false, false, true, true));
        json.add("pools", buildPools(
                "ruin", "ruin", "norman",
                "ruin", "ruin", "empty"));
        json.addProperty("theme_id",   "ancient_ruin");
        json.addProperty("palette_id", "stone_brick");
        json.addProperty("ruination_level", 0.85f);
        json.addProperty("style_seed", 1414L);
        json.add("function", buildFunction(
                buildRoster(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                buildAncientRuinSubbuildings(),
                false, false, false, false, false, false));
        json.add("style_detail", buildStyleDetail(
                "crenellated", "flat", "flat",
                "cross_slit", "none", 0,
                true, false, "paved", 1, 1));

        return json;
    }

    private JsonObject buildUndead() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "undead");
        json.add("layout",   buildLayout("irregular", 28, 38, 0, false));
        json.add("walls",    buildWalls(11, 16, 2));
        json.add("towers",   buildTowers("round", 4, 7, 8, 0.9f));
        json.add("donjon",   buildDonjon(true, 6, 8, 10, true, 0.45f));
        json.add("features", buildFeatures(
                true, 5, 4, "poison",
                true, false, false, true, 0.5f,
                false, true, true, true));
        json.add("pools", buildPools(
                "ruin", "ruin", "norman",
                "fantasy", "fantasy", "empty"));
        json.addProperty("theme_id",   "undead");
        json.addProperty("palette_id", "quartz");
        json.addProperty("ruination_level", 0.3f);
        json.addProperty("style_seed", 1515L);
        json.add("function", buildFunction(
                buildRoster(1, 0, 2, 0, 0, 8, 4, 0, 0, 1, 0, 0, 1, 0),
                buildUndeadSubbuildings(),
                false, true, false, false, true, false));
        json.add("style_detail", buildStyleDetail(
                "ruined", "ruined", "pointed",
                "cross_slit", "string_course", 5,
                false, false, "bare", 1, 1));
        return json;
    }

    private JsonObject buildCrystalSpire() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "crystal_spire");
        json.add("layout",   buildLayout("polygon", 26, 36, 6, false));
        json.add("walls",    buildWalls(10, 14, 2));
        json.add("towers",   buildTowers("round", 4, 6, 14, 0.9f));
        json.add("donjon",   buildDonjon(true, 6, 9, 18, true, 0.45f));
        json.add("features", buildFeatures(
                true, 4, 3, "empty",
                false, false, false, true, 0.7f,
                true, true, false, false));
        json.add("pools", buildPools(
                "fantasy", "fantasy", "fantasy",
                "fantasy", "fantasy", "storage"));
        json.addProperty("theme_id",   "crystal_spire");
        json.addProperty("palette_id", "quartz");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1616L);
        json.add("function", buildFunction(
                buildRoster(1, 1, 2, 2, 6, 4, 2, 4, 1, 0, 0, 4, 2, 1),
                buildCrystalSpireSubbuildings(),
                false, false, false, true, true, false));
        json.add("style_detail", buildStyleDetail(
                "pointed", "spire", "pointed",
                "wide_arch", "pilasters", 3,
                false, true, "fountain", 1, 2));
        return json;
    }
    private JsonObject buildCattingham() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "cattingham");
        json.add("layout",   buildLayout("square", 28, 36, 0, true));
        json.add("walls",    buildWalls(10, 14, 2));
        json.add("towers",   buildTowers("round", 4, 6, 6, 0.8f));
        json.add("donjon",   buildDonjon(true, 6, 8, 8, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, false, 0.0f,
                true, true, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "gothic", "gothic", "empty"));
        json.addProperty("theme_id",   "cattingham");
        json.addProperty("palette_id", "formal_palace");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1717L);
        json.add("style_detail", buildStyleDetail(
                "crenellated", "conical", "round",
                "wide_arch", "banded", 3,
                false, true, "fountain", 1, 1));
        json.add("function", buildFunction(
                buildRoster(1, 1, 3, 2, 4, 6, 2, 6, 2, 0, 1, 2, 0, 1),
                buildCattinghamSubbuildings(),
                true, false, true, true, false, false));
        return json;
    }

    private JsonObject buildGrandPalace() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "grand_palace");
        json.add("layout",   buildLayout("concentric", 32, 42, 0, true));
        json.add("walls",    buildWalls(12, 16, 2));
        json.add("towers",   buildTowers("round", 4, 6, 8, 0.9f));
        json.add("donjon",   buildDonjon(true, 7, 9, 10, true, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, true, 0.5f,
                true, true, false, false));
        json.add("pools", buildPools(
                "gothic", "gothic", "gothic",
                "gothic", "gothic", "empty"));
        json.addProperty("theme_id",   "grand_palace");
        json.addProperty("palette_id", "grand_palace");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1818L);
        json.add("style_detail", buildStyleDetail(
                "pointed", "spire", "pointed",
                "wide_arch", "pilasters", 4,
                false, true, "fountain", 1, 2));
        json.add("function", buildFunction(
                buildRoster(1, 1, 4, 3, 6, 6, 2, 8, 2, 0, 0, 3, 1, 2),
                buildGrandPalaceSubbuildings(),
                true, false, true, true, true, false));
        return json;
    }

    private JsonObject buildRusticManor() {
        JsonObject json = new JsonObject();
        json.addProperty("style_id", "rustic_manor");
        json.add("layout",   buildLayout("rectangle", 18, 24, 0, true));
        json.add("walls",    buildWalls(7, 10, 2));
        json.add("towers",   buildTowers("square", 3, 4, 3, 0.6f));
        json.add("donjon",   buildDonjon(true, 4, 6, 4, false, 0.5f));
        json.add("features", buildFeatures(
                false, 0, 0, "empty",
                false, false, false, false, 0.0f,
                true, true, false, false));
        json.add("pools", buildPools(
                "norman", "norman", "norman",
                "norman", "norman", "empty"));
        json.addProperty("theme_id",   "rustic_manor");
        json.addProperty("palette_id", "rustic_manor");
        json.addProperty("ruination_level", 0.0f);
        json.addProperty("style_seed", 1919L);
        json.add("style_detail", buildStyleDetail(
                "crenellated", "pyramidal", "flat",
                "wide_arch", "string_course", 4,
                true, false, "garden", 1, 1));
        json.add("function", buildFunction(
                buildRoster(1, 0, 1, 1, 2, 4, 1, 3, 1, 1, 1, 1, 0, 1),
                buildRusticManorSubbuildings(),
                true, false, true, true, false, false));
        return json;
    }

    // =========================================================================
    // Structural section builders
    // =========================================================================

    private JsonObject buildLayout(String planType, int minRadius, int maxRadius,
                                   int polygonSides, boolean useRoomStack) {
        JsonObject o = new JsonObject();
        o.addProperty("plan_type",     planType);
        o.addProperty("min_radius",    minRadius);
        o.addProperty("max_radius",    maxRadius);
        o.addProperty("polygon_sides", polygonSides);
        o.addProperty("use_room_stack_generation",  useRoomStack);

        return o;
    }

    private JsonObject buildWalls(int minHeight, int maxHeight, int thickness) {
        JsonObject o = new JsonObject();
        o.addProperty("min_wall_height", minHeight);
        o.addProperty("max_wall_height", maxHeight);
        o.addProperty("wall_thickness",  thickness);
        return o;
    }

    private JsonObject buildTowers(String shape, int minRadius, int maxRadius,
                                   int heightBonus, float frequency) {
        JsonObject o = new JsonObject();
        o.addProperty("tower_shape",        shape);
        o.addProperty("min_tower_radius",   minRadius);
        o.addProperty("max_tower_radius",   maxRadius);
        o.addProperty("tower_height_bonus", heightBonus);
        o.addProperty("tower_frequency",    frequency);
        return o;
    }

    private JsonObject buildDonjon(boolean hasDonjon, int minSize, int maxSize,
                                   int heightBonus, boolean hasInnerWard,
                                   float innerWardScale) {
        JsonObject o = new JsonObject();
        o.addProperty("has_donjon",          hasDonjon);
        o.addProperty("donjon_min_size",     minSize);
        o.addProperty("donjon_max_size",     maxSize);
        o.addProperty("donjon_height_bonus", heightBonus);
        o.addProperty("has_inner_ward",      hasInnerWard);
        o.addProperty("inner_ward_scale",    innerWardScale);
        return o;
    }

    private JsonObject buildFeatures(boolean hasMoat, int moatWidth, int moatDepth,
                                     String moatFill, boolean hasPortcullis,
                                     boolean hasDrawbridge, boolean hasBarbian,
                                     boolean hasWallTurrets, float turretFrequency,
                                     boolean addTorches, boolean addFlags,
                                     boolean addVines, boolean addRubble) {
        JsonObject o = new JsonObject();
        o.addProperty("has_moat",         hasMoat);
        o.addProperty("moat_width",       moatWidth);
        o.addProperty("moat_depth",       moatDepth);
        o.addProperty("moat_fill",        moatFill);
        o.addProperty("has_portcullis",   hasPortcullis);
        o.addProperty("has_drawbridge",   hasDrawbridge);
        o.addProperty("has_barbian",      hasBarbian);
        o.addProperty("has_wall_turrets", hasWallTurrets);
        o.addProperty("turret_frequency", turretFrequency);
        o.addProperty("add_torches",      addTorches);
        o.addProperty("add_flags",        addFlags);
        o.addProperty("add_vines",        addVines);
        o.addProperty("add_rubble",       addRubble);
        return o;
    }

    private JsonObject buildPools(String battlements, String gatehouses,
                                  String windows, String towerRoofs,
                                  String donjeonRoofs, String interior) {
        String mod = Life_in_the_village.MODID;
        JsonObject o = new JsonObject();
        o.addProperty("battlement_pool",  mod + ":castle/battlements/"  + battlements);
        o.addProperty("gatehouse_pool",   mod + ":castle/gatehouses/"   + gatehouses);
        o.addProperty("window_pool",      mod + ":castle/windows/"      + windows);
        o.addProperty("tower_roof_pool",  mod + ":castle/tower_roofs/"  + towerRoofs);
        o.addProperty("donjon_roof_pool", mod + ":castle/donjon_roofs/" + donjeonRoofs);
        o.addProperty("interior_pool",    mod + ":castle/interiors/"    + interior);
        return o;
    }

    // =========================================================================
    // Function section builders
    // =========================================================================

    private JsonObject buildFunction(JsonObject roster, JsonArray specs,
                                     boolean hasStable, boolean hasDungeon,
                                     boolean hasChapel, boolean hasLibrary,
                                     boolean hasAlchemistLab,
                                     boolean hasTrainingYard) {
        JsonObject o = new JsonObject();
        o.add("roster",             roster);
        o.add("subbuilding_specs",  specs);
        o.addProperty("has_stable",        hasStable);
        o.addProperty("has_dungeon",       hasDungeon);
        o.addProperty("has_chapel",        hasChapel);
        o.addProperty("has_library",       hasLibrary);
        o.addProperty("has_alchemist_lab", hasAlchemistLab);
        o.addProperty("has_training_yard", hasTrainingYard);
        return o;
    }

    private JsonObject buildRoster(int king, int queen, int lord, int lady,
                                   int scholar, int guard, int knight,
                                   int servant, int cook, int smith,
                                   int stablehand, int scribe,
                                   int alchemist, int priest) {
        JsonObject o = new JsonObject();
        o.addProperty("king",       king);
        o.addProperty("queen",      queen);
        o.addProperty("lord",       lord);
        o.addProperty("lady",       lady);
        o.addProperty("scholar",    scholar);
        o.addProperty("guard",      guard);
        o.addProperty("knight",     knight);
        o.addProperty("servant",    servant);
        o.addProperty("cook",       cook);
        o.addProperty("smith",      smith);
        o.addProperty("stablehand", stablehand);
        o.addProperty("scribe",     scribe);
        o.addProperty("alchemist",  alchemist);
        o.addProperty("priest",     priest);
        return o;
    }

    private JsonObject requiredSpec(String type, String zone, String lootKey) {
        return buildSpec(type, zone, 1, 1, lootKey, "");
    }

    private JsonObject optionalSpec(String type, String zone, int max,
                                    String lootKey) {
        return buildSpec(type, zone, 0, max, lootKey, "");
    }

    private JsonObject buildSpec(String type, String zone, int minCount,
                                 int maxCount, String lootKey, String pool) {
        JsonObject o = new JsonObject();
        o.addProperty("type",      type);
        o.addProperty("zone",      zone);
        o.addProperty("min_count", minCount);
        o.addProperty("max_count", maxCount);
        o.addProperty("loot_table", lootKey.isEmpty()
                ? "life_in_the_village:castle/empty"
                : "life_in_the_village:castle/" + lootKey);
        if (!pool.isEmpty()) {
            o.addProperty("pool",
                    Life_in_the_village.MODID + ":castle/subbuildings/" + pool);
        }
        return o;
    }

    // =========================================================================
    // Subbuilding spec arrays — one per style
    // =========================================================================

    private JsonArray buildNormanSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",      "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters",   "donjon_upper",  ""));
        a.add(requiredSpec("treasury",         "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",         "outer_ward",    ""));
        a.add(requiredSpec("well",             "courtyard",     ""));
        a.add(optionalSpec("armoury",          "wall_interior", 1, "armoury"));
        a.add(optionalSpec("guardhouse",       "tower_interior",2, ""));
        a.add(optionalSpec("kitchen",          "outer_ward",    1, ""));
        a.add(optionalSpec("granary",          "outer_ward",    1, "granary"));
        a.add(optionalSpec("smithy",           "outer_ward",    1, ""));
        a.add(optionalSpec("dungeon",          "underground",   1, ""));
        a.add(optionalSpec("gatehouse_office", "wall_interior", 1, ""));
        return a;
    }

    private JsonArray buildMoorishSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("alchemist_lab",  "inner_ward",    1, "alchemist_lab"));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    2, ""));
        a.add(optionalSpec("tailors_room",   "inner_ward",    1, ""));
        return a;
    }

    private JsonArray buildFantasySubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(requiredSpec("alchemist_lab",  "inner_ward",    "alchemist_lab"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    3, ""));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("dungeon",        "underground",   1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("cistern",        "underground",   1, ""));
        return a;
    }

    private JsonArray buildRuinSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(optionalSpec("dungeon", "underground", 1, ""));
        return a;
    }

    private JsonArray buildJapaneseSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("chapel",         "inner_ward",    1, ""));
        a.add(optionalSpec("training_yard",  "courtyard",     1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    3, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("armoury",        "wall_interior", 1, "armoury"));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        return a;
    }

    private JsonArray buildFrenchGothicSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("chapel",         "inner_ward",    ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    3, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("tailors_room",   "inner_ward",    1, ""));
        return a;
    }

    private JsonArray buildByzantineSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("chapel",         "inner_ward",    ""));
        a.add(optionalSpec("library",        "inner_ward",    1, "library"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    2, ""));
        a.add(optionalSpec("alchemist_lab",  "inner_ward",    1, "alchemist_lab"));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        return a;
    }

    private JsonArray buildEdwardianSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(optionalSpec("armoury",        "wall_interior", 1, "armoury"));
        a.add(optionalSpec("training_yard",  "courtyard",     1, ""));
        a.add(optionalSpec("guardhouse",     "tower_interior",3, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("granary",        "outer_ward",    1, "granary"));
        a.add(optionalSpec("smithy",         "outer_ward",    1, ""));
        a.add(optionalSpec("stable",         "courtyard",     1, ""));
        a.add(optionalSpec("gatehouse_office","wall_interior",1, ""));
        return a;
    }

    private JsonArray buildMughalSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("alchemist_lab",  "inner_ward",    1, "alchemist_lab"));
        a.add(optionalSpec("noble_quarters", "inner_ward",    3, ""));
        a.add(optionalSpec("tailors_room",   "inner_ward",    1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        return a;
    }

    private JsonArray buildDarkLordSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("dungeon",        "underground",   ""));
        a.add(requiredSpec("alchemist_lab",  "inner_ward",    "alchemist_lab"));
        a.add(optionalSpec("armoury",        "wall_interior", 1, "armoury"));
        a.add(optionalSpec("cistern",        "underground",   1, ""));
        a.add(optionalSpec("smithy",         "outer_ward",    1, ""));
        a.add(optionalSpec("library",        "inner_ward",    1, "library"));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("training_yard",  "courtyard",     1, ""));
        return a;
    }

    private JsonArray buildElvishSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("alchemist_lab",  "inner_ward",    1, "alchemist_lab"));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    2, ""));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("chapel",         "inner_ward",    1, ""));
        return a;
    }

    private JsonArray buildDwarvenSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("smithy",         "outer_ward",    ""));
        a.add(requiredSpec("armoury",        "wall_interior", "armoury"));
        a.add(optionalSpec("cistern",        "underground",   1, ""));
        a.add(optionalSpec("granary",        "outer_ward",    1, "granary"));
        a.add(optionalSpec("dungeon",        "underground",   1, ""));
        a.add(optionalSpec("training_yard",  "courtyard",     1, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("library",        "inner_ward",    1, "library"));
        return a;
    }

    private JsonArray buildSeaFortSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(optionalSpec("armoury",        "wall_interior", 1, "armoury"));
        a.add(optionalSpec("guardhouse",     "tower_interior",2, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("granary",        "outer_ward",    1, "granary"));
        a.add(optionalSpec("smithy",         "outer_ward",    1, ""));
        a.add(optionalSpec("gatehouse_office","wall_interior",1, ""));
        return a;
    }

    private JsonArray buildAncientRuinSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(optionalSpec("dungeon", "underground", 1, ""));
        return a;
    }

    private JsonArray buildUndeadSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("dungeon",        "underground",   ""));
        a.add(requiredSpec("armoury",        "wall_interior", "armoury"));
        a.add(optionalSpec("alchemist_lab",  "inner_ward",    1, "alchemist_lab"));
        a.add(optionalSpec("cistern",        "underground",   1, ""));
        a.add(optionalSpec("library",        "inner_ward",    1, "library"));
        return a;
    }
    private JsonArray buildCattinghamSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(requiredSpec("chapel",         "inner_ward",    ""));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    3, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("tailors_room",   "inner_ward",    1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    1, ""));
        return a;
    }

    private JsonArray buildGrandPalaceSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(requiredSpec("chapel",         "inner_ward",    ""));
        a.add(requiredSpec("alchemist_lab",  "inner_ward",    "alchemist_lab"));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    4, ""));
        a.add(optionalSpec("observatory",    "tower_interior",1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    2, ""));
        a.add(optionalSpec("tailors_room",   "inner_ward",    1, ""));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        return a;
    }

    private JsonArray buildRusticManorSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(optionalSpec("chapel",         "inner_ward",    1, ""));
        a.add(optionalSpec("library",        "inner_ward",    1, "library"));
        a.add(optionalSpec("kitchen",        "outer_ward",    1, ""));
        a.add(optionalSpec("granary",        "outer_ward",    1, "granary"));
        a.add(optionalSpec("stable",         "courtyard",     1, ""));
        return a;
    }

    private JsonArray buildCrystalSpireSubbuildings() {
        JsonArray a = new JsonArray();
        a.add(requiredSpec("throne_room",    "donjon_ground", ""));
        a.add(requiredSpec("royal_quarters", "donjon_upper",  ""));
        a.add(requiredSpec("treasury",       "donjon_ground", "treasury"));
        a.add(requiredSpec("barracks",       "outer_ward",    ""));
        a.add(requiredSpec("well",           "courtyard",     ""));
        a.add(requiredSpec("library",        "inner_ward",    "library"));
        a.add(requiredSpec("alchemist_lab",  "inner_ward",    "alchemist_lab"));
        a.add(requiredSpec("observatory",    "tower_interior",""));
        a.add(optionalSpec("council_chamber","donjon_upper",  1, ""));
        a.add(optionalSpec("scriptorium",    "inner_ward",    2, ""));
        a.add(optionalSpec("noble_quarters", "inner_ward",    2, ""));
        a.add(optionalSpec("cistern",        "underground",   1, ""));
        return a;
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private JsonObject block(String id, int weight) {
        JsonObject o = new JsonObject();
        o.addProperty("block",  id);
        o.addProperty("weight", weight);
        return o;
    }

    private JsonArray weightedBlocks(JsonObject... entries) {
        JsonArray arr = new JsonArray();
        for (JsonObject e : entries) arr.add(e);
        return arr;
    }
    private JsonObject buildStyleDetail(String battlementStyle, String towerRoofStyle,
                                        String archStyle, String windowStyle,
                                        String wallDecoration, int stringCourseInterval,
                                        boolean hasBatteredBase, boolean hasQuoins,
                                        String courtyardStyle,
                                        int merlonWidth, int merlonHeight) {
        JsonObject o = new JsonObject();
        o.addProperty("battlement_style",       battlementStyle);
        o.addProperty("tower_roof_style",       towerRoofStyle);
        o.addProperty("arch_style",             archStyle);
        o.addProperty("window_style",           windowStyle);
        o.addProperty("wall_decoration",        wallDecoration);
        o.addProperty("string_course_interval", stringCourseInterval);
        o.addProperty("has_battered_base",      hasBatteredBase);
        o.addProperty("has_quoins",             hasQuoins);
        o.addProperty("courtyard_style",        courtyardStyle);
        o.addProperty("merlon_width",           merlonWidth);
        o.addProperty("merlon_height",          merlonHeight);
        return o;
    }

    // =========================================================================
    // Save
    // =========================================================================

    private CompletableFuture<?> save(CachedOutput cache, JsonObject json) {
        String id   = json.get("style_id").getAsString();
        Path   path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/castle_styles/" + id + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }
}