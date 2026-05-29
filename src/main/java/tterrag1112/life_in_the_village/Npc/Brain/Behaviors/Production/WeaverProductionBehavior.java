package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LoomBlock;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

/**
 * Weaver processes wool and string into carpets and spun wool at the loom.
 * Inputs come from the stockpile (sheep wool from the farm). Outputs are
 * deposited to the weaver building for sale at market.
 *
 * <h3>Future</h3>
 * The loom workstation and quota system are already wired to accept clothing
 * recipes when that system is ready. Add new {@link ProductionRecipe} entries
 * and new {@link #stockQuotas()} entries without touching the abstract class.
 */
public class WeaverProductionBehavior extends AbstractProductionBehavior {

    private static final int MAX_BATCH = 8;

    // String spinning: 4 string → 1 white wool (useful when sheep are scarce).
    // Phase 6.6.5 — migrated to ProductionRecipe; kept as named static for
    // readability (referenced by name in buildRecipes below).
    // Phase 6.6.6 — public so HomeWeavingBehavior can reuse the same recipe
    // (single source of truth; same precedent as 6.4.5.1 widening of
    // WHEAT_TO_BREAD for HomeBakingBehavior).
    public static final ProductionRecipe SPIN_STRING =
            ProductionRecipe.of(Items.STRING, 4, Items.WHITE_WOOL, 1, 60);

    private static final List<ProductionRecipe> RECIPES = buildRecipes();

    // All wool items the weaver accepts as inputs
    private static final List<Item> WOOL_TYPES = List.of(
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL,
            Items.LIGHT_BLUE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL,
            Items.PINK_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL,
            Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL,
            Items.BLACK_WOOL);

    

    @Override protected BuildingType requiredBuildingType() { return BuildingType.WEAVER; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof LoomBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        Optional<Item> target = productionTarget(level, building);

        // Try to produce the priority target
        if (target.isPresent()) {
            Optional<ProductionRecipe> specific =
                    findRecipeForOutput(level, source, target.get());
            if (specific.isPresent()) return specific;
        }

        if (target.isEmpty()) return Optional.empty();

        // Opportunistic: any below-quota recipe with available inputs
        return findBestAvailable(level, building, source);
    }

