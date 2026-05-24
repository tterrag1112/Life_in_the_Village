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
     * Phase 6.3.3.q.2 — idempotent farm-Business hook for the spawn
     * pipeline. Used by {@code VillageInhabitantPopulator} (and any
     * other spawn path that produces a FARMER bound to a FARMHOUSE)
     * to guarantee that the farmhouse has a tracked
     * {@link Business} associated with it.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>If the NPC has no assigned building, no village id, or
     *       no profession of {@code FARMER}/{@code FARMHAND} — no-op,
     *       returns {@code empty}.</li>
     *   <li>If a {@link Business} already exists with the NPC's
     *       assigned building in its building list — no-op, returns
     *       that existing Business. Idempotent: safe to call
     *       multiple times per farmhouse without creating duplicates.</li>
     *   <li>Otherwise — delegates to
     *       {@link #promoteFarmerToBusinessOwner} which creates the
     *       Business with the NPC as owner (FamilyOwner preferred,
     *       NpcOwner fallback) and binds the farmhouse.</li>
     * </ul>
     *
     * <p>Why a separate API: the legacy {@code promoteFarmerTo-
     * BusinessOwner} always creates a new Business. Calling it on
     * the spawn pipeline directly would produce duplicates whenever
     * the populator runs again over the same farmhouse (save
     * migration, repeat-spawn paths, etc.). The idempotency check
     * here makes the hook safe at any call site.</p>
     */
    public static Optional<Business> ensureFarmBusiness(
            ServerLevel level, TownspersonMob npc, UUID villageId) {
        if (level == null || npc == null || villageId == null) return Optional.empty();
        UUID buildingId = npc.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return Optional.empty();
        var profession = npc.getProfession();
        if (profession
                != tterrag1112.life_in_the_village.Profession.Profession.FARMER
                && profession
                != tterrag1112.life_in_the_village.Profession.Profession.FARMHAND) {
            return Optional.empty();
        }
        BusinessSavedData bdata = BusinessSavedData.get(level);
        for (Business existing : bdata.getAllBusinesses()) {
            if (existing.getBuildingIds().contains(buildingId)) {
                return Optional.of(existing);
            }
        }
        return promoteFarmerToBusinessOwner(level, npc, villageId);
    }
}
