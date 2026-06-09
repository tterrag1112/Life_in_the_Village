package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Foundation 1 (F1a) — the canonical home of the four {@link God}s, mirroring
 * {@link ReligionRegistry}'s shape (static, lazy-init, idempotent).
 *
 * <p><b>The authored source (F1a cleanup).</b> The gods' deity content — name,
 * {@link DeityDomain}, character, demands, rewards, {@link Virtue}s, {@link Taboo}s —
 * is hand-authored here on the {@link God} (no longer derived from
 * {@code ReligionIdentity}/{@code Religion.deity()}, which kept only religion
 * NARRATIVE: cosmology / sacred history / aesthetics / practices). A religion
 * references its gods by id ({@link Religion#godIds()}); the reverse index maps a
 * god back to the religions that venerate it (for the piety tier + narrative).</p>
 */
public final class GodRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SUN_MOTHER   = "sun_mother";
    public static final String THE_PATTERN  = "the_pattern";
    public static final String SEA_MOTHER   = "sea_mother";
    public static final String FORGE_FATHER = "forge_father";

    private static final Map<String, God> GODS = new LinkedHashMap<>();
    /** F1a sub-stage 3a — reverse index: god id → the religions that venerate it.
     *  Used by the favour economy to resolve a god's piety tier (piety is belief in
     *  a RELIGION) and by 3b to map a god back to its religion for content. */
    private static final Map<String, List<String>> RELIGIONS_BY_GOD = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    private GodRegistry() {}

    public static God get(String id) {
        ensureInit();
        return GODS.get(id);
    }

    public static Optional<God> find(String id) {
        ensureInit();
        return Optional.ofNullable(GODS.get(id));
    }

    public static List<God> all() {
        ensureInit();
        return List.copyOf(GODS.values());
    }

    // ── Religion → god(s) resolver (F1a sub-stage 2) ─────────────────────────

    /**
     * The gods a religion venerates, in order (first = primary), resolved from
     * {@link Religion#godIds()}. An unknown / not-yet-registered god id is skipped
     * with a warn (graceful — a typo degrades, never NPEs). The link sub-stage 3
     * rides on (which god owns a religion's favour/miracles).
     */
    public static List<God> godsFor(Religion religion) {
        ensureInit();
        if (religion == null) return List.of();
        List<God> out = new java.util.ArrayList<>();
        for (String gid : religion.godIds()) {
            God g = GODS.get(gid);
            if (g == null) {
                LOGGER.warn("[GodRegistry] Religion '{}' references unknown god id '{}' — skipping",
                        religion.id(), gid);
                continue;
            }
            out.add(g);
        }
        return out;
    }

    /** The religion's primary (first venerated) god, or empty for a god-less one. */
    public static Optional<God> primaryGod(Religion religion) {
        List<God> gods = godsFor(religion);
        return gods.isEmpty() ? Optional.empty() : Optional.of(gods.get(0));
    }

    /** F1a sub-stage 3a — the religion ids that venerate {@code godId} (reverse of
     *  {@link Religion#godIds()}). Used to resolve a god's piety tier (the best
     *  belief among its venerating religions). Empty for an unknown god. */
    public static List<String> religionsVenerating(String godId) {
        ensureInit();
        return godId == null ? List.of() : RELIGIONS_BY_GOD.getOrDefault(godId, List.of());
    }

    /** F1a sub-stage 3b — a religion that venerates {@code godId} (its first), for
     *  reading RELIGION-narrative attributes (cosmology / calendar / sacred history)
     *  the deity attributes don't cover. Empty for an unknown / unvenerated god. */
    public static Optional<Religion> primaryReligionOf(String godId) {
        List<String> rids = religionsVenerating(godId);
        return rids.isEmpty() ? Optional.empty() : ReligionRegistry.find(rids.get(0));
    }

    /**
     * F1a sub-stage 3b — the distinct gods a player has standing with: the gods of
     * every religion the player believes in (deduped, belief order). The one shared
     * iteration subject for the divine-event ticks (visions / wrath / theophany), so
     * each doesn't re-derive it. Empty for an atheist player.
     */
    public static List<God> playerGods(ServerLevel level, UUID playerId) {
        ensureInit();
        PietyComponent piety = RiteSavedData.get(level).getPlayerPiety(playerId).orElse(null);
        if (piety == null) return List.of();
        LinkedHashSet<God> set = new LinkedHashSet<>();
        for (String rid : piety.beliefs().keySet()) {
            ReligionRegistry.find(rid).ifPresent(r -> set.addAll(godsFor(r)));
        }
        return new ArrayList<>(set);
    }

    // ── Multi-god policy (F1a sub-stage 4a — the ONE home for these decisions) ──

    /** Headline deity NAME → the PRIMARY god's name, else {@code fallback} (the
     *  religion display name / "" — matching the old {@code deity().orElse(...)}
     *  shape; an impersonal primary god has no name, so the fallback is used). */
    public static String primaryDeityName(Religion r, String fallback) {
        ensureInit();
        return r == null ? fallback : primaryGod(r).flatMap(God::name).orElse(fallback);
    }

    /** Union policy — does ANY god the religion venerates esteem {@code c} as a
     *  virtue? (A religion-level judgment recognises every virtue any of its gods
     *  esteems.) */
    public static boolean anyGodHoldsVirtue(Religion r, FaithConcept c) {
        if (r == null || c == null) return false;
        for (God g : godsFor(r)) {
            if (g.virtues().stream().anyMatch(v -> v.concept() == c)) return true;
        }
        return false;
    }

    /** Union policy — any god the religion venerates forbids {@code c} as a taboo. */
    public static boolean anyGodHoldsTaboo(Religion r, FaithConcept c) {
        if (r == null || c == null) return false;
        for (God g : godsFor(r)) {
            if (g.taboos().stream().anyMatch(t -> t.concept() == c)) return true;
        }
        return false;
    }

    /** Offense targeting — the gods of the religion that forbid {@code c} (the
     *  SPECIFIC offended god(s) on a taboo; single-god → the one god). */
    public static List<God> godsTabooing(Religion r, FaithConcept c) {
        ensureInit();
        if (r == null || c == null) return List.of();
        List<God> out = new ArrayList<>();
        for (God g : godsFor(r)) {
            if (g.taboos().stream().anyMatch(t -> t.concept() == c)) out.add(g);
        }
        return out;
    }

    /** Union of the religion's gods' virtues, deduped by concept (authoring order). */
    public static List<Virtue> unionVirtues(Religion r) {
        ensureInit();
        List<Virtue> out = new ArrayList<>();
        java.util.Set<FaithConcept> seen = new java.util.HashSet<>();
        if (r != null) for (God g : godsFor(r)) {
            for (Virtue v : g.virtues()) if (seen.add(v.concept())) out.add(v);
        }
        return out;
    }

    /** Union of the religion's gods' taboos, deduped by concept (authoring order). */
    public static List<Taboo> unionTaboos(Religion r) {
        ensureInit();
        List<Taboo> out = new ArrayList<>();
        java.util.Set<FaithConcept> seen = new java.util.HashSet<>();
        if (r != null) for (God g : godsFor(r)) {
            for (Taboo t : g.taboos()) if (seen.add(t.concept())) out.add(t);
        }
        return out;
    }

    // ── Init ───────────────────────────────────────────────────────────────

    private static synchronized void ensureInit() {
        if (initialised) return;
        register(sunMother());
        register(thePattern());
        register(seaMother());
        register(forgeFather());
        // Build the reverse index from the authored religion → godIds links.
        for (Religion r : ReligionRegistry.all()) {
            for (String gid : r.godIds()) {
                RELIGIONS_BY_GOD.computeIfAbsent(gid, k -> new ArrayList<>()).add(r.id());
            }
        }
        initialised = true;
        LOGGER.info("[GodRegistry] Registered {} gods", GODS.size());
    }

    private static void register(God g) {
        if (g == null) return;
        if (GODS.containsKey(g.id())) {
            LOGGER.warn("[GodRegistry] Duplicate god id: {}", g.id());
        }
        GODS.put(g.id(), g);
    }

    // ── Authored gods (F1a cleanup — the gods are now the authored source) ───

    private static God sunMother() {
        return new God(SUN_MOTHER, Optional.of("the Sun-Mother"), DeityDomain.SUN,
                "A warm but exacting mother: generous to the diligent, cold to the "
                        + "idle. She rises without fail and asks the same of her people.",
                "early rising, honest work, and gratitude at the harvest",
                "ripened fields, a warm hearth, and rebirth with the spring",
                List.of(
                        new Virtue(FaithConcept.HONEST_LABOUR,
                                "Honest labour rewards the labourer; the Sun-Mother sees the diligent hand."),
                        new Virtue(FaithConcept.GENEROSITY,
                                "A full granary is shared, not hoarded — plenty is the village's, not one house's.")),
                List.of(
                        new Taboo(FaithConcept.IDLENESS,
                                "To shirk the day's work is to spurn the light that gives it."),
                        new Taboo(FaithConcept.GREED,
                                "To hoard the harvest while a neighbour hungers is to insult the giver.")));
    }

    private static God thePattern() {
        return new God(THE_PATTERN, Optional.empty(), DeityDomain.FATE,
                "Impersonal and unbroken — the Pattern is not prayed to but kept faith "
                        + "with. It neither rewards nor punishes; it only records.",
                "truth in word and work, and a thread kept clean of knots",
                "a life that lies true in the weave, and a clean return to the whole",
                List.of(
                        new Virtue(FaithConcept.TRUTHFULNESS,
                                "Speak truly to the cloth; a false thread fouls every thread it crosses."),
                        new Virtue(FaithConcept.HARMONY,
                                "Keep the pattern whole — your thread is bound to every other.")),
                List.of(
                        new Taboo(FaithConcept.DECEIT,
                                "A lie is a knot in the weave; it snares more than the one who tied it."),
                        new Taboo(FaithConcept.DISCORD,
                                "To set thread against thread is to tear the cloth that holds you too.")));
    }

    private static God seaMother() {
        return new God(SEA_MOTHER, Optional.of("the Sea-Mother"), DeityDomain.SEA,
                "Vast, generous, and perilous — not cruel but not safe. She honours those "
                        + "who go out with respect and return what they do not need.",
                "humility before the deep, and remembrance of those it has taken",
                "full nets, fair passage, and a name the salt will keep",
                List.of(
                        new Virtue(FaithConcept.RESPECT_THE_SEA,
                                "The sea gives and the sea takes; go out humble and take only your need."),
                        new Virtue(FaithConcept.REMEMBRANCE,
                                "Salt remembers — sing the names of the lost so the deep keeps them well.")),
                List.of(
                        new Taboo(FaithConcept.RECKLESSNESS,
                                "To dare the deep heedlessly is to spit in the Sea-Mother's face."),
                        new Taboo(FaithConcept.SACRILEGE,
                                "To foul the shore or mock the drowned is to break faith with the deep.")));
    }

    private static God forgeFather() {
        return new God(FORGE_FATHER, Optional.of("the First Forge-Father"), DeityDomain.FORGE,
                "Stern, steadfast, and unbreaking — a father who measures a life by what "
                        + "it held against and whom it stood for, not by what it gathered.",
                "courage, loyalty to kin, and honour to the ancestors who held the line",
                "a name remembered as iron, and a place in the line that endures",
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
                                "To dishonour the ancestors' graves is to spurn the iron at your back.")));
    }
}
