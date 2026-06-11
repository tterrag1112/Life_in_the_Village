package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Eager-scan reload listener that walks
 * {@code data/<modid>/structures/<culture>/<style>/<type>/<variant>/level_<n>.nbt}
 * and indexes which variants are present per
 * {@code (culture, style, type, level)} key.
 *
 * <p>A1 stage 2 — additionally indexes PIECE files
 * ({@code <variant>/<piece>_level_<n>.nbt}, e.g. the terrace
 * row-house segments {@code row_house/left_level_1.nbt}) into a
 * separate per-variant-folder piece index served by
 * {@link #availablePieces}. Pieces deliberately do NOT join the
 * {@link #availableVariants} pool: a piece is only placeable as part
 * of a composed arrangement that forces it explicitly.
 *
 * <p>Registered on {@code AddServerReloadListenersEvent} alongside
 * {@code CultureRegistry}. Re-scans on resource reload.
 *
 * <p>Implements {@link BuildingAvailability}; consumers (the
 * selector and variant picker in V2 Layer 3) call the singleton
 * {@link #INSTANCE}.
 */
public final class StructureAvailabilityRegistry
        extends SimplePreparableReloadListener<StructureAvailabilityRegistry.Scanned>
        implements BuildingAvailability {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StructureAvailabilityRegistry.class);

    public static final StructureAvailabilityRegistry INSTANCE =
            new StructureAvailabilityRegistry();

    /** Initialised to the immutable empty map so calls before the
     *  first reload return Set.of() (= unavailable for everything),
     *  matching the natural behaviour of "no NBTs scanned yet". */
    private Map<Key, Set<String>> variants = Map.of();

    /** A1 stage 2 — piece index: (culture, style, type, level) →
     *  variant folder → authored piece names. Same lifecycle as
     *  {@link #variants}. */
    private Map<Key, Map<String, Set<String>>> pieces = Map.of();

    private StructureAvailabilityRegistry() {}

    @Override
    protected Scanned prepare(ResourceManager manager,
                              ProfilerFiller profiler) {
        Map<Key, Set<String>> loadedVariants = new HashMap<>();
        Map<Key, Map<String, Set<String>>> loadedPieces = new HashMap<>();
        manager.listResources("structures",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".nbt")
        ).forEach((id, resource) -> {
            ParsedPath parsed = parse(id.getPath());
            if (parsed != null) {
                Key key = new Key(parsed.culture, parsed.style, parsed.type,
                        parsed.level);
                loadedVariants.computeIfAbsent(key, k -> new HashSet<>())
                        .add(parsed.variantId);
                return;
            }
            ParsedPiece piece = parsePiece(id.getPath());
            if (piece != null) {
                Key key = new Key(piece.culture, piece.style, piece.type,
                        piece.level);
                loadedPieces.computeIfAbsent(key, k -> new HashMap<>())
                        .computeIfAbsent(piece.variantId, v -> new HashSet<>())
                        .add(piece.piece);
            }
        });
        // Freeze value sets.
        Map<Key, Set<String>> frozenVariants = new HashMap<>(loadedVariants.size());
        for (Map.Entry<Key, Set<String>> e : loadedVariants.entrySet()) {
            frozenVariants.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<Key, Map<String, Set<String>>> frozenPieces =
                new HashMap<>(loadedPieces.size());
        for (Map.Entry<Key, Map<String, Set<String>>> e : loadedPieces.entrySet()) {
            Map<String, Set<String>> perFolder = new HashMap<>(e.getValue().size());
            for (Map.Entry<String, Set<String>> f : e.getValue().entrySet()) {
                perFolder.put(f.getKey(), Set.copyOf(f.getValue()));
            }
            frozenPieces.put(e.getKey(), Map.copyOf(perFolder));
        }
        LOGGER.info("StructureAvailabilityRegistry: indexed {} (culture, style, "
                + "type, level) keys + {} piece-folder keys",
                frozenVariants.size(), frozenPieces.size());
        return new Scanned(frozenVariants, frozenPieces);
    }

    @Override
    protected void apply(Scanned prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.variants = Map.copyOf(prepared.variants());
        this.pieces = Map.copyOf(prepared.pieces());
    }

    @Override
    public Set<String> availableVariants(String culture, Style style,
                                         BuildingType type, int level) {
        return variants.getOrDefault(new Key(culture, style, type, level), Set.of());
    }

    @Override
    public Set<String> availablePieces(String culture, Style style,
                                       BuildingType type,
                                       String variantFolder, int level) {
        return pieces
                .getOrDefault(new Key(culture, style, type, level), Map.of())
                .getOrDefault(variantFolder, Set.of());
    }

    // =========================================================================
    // Path parsing
    // =========================================================================

    /** Index key. Hash + equality come free from the record. */
    public record Key(String culture, Style style, BuildingType type, int level) {}

    /** Prepared-snapshot carrier (variant index + piece index). */
    public record Scanned(Map<Key, Set<String>> variants,
                          Map<Key, Map<String, Set<String>>> pieces) {}

    private record ParsedPath(String culture, Style style, BuildingType type,
                              String variantId, int level) {}

    private record ParsedPiece(String culture, Style style, BuildingType type,
                               String variantId, String piece, int level) {}

    /**
     * Parses a resource path of the form
     * {@code structures/<culture>/<style>/<type>/<variant>/level_<n>.nbt}
     * into its components. Returns {@code null} when the path
     * doesn't match (different segment count, unknown style/type,
     * malformed level suffix, etc.) — kit fixtures under
     * {@code structures/default/castle/...} are skipped silently
     * because they have a different layout.
     */
    private static ParsedPath parse(String path) {
        String[] segs = canonicalSegments(path);
        if (segs == null) return null;
        Optional<Style> styleOpt = Style.fromFolder(segs[1]);
        if (styleOpt.isEmpty()) return null;
        BuildingType type = typeOf(segs[2]);
        if (type == null) return null;
        if (!segs[4].startsWith("level_")) return null;
        int level = parseLevel(segs[4].substring("level_".length()));
        if (level < 0) return null;
        return new ParsedPath(segs[0], styleOpt.get(), type, segs[3], level);
    }

    /**
     * A1 stage 2 — parses a PIECE path of the form
     * {@code structures/<culture>/<style>/<type>/<variant>/<piece>_level_<n>.nbt}
     * (non-empty {@code <piece>} prefix, e.g.
     * {@code row_house/left_level_1.nbt} → piece {@code left}).
     * Returns {@code null} when the filename has no piece prefix (that
     * is {@link #parse}'s case) or the path doesn't match.
     */
    private static ParsedPiece parsePiece(String path) {
        String[] segs = canonicalSegments(path);
        if (segs == null) return null;
        Optional<Style> styleOpt = Style.fromFolder(segs[1]);
        if (styleOpt.isEmpty()) return null;
        BuildingType type = typeOf(segs[2]);
        if (type == null) return null;
        int marker = segs[4].lastIndexOf("_level_");
        if (marker <= 0) return null;                    // no piece prefix
        int level = parseLevel(segs[4].substring(marker + "_level_".length()));
        if (level < 0) return null;
        return new ParsedPiece(segs[0], styleOpt.get(), type, segs[3],
                segs[4].substring(0, marker), level);
    }

    /** Shared 5-segment split of a canonical structures path, or null. */
    private static String[] canonicalSegments(String path) {
        if (!path.startsWith("structures/") || !path.endsWith(".nbt")) return null;
        String trimmed = path.substring(
                "structures/".length(), path.length() - ".nbt".length());
        String[] segs = trimmed.split("/");
        return segs.length == 5 ? segs : null;
    }

    private static BuildingType typeOf(String seg) {
        try {
            return BuildingType.valueOf(seg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Parses a level number, or -1 when malformed. */
    private static int parseLevel(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
