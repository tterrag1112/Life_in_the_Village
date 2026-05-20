package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.world.entity.ai.Brain;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-profession Brain customization hook. Mirrors the shape of
 * {@link tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionGoalFactory}
 * so future per-profession behavior wiring slots in naturally.
 *
 * <p>Phase 6.0 — empty registrars. The base activity wiring in
 * {@link TownspersonMob#makeBrain(com.mojang.serialization.Dynamic)}
 * does all the work; this hook is called afterward so future
 * profession-specific behaviors can layer on top without touching
 * the entity class.
 */
public final class ProfessionBrainFactory {

    private ProfessionBrainFactory() {}

    @FunctionalInterface
    public interface ProfessionBrainRegistrar {
        void configure(TownspersonMob npc, Brain<TownspersonMob> brain);
    }

    private static final Map<Profession, ProfessionBrainRegistrar> REGISTRARS =
            new EnumMap<>(Profession.class);

    /**
     * Hook for future phases. Call before any NPC is constructed (e.g.
     * from {@code commonSetup}) to attach a profession-specific
     * configurator.
     */
    public static void registerProfessionHandler(Profession profession,
                                                 ProfessionBrainRegistrar registrar) {
        REGISTRARS.put(profession, registrar);
    }

    /**
     * Entry point invoked from {@code TownspersonMob.makeBrain}. Runs
     * after the base activity wiring, allowing per-profession layering.
     */
    public static void configureBrain(TownspersonMob npc, Brain<TownspersonMob> brain) {
        configureLifeStage(npc, brain);
        ProfessionBrainRegistrar reg = REGISTRARS.get(npc.getProfession());
        if (reg != null) reg.configure(npc, brain);
    }

    private static void configureLifeStage(TownspersonMob npc, Brain<TownspersonMob> brain) {
        // Phase 6.0 — no life-stage-specific Brain behaviors. Reserved
        // so the dispatch shape matches ProfessionGoalFactory.
        LifeStage stage = npc.getLifeStage();
        if (stage == null) return;
    }
}
