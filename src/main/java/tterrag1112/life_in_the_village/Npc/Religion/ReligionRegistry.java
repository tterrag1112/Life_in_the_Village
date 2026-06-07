package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static registry of every {@link Religion}. Populated lazily on
 * first access; idempotent. Phase 5 culture wiring may swap in a
 * data-pack-driven loader; v1 hard-codes the four starter religions
 * per spec line 36.
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

    public static Religion get(String id) {
        ensureInit();
        return RELIGIONS.get(id);
    }

    public static Optional<Religion> find(String id) {
        ensureInit();
        return Optional.ofNullable(RELIGIONS.get(id));
    }

    public static List<Religion> all() {
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
                Optional.of("the Sun-Mother"),
                List.of(BookCategory.RELIGIOUS, BookCategory.HISTORY,
                        BookCategory.GUIDE));
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
                        // R3b-3 — the Loom's signature Thread-Binding.
                        Rite.SIGNATURE_RITE),
                List.of("loom_room", "household"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("First Threading",  30),
                        Map.entry("Second Threading", 90),
                        Map.entry("Third Threading", 180),
                        Map.entry("Fourth Threading",270))),
                Optional.empty(),
                List.of(BookCategory.RELIGIOUS, BookCategory.LITERATURE));
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
                        // R3b-3 — Tidecall's signature Voyage Blessing.
                        Rite.SIGNATURE_RITE),
                List.of("dock", "shore", "tidepool"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("Spring Tide",     45),
                        Map.entry("First Catch",   105),
                        Map.entry("Storm's Vigil", 200),
                        Map.entry("Last Catch",    300))),
                Optional.of("the Sea-Mother"),
                List.of(BookCategory.RELIGIOUS, BookCategory.TRAVELOGUE));
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
                        // R3b-3 — the Forge Creed's signature Ancestor Oath.
                        Rite.SIGNATURE_RITE),
                List.of("forge", "barracks", "ancestor_hall"),
                ReligiousCalendar.of(Map.ofEntries(
                        Map.entry("Founding Day",     12),
                        Map.entry("Anvil Vigil",     150),
                        Map.entry("Ancestor Day",    330))),
                Optional.of("the First Forge-Father"),
                List.of(BookCategory.RELIGIOUS, BookCategory.HISTORY));
    }

    /** Suppresses unused-import on Collections — reserved for future helpers. */
    @SuppressWarnings("unused")
    private static final List<String> DOC_HOOK = Collections.emptyList();
}