    // Phase 6.6.5 — meetsSkillGate(WeaverRecipe) removed. Base class
    // meetsSkillRequirements (6.4.10.1) is canonical.

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int available = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, source, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(available, MAX_BATCH);
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        VillageSavedData data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById).filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(workBuilding);
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        // Carpet — moderate stock of each color
        Map.of(Items.WHITE_CARPET, 32, Items.GRAY_CARPET, 16,
                Items.LIGHT_GRAY_CARPET, 16, Items.BLACK_CARPET, 8,
                Items.BROWN_CARPET, 8, Items.RED_CARPET, 8,
                Items.BLUE_CARPET, 8, Items.GREEN_CARPET, 8,
                Items.YELLOW_CARPET, 8, Items.ORANGE_CARPET, 8).forEach(q::put);
        // Lead — useful for farmers/stable
        q.put(Items.LEAD, 8);
        // Phase 6.6.4.2 — masterpiece quota kept low (dormant until a
        // master-tier weaver spawns AND wool source exists — wool needs
        // SHEPHERD activation per 6.6.0.4 deferred work).
        q.put(Items.WHITE_BANNER, 2);
        return q;
    }

    @Override
    protected List<Item> sellableOutputs() {
        return RECIPES.stream().map(ProductionRecipe::output).distinct().toList();
    }

    @Override
    protected boolean canProduceItem(Item item) {
        return RECIPES.stream().anyMatch(r -> r.output() == item);
    }

    @Override protected SoundEvent workSound() { return SoundEvents.UI_LOOM_TAKE_RESULT; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);

        // Buy string when low (for spinning and leads)
        int string = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.STRING) : 0;
        if (string < 8) toBuy.put(Items.STRING, 16 - string);

        // Buy white wool when low (base for most carpet production)
        int white = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.WHITE_WOOL) : 0;
        if (white < 4) toBuy.put(Items.WHITE_WOOL, 16 - white);

        return toBuy;
    }

    @Override
    protected void onProductionComplete(ServerLevel level,
                                        ProductionRecipe recipe, int batchSize) {
        if (workBuilding == null) return;
        WorkplaceAssignmentManager.onWorkplaceProduction(
                level, workBuilding.getId(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(recipe.output()).toString(),
                batchSize * recipe.outputCount());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Optional<ProductionRecipe> findRecipeForOutput(ServerLevel level,
                                                           Building source, Item output) {
        // Phase 6.8.4 — input-availability filter dropped (6.3.4.6
        // decoupling, matching MILLER/BAKER): return a skill-passing
        // recipe for the target regardless of input stock; the
        // analyze→executeBuy path procures string / wool.
        // calculateBatchSize (input-aware) gates production. The
        // input-gated findBestAvailable stays as the opportunistic
        // fallback only.
        return RECIPES.stream()
                .filter(r -> r.output() == output)
                .filter(this::meetsSkillRequirements)
                .findFirst();
    }

    private Optional<ProductionRecipe> findBestAvailable(ServerLevel level,
                                                        Building building,
                                                        Building source) {
        Map<Item, Integer> quotas = stockQuotas();
        ProductionRecipe best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (ProductionRecipe r : RECIPES) {
            if (!meetsSkillRequirements(r)) continue;
            if (!hasAllInputs(level, source, r)) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = quotas.getOrDefault(r.output(), 0);
            if (stock >= quota) continue;
            double ratio = quota == 0 ? 0.0 : (double) stock / quota;
            if (ratio < lowestRatio) { lowestRatio = ratio; best = r; }
        }
        return Optional.ofNullable(best);
    }

    private static boolean hasAllInputs(ServerLevel level, Building source,
                                        ProductionRecipe recipe) {
        for (var e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, source, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ── Recipe list ───────────────────────────────────────────────────────────

    // Phase 6.6.5 — recipes defined directly as ProductionRecipe.of(...)
    // with .withSkillRequirement(WEAVING, N) for tier gates. WeaverRecipe
    // inline record removed.

    private static ProductionRecipe weave(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0
                ? r.withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAVING, minSkill)
                : r;
    }

    private static List<ProductionRecipe> buildRecipes() {
        // Phase 6.6.4.2 tier ladder. Wool→carpet recipes split by color
        // accessibility: pre-fix all 16 colors were equally available;
        // post-fix the rarer dye combinations (cyan, magenta, etc.)
        // require WEAVING practice. Vanilla colour-mixing logic informs
        // the tiers — primary colours are entry-level, secondary need
        // some craft, tertiary need real skill.
        List<ProductionRecipe> r = new ArrayList<>();

        // String → white wool (spinning). Entry-level fiber work.
        r.add(SPIN_STRING);

        // Tier 1: basic colors — primary palette + black/white.
        Map.of(Items.WHITE_WOOL,  Items.WHITE_CARPET,
                Items.RED_WOOL,    Items.RED_CARPET,
                Items.YELLOW_WOOL, Items.YELLOW_CARPET,
                Items.BLUE_WOOL,   Items.BLUE_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 0)));

        // Tier 2: common — secondary colors.
        Map.of(Items.ORANGE_WOOL,     Items.ORANGE_CARPET,
                Items.GREEN_WOOL,      Items.GREEN_CARPET,
                Items.PINK_WOOL,       Items.PINK_CARPET,
                Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 15)));

        // Tier 3: skilled — tertiary colors.
        Map.of(Items.CYAN_WOOL,    Items.CYAN_CARPET,
                Items.MAGENTA_WOOL, Items.MAGENTA_CARPET,
                Items.PURPLE_WOOL,  Items.PURPLE_CARPET,
                Items.LIME_WOOL,    Items.LIME_CARPET,
                Items.BROWN_WOOL,   Items.BROWN_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 30)));

        // Tier 4: master — grayscale + black.
        Map.of(Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_CARPET,
                Items.GRAY_WOOL,       Items.GRAY_CARPET,
                Items.BLACK_WOOL,      Items.BLACK_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 40)));

        // Phase 6.6.5.3 — WHITE_BANNER promoted to true vanilla
        // multi-input (6 white_wool + 1 stick → 1 banner). Sticks
        // come from CARPENTER via DirectBusinessChannel — cross-
        // profession dependency intentional, mirrors LANTERN's
        // iron_nugget requirement.
        r.add(ProductionRecipe.of(
                Map.of(Items.WHITE_WOOL, 6, Items.STICK, 1),
                Items.WHITE_BANNER, 1, 80)
                .withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAVING, 50));

        // Phase 6.6.1.5 — removed lead-recipe stub comment. The previous
        // note pointed at a multi-input lead recipe that was never wired:
        // chooseRecipe only iterates WeaverRecipe (single-input) and never
        // referenced a ProductionRecipe.of(Map.of(...)) path. The
        // CandlemakerProductionBehavior is the codebase's actual first
        // multi-input consumer. If a leads recipe lands later, the
        // implementation path is to either generalise WeaverRecipe to
        // multi-input or add a parallel chooseRecipe branch for the
        // multi-input variant — clean call when needed, no stub today.

        return Collections.unmodifiableList(r);
    }
}