// src/main/java/tterrag1112/life_in_the_village/Guilds/Companies/BusinessSavedData.java
package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;

import java.util.*;
import java.util.stream.Collectors;

public class BusinessSavedData extends SavedData {

    // =========================================================================
    // SavedDataType / Codec
    // =========================================================================

    public static final SavedDataType<BusinessSavedData> TYPE =
            new SavedDataType<>(
                    // Phase 6.3.3.b — storage key kept as "litv_companies" for
                    // save compat. Class renamed Company → Business, but the
                    // on-disk .dat file lookup must continue to find existing
                    // worlds' data. Field key "businesses" is also accepted via
                    // optionalFieldOf below; legacy "companies" entries fall
                    // through to the empty default — acceptable for in-flight
                    // worlds where TRADING_BUSINESS / heirs / etc. are mostly
                    // empty regardless.
                    "litv_companies",
                    BusinessSavedData::new,
                    RecordCodecBuilder.create(i -> i.group(
                            Business.CODEC.listOf()
                                    .optionalFieldOf("businesses", new ArrayList<>())
                                    .forGetter(d -> new ArrayList<>(d.businesses.values())),
                            // Supply contracts — persisted alongside businesses
                            SupplyContract.CODEC.listOf()
                                    .optionalFieldOf("supplyContracts", new ArrayList<>())
                                    .forGetter(d -> new ArrayList<>(d.contracts.values()))
                    ).apply(i, BusinessSavedData::fromCodec))
            );

