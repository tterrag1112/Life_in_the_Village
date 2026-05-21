package tterrag1112.life_in_the_village.Npc.Brain;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.PostalBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BakerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderMaintenanceBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderRepaintBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CandlemakerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CarpenterProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.FarmerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.FarmhandBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.MillerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.MinerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.StonemasonProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WeaverProductionBehavior;
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

    static {
        // Phase 6.2.b — SCRIBE: postal delivery during SOCIAL activity.
        REGISTRARS.put(Profession.SCRIBE, (npc, brain) -> {
            ImmutableList<BehaviorControl<? super TownspersonMob>> scribeSocial =
                    ImmutableList.of(new PostalBehavior());
            brain.addActivity(NpcActivities.SOCIAL.get(), 7, scribeSocial);
        });

        // Phase 6.2.d.1 — workshop family: each profession adds its
        // ProductionBehavior to WORK @ 0. AbstractProductionBehavior writes
        // CARGO_DESTINATION when surplus accumulates, which SellToMarketBehavior
        // (universal, also in WORK) consumes to do the market trip.
        REGISTRARS.put(Profession.BAKER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new BakerProductionBehavior())));
        REGISTRARS.put(Profession.BLACKSMITH, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new BlacksmithProductionBehavior())));
        REGISTRARS.put(Profession.CANDLEMAKER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new CandlemakerProductionBehavior())));
        REGISTRARS.put(Profession.CARPENTER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new CarpenterProductionBehavior())));
        REGISTRARS.put(Profession.MILLER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new MillerProductionBehavior())));
        REGISTRARS.put(Profession.STONEMASON, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new StonemasonProductionBehavior())));
        REGISTRARS.put(Profession.WEAVER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new WeaverProductionBehavior())));

        // Phase 6.2.d.2 — outdoor work cluster.
        // BUILDER: 3 behaviors. Primary build-queue at WORK @ 0;
        // Maintenance + Repaint share WORK @ 1 (parity with the
        // original P_WORK_SECONDARY pairing).
        REGISTRARS.put(Profession.BUILDER, (npc, brain) -> {
            brain.addActivity(NpcActivities.WORK.get(), 0,
                    ImmutableList.of(new BuilderBehavior()));
            brain.addActivity(NpcActivities.WORK.get(), 1,
                    ImmutableList.of(
                            new BuilderMaintenanceBehavior(),
                            new BuilderRepaintBehavior()));
        });

        // FARMER + FARMHAND: separate behaviors per separate Goal classes.
        // FarmerBehavior includes the inlined PostJob periodic side-effect.
        REGISTRARS.put(Profession.FARMER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new FarmerBehavior())));
        REGISTRARS.put(Profession.FARMHAND, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new FarmhandBehavior())));

        // MINER: single behavior with inlined ChannelRouter procurement
        // (BuyFromNpc fold) and CARGO_DESTINATION-based sell handoff.
        REGISTRARS.put(Profession.MINER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new MinerBehavior())));
    }

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
