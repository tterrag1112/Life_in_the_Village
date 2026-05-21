package tterrag1112.life_in_the_village.Npc.Career;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;

import java.util.Optional;

/**
 * Phase 6.3.3.a — pure-observation listener for apprenticeship graduation.
 *
 * <p>Fires <em>after</em> {@link tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager#graduate
 * graduate()} has marked the contract complete and applied its
 * relationship + memory + role-projection cleanup. Listeners cannot
 * veto — the graduation is already committed by the time they see it.
 *
 * <p>Master is wrapped in {@link Optional} because graduation can fire
 * after master death (the {@code onMasterDeath} path), in which case
 * the master entity is gone and only the contract data remains. Apprentice
 * is always present (graduation requires a living apprentice).
 */
@FunctionalInterface
public interface ApprenticeshipGraduationListener {
    void onApprenticeshipGraduation(ApprenticeshipContract contract,
                                    TownspersonMob apprentice,
                                    Optional<TownspersonMob> master);
}
