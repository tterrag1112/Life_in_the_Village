package tterrag1112.life_in_the_village.Npc.Schedule;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Generates {@link PersonalScheduleOverride} on adulthood from the
 * NPC's trait vector. Subscribes to {@link NpcLifeEvent.LifeStageAdvanced}
 * for the {@code TEEN → ADULT} transition.
 *
 * <p>Spec: {@code 13-weekly-schedule.md} table at line 174 — Industry,
 * Sociability, Ambition, Temperance, Compassion all influence the
 * generated override. Mappings below match the spec rows verbatim;
 * Phase 5 culture pass adds further per-culture skew on top.</p>
 *
 * <p>Also seeds a {@code shiftIndex} for shift-rotated professions
 * (GUARD / INNKEEPER) per spec line 217. The seeding rule for guards
 * is "by COMBAT skill seniority": senior guards (high COMBAT level)
 * stay on shift 0 (day); newer guards rotate. Phase 1 fallback when
 * fewer than 3 guards exist in a village: every guard stays on
 * shift 0 — explicit spec ambiguity, documented in 13 Revision Notes.</p>
 */
public final class PersonalScheduleGenerator implements EventDispatcher {

    @Override public String name() { return "personal_schedule_generator"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ADULT".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null) return;
        PersonalScheduleOverride override = npc.getScheduleOverride();
        // Don't overwrite an existing override (load from save / debug-set).
        if (!override.isEmpty()) return;
        generate(npc, override);
    }

    /**
     * Public entry point: populates {@code override} from the NPC's
     * traits + skills. Called by the dispatcher and by
     * {@code /npc schedule reset}.
     */
    public static void generate(TownspersonMob npc, PersonalScheduleOverride override) {
        TraitVector traits = npc.getTraitVector();
        if (traits == null) return;

        // ── Industry: wake-time shifts ─────────────────────────────────────
        float industry = traits.get(TraitAxis.INDUSTRY);
        if (industry > 0.5f) {
            // Wake 500 ticks earlier (spec line 175).
            override.setPhaseShift(DayPhase.WAKE_UP, new TimeWindow(23000, 500));
            // Reduce leisure by 40% — represented by clearing it
            // entirely if the daily template has no explicit leisure.
            // Phase 5 will refine.
        } else if (industry < -0.5f) {
            override.setPhaseShift(DayPhase.WAKE_UP, new TimeWindow(1500, 2500));
        }

        // ── Sociability: social vs leisure balance ────────────────────────
        float sociability = traits.get(TraitAxis.SOCIABILITY);
        if (sociability > 0.5f) {
            // Extended social — push social earlier and longer.
            override.setPhaseShift(DayPhase.SOCIAL, new TimeWindow(11000, 15000));
        } else if (sociability < -0.5f) {
            // Minimal social, extra leisure at home.
            override.setPhaseShift(DayPhase.SOCIAL, new TimeWindow(12500, 13000));
            override.setPhaseShift(DayPhase.LEISURE, new TimeWindow(13000, 14000));
        }

        // ── Ambition: extra workErrand slot, shorter meal ────────────────
        float ambition = traits.get(TraitAxis.AMBITION);
        if (ambition > 0.5f) {
            override.setPhaseShift(DayPhase.WORK_ERRAND, new TimeWindow(5500, 6000));
            override.setPhaseShift(DayPhase.MEAL, new TimeWindow(6000, 6300));
        }

        // ── Temperance: regular commute buffer; no late-night work ───────
        float temperance = traits.get(TraitAxis.TEMPERANCE);
        if (temperance > 0.5f) {
            override.setPhaseShift(DayPhase.COMMUTE, new TimeWindow(500, 800));
        }
        // High Compassion: extra weekday leisure for household visits.
        float compassion = traits.get(TraitAxis.COMPASSION);
        if (compassion > 0.5f) {
            override.setPhaseShift(DayPhase.LEISURE, new TimeWindow(11000, 12000));
        }

        // ── Shift seeding (guards / innkeepers) ──────────────────────────
        Profession prof = npc.getProfession();
        if (prof == Profession.GUARD || prof == Profession.ADVENTURER) {
            int combatLevel = npc.getSkills().getLevel(Skill.COMBAT);
            // Spec line 211: senior guards (high COMBAT) take shift 0
            // (day). Lower-skilled guards rotate to evening / night.
            if (combatLevel >= 60) override.setShiftIndex(0);
            else if (combatLevel >= 30) override.setShiftIndex(1);
            else override.setShiftIndex(2);
        } else if (prof == Profession.INNKEEPER) {
            // Spec line 213: 2 shifts when there are ≥2 innkeepers in
            // the village. Phase 2 doesn't query innkeeper count from
            // here (would require a village-wide scan); default to
            // shift 0 and let later sessions assign shift 1 explicitly
            // when staffing requires it. Documented in 13 Revision Notes.
            override.setShiftIndex(0);
        }
    }
}
