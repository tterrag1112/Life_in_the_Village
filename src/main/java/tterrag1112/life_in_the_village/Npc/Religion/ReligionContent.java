package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Religion Rework R3a — the single per-religion content layer. Makes the four
 * faiths genuinely distinct without new rites: each religion tunes the EXISTING
 * rites' effects (mood/piety scalars) and flavor through a sparse
 * {@link RiteProfile} table, and this class is also the one place that brings
 * the previously-dead {@link Religion} flavor fields ({@code deity},
 * {@code coreTenets}) to life.
 *
 * <p><b>Lower-churn choice:</b> a parallel registry keyed by religion id rather
 * than extending the {@link Religion} record/codec — no schema change, no
 * field-cap pressure, and all the new content lives in exactly one file. The
 * resolution authority for "which religion governs effects here" is the
 * village's dominant religion (the officiating faith), per R3a disposition —
 * not the participant's personal belief (which still receives the piety
 * credit). With one religion per village these coincide; multi-faith is a
 * later R3 phase.</p>
 */
public final class ReligionContent {

    private ReligionContent() {}

    /** religionId → (Rite → profile). Sparse: only distinguished rites listed. */
    private static final Map<String, Map<Rite, RiteProfile>> PROFILES = build();

    // ── Resolution ────────────────────────────────────────────────────────

