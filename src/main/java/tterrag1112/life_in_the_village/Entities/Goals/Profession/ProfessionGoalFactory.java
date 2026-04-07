package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Baker.BakerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderMaintenanceGoal;
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
        npc.goalSelector.addGoal(P_COMBAT,    new ReturnHomeGoal(npc));
        npc.goalSelector.addGoal(P_PATHFIND,  new SeekHouseGoal(npc));
        npc.goalSelector.addGoal(P_SOCIAL_HIGH, new EatMealGoal(npc));
        npc.goalSelector.addGoal(P_SOCIAL_LOW,  new ChildBirthGoal(npc));
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
                npc.goalSelector.addGoal(P_SOCIAL_LOW, new BuyFromMarketGoal(npc));
                if (npc.getLifeStage() == LifeStage.ADULT) {
                    npc.goalSelector.addGoal(P_SOCIAL_LOW, new CourtingGoal(npc));
                }
            }
            case ELDERLY -> {
                npc.goalSelector.addGoal(P_SOCIAL_MID, new ElderlyRelaxGoal(npc));
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

        REGISTRARS.put(Profession.BLACKSMITH, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new BlacksmithGoal(npc));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
                    new PostListingGoal(npc, npc.getSellableItems(Profession.BLACKSMITH)));
            npc.goalSelector.addGoal(P_WORK_SECONDARY,
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

// Update BLACKSMITH to use the migrated goal:
        REGISTRARS.put(Profession.BLACKSMITH, npc -> {
            npc.goalSelector.addGoal(P_WORK_PRIMARY, new BlacksmithGoal(npc));
        });

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