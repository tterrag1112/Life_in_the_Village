package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessOwner;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.History.HistoryEventType;
import tterrag1112.life_in_the_village.Village.History.HistoryProducer;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.3.c.4 — FARMER equivalent of {@link MerchantPromotion}.
 * Creates a {@link Business} owned by a family (preferred) or by the
 * NPC directly (fallback when no household is registered).
 *
 * <p>Not auto-triggered in 6.3.3.c. The 6.3.3.f FARMHAND consolidation
 * (and any future content) calls this when a FARMER NPC's farmhouse
 * should become a tracked Business.
 */
public final class FarmerPromotion {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Seed transfer cap (bronze) — smaller than the merchant promotion
     *  cap since farms aren't trading houses. */
    private static final long PROMOTION_TRANSFER = 50L;

    private FarmerPromotion() {}

    /**
     * Creates a Business owned by the farmer's household (FamilyOwner)
     * or by the NPC themselves (NpcOwner) if no household exists.
     *
     * @return the new Business, or empty if prerequisites failed
     *         (no village, no farmhouse, NPC offline)
     */
    public static Optional<Business> promoteFarmerToBusinessOwner(
            ServerLevel level, TownspersonMob npc, UUID villageId) {
        if (npc == null || villageId == null) return Optional.empty();
        long now = level.getGameTime();
        VillageSavedData vdata = VillageSavedData.get(level);

        String businessName = npc.getNpcName() + "'s Farm";
        Business business = Business.create(businessName,
                new UUID(0L, 0L), // zero-UUID legacy ownerPlayerId
                villageId);
        business.setFoundedTick(now);

        // Resolve household for FamilyOwner preference.
        UUID householdId = resolveHouseholdId(vdata, npc);
        BusinessOwner owner = (householdId != null)
                ? new BusinessOwner.FamilyOwner(householdId)
                : new BusinessOwner.NpcOwner(npc.getUUID());
        business.setOwnership(owner);

        // Bind the farmhouse.
        UUID buildingId = npc.getAssignedBuildingId().orElse(Business.NO_BUILDING);
        business.addBuilding(buildingId);

        // Capital seed: min(PROMOTION_TRANSFER, NPC wallet).
        long actualTransfer = Math.min(PROMOTION_TRANSFER, npc.getWallet().toBronze());
        if (actualTransfer > 0L
                && npc.getEconomy().spend(
                        tterrag1112.life_in_the_village.Village.Economy.Currency
                                .CurrencyValue.of(actualTransfer))) {
            business.depositBronze(actualTransfer);
        }

        BusinessSavedData.get(level).addBusiness(business);

        // Heir-chain population — for FamilyOwner the household members
        // become heirs; for NpcOwner the NPC's own family seeds heirs
        // (delegated to the shared helper from 6.3.3.c.5).
        BusinessHeirs.populateForOwner(level, business);

        LOGGER.info("[FarmerPromotion] {} ({}) promoted to farm business '{}' (owner={}, treasury={})",
                npc.getNpcName(), npc.getUUID(), businessName,
                owner.kind(), business.getTreasuryBronze());

        Village v = vdata.getVillageById(villageId).orElse(null);
        if (v != null) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("village_name", v.getName());
            details.put("npc_name", npc.getNpcName());
            details.put("business_name", businessName);
            details.put("owner_kind", owner.kind());
            HistoryProducer.record(level, v,
                    HistoryEventType.BUSINESS_FOUNDED,  // reuse the existing event type
                    now, details, List.of(npc.getUUID()));
        }
        return Optional.of(business);
    }

    private static UUID resolveHouseholdId(VillageSavedData vdata, TownspersonMob npc) {
        return vdata.getHouseholdForNpc(npc.getUUID())
                .map(HouseholdData::getHouseholdId)
                .orElse(null);
    }

    /**
     * Phase 6.3.3.q.2 / 6.3.4.1.4 — idempotent Business hook for the
     * spawn pipeline. Originally FARMER-only ({@code ensureFarmBusiness});
     * generalized to cover any profession whose building is a tracked
     * proprietor concern. Owner-type defaulting is per-profession; the
     * polymorphic {@link BusinessOwner} hierarchy already captures the
     * variation that matters (Solo/Family/Guild/Village/Kingdom) so
     * there is no need for a parallel WorkshopBusiness type.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>If the NPC has no assigned building, no village id, or no
     *       Business-eligible profession — no-op, returns {@code empty}.</li>
     *   <li>If a {@link Business} already exists with the NPC's
     *       assigned building in its building list — no-op, returns
     *       that existing Business. Idempotent: safe to call multiple
     *       times per building without creating duplicates.</li>
     *   <li>Otherwise — creates a new Business with the
     *       profession-appropriate default owner (FARMER/FARMHAND →
     *       FamilyOwner preferred, NpcOwner fallback; workshop
     *       professions → NpcOwner). Seed capital transfers only for
     *       the FARMER path; workshop treasuries start empty and the
     *       proprietor's personal wallet remains the income source
     *       until commerce flows through the Business.</li>
     * </ul>
     */
    public static Optional<Business> ensureBusinessForBuilding(
            ServerLevel level, TownspersonMob npc, UUID villageId) {
        if (level == null || npc == null || villageId == null) return Optional.empty();
        UUID buildingId = npc.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return Optional.empty();
        Profession profession = npc.getProfession();
        if (!supportsBusiness(profession)) return Optional.empty();

        BusinessSavedData bdata = BusinessSavedData.get(level);
        for (Business existing : bdata.getAllBusinesses()) {
            if (existing.getBuildingIds().contains(buildingId)) {
                return Optional.of(existing);
            }
        }
        if (profession == Profession.FARMER || profession == Profession.FARMHAND) {
            return promoteFarmerToBusinessOwner(level, npc, villageId);
        }
        return createWorkshopBusiness(level, npc, villageId, profession);
    }

    /**
     * Profession allow-list for spawn-pipeline Business creation.
     * Add a profession here when its activation pass wires up
     * production behavior + storage; the data shape exists from day
     * one even if the only Business mechanic in play is the
     * idempotency record (treasury / heir chain / succession kick in
     * as later phases land).
     */
    private static boolean supportsBusiness(Profession p) {
        return switch (p) {
            case FARMER, FARMHAND, BAKER, MILLER -> true;
            default -> false;
        };
    }

    /**
     * Phase 6.3.4.1.4 — workshop Business creation. Mirrors
     * {@link #promoteFarmerToBusinessOwner} structurally but defaults
     * to {@link BusinessOwner.NpcOwner} (a solo proprietor) and skips
     * the seed-capital transfer per the design call: workshop
     * treasuries start at zero, the proprietor's wallet stays the
     * income source until commerce flows through the Business. The
     * heir chain still populates so future succession events have
     * data to work with.
     */
    private static Optional<Business> createWorkshopBusiness(
            ServerLevel level, TownspersonMob npc, UUID villageId,
            Profession profession) {
        long now = level.getGameTime();
        VillageSavedData vdata = VillageSavedData.get(level);

        String businessName = npc.getNpcName() + "'s " + workshopSuffix(profession);
        Business business = Business.create(businessName,
                new UUID(0L, 0L), // zero-UUID legacy ownerPlayerId
                villageId);
        business.setFoundedTick(now);
        business.setOwnership(new BusinessOwner.NpcOwner(npc.getUUID()));
        business.addBuilding(npc.getAssignedBuildingId().orElse(Business.NO_BUILDING));

        BusinessSavedData.get(level).addBusiness(business);
        BusinessHeirs.populateForOwner(level, business);

        LOGGER.info("[FarmerPromotion] {} ({}) registered workshop business '{}' (owner=npc, treasury=0)",
                npc.getNpcName(), npc.getUUID(), businessName);

        Village v = vdata.getVillageById(villageId).orElse(null);
        if (v != null) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("village_name", v.getName());
            details.put("npc_name", npc.getNpcName());
            details.put("business_name", businessName);
            details.put("owner_kind", "npc");
            details.put("profession", profession.name());
            HistoryProducer.record(level, v,
                    HistoryEventType.BUSINESS_FOUNDED,
                    now, details, List.of(npc.getUUID()));
        }
        return Optional.of(business);
    }

    private static String workshopSuffix(Profession p) {
        return switch (p) {
            case BAKER  -> "Bakery";
            case MILLER -> "Mill";
            default     -> p.getDisplayName();
        };
    }
}
