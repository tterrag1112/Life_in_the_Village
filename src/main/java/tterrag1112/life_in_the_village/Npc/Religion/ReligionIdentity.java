package tterrag1112.life_in_the_village.Npc.Religion;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Religion Deepening D1 — the realized-culture <b>identity</b> of a faith: its
 * cosmology, deity (character + domain), sacred history, virtues, taboos,
 * aesthetics, and practices. A parallel registry keyed by religion id (mirrors
 * {@link ReligionContent}) — <b>no {@link Religion} record/codec change.</b>
 *
 * <p><b>Reconciliation with the existing flavor fields:</b> the deity NAME stays
 * the single source in {@link Religion#deity()} (consumed by
 * {@link ReligionContent#invocation}); this identity adds the rich layer on top
 * ({@link Deity} = domain + character + demands + rewards, no name). The raw
 * {@link Religion#coreTenets()} likewise stay where they are (consumed by
 * {@link ReligionContent#tenet}); the {@link #virtues()} here are the structured,
 * concept-tagged ({@link FaithConcept}) version D2 will key behaviour on. Nothing
 * is duplicated or broken.</p>
 *
 * <p>Authorable: the entries are hand-written per faith (a refinable first pass).
 * The only consumer this phase is the {@code /religion identity} debug readout;
 * D2 consumes the virtues/taboos, D3 the deity/history/cosmology, D4 the
 * aesthetics — none of that is wired here.</p>
 */
public record ReligionIdentity(
        String religionId,
        String cosmology,
        Deity deity,
        SacredHistory history,
        List<Virtue> virtues,
        List<Taboo> taboos,
        Aesthetics aesthetics,
        List<String> practices
) {

    /** The deity's domain — added only for the four faiths' actual domains. The
     *  Loom has no personified deity but a domain of fate/pattern. */
    public enum DeityDomain { SUN, SEA, FORGE, FATE }

    /** The rich deity layer (the NAME stays in {@link Religion#deity()}). */
    public record Deity(DeityDomain domain, String character, String demands, String rewards) {}

    /** One ordered moment in a faith's sacred history. */
    public record HistoryEvent(String title, String text) {}

    /** Founding myth + the ordered key events (schisms, saints, foundational moments). */
    public record SacredHistory(String foundingMyth, List<HistoryEvent> events) {}

    /** An esteemed value — a {@link FaithConcept} D2 keys on + authored text. */
    public record Virtue(FaithConcept concept, String text) {}

    /** A forbidden act — a {@link FaithConcept} D2 keys on + authored text. */
    public record Taboo(FaithConcept concept, String text) {}

    /** Style hints for the later D4 NBT/aesthetic hook (authorable, unconsumed). */
    public record Aesthetics(String styleId, String palette, String iconography) {}

    // =========================================================================
    // Registry
    // =========================================================================

    private static final Map<String, ReligionIdentity> REGISTRY = build();

    /** The authored identity for {@code religionId}, or null when unauthored. */
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

    // ── Authored content (the four starter faiths — a refinable first pass) ──

    private static Map<String, ReligionIdentity> build() {
        Map<String, ReligionIdentity> m = new LinkedHashMap<>();

        // ── Sunstead — the Sun-Mother; solar / agrarian ─────────────────────
        m.put(ReligionRegistry.SUNSTEAD, new ReligionIdentity(
                ReligionRegistry.SUNSTEAD,
                "The Sun-Mother kindles the world at each dawn and carries it through "
                        + "the dark. Life is a turning wheel — seed, harvest, rest, return; "
                        + "what is given to the soil returns as bread. The dead pass into the "
                        + "long winter and are reborn with the spring.",
                new Deity(DeityDomain.SUN,
                        "A warm but exacting mother: generous to the diligent, cold to the "
                                + "idle. She rises without fail and asks the same of her people.",
                        "early rising, honest work, and gratitude at the harvest",
                        "ripened fields, a warm hearth, and rebirth with the spring"),
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
                List.of(
                        new Virtue(FaithConcept.HONEST_LABOUR,
                                "Honest labour rewards the labourer; the Sun-Mother sees the diligent hand."),
                        new Virtue(FaithConcept.GENEROSITY,
                                "A full granary is shared, not hoarded — plenty is the village's, not one house's.")),
                List.of(
                        new Taboo(FaithConcept.IDLENESS,
                                "To shirk the day's work is to spurn the light that gives it."),
                        new Taboo(FaithConcept.GREED,
                                "To hoard the harvest while a neighbour hungers is to insult the giver.")),
                new Aesthetics("sunstead", "gold, wheat-amber, dawn-rose",
                        "the radiant sun-disc, sheaves of wheat, the open furrow"),
                List.of("Greeting the morning sun at first light.",
                        "The shared harvest feast at the equinoxes.",
                        "Offering the first sheaf at the field's edge.")));

        // ── The Loom — abstract pattern / fate; no deity ────────────────────
        m.put(ReligionRegistry.THE_LOOM, new ReligionIdentity(
                ReligionRegistry.THE_LOOM,
                "There is no maker, only the Pattern — an endless weave in which every "
                        + "thread crosses another. A life is a thread; fate is the figure the "
                        + "threads compose. To live well is to weave true and not foul the cloth; "
                        + "in death a thread is taken up again into the whole.",
                new Deity(DeityDomain.FATE,
                        "Impersonal and unbroken — the Pattern is not prayed to but kept faith "
                                + "with. It neither rewards nor punishes; it only records.",
                        "truth in word and work, and a thread kept clean of knots",
                        "a life that lies true in the weave, and a clean return to the whole"),
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
                List.of(
                        new Virtue(FaithConcept.TRUTHFULNESS,
                                "Speak truly to the cloth; a false thread fouls every thread it crosses."),
                        new Virtue(FaithConcept.HARMONY,
                                "Keep the pattern whole — your thread is bound to every other.")),
                List.of(
                        new Taboo(FaithConcept.DECEIT,
                                "A lie is a knot in the weave; it snares more than the one who tied it."),
                        new Taboo(FaithConcept.DISCORD,
                                "To set thread against thread is to tear the cloth that holds you too.")),
                new Aesthetics("the_loom", "undyed linen, grey, indigo",
                        "interlaced threads, the loom-frame, the unbroken knotwork band"),
                List.of("Communal confession to mend a fouled thread.",
                        "The quarterly Threadings, marking the year's weave.",
                        "Speaking one's dealings plainly before the loom-room.")));

        // ── Tidecall — the Sea-Mother; maritime ─────────────────────────────
        m.put(ReligionRegistry.TIDECALL, new ReligionIdentity(
                ReligionRegistry.TIDECALL,
                "The Sea-Mother is the deep that gives and takes without malice — fish and "
                        + "storm from the same water. The world is her shore; the living are "
                        + "her guests upon it. The drowned are gathered back into her, and the "
                        + "salt remembers every name.",
                new Deity(DeityDomain.SEA,
                        "Vast, generous, and perilous — not cruel but not safe. She honours those "
                                + "who go out with respect and return what they do not need.",
                        "humility before the deep, and remembrance of those it has taken",
                        "full nets, fair passage, and a name the salt will keep"),
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
                List.of(
                        new Virtue(FaithConcept.RESPECT_THE_SEA,
                                "The sea gives and the sea takes; go out humble and take only your need."),
                        new Virtue(FaithConcept.REMEMBRANCE,
                                "Salt remembers — sing the names of the lost so the deep keeps them well.")),
                List.of(
                        new Taboo(FaithConcept.RECKLESSNESS,
                                "To dare the deep heedlessly is to spit in the Sea-Mother's face."),
                        new Taboo(FaithConcept.SACRILEGE,
                                "To foul the shore or mock the drowned is to break faith with the deep.")),
                new Aesthetics("tidecall", "sea-green, foam-white, storm-grey",
                        "the cresting wave, the gull, the knotted net and the tide-line"),
                List.of("Singing the names of the lost at the Storm's Vigil.",
                        "Blessing a boat before its first voyage.",
                        "Returning the first catch's smallest fish to the water.")));

        // ── The Forge Creed — the First Forge-Father; ancestral / martial ───
        m.put(ReligionRegistry.FORGE_CREED, new ReligionIdentity(
                ReligionRegistry.FORGE_CREED,
                "The First Forge-Father struck the world from cold iron and tempered it in "
                        + "his people. The dead are not gone — they are the iron at our backs, the "
                        + "line that held before us. To live is to add one's own strength to that "
                        + "line; to die well is to be remembered as iron that did not break.",
                new Deity(DeityDomain.FORGE,
                        "Stern, steadfast, and unbreaking — a father who measures a life by what "
                                + "it held against and whom it stood for, not by what it gathered.",
                        "courage, loyalty to kin, and honour to the ancestors who held the line",
                        "a name remembered as iron, and a place in the line that endures"),
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
                List.of(
                        new Virtue(FaithConcept.HONOUR_THE_ANCESTORS,
                                "Honour the ancestors who held the line; iron remembers, so should we."),
                        new Virtue(FaithConcept.LOYALTY,
                                "Stand for those behind you — the line holds only while each link holds."),
                        new Virtue(FaithConcept.VALOUR,
                                "Meet trial standing; courage is the temper that keeps iron from breaking.")),
                List.of(
                        new Taboo(FaithConcept.COWARDICE,
                                "To abandon kin in the breach is to break the line and one's own name with it."),
                        new Taboo(FaithConcept.SACRILEGE,
                                "To dishonour the ancestors' graves is to spurn the iron at your back.")),
                new Aesthetics("forge_creed", "iron-grey, ember-red, ash-black",
                        "the anvil and hammer, the ancestral grave-stone, the unbroken chain"),
                List.of("The Ancestor Oath sworn before the graves.",
                        "The Anvil Vigil of resolve before a trial.",
                        "Tending the ancestors' grave-stones and speaking their deeds.")));

        return m;
    }
}
