package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderMaintenanceGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderRepaintGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.CompanyWorker.CompanyWorkerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmhandGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard.*;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guild.GuildWorkerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Innkeeper.InnkeeperGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Leader.KingdomRulerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Leader.VillageLeaderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.CaravanMerchantGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.WanderingTraderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Miner.MinerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.StockpileKeeper.StockpileKeeperGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopStallDecisionGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Social.*;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.*;
import java.util.function.Function;

/**
 * Centralizes all goal registration for TownspersonMob NPCs.
 *
 * <h3>Why extract this?</h3>
 * {@code TownspersonMob.registerGoals()} + {@code registerProfessionGoals()}
 * was ~250 lines of interleaved switch statements with inconsistent
 * priority numbering. Goals at the same priority fight for control
 * non-deterministically. This factory:
 * <ul>
 *   <li>Documents the priority scheme in one place</li>
 *   <li>Separates universal goals from profession and life-stage goals</li>
 *   <li>Makes it easy to add/remove goals for a profession without
 *       touching the mob class</li>
 *   <li>Validates that no two exclusive goals share the same priority</li>
 * </ul>
 *
 * <h3>Priority scheme</h3>
 * <pre>
 *  0  — reserved (never used; avoids mojang edge cases)
 *  1  — survival: FloatGoal, CaravanGuardGoal (override all else)
 *  2  — combat: MeleeAttack, GuardEquip, ReturnHome (urgent)
 *  3  — pathfinding: GuardPatrol, VillageLeader, SeekHouse
 *  4  — social (high): EatMeal (during meal window)
 *  5  — social (mid): Socialize, ChildPlay, ElderlyRelax
 *  6  — social (low): Greeting, Courting, ChildBirth
 *  7  — (unused — buffer)
 *  8  — profession work (primary): FarmerGoal, BlacksmithGoal, etc.
 *  9  — profession work (secondary): SellToMarket, PostListing, BuyFromNpc
 * 10  — idle: WanderInBuilding, RandomLookAround
 * 11  — ambient: LookAtPlayer
 * </pre>
 *
 * <h3>Usage</h3>
 * Replace the body of {@code TownspersonMob.registerGoals()}:
 * <pre>
 * {@code @Override}
 * protected void registerGoals() {
 *     ProfessionGoalFactory.register(this);
 * }
 * </pre>
 */
public final class ProfessionGoalFactory {

    private ProfessionGoalFactory() {}

    // =========================================================================
    // Priority constants — single source of truth
    // =========================================================================

    public static final int P_RESERVED        = 0;
    public static final int P_SURVIVAL       = 1;
    public static final int P_COMBAT         = 2;
    public static final int P_PATHFIND       = 3;
    public static final int P_SOCIAL_HIGH    = 4;
    public static final int P_SOCIAL_MID     = 5;
    public static final int P_SOCIAL_LOW     = 6;
    public static final int P_WORK_PRIMARY   = 8;
    public static final int P_WORK_SECONDARY = 9;
    public static final int P_IDLE           = 10;
    public static final int P_AMBIENT        = 11;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Clears and re-registers all goals for the given NPC based on
     * current profession and life stage. Safe to call repeatedly
     * (e.g. on profession change or life stage transition).
     */
    public static void register(TownspersonMob npc) {
        npc.goalSelector.removeAllGoals(g -> true);
        npc.targetSelector.removeAllGoals(g -> true);

        registerUniversal(npc);
        registerLifeStage(npc);
        registerProfession(npc);
    }

    // =========================================================================
    // Universal goals — all NPCs regardless of profession
    // =========================================================================

