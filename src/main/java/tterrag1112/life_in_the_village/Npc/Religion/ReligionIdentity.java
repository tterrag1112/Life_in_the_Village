package tterrag1112.life_in_the_village.Npc.Religion;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Religion Deepening D1 — the realized-culture <b>narrative</b> of a faith: its
 * cosmology, sacred history, aesthetics, and practices. A registry keyed by religion
 * id, read by the divine-event layer (visions / scripture) for the religion's story.
 *
 * <p><b>F1a cleanup — the deity moved out.</b> The deity identity (name, domain,
 * character, demands, rewards, virtues, taboos) now lives on the first-class
 * {@link God} (authored in {@link GodRegistry}); a religion references its gods by
 * id. This registry keeps only the RELIGION narrative — cosmology, {@link
 * SacredHistory}, {@link Aesthetics}, and practices — which is not a god concept.</p>
 */
public record ReligionIdentity(
        String religionId,
        String cosmology,
        SacredHistory history,
        Aesthetics aesthetics,
        List<String> practices
) {

    /** One ordered moment in a faith's sacred history. */
    public record HistoryEvent(String title, String text) {}

    /** Founding myth + the ordered key events (schisms, saints, foundational moments). */
    public record SacredHistory(String foundingMyth, List<HistoryEvent> events) {}

    /** Style hints for the later aesthetic hook (authorable, unconsumed). */
    public record Aesthetics(String styleId, String palette, String iconography) {}

    // =========================================================================
    // Registry
    // =========================================================================

    private static final Map<String, ReligionIdentity> REGISTRY = build();

    /** The authored narrative for {@code religionId}, or null when unauthored. */
    public static ReligionIdentity get(String religionId) {
        return REGISTRY.get(religionId);
    }

    public static Collection<ReligionIdentity> all() { return REGISTRY.values(); }

    /** The sacred-history event with the given title for {@code religionId}, or
     *  empty when the faith (or the title) is unknown. Used by D3c commemoration to
     *  resolve a calendar festival to the event it remembers. */
    public static java.util.Optional<HistoryEvent> eventByTitle(String religionId, String title) {
        ReligionIdentity id = get(religionId);
        if (id == null || title == null) return java.util.Optional.empty();
        for (HistoryEvent e : id.history().events()) {
            if (e.title().equalsIgnoreCase(title)) return java.util.Optional.of(e);
        }
        return java.util.Optional.empty();
    }

    // ── Authored narrative (the four starter faiths) ─────────────────────────

    private static Map<String, ReligionIdentity> build() {
        Map<String, ReligionIdentity> m = new LinkedHashMap<>();

        // ── Sunstead — solar / agrarian ─────────────────────────────────────
        m.put(ReligionRegistry.SUNSTEAD, new ReligionIdentity(
                ReligionRegistry.SUNSTEAD,
                "The Sun-Mother kindles the world at each dawn and carries it through "
                        + "the dark. Life is a turning wheel — seed, harvest, rest, return; "
                        + "what is given to the soil returns as bread. The dead pass into the "
                        + "long winter and are reborn with the spring.",
                new SacredHistory(
                        "When the first field went hungry, a farmwife knelt at dawn and offered "
                                + "her labour rather than her grief. The Sun-Mother answered, and the "
                                + "furrow ran gold.",
                        List.of(
                                new HistoryEvent("The First Furrow",
                                        "The founding dawn — the first seed offered and the first harvest given."),
                                new HistoryEvent("The Long Winter",
                                        "A famine endured when the villages opened their granaries to one another."),
                                new HistoryEvent("The Harvest Concord",
                                        "The custom of the shared feast, binding neighbouring fields in plenty."))),
                new Aesthetics("sunstead", "gold, wheat-amber, dawn-rose",
                        "the radiant sun-disc, sheaves of wheat, the open furrow"),
                List.of("Greeting the morning sun at first light.",
                        "The shared harvest feast at the equinoxes.",
                        "Offering the first sheaf at the field's edge.")));

        // ── The Loom — abstract pattern / fate ──────────────────────────────
        m.put(ReligionRegistry.THE_LOOM, new ReligionIdentity(
                ReligionRegistry.THE_LOOM,
                "There is no maker, only the Pattern — an endless weave in which every "
                        + "thread crosses another. A life is a thread; fate is the figure the "
                        + "threads compose. To live well is to weave true and not foul the cloth; "
                        + "in death a thread is taken up again into the whole.",
                new SacredHistory(
                        "The first weavers saw that no thread stands alone — pull one and the whole "
                                + "cloth shifts — and named the world a Loom.",
                        List.of(
                                new HistoryEvent("The First Threading",
                                        "The seeing of the Pattern: that each act crosses every other."),
                                new HistoryEvent("The Unravelling",
                                        "A schism over a great lie that knotted the cloth; mended by communal confession."),
                                new HistoryEvent("The Great Weaving",
                                        "The festival in which the whole community's year is woven into one figure."))),
                new Aesthetics("the_loom", "undyed linen, grey, indigo",
                        "interlaced threads, the loom-frame, the unbroken knotwork band"),
                List.of("Communal confession to mend a fouled thread.",
                        "The quarterly Threadings, marking the year's weave.",
                        "Speaking one's dealings plainly before the loom-room.")));

        // ── Tidecall — maritime ─────────────────────────────────────────────
        m.put(ReligionRegistry.TIDECALL, new ReligionIdentity(
                ReligionRegistry.TIDECALL,
                "The Sea-Mother is the deep that gives and takes without malice — fish and "
                        + "storm from the same water. The world is her shore; the living are "
                        + "her guests upon it. The drowned are gathered back into her, and the "
                        + "salt remembers every name.",
                new SacredHistory(
                        "When a fleet was lost to a sudden storm, the shore did not curse the sea but "
                                + "sang the names of the drowned into it — and the next tide came gentle.",
                        List.of(
                                new HistoryEvent("The First Catch",
                                        "The first net cast with thanks rather than greed, and drawn up full."),
                                new HistoryEvent("The Storm's Vigil",
                                        "The mourning-watch for those lost to the deep, sung from the shore."),
                                new HistoryEvent("The Tides' Return",
                                        "The festival of the sea's return after the lean season."))),
                new Aesthetics("tidecall", "sea-green, foam-white, storm-grey",
                        "the cresting wave, the gull, the knotted net and the tide-line"),
                List.of("Singing the names of the lost at the Storm's Vigil.",
                        "Blessing a boat before its first voyage.",
                        "Returning the first catch's smallest fish to the water.")));

        // ── The Forge Creed — ancestral / martial ───────────────────────────
        m.put(ReligionRegistry.FORGE_CREED, new ReligionIdentity(
                ReligionRegistry.FORGE_CREED,
                "The First Forge-Father struck the world from cold iron and tempered it in "
                        + "his people. The dead are not gone — they are the iron at our backs, the "
                        + "line that held before us. To live is to add one's own strength to that "
                        + "line; to die well is to be remembered as iron that did not break.",
                new SacredHistory(
                        "When the wall was breached, a smith who could have fled instead stood in the "
                                + "gap with the ancestors' names on his lips — and the line held.",
                        List.of(
                                new HistoryEvent("The First Forging",
                                        "The world struck from cold iron and tempered in its people."),
                                new HistoryEvent("The Anvil Vigil",
                                        "The night of resolve kept before the ancestors on the eve of trial."),
                                new HistoryEvent("Founding Day",
                                        "The oath that bound the living to the line of the dead."))),
                new Aesthetics("forge_creed", "iron-grey, ember-red, ash-black",
                        "the anvil and hammer, the ancestral grave-stone, the unbroken chain"),
                List.of("The Ancestor Oath sworn before the graves.",
                        "The Anvil Vigil of resolve before a trial.",
                        "Tending the ancestors' grave-stones and speaking their deeds.")));

        return m;
    }
}
