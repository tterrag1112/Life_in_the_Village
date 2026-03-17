package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UpgradeRequirements {

    private final Map<Item, Integer> requiredItems;
    private final int coinCost;


    private UpgradeRequirements(Map<Item, Integer> requiredItems, int coinCost) {
        this.requiredItems = requiredItems;
        this.coinCost      = coinCost;

    }

    /**
     * Computes material requirements by diffing the current and next
     * level NBT structures. Only blocks present in the next level but
     * not the current level are counted as required materials.
     */
    public static Optional<UpgradeRequirements> compute(
            ServerLevel level,
            Building building) {

        Identifier currentId = building.getStructureId();
        Identifier nextId = Identifier.fromNamespaceAndPath(
                currentId.getNamespace(),
                currentId.getPath().replaceAll("level_\\d+",
                        "level_" + (building.getLevel() + 1))
        );

        Optional<StructureTemplate> currentTemplate =
                BuildingPlacer.loadTemplate(level, currentId);
        Optional<StructureTemplate> nextTemplate =
                BuildingPlacer.loadTemplate(level, nextId);

        // No higher level structure exists
        if (nextTemplate.isEmpty()) return Optional.empty();

        // Also check BuildingRegistry max level
        if (!BuildingRegistry.canUpgrade(building))
            return Optional.empty();

        Map<Block, Integer> currentBlocks = countBlocks(
                currentTemplate.orElse(null));
        Map<Block, Integer> nextBlocks = countBlocks(
                nextTemplate.get());

        Map<Item, Integer> required = new HashMap<>();
        for (Map.Entry<Block, Integer> entry
                : nextBlocks.entrySet()) {
            Block block = entry.getKey();
            int diff = entry.getValue()
                    - currentBlocks.getOrDefault(block, 0);
            if (diff > 0) {
                Item item = block.asItem();
                if (item != net.minecraft.world.item.Items.AIR) {
                    required.merge(item, diff, Integer::sum);
                }
            }
        }

        // Get coin cost from BuildingRegistry
        int coinCost = BuildingRegistry.get(building.getType())
                .map(def -> def.constructionCost())
                .orElse(0);

        return Optional.of(new UpgradeRequirements(
                required, coinCost));
    }

    private static Map<Block, Integer> countBlocks(StructureTemplate template) {
        Map<Block, Integer> counts = new HashMap<>();
        if (template == null) return counts;

        // Use size to iterate all positions and sample block states
        var size = template.getSize();
        List<StructureTemplate.StructureBlockInfo> blocks =
                template.filterBlocks(
                        BlockPos.ZERO,
                        new StructurePlaceSettings(),
                        Blocks.AIR,
                        false
                );
        // filterBlocks returns non-air blocks
        for (var info : blocks) {
            counts.merge(info.state().getBlock(), 1, Integer::sum);
        }
        return counts;
    }

    public Map<Item, Integer> getRequiredItems() {
        return Map.copyOf(requiredItems);
    }

    public boolean isSatisfied(SimpleContainer inventory) {
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int found = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(entry.getKey())) found += stack.getCount();
            }
            if (found < entry.getValue()) return false;
        }
        return true;
    }

    public void consumeFrom(SimpleContainer inventory) {
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int remaining = entry.getValue();
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(entry.getKey())) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                    if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }
    public int getCoinCost() { return coinCost; }

}
