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
        List<BlacksmithRecipeData.SmeltingRecipe> smelting = new ArrayList<>();
        List<BlacksmithRecipeData.CraftingRecipe> crafting = new ArrayList<>();

        // Load smelting
        loadFile(manager, "blacksmith_recipes/smelting.json",
                json -> {
                    for (var el : json.getAsJsonArray("recipes")) {
                        JsonObject r = el.getAsJsonObject();
                        Item input  = getItem(r.get("input").getAsString());
                        Item output = getItem(r.get("output").getAsString());
                        if (input == null || output == null) return;
                        // Phase 6.6.2.2 — optional min_skill_level for tier gating.
                        int minSkill = r.has("min_skill_level")
                                ? r.get("min_skill_level").getAsInt() : 0;
                        smelting.add(new BlacksmithRecipeData.SmeltingRecipe(
                                input, output,
                                r.get("count").getAsInt(),
                                r.get("ticks").getAsInt(),
                                minSkill
                        ));
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
                        crafting.add(new BlacksmithRecipeData.CraftingRecipe(
                                input,
                                r.get("input_count").getAsInt(),
                                output,
                                r.get("count").getAsInt(),
                                r.get("ticks").getAsInt(),
                                minSkill
                        ));
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