package tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith;

import net.minecraft.tags.ItemTags;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.ProfessionSpecialization;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.SpecializationManager;

/**
 * Blacksmith specialization — narrows which items a blacksmith NPC will
 * produce without adding new building types.
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li>{@link #GENERALIST} — no filter, produces anything in the recipe book</li>
 *   <li>{@link #TOOLSMITH} — picks, axes, shovels, hoes, shears, flint &amp; steel</li>
 *   <li>{@link #ARMORER} — all armor slots and shields</li>
 *   <li>{@link #WEAPONSMITH} — swords, bows, crossbows, tridents</li>
 * </ul>
 *
 * <h3>Assignment</h3>
 * Specializations are assigned via {@link SpecializationManager#setSpecialization}.
 * A small village with one blacksmith should remain {@link #GENERALIST}. Larger
 * villages with 3+ blacksmiths can split specializations for economic depth.
 */
public enum BlacksmithSpecialization implements ProfessionSpecialization {

    /** Makes anything the blacksmith recipe registry supports. */
    GENERALIST("Generalist Smith"),

    /** Tools only — pickaxes, axes, shovels, hoes, shears, flint &amp; steel. */
    TOOLSMITH("Toolsmith"),

    /** Armor only — helmets, chestplates, leggings, boots, shields. */
    ARMORER("Armorer"),

    /** Weapons only — swords, bows, crossbows, tridents. */
    WEAPONSMITH("Weaponsmith");

    // ── Self-register with SpecializationManager ──────────────────────────────
    static {
        SpecializationManager.register(
                "BlacksmithSpecialization",
                BlacksmithSpecialization::valueOf);
    }

    private final String displayName;

    BlacksmithSpecialization(String displayName) {
        this.displayName = displayName;
    }

    @Override public String displayName()   { return displayName; }
    @Override public boolean isGeneralist() { return this == GENERALIST; }

    /**
     * True if this specialization permits producing the given output item.
     * Generalists allow everything; other specs filter by item category.
     *
     * <p>Note: uses {@code instanceof} against vanilla item classes plus the
     * ingot tag for raw metal production, which every blacksmith does
     * regardless of specialization (ingots are an intermediate good).</p>
     */
    @Override
    public boolean allowsOutput(Item item) {
        if (this == GENERALIST) return true;

        net.minecraft.world.item.ItemStack stack = item.getDefaultInstance();

        // Every smith can smelt raw ores into ingots regardless of spec —
        // ingots are inputs to the spec's real recipes.
        if (item == Items.IRON_INGOT
                || item == Items.GOLD_INGOT
                || item == Items.COPPER_INGOT
                || item == Items.NETHERITE_INGOT) {
            return true;
        }

        return switch (this) {
            case TOOLSMITH -> stack.is(ItemTags.PICKAXES)
                    || stack.is(ItemTags.AXES)
                    || stack.is(ItemTags.SHOVELS)
                    || stack.is(ItemTags.HOES)
                    || item == Items.SHEARS
                    || item == Items.FLINT_AND_STEEL
                    || item == Items.FISHING_ROD;

            case ARMORER -> stack.is(ItemTags.HEAD_ARMOR)
                    || stack.is(ItemTags.CHEST_ARMOR)
                    || stack.is(ItemTags.LEG_ARMOR)
                    || stack.is(ItemTags.FOOT_ARMOR)
                    || item == Items.SHIELD;

            case WEAPONSMITH -> stack.is(ItemTags.SWORDS)
                    || item == Items.BOW
                    || item == Items.CROSSBOW
                    || item == Items.TRIDENT;

            default -> true;
        };
    }
}