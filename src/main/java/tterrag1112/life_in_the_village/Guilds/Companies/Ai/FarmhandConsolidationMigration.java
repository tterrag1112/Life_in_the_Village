package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessOwner;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.EmploymentTier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.UUID;

/**
 * Phase 6.3.3.f.6 — one-shot save migration for the FARMHAND → FARMER
 * consolidation. Idempotent: safe to run on every server start. Each
 * run logs a single summary line; subsequent runs find no work to do
 * (the per-NPC profession rewrite has already happened at NBT-load
 * time via {@code TownspersonMob.readAdditionalSaveData}).
 *
 * <h3>Two phases</h3>
 * <ol>
 *   <li><strong>Promote FARMERs to Business owners</strong>: walk all
 *       FARMHOUSE buildings; for any farmhouse without an existing
 *       farm Business, find the FARMER NPC assigned and call
 *       {@link FarmerPromotion#promoteFarmerToBusinessOwner}.</li>
 *   <li><strong>Add APPRENTICE workers</strong>: walk all loaded
 *       FARMER NPCs (which post-load includes the former-FARMHANDs);
 *       for each one whose home is a farmhouse Business and who isn't
 *       already in the workers map AND isn't the owner, add as an
 *       APPRENTICE-tier worker.</li>
 * </ol>
 *
 * <p>Triggered from {@code Life_in_the_village.onServerStarting} so it
 * runs once per server boot across every loaded ServerLevel.
 */
public final class FarmhandConsolidationMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FarmhandConsolidationMigration() {}

    public static void migrateAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            migrate(level);
        }
    }

    public static void migrate(ServerLevel level) {
        if (level == null) return;
        VillageSavedData vdata = VillageSavedData.get(level);
        BusinessSavedData bdata = BusinessSavedData.get(level);

        int promoted = 0;
        int workersAdded = 0;

        // Phase 1: auto-promote FARMERs at FARMHOUSEs that lack a business.
        for (Building b : vdata.getAllBuildings()) {
            if (b.getType() != BuildingType.FARMHOUSE) continue;
            if (hasBusinessForBuilding(bdata, b.getId())) continue;
            TownspersonMob farmer = findFarmerAtFarmhouse(level, vdata, b.getId());
            if (farmer == null) continue;
            UUID villageId = farmer.getAssignedVillageName()
                    .flatMap(vdata::getVillageByName)
                    .map(v -> v.getId())
                    .orElse(null);
            if (villageId == null) continue;
            if (FarmerPromotion.promoteFarmerToBusinessOwner(level, farmer, villageId)
                    .isPresent()) {
                promoted++;
            }
        }

        // Phase 2: enroll former-FARMHANDs (now FARMERs) as APPRENTICE
        // workers in their assigned farmhouse's Business.
        for (TownspersonMob npc : findAllFarmers(level)) {
            UUID buildingId = npc.getAssignedBuildingId().orElse(null);
            if (buildingId == null) continue;
            Business business = findBusinessForBuilding(bdata, buildingId);
            if (business == null) continue;
            if (isOwnerOrManager(business, npc.getUUID())) continue;
            if (business.getWorkers().stream()
                    .anyMatch(w -> w.npcId().equals(npc.getUUID()))) continue;
            business.addWorker(new Business.BusinessWorker(
                    npc.getUUID(),
                    Business.WorkerRole.PRODUCER,
                    Business.ProducerType.FARMER,
                    buildingId,
                    /* wagePerDay */ 4L,
                    /* lastPaidTick */ level.getGameTime(),
                    /* assignedItemId */ "",
                    /* dailyTargetCount */ 8,
                    EmploymentTier.APPRENTICE));
            workersAdded++;
        }

        if (promoted > 0 || workersAdded > 0) {
            LOGGER.info("[FarmhandConsolidation] {}: promoted {} farms to Businesses; "
                            + "added {} APPRENTICE workers to farm rosters",
                    level.dimension().identifier(), promoted, workersAdded);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean hasBusinessForBuilding(BusinessSavedData bdata, UUID buildingId) {
        for (Business b : bdata.getAllBusinesses()) {
            if (b.getBuildingIds().contains(buildingId)) return true;
        }
        return false;
    }

    private static Business findBusinessForBuilding(BusinessSavedData bdata, UUID buildingId) {
        for (Business b : bdata.getAllBusinesses()) {
            if (b.getBuildingIds().contains(buildingId)) return b;
        }
        return null;
    }

    private static TownspersonMob findFarmerAtFarmhouse(ServerLevel level,
                                                        VillageSavedData vdata,
                                                        UUID farmhouseId) {
        for (TownspersonMob npc : findAllFarmers(level)) {
            if (npc.getAssignedBuildingId().filter(farmhouseId::equals).isPresent()) {
                // The first FARMER we find at the farmhouse is the head;
                // subsequent FARMERs (formerly FARMHANDs) become workers
                // in phase 2.
                return npc;
            }
        }
        return null;
    }

    private static java.util.List<TownspersonMob> findAllFarmers(ServerLevel level) {
        java.util.List<TownspersonMob> out = new java.util.ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof TownspersonMob npc
                    && npc.getProfession() == Profession.FARMER
                    && npc.isAlive()) {
                out.add(npc);
            }
        }
        return out;
    }

    private static boolean isOwnerOrManager(Business business, UUID npcUuid) {
        BusinessOwner owner = business.getOwner();
        if (owner != null && npcUuid.equals(owner.getPrimaryUuid())) return true;
        return business.getManagerId().map(npcUuid::equals).orElse(false);
    }
}