    /** The dominant (officiating) religion id for a village — the canonical
     *  authority for rite-effect tuning. Falls back to Sunstead. */
    public static String villageReligionId(ServerLevel level, Village village) {
        if (level == null || village == null) return ReligionRegistry.SUNSTEAD;
        String culture = VillageSavedData.get(level).getKingdomForVillage(village.getId())
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                .orElse("default");
        return ReligionRegistry.dominantReligionFor(culture);
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    /** The tuning/flavor profile for a (religion, rite), or {@link
     *  RiteProfile#DEFAULT} when the religion doesn't distinguish that rite. */
    public static RiteProfile profileFor(String religionId, Rite rite) {
        Map<Rite, RiteProfile> byRite = PROFILES.get(religionId);
        if (byRite == null) return RiteProfile.DEFAULT;
        return byRite.getOrDefault(rite, RiteProfile.DEFAULT);
    }

    /**
     * Deity-aware invocation phrase woven into rite text. Named deity where the
     * religion has one ("the Sun-Mother"); the faith's own name for an abstract
     * religion ("The Loom"). Consumes the formerly-dead {@code deity} field.
     */
    public static String invocation(String religionId) {
        Religion r = ReligionRegistry.get(religionId);
        if (r == null) return "the divine";
        return r.deity().orElse(r.displayName());
    }

    /** A core tenet line for confession / sermon flavor, if the religion has
     *  any. Consumes the formerly-dead {@code coreTenets} field. */
    public static Optional<String> tenet(String religionId, RandomSource rng) {
        Religion r = ReligionRegistry.get(religionId);
        if (r == null) return Optional.empty();
        List<String> tenets = r.coreTenets();
        if (tenets.isEmpty()) return Optional.empty();
        return Optional.of(tenets.get(rng == null ? 0 : rng.nextInt(tenets.size())));
    }

    /** Convenience: the rite's flavor line for this religion, if specified. */
    public static Optional<String> flavor(String religionId, Rite rite) {
        return profileFor(religionId, rite).flavor();
    }

    // ── Authored content (the four starter religions) ─────────────────────

    private static Map<String, Map<Rite, RiteProfile>> build() {
        Map<String, Map<Rite, RiteProfile>> m = new HashMap<>();

        // Sunstead — solar / agrarian, the Sun-Mother. Harvest + labour emphasis.
        m.put(ReligionRegistry.SUNSTEAD, profiles(
                Map.entry(Rite.HARVEST_THANKSGIVING,
                        RiteProfile.of(1.5f, 1.5f, "the Sun-Mother blesses the gathered harvest")),
                Map.entry(Rite.BLESSING,
                        RiteProfile.of(1.2f, 1.0f, "may your fields ripen golden")),
                Map.entry(Rite.COMING_OF_AGE,
                        RiteProfile.of(1.1f, 1.0f, "honest labour now rewards the labourer")),
                Map.entry(Rite.FEAST_DAY,
                        RiteProfile.flavored("a day of sun and plenty")),
                Map.entry(Rite.CONSECRATION,
                        RiteProfile.of(1.2f, 1.2f, "this ground is given to the Sun-Mother's light")),
                // R3b-2 — Sunstead atones communally under the open sky.
                Map.entry(Rite.PURIFICATION,
                        RiteProfile.of(1.0f, 1.0f, "let the sun burn away the shadow on your heart")),
                // R3b-3 — signature First Furrow: a bright agrarian boon (mood +
                // a treasury blessing for the sown year), no relationship lean.
                Map.entry(Rite.SIGNATURE_RITE,
                        RiteProfile.signature(1.2f, 0, 40L,
                                "the First Furrow — may the first seed take root and the year run golden"))));

        // The Loom — abstract fate, no deity. Confession-centric, pattern flavor.
        m.put(ReligionRegistry.THE_LOOM, profiles(
                Map.entry(Rite.CONFESSION,
                        RiteProfile.of(1.5f, 1.0f, "every thread is seen in the pattern")),
                Map.entry(Rite.NAMING,
                        RiteProfile.of(1.1f, 1.0f, "a new thread is set upon the Loom")),
                Map.entry(Rite.FUNERAL,
                        RiteProfile.of(1.0f, 1.0f, "the thread is cut, the pattern remains")),
                // Quieter feast days — the Loom keeps to the household.
                Map.entry(Rite.FEAST_DAY,
                        RiteProfile.tuned(0.8f, 1.0f)),
                Map.entry(Rite.CONSECRATION,
                        RiteProfile.of(1.0f, 1.0f, "this loom-room is woven into the pattern")),
                // R3b-2 — the confession-centric Loom's group atonement runs deep.
                Map.entry(Rite.PURIFICATION,
                        RiteProfile.of(1.3f, 1.0f, "the snarled threads are combed straight again")),
                // R3b-3 — signature Thread-Binding: the binding of fates — a
                // strong relationship boost among attendees (no deity, abstract).
                Map.entry(Rite.SIGNATURE_RITE,
                        RiteProfile.signature(1.0f, 8, 0L,
                                "the Thread-Binding — your fates are woven into one cloth"))));

        // Tidecall — sea-spirits, the Sea-Mother. Voyage blessings, tide feasts.
        m.put(ReligionRegistry.TIDECALL, profiles(
                Map.entry(Rite.FEAST_DAY,
                        RiteProfile.of(1.4f, 1.2f, "the tide runs high and the nets run full")),
                Map.entry(Rite.BLESSING,
                        RiteProfile.of(1.2f, 1.0f, "fair winds and a following sea")),
                Map.entry(Rite.NAMING,
                        RiteProfile.of(1.1f, 1.0f, "salt remembers the new name")),
                Map.entry(Rite.HARVEST_THANKSGIVING,
                        RiteProfile.flavored("thanks for the sea's harvest")),
                Map.entry(Rite.CONSECRATION,
                        RiteProfile.of(1.1f, 1.1f, "the Sea-Mother claims this shore")),
                // R3b-2 — Storm's Vigil: a sea-mourning for those lost to the deep.
                Map.entry(Rite.VIGIL,
                        RiteProfile.of(1.0f, 1.0f, "we keep the watch for those the sea has taken")),
                // R3b-3 — signature Voyage Blessing: a protective blessing for
                // seafarers — a steadying mood pulse (no relationship/treasury).
                Map.entry(Rite.SIGNATURE_RITE,
                        RiteProfile.signature(1.2f, 0, 0L,
                                "the Voyage Blessing — may the sea bear you out and bring you home"))));

        // The Forge Creed — ancestor / martial. Honor funerals, martial coming-of-age.
        m.put(ReligionRegistry.FORGE_CREED, profiles(
                Map.entry(Rite.FUNERAL,
                        RiteProfile.of(1.5f, 1.3f, "their name is struck into the anvil of memory")),
                Map.entry(Rite.COMING_OF_AGE,
                        RiteProfile.of(1.3f, 1.1f, "stand now for those behind you")),
                Map.entry(Rite.OFFERING,
                        RiteProfile.of(1.1f, 1.1f, "honour the ancestors who held the line")),
                Map.entry(Rite.MARRIAGE,
                        RiteProfile.flavored("two lines forged into one")),
                // R3b-1 — the Forge Creed's kingdom-day feast (its holy day),
                // previously DEFAULT; a martial ancestor-day observance.
                Map.entry(Rite.FEAST_DAY,
                        RiteProfile.of(1.1f, 1.0f, "we feast for the ancestors who held the line")),
                Map.entry(Rite.CONSECRATION,
                        RiteProfile.of(1.3f, 1.2f, "this hall is forged into the ancestors' keeping")),
                // R3b-2 — Anvil Vigil: a vigil of martial resolve before the
                // ancestors (a stronger, steadying observance).
                Map.entry(Rite.VIGIL,
                        RiteProfile.of(1.3f, 1.0f, "we stand the Anvil Vigil; the line will hold")),
                // R3b-3 — signature Ancestor Oath: resolve/honor mood + a kin-bond
                // relationship nudge among those who swear together.
                Map.entry(Rite.SIGNATURE_RITE,
                        RiteProfile.signature(1.2f, 6, 0L,
                                "the Ancestor Oath — swear before those who held the line"))));

        return Map.copyOf(m);
    }

    @SafeVarargs
    private static Map<Rite, RiteProfile> profiles(Map.Entry<Rite, RiteProfile>... entries) {
        Map<Rite, RiteProfile> out = new EnumMap<>(Rite.class);
        for (Map.Entry<Rite, RiteProfile> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }
}
