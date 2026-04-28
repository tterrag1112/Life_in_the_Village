package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Doc 15 §"Player-requested repaint" — cost calculator.
 *
 * <p>Cost formula (verbatim from doc 15):</p>
 * <pre>
 *   dye items per slot = 4 × ceil(footprintArea / 64)
 *   total dye items    = sum(dyes per painted slot)
 *   bronze fee         = small labour fee, scales with painted slots
 * </pre>
 *
 * <p>The doc gives an example: a 9×7 cottage with all three slots →
 * {@code 4 × 3 × ceil(63/64) = 12 dye items}. Footprint area is the
 * declared XZ extent of the building's
 * {@link Building.BuildingShape}.</p>
 */
public final class RepaintCost {

    /** Doc 15: 4 dyes per 64-block tile. */
    public static final int DYES_PER_TILE = 4;
    /** Doc 15: tile size for the footprint-area divisor. */
    public static final int FOOTPRINT_TILE = 64;

    /** Bronze fee per slot painted. Picked to be modest — doc 15 says
     *  "small labor fee". Tunable in a future revision. */
    public static final long BRONZE_FEE_PER_SLOT = 4L;

    private RepaintCost() {}

    /**
     * Result of the cost calculation. {@code dyes} maps each
     * {@link DyeColor} the player needs to the count required;
     * {@code bronzeFee} is the labour fee in bronze.
     */
    public record Result(Map<DyeColor, Integer> dyes, long bronzeFee) {
        public Result {
            dyes = dyes == null ? Map.of() : Map.copyOf(dyes);
        }

        /** Total dye items needed across all colours. */
        public int totalDyeItems() {
            int n = 0;
            for (int c : dyes.values()) n += c;
            return n;
        }
    }

    /**
     * Computes the cost for repainting {@code building}'s
     * {@code slotsToPaint} with the supplied colours. {@code primary},
     * {@code accent}, {@code roof} may be {@code null} when the
     * matching slot is not in {@code slotsToPaint}; passing colours
     * for slots that aren't in the set is silently ignored.
     */
    public static Result calculate(Building building,
                                   Set<ColorSlot> slotsToPaint,
                                   @Nullable DyeColor primary,
                                   @Nullable DyeColor accent,
                                   @Nullable DyeColor roof) {
        Map<DyeColor, Integer> dyes = new EnumMap<>(DyeColor.class);
        if (slotsToPaint == null || slotsToPaint.isEmpty() || building == null) {
            return new Result(dyes, 0L);
        }

        Building.BuildingShape shape = building.getShape();
        int area = Math.max(1, shape.getWidth() * shape.getLength());
        int dyesPerSlot = DYES_PER_TILE
                * (int) Math.max(1, Math.ceil(area / (double) FOOTPRINT_TILE));

        int slotsCount = 0;
        if (slotsToPaint.contains(ColorSlot.PRIMARY) && primary != null) {
            dyes.merge(primary, dyesPerSlot, Integer::sum);
            slotsCount++;
        }
        if (slotsToPaint.contains(ColorSlot.ACCENT) && accent != null) {
            dyes.merge(accent, dyesPerSlot, Integer::sum);
            slotsCount++;
        }
        if (slotsToPaint.contains(ColorSlot.ROOF) && roof != null) {
            dyes.merge(roof, dyesPerSlot, Integer::sum);
            slotsCount++;
        }

        long bronze = BRONZE_FEE_PER_SLOT * slotsCount;
        return new Result(dyes, bronze);
    }

    /**
     * Resolves the {@link DyeColor} dye item required for cost checks.
     * Vanilla item ids follow {@code <color>_dye} (e.g. {@code red_dye},
     * {@code light_gray_dye}).
     */
    public static Item dyeItemFor(DyeColor color) {
        Identifier id = Identifier.fromNamespaceAndPath(
                "minecraft", color.name().toLowerCase(Locale.ROOT) + "_dye");
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalStateException(
                        "RepaintCost: no dye item for " + color));
    }
}
