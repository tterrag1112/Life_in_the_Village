package tterrag1112.life_in_the_village.Npc.Verbs;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.AskAboutLifeVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.AskAboutVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.ChallengeVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.ComplimentVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.CommissionVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.GiveGiftVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.BorrowBookVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.CommissionBookCopyVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.CommissionLetterVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.GreetVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.InsultVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.ListenInVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.TakeLessonVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.Impl.TellRumorVerb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static catalogue of {@link PlayerVerb} implementations. Lazy-init
 * idempotent. Phase 1 ships 8 starter verbs per spec
 * {@code 09-player-verbs.md} line 46.
 */
public final class PlayerVerbRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, PlayerVerb> VERBS = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    private PlayerVerbRegistry() {}

    public static synchronized void register(PlayerVerb verb) {
        if (VERBS.containsKey(verb.id())) {
            LOGGER.warn("[PlayerVerbRegistry] Duplicate verb id: {}", verb.id());
        }
        VERBS.put(verb.id(), verb);
    }

    public static Optional<PlayerVerb> get(String id) {
        ensureInit();
        return Optional.ofNullable(VERBS.get(id));
    }

    public static List<PlayerVerb> all() {
        ensureInit();
        return List.copyOf(VERBS.values());
    }

    /**
     * Returns verbs for which {@link PlayerVerb#isAvailable} returns
     * true under {@code ctx}, sorted by {@link PlayerVerb#displayOrder}.
     * Cooldown checks are NOT performed here — they're checked by each
     * verb's invoke path so the UI can still surface the button with
     * a "on cooldown" status.
     */
    public static List<PlayerVerb> availableFor(VerbContext ctx) {
        ensureInit();
        List<PlayerVerb> out = new ArrayList<>();
        for (PlayerVerb v : VERBS.values()) {
            try {
                if (v.isAvailable(ctx)) out.add(v);
            } catch (Throwable t) {
                LOGGER.debug("[PlayerVerbRegistry] {}.isAvailable threw: {}", v.id(), t.getMessage());
            }
        }
        out.sort(Comparator.comparingInt(PlayerVerb::displayOrder));
        return out;
    }

    public static synchronized void ensureInit() {
        if (initialised) return;
        registerDefaults();
        initialised = true;
        LOGGER.info("[PlayerVerbRegistry] Registered {} verbs", VERBS.size());
    }

    private static void registerDefaults() {
        register(new GreetVerb());
        register(new GiveGiftVerb());
        register(new AskAboutLifeVerb());
        register(new AskAboutVerb());
        register(new ComplimentVerb());
        register(new CommissionVerb());
        register(new ChallengeVerb());
        register(new InsultVerb());
        register(new ListenInVerb());
        register(new TellRumorVerb());
        // Phase 2 task 17: scribal verbs.
        register(new CommissionLetterVerb());
        register(new CommissionBookCopyVerb());
        register(new BorrowBookVerb());
        register(new TakeLessonVerb());
    }
}
