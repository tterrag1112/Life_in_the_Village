package tterrag1112.life_in_the_village.Npc.Brain;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.CaravanGuardBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.GuardMeleeAttackBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.GuardPatrolBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.GuardScanForHostilesBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.KingdomRulerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic.VillageLeaderBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.PostalBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderMaintenanceBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BuilderRepaintBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.HealerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.HealerLivestockVisitBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.InnkeeperBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.LibrarianBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ScholarBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.CaravanMerchantBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.BusinessWorkerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.GuildWorkerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.MerchantBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.StockpileKeeperBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.WanderingTraderBehavior;
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
        // SCRIBE: postal delivery during SOCIAL (Phase 6.2.b).
        // ScribeWorkBehavior (WORK@0) retired T7 — DoTaskBehavior (universal WORK@1)
        // drives scribe task execution via ScribeService.
        REGISTRARS.put(Profession.SCRIBE, (npc, brain) ->
                brain.addActivity(NpcActivities.SOCIAL.get(), 7,
                        ImmutableList.of(new PostalBehavior())));

        // T7: BAKER, BLACKSMITH, CANDLEMAKER, CARPENTER, MILLER, STONEMASON,
        // WEAVER — legacy *ProductionBehavior registrar entries retired.
        // DoTaskBehavior (universal WORK@1 in TownspersonMob.makeBrain) drives
        // all 7 professions via the task system; no per-profession WORK@0 entry
        // is needed. SellToMarketBehavior (universal WORK@0) handles surplus sales.

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

        // FARMER + FARMHAND: G2b — ShepherdBehavior + BeekeeperBehavior deleted;
        // all species work now runs through DoTaskBehavior (WORK@1) via shear/collect_honey
        // tasks emitted by AnimalTaskSource. WORK@0 is empty for both professions.
        // Phase 6.3.3.k.5 — predator scan (CORE: always-on) and GuardMeleeAttack
        // (FIGHT) remain so farmers can defend their herds from wolves.
        REGISTRARS.put(Profession.FARMER, (npc, brain) -> {
            brain.addActivity(net.minecraft.world.entity.schedule.Activity.CORE, 2,
                    ImmutableList.of(new tterrag1112.life_in_the_village.Npc
                            .Brain.Behaviors.Civic.PredatorScanBehavior()));
            brain.addActivity(NpcActivities.FIGHT.get(), 0,
                    ImmutableList.of(new GuardMeleeAttackBehavior()));
        });
        // Phase 6.3.3.f — FARMHAND consolidated into FARMER as the
        // APPRENTICE tier; the dedicated FarmhandBehavior was deleted.
        // Profession.FARMHAND remains as a @Deprecated load-time alias
        // (save migration rewrites FARMHAND-tagged NPCs to FARMER).
        REGISTRARS.put(Profession.FARMHAND, (npc, brain) -> {
            brain.addActivity(net.minecraft.world.entity.schedule.Activity.CORE, 2,
                    ImmutableList.of(new tterrag1112.life_in_the_village.Npc
                            .Brain.Behaviors.Civic.PredatorScanBehavior()));
            brain.addActivity(NpcActivities.FIGHT.get(), 0,
                    ImmutableList.of(new GuardMeleeAttackBehavior()));
        });


        // Phase 6.2.d.3 — service-profession cluster.
        // Stationary "wait at counter, serve clients" pattern. Each
        // behavior ports its work-phase Goal; Track 4 verb hooks
        // (treatment, scribal commission, lending, lessons, rest) are
        // preserved by black-boxing the existing service-delivery code.
        // SCRIBE: layered on top of the SOCIAL @7 PostalBehavior entry.
        // Phase 6.3.3.k.6 — HEALER also visits livestock buildings
        // with elevated diseaseLevel at lower priority than NPC
        // patient treatment. WORK @ priority 1 means NPCs take
        // precedence when both kinds of work are available.
        REGISTRARS.put(Profession.HEALER, (npc, brain) -> {
            brain.addActivity(NpcActivities.WORK.get(), 0,
                    ImmutableList.of(new HealerBehavior()));
            brain.addActivity(NpcActivities.WORK.get(), 1,
                    ImmutableList.of(new HealerLivestockVisitBehavior()));
        });
        REGISTRARS.put(Profession.LIBRARIAN, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new LibrarianBehavior())));
        REGISTRARS.put(Profession.SCHOLAR, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new ScholarBehavior())));
        REGISTRARS.put(Profession.INNKEEPER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new InnkeeperBehavior())));

        // PRIEST — officiate rites / produce temple goods / bless (foundation).
        REGISTRARS.put(Profession.PRIEST, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(
                                new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                        .Production.PriestBehavior())));


        // Phase 6.2.d.4 — civic + guard cluster.
        REGISTRARS.put(Profession.VILLAGE_LEADER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new VillageLeaderBehavior())));
        REGISTRARS.put(Profession.KINGDOM_RULER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new KingdomRulerBehavior())));
        // GUARD: patrol + caravan escort (6.2.d.4/d.5) plus combat (6.2.e).
        // GuardScanForHostilesBehavior runs in CORE (always-on threat scan +
        // hurt-by retaliation + stale-target cleanup). When it writes
        // ATTACK_TARGET, customServerAiStep pushes the FIGHT activity, which
        // runs GuardMeleeAttackBehavior to chase and strike.
        REGISTRARS.put(Profession.GUARD, (npc, brain) -> {
            brain.addActivity(NpcActivities.WORK.get(), 0,
                    ImmutableList.of(new CaravanGuardBehavior()));
            brain.addActivity(NpcActivities.WORK.get(), 1,
                    ImmutableList.of(new GuardPatrolBehavior()));
            brain.addActivity(net.minecraft.world.entity.schedule.Activity.CORE, 1,
                    ImmutableList.of(new GuardScanForHostilesBehavior()));
            brain.addActivity(NpcActivities.FIGHT.get(), 0,
                    ImmutableList.of(new GuardMeleeAttackBehavior()));
        });

        // Phase 6.2.d.5 — trade + guild + business.
        // MERCHANT: same priority + self-gate arrangement as GUARD.
        // CaravanMerchantBehavior wins WORK @0 when on caravan duty;
        // MerchantBehavior at WORK @1 otherwise.
        REGISTRARS.put(Profession.MERCHANT, (npc, brain) -> {
            brain.addActivity(NpcActivities.WORK.get(), 0,
                    ImmutableList.of(new CaravanMerchantBehavior()));
            brain.addActivity(NpcActivities.WORK.get(), 1,
                    ImmutableList.of(new MerchantBehavior()));
        });
        REGISTRARS.put(Profession.WANDERING_TRADER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new WanderingTraderBehavior())));
        REGISTRARS.put(Profession.STOCKPILE_KEEPER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new StockpileKeeperBehavior())));
        REGISTRARS.put(Profession.GUILDWORKER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new GuildWorkerBehavior())));
        REGISTRARS.put(Profession.COMPANY_WORKER, (npc, brain) ->
                brain.addActivity(NpcActivities.WORK.get(), 0,
                        ImmutableList.of(new BusinessWorkerBehavior())));
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
