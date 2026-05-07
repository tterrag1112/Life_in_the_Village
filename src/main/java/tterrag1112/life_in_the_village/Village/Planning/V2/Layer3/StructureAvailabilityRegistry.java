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
 * <p>Registered on {@code AddServerReloadListenersEvent} alongside
 * {@code CultureRegistry}. Re-scans on resource reload.
 *
 * <p>Implements {@link BuildingAvailability}; consumers (the
 * selector and variant picker in V2 Layer 3) call the singleton
 * {@link #INSTANCE}.
 */
public final class StructureAvailabilityRegistry
        extends SimplePreparableReloadListener<Map<StructureAvailabilityRegistry.Key, Set<String>>>
        implements BuildingAvailability {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StructureAvailabilityRegistry.class);

    public static final StructureAvailabilityRegistry INSTANCE =
            new StructureAvailabilityRegistry();

    /** Initialised to the immutable empty map so calls before the
     *  first reload return Set.of() (= unavailable for everything),
     *  matching the natural behaviour of "no NBTs scanned yet". */
    private Map<Key, Set<String>> variants = Map.of();

    private StructureAvailabilityRegistry() {}

    @Override
    protected Map<Key, Set<String>> prepare(ResourceManager manager,
                                            ProfilerFiller profiler) {
        Map<Key, Set<String>> loaded = new HashMap<>();
        manager.listResources("structures",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".nbt")
        ).forEach((id, resource) -> {
            ParsedPath parsed = parse(id.getPath());
            if (parsed == null) return;
            Key key = new Key(parsed.culture, parsed.style, parsed.type, parsed.level);
            loaded.computeIfAbsent(key, k -> new HashSet<>()).add(parsed.variantId);
        });
        // Freeze value sets.
        Map<Key, Set<String>> frozen = new HashMap<>(loaded.size());
        for (Map.Entry<Key, Set<String>> e : loaded.entrySet()) {
            frozen.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        LOGGER.info("StructureAvailabilityRegistry: indexed {} (culture, style, type, level) keys",
                frozen.size());
        return frozen;
    }

    @Override
    protected void apply(Map<Key, Set<String>> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.variants = Map.copyOf(prepared);
    }

    @Override
    public Set<String> availableVariants(String culture, Style style,
                                         BuildingType type, int level) {
        return variants.getOrDefault(new Key(culture, style, type, level), Set.of());
    }

    // =========================================================================
    // Path parsing
    // =========================================================================

    /** Index key. Hash + equality come free from the record. */
    public record Key(String culture, Style style, BuildingType type, int level) {}

    private record ParsedPath(String culture, Style style, BuildingType type,
                              String variantId, int level) {}

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
        if (!path.startsWith("structures/") || !path.endsWith(".nbt")) return null;
        String trimmed = path.substring(
                "structures/".length(), path.length() - ".nbt".length());
        String[] segs = trimmed.split("/");
        if (segs.length != 5) return null;
        Optional<Style> styleOpt = Style.fromFolder(segs[1]);
        if (styleOpt.isEmpty()) return null;
        BuildingType type;
        try {
            type = BuildingType.valueOf(segs[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!segs[4].startsWith("level_")) return null;
        int level;
        try {
            level = Integer.parseInt(segs[4].substring("level_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        return new ParsedPath(segs[0], styleOpt.get(), type, segs[3], level);
    }
}
