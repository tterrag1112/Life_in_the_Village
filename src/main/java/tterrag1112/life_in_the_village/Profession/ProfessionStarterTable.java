package tterrag1112.life_in_the_village.Profession;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Starter tool kit for a freshly-assigned profession. Applied once
 * inside {@code TownspersonMob.setProfession} on the first non-NONE
 * assignment, alongside the bronze payout from
 * {@link tterrag1112.life_in_the_village.Village.Economy.Currency.NpcStartingWealth}
 * (which owns the cash side).
 *
 * <p>NONE / CITIZEN return an empty bundle — drifters carry whatever
 * they pick up later. Stacks are copied before insertion so the table
 * is safe to share.</p>
 */
public final class ProfessionStarterTable {

    private static final Map<Profession, List<ItemStack>> TABLE = build();
    private static final List<ItemStack> EMPTY = List.of();

    private ProfessionStarterTable() {}

    /** Returns the per-profession item bundle. Never null; may be empty. */
    public static List<ItemStack> itemsFor(Profession profession) {
        if (profession == null) return EMPTY;
        return TABLE.getOrDefault(profession, EMPTY);
    }

    private static Map<Profession, List<ItemStack>> build() {
        Map<Profession, List<ItemStack>> m = new EnumMap<>(Profession.class);

        // ── Primary producers ────────────────────────────────────────────
        m.put(Profession.FARMER,    List.of(stack(Items.WHEAT_SEEDS, 8), stack(Items.WHEAT, 4)));
        m.put(Profession.FARMHAND,  List.of(stack(Items.WHEAT_SEEDS, 4)));
        m.put(Profession.MINER,     List.of(stack(Items.STONE_PICKAXE, 1)));
        m.put(Profession.BUILDER,   List.of(stack(Items.OAK_PLANKS, 16)));

        // ── Craft workers ───────────────────────────────────────────────
        m.put(Profession.BLACKSMITH,  List.of(stack(Items.IRON_INGOT, 4), stack(Items.COAL, 8)));
        m.put(Profession.CARPENTER,   List.of(stack(Items.OAK_PLANKS, 16)));
        m.put(Profession.STONEMASON,  List.of(stack(Items.COBBLESTONE, 16)));
        m.put(Profession.WEAVER,      List.of(stack(Items.WHITE_WOOL, 4)));
        m.put(Profession.CANDLEMAKER, List.of(stack(Items.HONEYCOMB, 4)));
        m.put(Profession.MILLER,      List.of(stack(Items.WHEAT, 8)));
        m.put(Profession.BAKER,       List.of(stack(Items.WHEAT, 4), stack(Items.BREAD, 2)));

        // ── Service ─────────────────────────────────────────────────────
        m.put(Profession.INNKEEPER, List.of(stack(Items.BREAD, 4), stack(Items.COOKED_BEEF, 2)));

        // ── Combat / adventure ──────────────────────────────────────────
        m.put(Profession.GUARD,      List.of(stack(Items.IRON_SWORD, 1), stack(Items.SHIELD, 1)));
        m.put(Profession.ADVENTURER, List.of(stack(Items.IRON_SWORD, 1), stack(Items.BREAD, 4)));

        // ── Civic / scholarship ────────────────────────────────────────
        m.put(Profession.SCRIBE,    List.of(
                stack(Items.PAPER, 8), stack(Items.FEATHER, 4), stack(Items.INK_SAC, 2)));
        m.put(Profession.LIBRARIAN, List.of(stack(Items.BOOK, 2)));
        m.put(Profession.SCHOLAR,   List.of(stack(Items.BOOK, 1), stack(Items.PAPER, 4)));

        // ── Religion / medicine ─────────────────────────────────────────
        m.put(Profession.PRIEST, List.of(stack(Items.BOOK, 1)));
        // Healer carries herbs + bandage stand-in; their RemedyType
        // stash lives separately on HealerInventory.
        m.put(Profession.HEALER, List.of(stack(Items.SWEET_BERRIES, 4), stack(Items.WHITE_WOOL, 2)));

        return m;
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
