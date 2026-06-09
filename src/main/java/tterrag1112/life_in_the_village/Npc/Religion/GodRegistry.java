package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Foundation 1 (F1a) — the canonical home of the four {@link God}s, mirroring
 * {@link ReligionRegistry}'s shape (static, lazy-init, idempotent). This sub-stage
 * has <b>no consumer</b> besides the {@code /religion gods} debug readout — gods are
 * not yet referenced by any religion or by the divine layer (sub-stages 2/3).
 *
 * <p><b>Derived, not re-authored.</b> Each god is built at init from its existing
 * authored source — {@link ReligionIdentity#get} (domain/character/demands/rewards/
 * virtues/taboos) + {@link ReligionRegistry#get}'s {@code deity()} (the name) — so
 * there is a single source of truth and no drift while gods and religions coexist.
 * This derivation is <b>transient</b>: sub-stage 2 makes the religion reference gods
 * and {@code ReligionIdentity}/{@code Religion.deity()} become thin delegates, at
 * which point the god is the sole source.</p>
 */
public final class GodRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SUN_MOTHER   = "sun_mother";
    public static final String THE_PATTERN  = "the_pattern";
    public static final String SEA_MOTHER   = "sea_mother";
    public static final String FORGE_FATHER = "forge_father";

    private static final Map<String, God> GODS = new LinkedHashMap<>();
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

    // ── Init ───────────────────────────────────────────────────────────────

    private static synchronized void ensureInit() {
        if (initialised) return;
        register(derive(SUN_MOTHER,   ReligionRegistry.SUNSTEAD));
        register(derive(THE_PATTERN,  ReligionRegistry.THE_LOOM));
        register(derive(SEA_MOTHER,   ReligionRegistry.TIDECALL));
        register(derive(FORGE_FATHER, ReligionRegistry.FORGE_CREED));
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

    /**
     * Builds a god from its religion's authored content: the rich
     * {@link ReligionIdentity.Deity} layer + virtues/taboos, and the personal name
     * from {@link Religion#deity()} (empty → an impersonal god, the Pattern).
     */
    private static God derive(String godId, String religionId) {
        ReligionIdentity identity = ReligionIdentity.get(religionId);
        if (identity == null) {
            LOGGER.warn("[GodRegistry] No identity for {} — skipping god {}", religionId, godId);
            return null;
        }
        Religion religion = ReligionRegistry.get(religionId);
        Optional<String> name = religion != null ? religion.deity() : Optional.empty();
        ReligionIdentity.Deity deity = identity.deity();
        return new God(godId, name, deity.domain(),
                deity.character(), deity.demands(), deity.rewards(),
                identity.virtues(), identity.taboos());
    }
}