    private static BusinessSavedData fromCodec(List<Business> businessList,
                                              List<SupplyContract> contractList) {
        BusinessSavedData d = new BusinessSavedData();
        businessList.forEach(c -> d.businesses.put(c.getBusinessId(), c));
        contractList.forEach(c -> d.contracts.put(c.contractId(), c));
        return d;
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final Map<UUID, Business>         businesses = new LinkedHashMap<>();
    private final Map<UUID, SupplyContract>  contracts = new LinkedHashMap<>();

    // =========================================================================
    // Constructor & accessor
    // =========================================================================

    public BusinessSavedData() {}

    public static BusinessSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Business CRUD
    // =========================================================================

    public void addBusiness(Business business) {
        businesses.put(business.getBusinessId(), business);
        setDirty();
    }

    public void removeBusiness(UUID id) {
        businesses.remove(id);
        // Cancel all contracts involving this business
        contracts.values().stream()
                .filter(c -> c.buyerCompanyId().equals(id)
                        || c.supplierCompanyId().equals(id))
                .map(SupplyContract::contractId)
                .collect(Collectors.toList())
                .forEach(contractId -> contracts.put(
                        contractId,
                        contracts.get(contractId)
                                .withStatus(SupplyContract.ContractStatus.CANCELLED)));
        setDirty();
    }

    public Optional<Business> getById(UUID id) {
        return Optional.ofNullable(businesses.get(id));
    }

    public List<Business> getByOwner(UUID playerId) {
        return businesses.values().stream()
                .filter(c -> c.getOwnerPlayerId().equals(playerId))
                .toList();
    }

    public Optional<Business> getBusinessForWorker(UUID npcId) {
        return businesses.values().stream()
                .filter(c -> c.getWorker(npcId).isPresent())
                .findFirst();
    }

    public Collection<Business> getAllBusinesses() {
        return Collections.unmodifiableCollection(businesses.values());
    }

    /**
     * T5b-1 — all businesses registered with {@code guildId}. The
     * reverse of {@link Business#registerWithGuild}; the guild → business
     * → NPC request cascade (T5b-2/3) will use this, optionally further
     * filtering by the business's craft Profession.
     */
    public List<Business> forGuild(UUID guildId) {
        if (guildId == null) return List.of();
        return businesses.values().stream()
                .filter(b -> b.getGuildId().map(guildId::equals).orElse(false))
                .toList();
    }

    /** Exposes dirty marking to external systems (e.g. SupplyContractManager). */
    public void markDirty() { setDirty(); }

    // =========================================================================
    // Supply Contract CRUD
    // =========================================================================

    /**
     * Stores a newly proposed contract (status = PENDING).
     * Use {@link SupplyContractManager#proposeContract} rather than calling
     * this directly, as that method also notifies the supplier.
     */
    public void addContract(SupplyContract contract) {
        contracts.put(contract.contractId(), contract);
        setDirty();
    }

    /**
     * Replaces an existing contract record in-place.
     * Called after any status or missedDeliveries change.
     */
    public void updateContract(SupplyContract contract) {
        contracts.put(contract.contractId(), contract);
        setDirty();
    }

    /** Returns all contracts regardless of status. */
    public List<SupplyContract> getAllContracts() {
        return List.copyOf(contracts.values());
    }

    /**
     * Returns all contracts where the given business is the buyer or the supplier,
     * in any status. Useful for displaying the full contract list on the
     * management screen.
     */
    public List<SupplyContract> getContractsForBusiness(UUID businessId) {
        return contracts.values().stream()
                .filter(c -> c.buyerCompanyId().equals(businessId)
                        || c.supplierCompanyId().equals(businessId))
                .collect(Collectors.toList());
    }

    /**
     * Returns PENDING contracts where the given business is the supplier —
     * used to show incoming proposals to the supplier owner.
     */
    public List<SupplyContract> getPendingContractsForSupplier(UUID supplierCompanyId) {
        return contracts.values().stream()
                .filter(c -> c.supplierCompanyId().equals(supplierCompanyId)
                        && c.isPending())
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Tick constants
    // =========================================================================

    private static final long TICK_INTERVAL      = 20L;
    private static final long BUILDING_TAX_INTERVAL = 24000L * 7; // weekly
    // T5b-1 — guild registration sweep cadence (once per in-game day).
    private static final long GUILD_REGISTER_INTERVAL = 24000L;

    // =========================================================================
    // Main server tick
    // =========================================================================

    public void tick(ServerLevel level,
                     VillageSavedData villageData,
                     long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        for (Business business : businesses.values()) {
            if (!business.isActive()) continue;

            // ── Payroll ───────────────────────────────────────────────────────
            List<UUID> unpaid = business.runPayroll(currentTick, villageData);
            if (!unpaid.isEmpty()) {
                notifyOwnerUnpaid(level, business, unpaid);
            } else {
                // All workers paid — give the owner village reputation
                tickReputationForWages(level, business, currentTick);
            }

            // ── Worker production ─────────────────────────────────────────────
            if (business.getWorkSchedule().isWorkTime(currentTick)) {
                tickWorkerProduction(level, business, currentTick, villageData);
            }
        }

        // ── Guild registration ─────────────────────────────────────────────────
        // T5b-1 — throttled daily sweep binding each business to its
        // village guild (derived from building-type craft). Idempotent:
        // makes no changes when affiliations are already correct.
        if (currentTick % GUILD_REGISTER_INTERVAL == 0) {
            BusinessGuildRegistrar.registerAll(level, this, villageData);
        }

        // ── Building tax ──────────────────────────────────────────────────────
        tickBuildingTax(level, villageData, currentTick);

        // ── Supply contracts ──────────────────────────────────────────────────
        // Runs on the daily payroll cadence (every 24 000 ticks).
        // We piggyback on the TICK_INTERVAL guard above; the contract manager
        // internally gates on PAY_INTERVAL so contracts settle once per day.
        if (currentTick % 24000L == 0) {
            SupplyContractManager.tickContracts(level, this, villageData);
        }

        setDirty();
    }

    // =========================================================================
    // Worker production tick
    // =========================================================================

    private void tickWorkerProduction(ServerLevel level,
                                      Business business,
                                      long currentTick,
                                      VillageSavedData villageData) {
        for (Business.BusinessWorker worker : business.getWorkers()) {
            if (worker.assignedItemId().isEmpty()) continue;
            if (worker.role() != Business.WorkerRole.PRODUCER) continue;

            level.getEntitiesOfClass(
                    tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                    new net.minecraft.world.phys.AABB(
                            -30000000, -2048, -30000000,
                            30000000,  2048, 30000000),
                    npc -> npc.getUUID().equals(worker.npcId())
                            && npc.getBusinessId()
                            .map(id -> id.equals(business.getBusinessId()))
                            .orElse(false)
            ).stream().findFirst().ifPresent(npc -> {
                npc.setCurrentActivity("Working for " + business.getName());
                // Production tick is driven by CompanyWorkerGoal on the NPC entity
            });
        }
    }

    // =========================================================================
    // Building tax tick
    // =========================================================================

    private void tickBuildingTax(ServerLevel level,
                                 VillageSavedData vdata,
                                 long currentTick) {
        if (currentTick % BUILDING_TAX_INTERVAL != 0) return;

        for (Business business : businesses.values()) {
            if (!business.isActive()) continue;

            long totalDue = 0L;

            for (UUID bid : business.getBuildingIds()) {
                var building = vdata.getBuildingById(bid).orElse(null);
                if (building == null) continue;

                var village = vdata.getVillageById(
                        business.getHomeVillageId()).orElse(null);
                if (village == null) continue;

                long tax = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculateWeeklyTax(
                                building, village, vdata);

                if (tax == 0L) {
                    int footprint = building.getShape().getWidth()
                            * building.getShape().getLength();
                    long rate = vdata.getPropertyTaxRate(
                            business.getHomeVillageId());
                    tax = footprint * rate;
                }

                totalDue += tax;
            }

            if (totalDue == 0L) continue;

            if (business.getTreasuryBronze() >= totalDue) {
                business.withdrawFromTreasury(totalDue);
                setDirty();
                notifyOwner(level, business,
                        net.minecraft.network.chat.Component.literal(
                                        "Building tax of " + totalDue
                                                + "b collected from "
                                                + business.getName()
                                                + " treasury.")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                notifyOwner(level, business,
                        net.minecraft.network.chat.Component.literal(
                                        "\u26A0 " + business.getName()
                                                + " cannot afford building tax ("
                                                + totalDue + "b due). "
                                                + "Deposit funds to avoid losing buildings.")
                                .withStyle(net.minecraft.ChatFormatting.RED));
            }
        }
    }

    // =========================================================================
    // Reputation helper
    // =========================================================================

    /**
     * Awards village reputation to the business owner once per pay cycle when
     * all workers were successfully paid. Only fires once per 24 000-tick day
     * to avoid spam (checked against the payroll interval).
     */
    private void tickReputationForWages(ServerLevel level,
                                        Business business,
                                        long currentTick) {
        // Only once per in-game day
        if (currentTick % 24000L != 0) return;

        ServerPlayer owner = level.getServer()
                .getPlayerList()
                .getPlayer(business.getOwnerPlayerId());
        if (owner == null) return; // owner offline — skip, not penalised

        ReputationManager.onCompanyWagesPaid(
                owner, business.getHomeVillageId(), level);
    }

    // =========================================================================
    // Notification helpers
    // =========================================================================

    private void notifyOwnerUnpaid(ServerLevel level,
                                   Business business,
                                   List<UUID> unpaidIds) {
        var player = level.getServer().getPlayerList()
                .getPlayer(business.getOwnerPlayerId());
        if (player != null) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                                    "[" + business.getName()
                                            + "] Insufficient funds to pay "
                                            + unpaidIds.size()
                                            + " worker(s). Add funds to the"
                                            + " business treasury.")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    false);
        }
    }

    private void notifyOwner(ServerLevel level,
                             Business business,
                             net.minecraft.network.chat.Component msg) {
        var player = level.getServer().getPlayerList()
                .getPlayer(business.getOwnerPlayerId());
        if (player != null) player.displayClientMessage(msg, false);
    }
}