package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Map;

/**
 * Liveliness L3 (Part B) — cosmetic "held prop" overlay for ambient
 * movement (strolling, gathering, tidying). Picks a contextually-appropriate
 * display item and writes it to {@code CARRYING_DISPLAY_ITEM}, which the CORE
 * {@code CarryHoldAnimationBehavior} renders. Purely visual: never touches
 * real inventory; cheap (no world scan — one inventory walk + a static map);
 * the caller clears the memory on stop.
 */
public final class AmbientProps {

    private AmbientProps() {}

    /** Tiny static profession→prop map (no scan). Flag if it grows large. */
    private static final Map<Profession, Item> PROPS = Map.ofEntries(
            Map.entry(Profession.BLACKSMITH,  Items.IRON_AXE),
            Map.entry(Profession.CARPENTER,   Items.OAK_PLANKS),
            Map.entry(Profession.STONEMASON,  Items.STONE_BRICKS),
            Map.entry(Profession.WEAVER,      Items.WHITE_WOOL),
            Map.entry(Profession.CANDLEMAKER, Items.CANDLE),
            Map.entry(Profession.BAKER,       Items.BREAD),
            Map.entry(Profession.MILLER,      Items.WHEAT),
            Map.entry(Profession.MERCHANT,    Items.EMERALD),
            Map.entry(Profession.LIBRARIAN,   Items.BOOK),
            Map.entry(Profession.SCRIBE,      Items.PAPER),
            Map.entry(Profession.SCHOLAR,     Items.WRITTEN_BOOK),
            Map.entry(Profession.FARMER,      Items.IRON_HOE),
            Map.entry(Profession.FARMHAND,    Items.IRON_HOE),
            Map.entry(Profession.MINER,       Items.IRON_PICKAXE));

    /**
     * A cosmetic display item for this NPC: whatever it already carries
     * (first non-empty inventory slot), else a profession-appropriate prop,
     * else empty.
     */
    public static ItemStack displayItem(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) return s.copyWithCount(1);
        }
        Item prop = PROPS.get(entity.getProfession());
        return prop == null ? ItemStack.EMPTY : new ItemStack(prop);
    }

    /** Sets the carry overlay if a sensible prop exists (no-op otherwise). */
    public static void applyDisplay(TownspersonMob entity) {
        ItemStack display = displayItem(entity);
        if (!display.isEmpty()) {
            entity.getBrain().setMemory(
                    NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), display);
        }
    }
}
