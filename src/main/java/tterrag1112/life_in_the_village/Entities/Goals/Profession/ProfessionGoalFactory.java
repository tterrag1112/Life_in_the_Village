package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Baker.BakerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderMaintenanceGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderRepaintGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Candlemaker.CandlemakerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Carpenter.CarpenterGoal;
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
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Miller.MillerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Miner.MinerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.StockpileKeeper.StockpileKeeperGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Stonemason.StonemasonGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Weaver.WeaverGoal;
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
 *  3  — duty: ConstableInvestigationGoal (role-power-gated; preempts pathfind/work)
 *  4  — pathfinding: GuardPatrol, VillageLeader, KingdomRuler
 *  5  — social (high): EatMeal (during meal window)
 *  6  — social (mid): Socialize, ChildPlay, Mentor
 *  7  — social (low): Greeting, Courting, ChildBirth, Hobby, BuyGoods
 *  8  — homeseek: SeekHouseGoal (homeless + off-work only)
 *  9  — profession work (primary): FarmerGoal, BlacksmithGoal, etc.
 * 10  — profession work (secondary): SellToMarket, PostListing, BuilderMaintenance
 * 11  — profession work (tertiary): BuyFromNpc, BuilderRepaint
 * 12  — idle: WanderInBuilding
 * 13  — ambient: LookAtPlayer, RandomLookAround, ElderlyRelax
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
    /** Duty interrupt band — investigation / similar role-power-gated
     *  goals that preempt every non-combat behavior when active.
     *  Slots above {@link #P_PATHFIND} so leader/ruler/guard duty
     *  yields to an active crime investigation; below {@link #P_COMBAT}
     *  so a constable under attack still fights first. */
    public static final int P_DUTY           = 3;
    public static final int P_PATHFIND       = 4;
    public static final int P_SOCIAL_HIGH    = 5;
    public static final int P_SOCIAL_MID     = 6;
    public static final int P_SOCIAL_LOW     = 7;
    /** Dedicated band for the homeless-house-seek behavior. Distinct
     *  from {@link #P_PATHFIND} so it doesn't share a slot with the
     *  leader/ruler/guard duty goals; the goal's own canUse gate
     *  (homeless &amp; off-work) keeps it temporally exclusive. */
    public static final int P_HOMESEEK       = 8;
    public static final int P_WORK_PRIMARY   = 9;
    public static final int P_WORK_SECONDARY = 10;
    /** Subordinate work band — non-dominant secondary work goals that
     *  must yield to a concurrent {@link #P_WORK_SECONDARY} goal
     *  (e.g. buying raw materials yielding to selling finished goods). */
    public static final int P_WORK_TERTIARY  = 11;
    public static final int P_IDLE           = 12;
    public static final int P_AMBIENT        = 13;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Clears and re-registers all goals for the given NPC based on
     * current profession and life stage. Safe to call repeatedly
     * (e.g. on profession change or life stage transition).
     */
    /**
     * Whether to run {@link GoalPriorityValidator} after each
     * registration. Always-on by default; flip off if it ever proves
     * costly during mass-spawn paths.
     */
    public static boolean VALIDATE_AFTER_REGISTER = true;

    public static void register(TownspersonMob npc) {
        npc.goalSelector.removeAllGoals(g -> true);
        npc.targetSelector.removeAllGoals(g -> true);

        registerUniversal(npc);
        registerLifeStage(npc);
        registerProfession(npc);

        if (VALIDATE_AFTER_REGISTER) {
            GoalPriorityValidator.validate(npc);
        }
    }

    // =========================================================================
    // Universal goals — all NPCs regardless of profession
    // =========================================================================

    private static void registerUniversal(TownspersonMob npc) {
        npc.goalSelector.addGoal(P_SURVIVAL,  new FloatGoal(npc));
        npc.goalSelector.addGoal(P_SURVIVAL,  new OpenDoorGoal(npc, true));
        npc.goalSelector.addGoal(P_COMBAT,    new ReturnHomeGoal(npc));
        npc.goalSelector.addGoal(P_HOMESEEK,  new SeekHouseGoal(npc));
        npc.goalSelector.addGoal(P_SOCIAL_HIGH, new EatMealGoal(npc));
        npc.goalSelector.addGoal(P_SOCIAL_LOW,  new ChildBirthGoal(npc));
        // Hobby goal (Phase 2 task 14) — slots above WanderInBuilding so
        // an NPC in LEISURE actually goes and does their hobby instead
        // of milling around at home.
        npc.goalSelector.addGoal(P_SOCIAL_LOW,
                new tterrag1112.life_in_the_village.Npc.Hobby.HobbyGoal(npc));
        // Phase 3 task 24: greet players who enter the NPC's assigned
        // business-front building. Slots between combat and work so
        // greeting pre-empts production. Stays no-op until external
        // GreeterAssignment.assign() seats a target player.
        npc.goalSelector.addGoal(P_SOCIAL_HIGH,
                new tterrag1112.life_in_the_village.Npc.BusinessFront.GreetPlayerGoal(npc));
        // Phase 3 task 19: village_constable's investigation pass.
        // Slotted at P_DUTY (above P_PATHFIND, below P_COMBAT) so an
        // active investigation interrupts leader/ruler/guard duty and
        // ordinary work, but a constable under attack still defends
        // themselves first. canUse short-circuits when the NPC doesn't
        // currently hold INVESTIGATE_CRIME, so non-constables pay only
        // the Goal-list overhead.
        npc.goalSelector.addGoal(P_DUTY,
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
                npc.goalSelector.addGoal(P_SOCIAL_LOW, new BuyGoodsGoal(npc));
                if (npc.getLifeStage() == LifeStage.ADULT) {
                    npc.goalSelector.addGoal(P_SOCIAL_LOW, new CourtingGoal(npc));
                }
            }
            case ELDERLY -> {
                // ElderlyRelax is a do-nothing fallback — any real
                // elderly behavior (MentorGoal, Hobby, social) should
                // preempt it. Sit it at P_AMBIENT so it never thrashes
                // a higher-intent MOVE goal.
                npc.goalSelector.addGoal(P_AMBIENT, new ElderlyRelaxGoal(npc));
                npc.goalSelector.addGoal(P_SOCIAL_LOW, new GreetingGoal(npc));
                // Phase 2 task 15 — elderly with master-tier skill mentor a
                // younger colleague at the same workplace.
                npc.goalSelector.addGoal(P_SOCIAL_MID,
                        new tterrag1112.life_in_the_village.Entities.Goals.Social.MentorGoal(npc));
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

        REGISTRARS.put(Profession.BLACKSMITH, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new BlacksmithGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.BLACKSMITH)));
            // Buying raw materials yields to selling finished goods —
            // subordinate band so SellToMarket dominates.
            npc.goalSelector.addGoal(P_WORK_TERTIARY,
                    new BuyFromNpcGoal(npc,
                            npc::needsOre,
                            () -> net.minecraft.world.item.Items.RAW_IRON,
                            () -> 16, 1200));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.BLACKSMITH)));
            npc.goalSelector.addGoal(P_SOCIAL_LOW,        // ← new
                    new WorkshopStallDecisionGoal(npc));
        });

        REGISTRARS.put(Profession.CARPENTER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new CarpenterGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.CARPENTER)));
            npc.goalSelector.addGoal(P_SOCIAL_LOW,        // ← new
                    new WorkshopStallDecisionGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.CARPENTER)));
        });
        REGISTRARS.put(Profession.MILLER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new MillerGoal(npc));

        });

        REGISTRARS.put(Profession.BAKER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new BakerGoal(npc));
        });

        REGISTRARS.put(Profession.STONEMASON, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new StonemasonGoal(npc));
        });

        REGISTRARS.put(Profession.WEAVER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new WeaverGoal(npc));
        });

        REGISTRARS.put(Profession.CANDLEMAKER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new CandlemakerGoal(npc));
        });

        REGISTRARS.put(Profession.MINER, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new MinerGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new SellToMarketGoal(npc, npc.getSellableItems(Profession.MINER)));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.MINER)));
            // Pickaxe purchase yields to selling finished ore.
            npc.goalSelector.addGoal(P_WORK_TERTIARY,
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
            // Repaint is subordinate to maintenance: maintenance stays at
            // P_WORK_SECONDARY, repaint drops to P_WORK_TERTIARY so the
            // priority itself encodes "maintenance dominant".
            npc.goalSelector.addGoal(P_WORK_TERTIARY, new BuilderRepaintGoal(npc));
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
            // Phase 2 task 18: postal round during SOCIAL phase.
            npc.goalSelector.addGoal(P_SOCIAL_LOW,
                    new tterrag1112.life_in_the_village.Entities.Goals.Profession.Scribal.PostalGoal(npc));
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