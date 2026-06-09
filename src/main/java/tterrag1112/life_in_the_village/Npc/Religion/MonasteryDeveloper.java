package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Apprentice.MentorshipBonus;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts.MonasticCraft;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Religion Rework R6c — the self-organizing monastery. A daily per-village pass
 * (hooked from {@code RiteScheduler.dailyTick}, alongside {@code TempleProsperity})
 * that steers <b>idle initiates</b> — monks not yet producing any craft the
 * monastery supports — toward developing a skill the monastery <b>needs and can
 * support</b>, so the house fills its own gaps over time (emergent, not scripted).
 *
 * <p>Mechanism (all reused, nothing parallel): for each monastery, the needed +
 * supported crafts come from {@link MonasticCrafts}; each idle initiate is steered
 * toward a needed craft (preferring an <i>uncovered</i> one so initiates
 * diversify — some become beekeepers, some scribes), via a small directed skill
 * seed (mirroring {@code FamilySkillSeeder}'s magnitude) and, where a matching
 * hobby exists, the existing hobby-drift path. A co-located senior monk skilled
 * in that craft accelerates it ({@link MentorshipBonus#NPC_MENTORSHIP_MULTIPLIER}).
 * {@code SkillXp.award} applies its own auto-mentorship/ambition on top. Bounded +
 * gradual: a small daily seed lifts the initiate to a viable producer (skill ≥ 1)
 * over ~1–2 weeks, after which production XP takes over and the seeding stops.</p>
 *
 * <p>Out of scope (R6d): mealtime distribution/consumption + the monastery money
 * economy; the Abbot issuing assignments (needs are DERIVED here, not abbot-issued).</p>
 */
public final class MonasteryDeveloper {

    private MonasteryDeveloper() {}

    /** Base directed skill seed per day for an idle initiate (≈ FamilySkillSeeder
     *  magnitude; small + gradual). */
    private static final int SEED_XP_PER_DAY = 4;
    /** A monk at/above this skill in a craft mentors initiates developing it. */
    private static final int MENTOR_LEVEL = 30;
    /** Village-bounds inflation for the monk scan (mirrors TempleProsperity). */
    private static final double SCAN_INFLATE = 32;

    /** Daily per-village pass. */
    public static void tickVillage(ServerLevel level, Village village,
                                   VillageSavedData data, long now) {
        if (level == null || village == null) return;
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            if (b.getType() != BuildingType.MONASTERY && b.getType() != BuildingType.ABBEY) continue;
            developMonastery(level, village, data, b, now);
        }
    }

    private static void developMonastery(ServerLevel level, Village village,
                                         VillageSavedData data, Building monastery, long now) {
        List<TownspersonMob> monks = monksOf(level, village, data, monastery);
        if (monks.isEmpty()) return;

        List<MonasticCraft> supported = MonasticCrafts.supportedAt(level, monastery);
        if (supported.isEmpty()) return;

        // "Covered" = a supported craft that already has a producer (some monk at
        // skill ≥ its min level). Updated as initiates are assigned so the next
        // initiate diversifies toward an uncovered need.
        Set<MonasticCraft> covered = new HashSet<>();
        for (MonasticCraft c : supported) {
            for (TownspersonMob m : monks) {
                if (MonasticCrafts.isProducer(m, c)) { covered.add(c); break; }
            }
        }

        for (TownspersonMob monk : monks) {
            // Only IDLE INITIATES (not already producing any supported craft).
            boolean producesSomething = supported.stream()
                    .anyMatch(c -> MonasticCrafts.isProducer(monk, c));
            if (producesSomething) continue;

            MonasticCraft target = chooseTarget(level, monastery, supported, covered);
            if (target == null) continue;   // nothing needed/supported right now

            // Steer the existing hobby-drift path too, where a matching hobby exists.
            if (target.hobbyId() != null) {
                monk.getHobbyPreference().setTopHobbies(List.of(target.hobbyId()));
            }

            // Directed seed — accelerated by a co-located senior monk in that craft.
            float xp = SEED_XP_PER_DAY;
            if (hasMentor(monks, monk, target)) xp *= MentorshipBonus.NPC_MENTORSHIP_MULTIPLIER;
            SkillXp.award(monk, target.skill(), xp, now);

            covered.add(target); // this initiate now develops it → next one diversifies
        }
    }

    /** The craft to steer an initiate toward: a needed (store below target),
     *  supported craft — preferring an UNCOVERED one (no producer/developer yet),
     *  then the greatest store shortfall. Null when nothing is needed+supported. */
    private static MonasticCraft chooseTarget(ServerLevel level, Building monastery,
                                              List<MonasticCraft> supported,
                                              Set<MonasticCraft> covered) {
        MonasticCraft best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MonasticCraft c : supported) {
            int need = MonasticCrafts.need(level, monastery, c);
            if (need <= 0) continue;                       // already stocked
            // Uncovered needs win decisively (diversify), then by deficit size.
            int score = (covered.contains(c) ? 0 : 1000) + need;
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** A senior monk (other than {@code learner}) at/above {@link #MENTOR_LEVEL}
     *  in the craft's skill — they share the monastery, so they are co-located. */
    private static boolean hasMentor(List<TownspersonMob> monks, TownspersonMob learner,
                                     MonasticCraft craft) {
        for (TownspersonMob m : monks) {
            if (m == learner) continue;
            if (m.getSkills().getLevel(craft.skill()) >= MENTOR_LEVEL) return true;
        }
        return false;
    }

    /** Loaded monks assigned to {@code monastery} (mirrors TempleProsperity's scan). */
    private static List<TownspersonMob> monksOf(ServerLevel level, Village village,
                                                VillageSavedData data, Building monastery) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(SCAN_INFLATE)).orElse(null);
        if (bounds == null) return List.of();
        UUID bid = monastery.getId();
        return new ArrayList<>(level.getEntitiesOfClass(TownspersonMob.class, bounds,
                npc -> npc.getProfession() == Profession.MONK
                        && npc.getAssignedBuildingId().map(bid::equals).orElse(false)));
    }
}