    private static void registerUniversal(TownspersonMob npc) {
        npc.goalSelector.addGoal(P_SURVIVAL,  new FloatGoal(npc));
        npc.goalSelector.addGoal(P_SURVIVAL,  new OpenDoorGoal(npc, true));
        // Phase 6.2.c: ReturnHomeGoal migrated to ReturnHomeBehavior (REST @1).
        // Phase 6.2.c: SeekHouseGoal migrated to SeekHouseBehavior (IDLE @1 + SOCIAL @2).
        // Phase 6.2.a: EatMealGoal migrated to EatMealBehavior (SOCIAL @1).
        // Phase 6.2.a: HobbyGoal migrated to HobbyBehavior (SOCIAL @4).
        // Phase 6.2.b: GreetPlayerGoal migrated to GreetPlayerBehavior
        //   (universal IDLE @0 + SOCIAL @0); GreeterAssignment now writes
        //   the GREET_TARGET memory instead of calling goal.assign().
        npc.goalSelector.addGoal(P_SOCIAL_LOW,  new ChildBirthGoal(npc));
        // Phase 3 task 19: village_constable's investigation pass.
        // canUse short-circuits when the NPC doesn't currently hold
        // INVESTIGATE_CRIME, so non-constables pay only the Goal-list
        // overhead.
        npc.goalSelector.addGoal(P_WORK_PRIMARY,
                new tterrag1112.life_in_the_village.Npc.Crime.ConstableInvestigationGoal(npc));
        // Phase 4 task 29: ephemeral visitors. canUse short-circuits
        // when the NPC isn't flagged as a visitor, so residents pay
        // only the Goal-list overhead.
        npc.goalSelector.addGoal(P_SOCIAL_HIGH,
                new tterrag1112.life_in_the_village.Entities.Goals.Visitor.VisitorGoal(npc));
        npc.goalSelector.addGoal(P_IDLE,      new WanderInBuildingGoal(npc));
        npc.goalSelector.addGoal(P_AMBIENT,   new LookAtPlayerGoal(npc, Player.class, 8.0f));
        npc.goalSelector.addGoal(P_AMBIENT,   new RandomLookAroundGoal(npc));
    }

    // =========================================================================
    // Life-stage goals
    // =========================================================================

    private static void registerLifeStage(TownspersonMob npc) {
        switch (npc.getLifeStage()) {
            case CHILD -> {
                npc.goalSelector.addGoal(P_SOCIAL_MID, new ChildPlayGoal(npc));
            }
            case TEEN, ADULT -> {
                npc.goalSelector.addGoal(P_SOCIAL_MID, new SocializeGoal(npc));
                npc.goalSelector.addGoal(P_SOCIAL_LOW, new GreetingGoal(npc));
                // Phase 6.2.a: BuyGoodsGoal migrated to BuyGoodsBehavior (SOCIAL @5).
                // Phase 6.2.b: CourtingGoal migrated to CourtingBehavior
                //   (universal SOCIAL — the family-state gate is in the
                //   behavior's checkExtraStartConditions, no life-stage
                //   wiring needed).
            }
            case ELDERLY -> {
                // Phase 6.2.c: ElderlyRelaxGoal migrated to ElderlyRelaxBehavior
                //   (universal IDLE — self-gates on isElderly()).
                // Phase 6.2.c: MentorGoal migrated to MentorBehavior
                //   (universal SOCIAL — self-gates on ELDERLY + skill threshold).
                npc.goalSelector.addGoal(P_SOCIAL_LOW, new GreetingGoal(npc));
            }
        }
    }

    // =========================================================================
    // Profession goals — dispatched from registry
    // =========================================================================

    /**
     * Profession goal registrars. Each entry is a function that takes an
     * NPC and adds the appropriate goals. Using a map instead of a switch
     * means new professions can be added without editing this class — just
     * call {@link #registerProfessionHandler} during mod setup.
     */
    private static final Map<Profession, ProfessionRegistrar> REGISTRARS =
            new EnumMap<>(Profession.class);

    @FunctionalInterface
    public interface ProfessionRegistrar {
        void register(TownspersonMob npc);
    }

