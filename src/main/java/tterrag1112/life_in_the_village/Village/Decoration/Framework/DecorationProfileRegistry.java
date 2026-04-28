package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Doc 01 — registry of {@link DecorationProfile}s indexed by
 * {@link DecorationProfile#pieceId}.
 *
 * <p>Populated at mod init by content subsystems (street furniture,
 * signs, headstones, etc. in later phases). Empty at the close of
 * Phase 0b — this prompt only ships the data model.</p>
 *
 * <p>Lookups follow the doc 01 §"Open decisions" culture fallback
 * chain: profiles registered for the village's culture take
 * precedence; if none match, the matcher falls through to
 * {@code "default"} culture profiles. Anything else burns the slot
 * silently — there is no further legacy fallback.</p>
 *
 * <p>The registry is a plain singleton keyed by string; it holds no
 * runtime state beyond the profile map and is safe to register into
 * during static initialisation of content classes.</p>
 */
public final class DecorationProfileRegistry {

    public static final DecorationProfileRegistry INSTANCE = new DecorationProfileRegistry();

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DecorationProfileRegistry.class);

    /** Doc 01 §"Open decisions": fallback culture id. */
    public static final String DEFAULT_CULTURE = "default";

    /** Insertion-ordered for deterministic iteration. */
    private final Map<String, DecorationProfile> byPieceId = new LinkedHashMap<>();

    private DecorationProfileRegistry() {}

    // ── Registration ─────────────────────────────────────────────────────

    /**
     * Adds {@code profile} to the registry. A second registration
     * for the same {@link DecorationProfile#pieceId} replaces the
     * earlier entry and logs a warning — this typically signals a
     * datapack reload race or a duplicate static-init sequence.
     */
    public void register(DecorationProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("DecorationProfileRegistry: null profile");
        }
        DecorationProfile prior = byPieceId.put(profile.pieceId(), profile);
        if (prior != null) {
            LOGGER.warn("DecorationProfileRegistry: pieceId '{}' replaced "
                    + "(prior culture={}, new culture={})",
                    profile.pieceId(), prior.culture(), profile.culture());
        }
    }

    /** Removes every registered profile. Test hook; production code
     *  should not call this. */
    public void clear() {
        byPieceId.clear();
    }

    // ── Lookup ───────────────────────────────────────────────────────────

    /** Returns the profile registered under {@code pieceId}. */
    public Optional<DecorationProfile> resolve(String pieceId) {
        if (pieceId == null) return Optional.empty();
        return Optional.ofNullable(byPieceId.get(pieceId));
    }

    /**
     * Doc 01 eligibility query. Returns every profile that matches
     * the {@code slot} and is appropriate for {@code culture},
     * applying the culture fallback chain.
     *
     * <p>Filter sequence:</p>
     * <ol>
     *   <li>Slot-tag and footprint check via
     *       {@link DecorationProfile#matchesSlot}.</li>
     *   <li>Biome constraint (if present): the optional
     *       {@code slotBiome} parameter is compared to
     *       {@code profile.biomeConstraint()}; missing biome data
     *       fails the constraint.</li>
     *   <li>Culture filter: if any profile's culture matches
     *       {@code culture}, return only those; otherwise fall
     *       through to {@link #DEFAULT_CULTURE} profiles. Empty list
     *       on full miss.</li>
     * </ol>
     *
     * <p><b>Edge case</b> — a slot with zero tags matches no
     * profiles. Doc 01 §"DecorationSlot": tags advertise "what kind
     * of decoration fits"; an empty set carries no signal. The
     * matcher's burn-the-slot policy applies.</p>
     *
     * @param slot       slot under consideration
     * @param culture    village's culture id
     * @param slotBiome  biome resource location at the slot, or
     *                   {@link Optional#empty()} when the caller
     *                   doesn't have it cheaply (the matcher
     *                   typically resolves once per village)
     */
    public List<DecorationProfile> eligibleFor(DecorationSlot slot,
                                               String culture,
                                               Optional<Identifier> slotBiome) {
        if (slot == null) return List.of();
        // Doc 01 edge case: slot without tags carries no signal.
        if (slot.tags().isEmpty()) return List.of();

        // Pass 1: hard filters (slot-tag + footprint + biome).
        List<DecorationProfile> matches = new ArrayList<>();
        for (DecorationProfile p : byPieceId.values()) {
            if (!p.matchesSlot(slot)) continue;
            if (!biomeMatches(p, slotBiome)) continue;
            matches.add(p);
        }
        if (matches.isEmpty()) return List.of();

        // Pass 2: culture fallback chain.
        String resolvedCulture = culture == null ? DEFAULT_CULTURE : culture;
        List<DecorationProfile> sameCulture = new ArrayList<>();
        List<DecorationProfile> defaultCulture = new ArrayList<>();
        for (DecorationProfile p : matches) {
            if (p.culture().equals(resolvedCulture)) sameCulture.add(p);
            else if (DEFAULT_CULTURE.equals(p.culture())) defaultCulture.add(p);
        }
        if (!sameCulture.isEmpty()) {
            return Collections.unmodifiableList(sameCulture);
        }
        return Collections.unmodifiableList(defaultCulture);
    }

    /** Convenience overload when biome data isn't available — the
     *  matcher passes {@link Optional#empty()} and any profile with
     *  a biome constraint is filtered out. */
    public List<DecorationProfile> eligibleFor(DecorationSlot slot, String culture) {
        return eligibleFor(slot, culture, Optional.empty());
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    /** Read-only snapshot of all registered profiles in registration
     *  order. */
    public List<DecorationProfile> all() {
        return List.copyOf(byPieceId.values());
    }

    public int size() { return byPieceId.size(); }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean biomeMatches(DecorationProfile profile,
                                        Optional<Identifier> slotBiome) {
        if (profile.biomeConstraint().isEmpty()) return true;
        if (slotBiome.isEmpty()) return false;
        return profile.biomeConstraint().get().equals(slotBiome.get());
    }
}
