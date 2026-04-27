package tterrag1112.life_in_the_village.Npc.Aging;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bus listener that seeds {@link ChildhoodState} when a new NPC is
 * born. Picks a primary caregiver, two interested skills, and a
 * tentative profession preference. Spec line 116-122.
 *
 * <p>Listens for {@link NpcLifeEvent.BirthInFamily}; the event's
 * {@code subject} is the parent, so the actual child entity is
 * looked up from the parent's level via the new-child UUID.</p>
 */
public final class ChildhoodInitializer implements EventDispatcher {

    /** Spec line 122: caregiver-relationship seeded at +50. */
    public static final int PRIMARY_CAREGIVER_BOND = 50;
    /** Spec line 122: secondary attachment seeded at +30. */
    public static final int SECONDARY_CAREGIVER_BOND = 30;

    @Override public String name() { return "childhood_initializer"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.BirthInFamily birth)) return;
        TownspersonMob parent = birth.subject();
        if (parent == null || !(parent.level() instanceof ServerLevel sl)) return;
        UUID childId = birth.childId();
        TownspersonMob child = TownspersonMob.findByUUID(sl, childId).orElse(null);
        if (child == null) return; // child entity not loaded yet — initialiser runs again on next birth roll
        seed(child, parent, sl);
    }

    /**
     * Public entry point used by debug commands and test harnesses
     * to seed an arbitrary child without firing the bus event.
     */
    public static void seed(TownspersonMob child, TownspersonMob caregiver,
                            ServerLevel level) {
        if (child == null) return;
        ChildhoodState state = child.getChildhoodState();
        if (state.primaryCaregiverId().isPresent()) return; // already seeded

        // Caregiver selection: caller-supplied (parent) when present;
        // else fall back to the household head.
        UUID primaryCaregiver = caregiver != null ? caregiver.getUUID() : null;
        if (primaryCaregiver == null) {
            primaryCaregiver = child.getFamily().getHeadOfHouseholdId().orElse(null);
        }
        state.setPrimaryCaregiverId(primaryCaregiver);
        if (primaryCaregiver != null) {
            child.getNpcRelationships().adjust(primaryCaregiver,
                    PRIMARY_CAREGIVER_BOND, level.getGameTime(),
                    RelationshipOrigin.BORN_SAME_HOUSEHOLD);
            // Mirror on caregiver if loaded.
            TownspersonMob c = TownspersonMob.findByUUID(level, primaryCaregiver).orElse(null);
            if (c != null) {
                c.getNpcRelationships().adjust(child.getUUID(),
                        PRIMARY_CAREGIVER_BOND, level.getGameTime(),
                        RelationshipOrigin.BORN_SAME_HOUSEHOLD);
            }
        }

        // Secondary caregiver: spouse or another household head if
        // distinct. Phase 5 polish picks 1-2; v1 picks at most one.
        Optional<UUID> spouse = caregiver == null
                ? Optional.empty() : caregiver.getFamily().getSpouseId();
        if (spouse.isPresent() && !spouse.get().equals(primaryCaregiver)) {
            child.getNpcRelationships().adjust(spouse.get(),
                    SECONDARY_CAREGIVER_BOND, level.getGameTime(),
                    RelationshipOrigin.BORN_SAME_HOUSEHOLD);
        }

        // Interested skills: 2 random picks weighted by parent's
        // primary skill (echoes into the child's interest profile).
        RandomSource rng = level.getRandom();
        List<Skill> pool = new ArrayList<>(Arrays.asList(Skill.values()));
        java.util.Collections.shuffle(pool, new java.util.Random(rng.nextLong()));
        List<ChildhoodSkill> interested = new ArrayList<>();
        for (int i = 0; i < Math.min(2, pool.size()); i++) {
            int interest = 4 + rng.nextInt(7); // 4..10
            interested.add(new ChildhoodSkill(pool.get(i), interest));
        }
        state.setInterestedSkills(interested);

        // Profession preference: 50% chance to inherit caregiver's
        // profession with moderate strength; otherwise NONE for now
        // (re-rolled at coming-of-age when traits + skills are set).
        Profession parentProf = caregiver == null ? Profession.NONE : caregiver.getProfession();
        boolean inherits = rng.nextFloat() < 0.5f
                && parentProf != Profession.NONE && parentProf != Profession.CITIZEN;
        if (inherits) {
            state.setPreferredProfession(new ProfessionPreference(parentProf, 6));
        }

        // Schooling will activate when a librarian/scholar is in
        // village; the librarian goal already handles the per-tick
        // grant, so just mark schoolingActive eagerly when one
        // exists nearby.
        state.setSchoolingActive(scanForSchoolingHost(child, level));
    }

    private static boolean scanForSchoolingHost(TownspersonMob child, ServerLevel level) {
        return !level.getEntitiesOfClass(TownspersonMob.class,
                child.getBoundingBox().inflate(64.0),
                m -> m.getProfession() == Profession.LIBRARIAN
                        || m.getProfession() == Profession.SCHOLAR).isEmpty();
    }

    /** Phase-5 polish helper — recomputes preferred profession from traits + interests. */
    public static void recomputePreference(TownspersonMob child) {
        ChildhoodState state = child.getChildhoodState();
        if (state.hasPreference()) return;
        // Pick the profession whose primary skill matches one of
        // the child's high-interest skills.
        for (ChildhoodSkill cs : state.interestedSkills()) {
            for (Profession p : Profession.values()) {
                var ps = tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills.of(p)
                        .orElse(null);
                if (ps != null && ps.primary() == cs.skill()
                        && p != Profession.NONE && p != Profession.CITIZEN) {
                    state.setPreferredProfession(new ProfessionPreference(p, cs.interest()));
                    return;
                }
            }
        }
        // Trait fallback: high Industry → FARMER; high Sociability →
        // INNKEEPER; high Honesty → SCRIBE.
        var traits = child.getTraitVector();
        if (traits.get(TraitAxis.HONESTY) > 0.5f) {
            state.setPreferredProfession(new ProfessionPreference(Profession.SCRIBE, 5));
        } else if (traits.get(TraitAxis.SOCIABILITY) > 0.5f) {
            state.setPreferredProfession(new ProfessionPreference(Profession.INNKEEPER, 5));
        } else if (traits.get(TraitAxis.INDUSTRY) > 0.5f) {
            state.setPreferredProfession(new ProfessionPreference(Profession.FARMER, 5));
        }
    }
}
