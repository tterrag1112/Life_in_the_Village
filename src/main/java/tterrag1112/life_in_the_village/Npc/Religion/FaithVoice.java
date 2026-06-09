package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Religion Deepening D3b — the faith's <b>voice</b>: a single content-driven line
 * source that lets a clergy member or a devout adherent speak their religion. It
 * draws straight from the speaker's {@link God} (F1a — its name, character, demands,
 * rewards, and domain idiom) plus the religion's union of authored virtues — so a
 * Sunstead priest invokes the Sun-Mother and echoes honest labour, while a Tidecall
 * priest blesses by the tides and a Loom priest speaks of the Pattern with no deity
 * name at all.
 *
 * <p><b>One weighted source, not an override.</b> {@link NpcDialogue} consults this
 * as one more pool alongside profession/trait/season lines; the frequency gating
 * (clergy readily, devout laity occasionally) lives at the call site. This class
 * only answers "who may voice their faith" ({@link #speaks}) and "what would they
 * say" ({@link #line}).</p>
 *
 * <p>Eligibility: a clergy NPC (PRIEST/MONK) always voices its faith; a lay adherent
 * only when DEVOUT or PIOUS. A lukewarm/unaffiliated/atheist NPC has no faith voice
 * — {@link #line} returns empty and the caller falls back to normal lines. No
 * {@link Religion} codec change; no new memory; lines are picked on interaction.</p>
 */
public final class FaithVoice {

    private FaithVoice() {}

    /** True when {@code npc} is allowed to voice its faith: clergy always, a lay
     *  adherent only when DEVOUT/PIOUS (a lukewarm/unaffiliated NPC stays quiet). */
    public static boolean speaks(TownspersonMob npc) {
        if (npc.getPiety().primaryReligion().isEmpty()) return false;
        if (isClergy(npc)) return true;
        PietyTier tier = npc.getPiety().primaryTier();
        return tier == PietyTier.DEVOUT || tier == PietyTier.PIOUS;
    }

    /** PRIEST/MONK speak their faith readily (vs. occasional devout laity). */
    public static boolean isClergy(TownspersonMob npc) {
        Profession p = npc.getProfession();
        return p == Profession.PRIEST || p == Profession.MONK;
    }

    /**
     * A faith-flavoured line for {@code npc}, drawn from its {@link ReligionIdentity}
     * — a deity-invoking greeting, a blessing in the faith's idiom, what the faith
     * asks, or a virtue spoken plainly — varied per call. Empty when the NPC is not
     * eligible (so the caller uses its normal lines), or for an unknown religion.
     * A faith with no authored identity falls back to its core tenets — never a
     * generic non-religious line.
     */
    public static Optional<String> line(TownspersonMob npc, RandomSource rng) {
        if (!speaks(npc)) return Optional.empty();
        String faith = npc.getPiety().primaryReligion().orElse(null);
        if (faith == null) return Optional.empty();
        Religion religion = ReligionRegistry.get(faith);
        if (religion == null) return Optional.empty();
        // F1a 4a — deity attributes (name / domain / demands / rewards) from the
        // PRIMARY god; "what the faith esteems" (the virtue pool) from the UNION.
        God god = GodRegistry.primaryGod(religion).orElse(null);
        List<Virtue> unionVirtues = GodRegistry.unionVirtues(religion);

        List<String> pool = new ArrayList<>();
        if (god != null) {
            boolean personified = god.name().isPresent();
            // The holy name: the deity where one exists, the abstract "the Pattern"
            // for an impersonal god.
            String holy = god.name().orElse("the Pattern");
            // 1. A faith greeting in the deity's domain idiom.
            pool.add(domainGreeting(god.domain(), holy));
            // 2. A blessing — invoking the deity where there is one; abstract for the
            //    Loom (the Pattern "neither rewards nor punishes; it only records").
            pool.add(personified
                    ? "May " + holy + " grant you " + god.rewards() + "."
                    : "Weave true, and keep your thread clean of knots.");
            // 3. What the faith asks of its people.
            pool.add(capitalize(holy) + " asks " + god.demands() + ".");
            // 4. A virtue spoken plainly (already a complete authored sentence).
            if (!unionVirtues.isEmpty()) {
                Virtue v = unionVirtues.get(rng.nextInt(unionVirtues.size()));
                pool.add(v.text());
            }
        } else {
            // No authored identity — speak the faith's core tenets rather than a
            // generic line (still on-faith, just sparser).
            pool.addAll(religion.coreTenets());
            if (pool.isEmpty()) return Optional.empty();
        }

        return Optional.of(pool.get(rng.nextInt(pool.size())));
    }

    /** A deity-domain-flavoured greeting. Uses the holy name (single-sourced) so the
     *  deity is never re-spelled here; the Loom (FATE, abstract) speaks of the
     *  Pattern with no personification. */
    private static String domainGreeting(DeityDomain domain, String holy) {
        return switch (domain) {
            case SUN   -> capitalize(holy) + "'s light upon you, traveller.";
            case SEA   -> capitalize(holy) + "'s tides carry you safely.";
            case FORGE -> capitalize(holy) + "'s iron at your back, traveller.";
            case FATE  -> "May your thread run true in the Pattern.";
        };
    }

    /** Capitalises a leading "the " holy name for sentence-initial use
     *  ("the Sun-Mother" → "The Sun-Mother"). */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
