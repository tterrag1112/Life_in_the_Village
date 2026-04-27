package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Doc 15 §"Tintable blocks (opt-out convention)" — the static lookup
 * driving the placement-time tint pass.
 *
 * <p>For each tintable {@code white_*} marker block, the table holds
 * the {@link ColorSlot} it belongs to and a {@link Map}&lt;{@link
 * DyeColor},{@link Block}&gt; resolving every dye colour to the
 * corresponding coloured block. The table is built and validated at
 * class init: if any of the 16 dye colours is missing for any
 * tintable base, an {@link IllegalStateException} is raised so the
 * mismatch fails loudly on first load instead of silently leaving
 * blocks white at runtime.</p>
 *
 * <p>{@code light_gray_*} is the documented opt-out; it never appears
 * as a tint source so it stays white.</p>
 */
public final class TintBlockTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TintBlockTable.class);

    /** Suffixes used for each tintable base. */
    private enum Family {
        TERRACOTTA("terracotta"),
        WOOL("wool"),
        CONCRETE("concrete"),
        CONCRETE_POWDER("concrete_powder"),
        GLAZED_TERRACOTTA("glazed_terracotta"),
        CARPET("carpet"),
        CANDLE("candle");
        final String suffix;
        Family(String suffix) { this.suffix = suffix; }
    }

    public record TintEntry(ColorSlot slot, Map<DyeColor, Block> byColor) {}

    private static final Map<Block, TintEntry> TABLE;

    static {
        Map<Block, TintEntry> table = new LinkedHashMap<>();

        // PRIMARY — body colour markers.
        table.put(Blocks.WHITE_TERRACOTTA, build(ColorSlot.PRIMARY, Family.TERRACOTTA));
        table.put(Blocks.WHITE_WOOL,       build(ColorSlot.PRIMARY, Family.WOOL));

        // ACCENT — trim / detail markers. white_glazed_terracotta is
        // listed under both accent and roof in doc 15; per the P0a-10
        // brief the slot is decided by block type, not position, so
        // it lives under ACCENT here. Recorded in doc 15 revision notes.
        table.put(Blocks.WHITE_CONCRETE,         build(ColorSlot.ACCENT, Family.CONCRETE));
        table.put(Blocks.WHITE_CONCRETE_POWDER,  build(ColorSlot.ACCENT, Family.CONCRETE_POWDER));
        table.put(Blocks.WHITE_GLAZED_TERRACOTTA, build(ColorSlot.ACCENT, Family.GLAZED_TERRACOTTA));

        // ROOF — slope / capping markers.
        table.put(Blocks.WHITE_CARPET, build(ColorSlot.ROOF, Family.CARPET));
        table.put(Blocks.WHITE_CANDLE, build(ColorSlot.ROOF, Family.CANDLE));

        TABLE = Map.copyOf(table);
        LOGGER.info("TintBlockTable: validated {} tintable bases × {} colours",
                TABLE.size(), DyeColor.values().length);
    }

    private TintBlockTable() {}

    /**
     * Returns the tint entry for {@code source}, or empty when
     * {@code source} isn't a recognised tintable marker.
     */
    public static Optional<TintEntry> entryFor(Block source) {
        return Optional.ofNullable(TABLE.get(source));
    }

    /**
     * Resolves the coloured block for {@code source} + {@code dye},
     * or empty when the source is not tintable. Once an entry exists,
     * every {@link DyeColor} is guaranteed to map to a real block
     * (validated at class init).
     */
    public static Optional<Block> resolve(Block source, DyeColor dye) {
        TintEntry entry = TABLE.get(source);
        if (entry == null) return Optional.empty();
        return Optional.of(entry.byColor().get(dye));
    }

    /** Read-only diagnostic snapshot of the loaded table. */
    public static Map<Block, TintEntry> all() { return TABLE; }

    // ── Construction helpers ──────────────────────────────────────────────

    private static TintEntry build(ColorSlot slot, Family family) {
        Map<DyeColor, Block> byColor = new EnumMap<>(DyeColor.class);
        for (DyeColor dye : DyeColor.values()) {
            String id = dye.name().toLowerCase(Locale.ROOT) + "_" + family.suffix;
            Block block = lookup(id);
            if (block == null || block == Blocks.AIR) {
                throw new IllegalStateException(
                        "TintBlockTable: missing block 'minecraft:" + id
                                + "' required for slot=" + slot
                                + " family=" + family);
            }
            byColor.put(dye, block);
        }
        return new TintEntry(slot, java.util.Collections.unmodifiableMap(byColor));
    }

    private static Block lookup(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }
}