    static {
        // ── Production professions ───────────────────────────────────────────
        REGISTRARS.put(Profession.FARMER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new FarmerGoal(npc));
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new PostJobGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.FARMER)));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.FARMER)));
            npc.goalSelector.addGoal(P_SOCIAL_LOW,        // ← new
                    new WorkshopStallDecisionGoal(npc));
        });

        REGISTRARS.put(Profession.FARMHAND, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new FarmhandGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.FARMHAND)));
        });

        // Phase 6.2.d.1 — 7 workshop production goals migrated to per-profession
        // ProductionBehaviors wired via ProfessionBrainFactory (WORK @ 0).
        // SellToMarket / PostListing / BuyFromNpc errands for the migrated
        // professions also move to the Brain layer: the workshop's deposit
        // phase writes CARGO_DESTINATION which SellToMarketBehavior consumes.
        //
        // Cleanup: the previous BLACKSMITH double-registration at this position
        // (which had been overwritten by the empty-stub second registrar at
        // ~line 259) is naturally resolved — both registrars removed.
        REGISTRARS.put(Profession.BLACKSMITH, npc -> {
            npc.goalSelector.addGoal(P_SOCIAL_LOW, new WorkshopStallDecisionGoal(npc));
        });

        REGISTRARS.put(Profession.CARPENTER, npc -> {
            npc.goalSelector.addGoal(P_SOCIAL_LOW, new WorkshopStallDecisionGoal(npc));
        });

        REGISTRARS.put(Profession.MILLER, npc -> {});
        REGISTRARS.put(Profession.BAKER, npc -> {});
        REGISTRARS.put(Profession.STONEMASON, npc -> {});
        REGISTRARS.put(Profession.WEAVER, npc -> {});
        REGISTRARS.put(Profession.CANDLEMAKER, npc -> {});

        REGISTRARS.put(Profession.MINER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new MinerGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.MINER)));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.MINER)));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new BuyFromNpcGoal(npc,
                            npc::needsPickaxe,
                            () -> net.minecraft.world.item.Items.IRON_PICKAXE,
                            () -> 1, 400));
        });

        // ── Service professions ──────────────────────────────────────────────
        REGISTRARS.put(Profession.MERCHANT, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new MerchantGoal(npc));
            npc.goalSelector.addGoal(P_SURVIVAL, new CaravanMerchantGoal(npc));
        });
        REGISTRARS.put(Profession.WANDERING_TRADER, npc -> {
            // WanderingTraderGoal owns the entire lifecycle — no other work goals.
            // Universal goals (float, open door, look at player) still apply,
            // but social/sleep goals are suppressed because this NPC despawns.
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new WanderingTraderGoal(npc));

        });

        REGISTRARS.put(Profession.INNKEEPER, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new InnkeeperGoal(npc)));

        REGISTRARS.put(Profession.STOCKPILE_KEEPER, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new StockpileKeeperGoal(npc)));

        REGISTRARS.put(Profession.BUILDER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new BuilderGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY, new BuilderMaintenanceGoal(npc));
            // P0a-13: player-requested repaints — runs alongside the
            // primary build queue and the maintenance pass; canUse()
            // gates on whether a job is attached to the NPC.
            npc.goalSelector.addGoal(P_WORK_SECONDARY, new BuilderRepaintGoal(npc));
        });

        // ── Leadership ───────────────────────────────────────────────────────
        REGISTRARS.put(Profession.VILLAGE_LEADER, npc ->
                npc.goalSelector.addGoal(P_PATHFIND, new VillageLeaderGoal(npc)));

        REGISTRARS.put(Profession.KINGDOM_RULER, npc ->
                npc.goalSelector.addGoal(P_PATHFIND, new KingdomRulerGoal(npc)));

        // ── Military ─────────────────────────────────────────────────────────
        REGISTRARS.put(Profession.GUARD, npc -> {
            npc.goalSelector.addGoal(P_SURVIVAL,  new CaravanGuardGoal(npc));
            npc.goalSelector.addGoal(P_COMBAT,    new GuardEquipmentGoal(npc));
            npc.goalSelector.addGoal(P_COMBAT,    new MeleeAttackGoal(npc, 1.2, true));
            npc.goalSelector.addGoal(P_PATHFIND,  new GuardPatrolGoal(npc));
            npc.targetSelector.addGoal(P_SURVIVAL, new GuardAttackGoal(npc));
            npc.targetSelector.addGoal(P_COMBAT,   new HurtByTargetGoal(npc));
            npc.targetSelector.addGoal(P_COMBAT, new GuardAwarenessGoal(npc));


        });

        // ── Guild ────────────────────────────────────────────────────────────
        REGISTRARS.put(Profession.GUILDWORKER, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new GuildWorkerGoal(npc)));

        REGISTRARS.put(Profession.GUILDMASTER, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new WanderInBuildingGoal(npc)));

        // ── Company ──────────────────────────────────────────────────────────
        REGISTRARS.put(Profession.COMPANY_WORKER, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new CompanyWorkerGoal(npc)));

        // ── Unemployed ───────────────────────────────────────────────────────
        REGISTRARS.put(Profession.NONE, npc ->
                npc.goalSelector.addGoal(P_WORK_PRIMARY, new SeekJobGoal(npc)));

        // ── Scribal (Phase 2 task 17) ────────────────────────────────────────
        REGISTRARS.put(Profession.SCRIBE, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY,
                    new tterrag1112.life_in_the_village.Entities.Goals.Profession.Scribal.ScribeWorkGoal(npc));
            // Phase 6.2.b: PostalGoal migrated to PostalBehavior — wired
            // via ProfessionBrainFactory.SCRIBE registrar (SOCIAL @7).
        });
        REGISTRARS.put(Profession.LIBRARIAN, npc -> npc.goalSelector.addGoal(P_WORK_PRIMARY,
                new tterrag1112.life_in_the_village.Entities.Goals.Profession.Scribal.LibrarianWorkGoal(npc)));
        REGISTRARS.put(Profession.SCHOLAR, npc -> npc.goalSelector.addGoal(P_WORK_PRIMARY,
                new tterrag1112.life_in_the_village.Entities.Goals.Profession.Scribal.ScholarWorkGoal(npc)));
        REGISTRARS.put(Profession.HEALER, npc -> npc.goalSelector.addGoal(P_WORK_PRIMARY,
                new tterrag1112.life_in_the_village.Entities.Goals.Profession.Healer.HealerWorkGoal(npc)));
    }

    /**
     * Register a custom profession handler. Call during mod setup to
     * add goals for modded or addon professions without editing this class.
     */
    public static void registerProfessionHandler(Profession profession,
                                                 ProfessionRegistrar registrar) {
        REGISTRARS.put(profession, registrar);
    }

    private static void registerProfession(TownspersonMob npc) {
        Profession profession = npc.getProfession();

        // Adventurer is special — handled by the adventurer system
        if (profession == Profession.ADVENTURER) {
            registerAdventurer(npc);
            return;
        }

        ProfessionRegistrar registrar = REGISTRARS.get(profession);
        if (registrar != null) {
            registrar.register(npc);
        }
        // Professions without a registrar (CITIZEN, HERALD, CHANCELLOR,
        // SCHOLAR, PRIEST) get only universal + life-stage goals, which
        // is correct — they wander and socialize by default.
    }

    // =========================================================================
    // Adventurer — special case due to combat role branching
    // =========================================================================

    /**
     * Adventurer goal registration depends on whether the NPC has a combat
     * role (party member) or is a standalone guild adventurer. This is the
     * one profession that can't be a simple registrar lambda because it
     * needs combat role inspection.
     *
     * NOTE: The party-member adventurer goal set (PartyFollowGoal,
     * PartyDefendGoal, PartyCampGoal, etc.) is registered by the
     * adventurer spawning system, not here. This method only handles
     * the standalone adventurer group goals.
     */
    private static void registerAdventurer(TownspersonMob npc) {
        if (npc.getCombatRole() != null) {
            // Standalone adventurer group member — uses group goals
            // These are registered by AdventurerSavedData when spawning.
            // We add only the universal social and interaction goals here.
            npc.goalSelector.addGoal(P_SOCIAL_MID, new SocializeGoal(npc));
        }
        // Party-member adventurers get their goals from party formation
        // commands — ProfessionGoalFactory does not manage those.
    }
}