package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static <b>template catalog</b> of the starter {@link Religion}s. Populated lazily
 * on first access; idempotent. v1 hard-codes the four starters per spec line 36.
 *
 * <p><b>F1b 1b — templates, not the live set.</b> The world's live religions live in
 * the per-world {@link ReligionSavedData} (seeded from these templates on first
 * access) and are read through the {@link Religions} facade. This class's read
 * accessor is now {@link #templates()} — package-private, template-named, with the
 * seeder as its only runtime reader. The {@code id} constants and
 * {@link #dominantReligionFor} (culture→default-id) stay public seed/config.</p>
 *
 * <p>Holy-day mapping note (spec "Things to flag" #1): "Spring
 * Equinox" / "Full Moon" / "Kingdom Day" become specific day-of-
 * year integers in {@link ReligiousCalendar}. v1 picks reasonable
 * static values aligned to Earth-calendar quarter days; Phase 5
 * culture pass may shift them.</p>
 */
public final class ReligionRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SUNSTEAD     = "sunstead";
    public static final String THE_LOOM     = "the_loom";
    public static final String TIDECALL     = "tidecall";
    public static final String FORGE_CREED  = "forge_creed";

    private static final Map<String, Religion> RELIGIONS = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    private ReligionRegistry() {}

    // ── Template catalog (F1b 1b — the SEED source, not the live set) ─────────
    // After the per-world migration these are TEMPLATE accessors: the only runtime
    // reader is ReligionSavedData.seedIfEmpty (same package). Every live lookup goes
    // through the per-world Religions facade. Package-private + template-named so any
    // straggler reaching for the old static get/find/all fails to compile — the
    // compiler is the coverage proof that the migration is complete.

    /** Every seed template (copied into a fresh world's {@link ReligionSavedData}).
     *  The sole runtime reader is {@link ReligionSavedData#seedIfEmpty()}; a by-id
     *  template lookup can be re-added if a seeder ever needs one. */
    static List<Religion> templates() {
        ensureInit();
        return List.copyOf(RELIGIONS.values());
    }

    /** Maps a culture string (Plainfolk / Silkwood / Tidereach /
     *  Highmarch) to its dominant religion. Phase 5 culture pass
     *  swaps to a data-driven mapping. */
    public static String dominantReligionFor(String culture) {
        if (culture == null) return SUNSTEAD;
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "silkwood"   -> THE_LOOM;
            case "tidereach"  -> TIDECALL;
            case "highmarch"  -> FORGE_CREED;
            case "plainfolk", "default" -> SUNSTEAD;
            default -> SUNSTEAD;
        };
    }

    // ── Init ───────────────────────────────────────────────────────────────

    private static synchronized void ensureInit() {
        if (initialised) return;
        register(sunstead());
        register(theLoom());
        register(tidecall());
        register(forgeCreed());
        initialised = true;
        LOGGER.info("[ReligionRegistry] Registered {} religions", RELIGIONS.size());
    }

    private static void register(Religion r) {
        if (RELIGIONS.containsKey(r.id())) {
            LOGGER.warn("[ReligionRegistry] Duplicate religion id: {}", r.id());
        }
        RELIGIONS.put(r.id(), r);
    }

    // ── Starter religions ──────────────────────────────────────────────────

    private static Religion sunstead() {
        return new Religion(
                SUNSTEAD,
                "Sunstead",
                List.of("Honest labour rewards the labourer.",
                        "The sun returns; so does the harvest.",
                        "Greet the morning."),
                List.of(Rite.values()), // all 10
                List.of("field", "village_square"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("Spring Equinox", 80),   // ~ March 21
                        Map.entry("Harvest Equinox", 264), // ~ September 21
                        Map.entry("Midsummer", 172))),
                List.of(BookCategory.RELIGIOUS, BookCategory.HISTORY,
                        BookCategory.GUIDE),
                List.of(GodRegistry.SUN_MOTHER));   // F1a — venerates the Sun-Mother
    }

    private static Religion theLoom() {
        return new Religion(
                THE_LOOM,
                "The Loom",
                List.of("Every thread crosses another.",
                        "Pattern is fate; fate is pattern.",
                        "Speak truly to the cloth."),
                List.of(Rite.CONFESSION, Rite.BLESSING, Rite.NAMING,
                        Rite.FUNERAL, Rite.OFFERING, Rite.TITHE,
                        Rite.FEAST_DAY,
                        // R3b-2 — the confession-centric Loom observes communal
                        // purification (group atonement).
                        Rite.PURIFICATION,
                        // R3b-3 / R3d-2 — the Loom's signature Thread-Binding +
                        // its grand Great Weaving.
                        Rite.SIGNATURE_RITE, Rite.GRAND_FESTIVAL),
                List.of("loom_room", "household"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("First Threading",  30),
                        Map.entry("Second Threading", 90),
                        Map.entry("Third Threading", 180),
                        Map.entry("Fourth Threading",270))),
                List.of(BookCategory.RELIGIOUS, BookCategory.LITERATURE),
                List.of(GodRegistry.THE_PATTERN));  // F1a — venerates the (impersonal) Pattern
    }

    private static Religion tidecall() {
        return new Religion(
                TIDECALL,
                "Tidecall",
                List.of("The sea gives. The sea takes.",
                        "Listen for the song under the waves.",
                        "Salt remembers."),
                List.of(Rite.BLESSING, Rite.FEAST_DAY, Rite.OFFERING,
                        Rite.NAMING, Rite.FUNERAL, Rite.MARRIAGE,
                        Rite.HARVEST_THANKSGIVING,
                        // R3b-2 — Tidecall keeps the "Storm's Vigil" (a sea-
                        // mourning vigil for those lost to the deep).
                        Rite.VIGIL,
                        // R3b-3 / R3d-2 — Tidecall's signature Voyage Blessing +
                        // its grand Tides' Return.
                        Rite.SIGNATURE_RITE, Rite.GRAND_FESTIVAL),
                List.of("dock", "shore", "tidepool"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("Spring Tide",     45),
                        Map.entry("First Catch",   105),
                        Map.entry("Storm's Vigil", 200),
                        Map.entry("Last Catch",    300))),
                List.of(BookCategory.RELIGIOUS, BookCategory.TRAVELOGUE),
                List.of(GodRegistry.SEA_MOTHER));   // F1a — venerates the Sea-Mother
    }

    private static Religion forgeCreed() {
        return new Religion(
                FORGE_CREED,
                "The Forge Creed",
                List.of("Honour the ancestors who held the line.",
                        "Iron remembers; so should we.",
                        "Stand for those behind you."),
                List.of(Rite.COMING_OF_AGE, Rite.MARRIAGE, Rite.NAMING,
                        Rite.FUNERAL, Rite.BLESSING, Rite.OFFERING,
                        Rite.TITHE, Rite.FEAST_DAY,
                        // R3b-2 — the martial Forge Creed keeps the "Anvil
                        // Vigil" (a vigil of resolve before the ancestors).
                        Rite.VIGIL,
                        // R3b-3 / R3d-2 — the Forge Creed's signature Ancestor
                        // Oath + its grand Founding Day festival.
                        Rite.SIGNATURE_RITE, Rite.GRAND_FESTIVAL),
                List.of("forge", "barracks", "ancestor_hall"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("Founding Day",     12),
                        Map.entry("Anvil Vigil",     150),
                        Map.entry("Ancestor Day",    330))),
                List.of(BookCategory.RELIGIOUS, BookCategory.HISTORY),
                List.of(GodRegistry.FORGE_FATHER)); // F1a — venerates the First Forge-Father
    }

    /** Suppresses unused-import on Collections — reserved for future helpers. */
    @SuppressWarnings("unused")
    private static final List<String> DOC_HOOK = Collections.emptyList();
}
