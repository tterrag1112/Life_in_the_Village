package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 26 lines 81-102 — promotes eligible MERCHANT NPCs to
 * trading-company owners.
 *
 * <h3>Eligibility</h3>
 * <ul>
 *   <li>Profession is {@link Profession#MERCHANT}.</li>
 *   <li>{@link Skill#COMMERCE} ≥ {@link #COMMERCE_THRESHOLD} (70).</li>
 *   <li>Personal wallet ≥ {@link #STARTUP_CAPITAL} (500 br).</li>
 *   <li>Continuously merchant for ≥ {@link #TENURE_DAYS} (365). The
 *   tenure clock lives on
 *   {@link TownspersonMob#getProfessionStartedTick} and resets on
 *   every {@code setProfession} call, so retraining naturally
 *   restarts the timer (per "Things to flag" #2).</li>
 *   <li>Currently runs a market stall — {@link BuildingType#MARKET}
 *   assigned via {@code getAssignedBuildingId}.</li>
 * </ul>
 *
 * <h3>Promotion</h3>
 * Creates a TRADING_COMPANY {@link Company} owned by the NPC, transfers
 * 100 br from the NPC's wallet into the company treasury, makes the
 * NPC the owner-as-PRODUCER worker, and seeds the {@code company_owner}
 * office. The {@link tterrag1112.life_in_the_village.Guilds.Companies.Company.OwnerType}
 * flips to NPC and the per-company succession state stays ACTIVE.
 */
public final class MerchantPromotion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec line 86 — Grandmaster commerce floor. */
    public static final int COMMERCE_THRESHOLD = 70;
    /** Spec line 87 — initial capital floor in NPC wallet. */
    public static final long STARTUP_CAPITAL = 500L;
    /** Bronze transferred from NPC wallet to company treasury on promotion. */
    public static final long PROMOTION_TRANSFER = 100L;
    /** Spec line 88 — continuous-merchant tenure threshold. */
    public static final int TENURE_DAYS = 365;
    /** One in-game day in ticks. */
    public static final long DAY = 24000L;

    private MerchantPromotion() {}

    /** Returns true when {@code npc} satisfies every eligibility
     *  criterion. Used both by the daily scan and by /company
     *  promote &lt;npc&gt;. */
    public static boolean isEligible(TownspersonMob npc, ServerLevel level) {
        if (npc == null) return false;
        if (npc.getProfession() != Profession.MERCHANT) return false;
        if (npc.getSkills().getLevel(Skill.COMMERCE) < COMMERCE_THRESHOLD) return false;
        if (npc.getWallet().toBronze() < STARTUP_CAPITAL) return false;
        long started = npc.getProfessionStartedTick();
        if (started <= 0L) return false;
        if (level.getGameTime() - started < TENURE_DAYS * DAY) return false;
        // Must run a market stall (MARKET building assignment).
        UUID buildingId = npc.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return false;
        Optional<Building> b = VillageSavedData.get(level).getBuildingById(buildingId);
        return b.isPresent() && b.get().getType() == BuildingType.MARKET;
    }

    /**
     * Promotes the NPC. Returns the new {@link Company} on success,
     * empty when eligibility fails or required village context is
     * missing.
     */
    public static Optional<Company> promote(ServerLevel level, TownspersonMob npc) {
        if (!isEligible(npc, level)) return Optional.empty();
        UUID villageId = npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(Village::getId).orElse(null);
        if (villageId == null) return Optional.empty();
        return Optional.of(promoteUnchecked(level, npc, villageId));
    }

    /** Force-promote path used by /company promote when eligibility
     *  is intentionally bypassed for testing. Caller has verified the
     *  NPC is a merchant assigned to a market. */
    public static Company forcePromote(ServerLevel level, TownspersonMob npc) {
        UUID villageId = npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(Village::getId).orElse(null);
        if (villageId == null) {
            throw new IllegalStateException("NPC has no assigned village");
        }
        return promoteUnchecked(level, npc, villageId);
    }

    private static Company promoteUnchecked(ServerLevel level, TownspersonMob npc,
                                            UUID villageId) {
        long now = level.getGameTime();
        String companyName = npc.getNpcName() + "'s Trading House";
        Company company = Company.create(companyName,
                /* legacy player owner field — sentinel zero */
                new UUID(0L, 0L),
                villageId);
        company.setCompanyType(Company.CompanyType.TRADING_COMPANY);
        company.setNpcOwner(npc.getUUID());
        company.setFoundedTick(now);

        // Owner as PRODUCER worker at their own market.
        UUID buildingId = npc.getAssignedBuildingId().orElse(Company.NO_BUILDING);
        company.addBuilding(buildingId);
        company.addWorker(new Company.CompanyWorker(
                npc.getUUID(),
                Company.WorkerRole.PRODUCER,
                Company.ProducerType.GENERIC,
                buildingId,
                /* wagePerDay */ 0L, /* lastPaidTick */ now,
                /* assignedItemId */ "", /* dailyTargetCount */ 8));

        // Capital transfer: NPC wallet → company treasury via spend().
        long actualTransfer = Math.min(PROMOTION_TRANSFER, npc.getWallet().toBronze());
        if (actualTransfer > 0L
                && npc.getEconomy().spend(
                        tterrag1112.life_in_the_village.Village.Economy.Currency
                                .CurrencyValue.of(actualTransfer))) {
            company.depositBronze(actualTransfer);
        }

        CompanySavedData.get(level).addCompany(company);
        LOGGER.info("[MerchantPromotion] {} ({}) promoted to TRADING_COMPANY '{}' (treasury={})",
                npc.getNpcName(), npc.getUUID(), companyName, company.getTreasuryBronze());
        return company;
    }
}
