// src/main/java/tterrag1112/life_in_the_village/Guilds/Companies/CompanySavedData.java
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

public class CompanySavedData extends SavedData {

    // =========================================================================
    // SavedDataType / Codec
    // =========================================================================

    public static final SavedDataType<CompanySavedData> TYPE =
            new SavedDataType<>(
                    "litv_companies",
                    CompanySavedData::new,
                    RecordCodecBuilder.create(i -> i.group(
                            Company.CODEC.listOf()
                                    .optionalFieldOf("companies", new ArrayList<>())
                                    .forGetter(d -> new ArrayList<>(d.companies.values())),
                            // Supply contracts — persisted alongside companies
                            SupplyContract.CODEC.listOf()
                                    .optionalFieldOf("supplyContracts", new ArrayList<>())
                                    .forGetter(d -> new ArrayList<>(d.contracts.values()))
                    ).apply(i, CompanySavedData::fromCodec))
            );

    private static CompanySavedData fromCodec(List<Company> companyList,
                                              List<SupplyContract> contractList) {
        CompanySavedData d = new CompanySavedData();
        companyList.forEach(c -> d.companies.put(c.getCompanyId(), c));
        contractList.forEach(c -> d.contracts.put(c.contractId(), c));
        return d;
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final Map<UUID, Company>         companies = new LinkedHashMap<>();
    private final Map<UUID, SupplyContract>  contracts = new LinkedHashMap<>();

    // =========================================================================
    // Constructor & accessor
    // =========================================================================

    public CompanySavedData() {}

    public static CompanySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Company CRUD
    // =========================================================================

    public void addCompany(Company company) {
        companies.put(company.getCompanyId(), company);
        setDirty();
    }

    public void removeCompany(UUID id) {
        companies.remove(id);
        // Cancel all contracts involving this company
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

    public Optional<Company> getById(UUID id) {
        return Optional.ofNullable(companies.get(id));
    }

    public List<Company> getByOwner(UUID playerId) {
        return companies.values().stream()
                .filter(c -> c.getOwnerPlayerId().equals(playerId))
                .toList();
    }

    public Optional<Company> getCompanyForWorker(UUID npcId) {
        return companies.values().stream()
                .filter(c -> c.getWorker(npcId).isPresent())
                .findFirst();
    }

    public Collection<Company> getAllCompanies() {
        return Collections.unmodifiableCollection(companies.values());
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
     * Returns all contracts where the given company is the buyer or the supplier,
     * in any status. Useful for displaying the full contract list on the
     * management screen.
     */
    public List<SupplyContract> getContractsForCompany(UUID companyId) {
        return contracts.values().stream()
                .filter(c -> c.buyerCompanyId().equals(companyId)
                        || c.supplierCompanyId().equals(companyId))
                .collect(Collectors.toList());
    }

    /**
     * Returns PENDING contracts where the given company is the supplier —
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

    // =========================================================================
    // Main server tick
    // =========================================================================

    public void tick(ServerLevel level,
                     VillageSavedData villageData,
                     long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        for (Company company : companies.values()) {
            if (!company.isActive()) continue;

            // ── Payroll ───────────────────────────────────────────────────────
            List<UUID> unpaid = company.runPayroll(currentTick, villageData);
            if (!unpaid.isEmpty()) {
                notifyOwnerUnpaid(level, company, unpaid);
            } else {
                // All workers paid — give the owner village reputation
                tickReputationForWages(level, company, currentTick);
            }

            // ── Worker production ─────────────────────────────────────────────
            if (company.getWorkSchedule().isWorkTime(currentTick)) {
                tickWorkerProduction(level, company, currentTick, villageData);
            }
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
                                      Company company,
                                      long currentTick,
                                      VillageSavedData villageData) {
        for (Company.CompanyWorker worker : company.getWorkers()) {
            if (worker.assignedItemId().isEmpty()) continue;
            if (worker.role() != Company.WorkerRole.PRODUCER) continue;

            level.getEntitiesOfClass(
                    tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                    new net.minecraft.world.phys.AABB(
                            -30000000, -2048, -30000000,
                            30000000,  2048, 30000000),
                    npc -> npc.getUUID().equals(worker.npcId())
                            && npc.getCompanyId()
                            .map(id -> id.equals(company.getCompanyId()))
                            .orElse(false)
            ).stream().findFirst().ifPresent(npc -> {
                npc.setCurrentActivity("Working for " + company.getName());
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

        for (Company company : companies.values()) {
            if (!company.isActive()) continue;

            long totalDue = 0L;

            for (UUID bid : company.getBuildingIds()) {
                var building = vdata.getBuildingById(bid).orElse(null);
                if (building == null) continue;

                var village = vdata.getVillageById(
                        company.getHomeVillageId()).orElse(null);
                if (village == null) continue;

                long tax = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculateWeeklyTax(
                                building, village, vdata);

                if (tax == 0L) {
                    int footprint = building.getShape().getWidth()
                            * building.getShape().getLength();
                    long rate = vdata.getPropertyTaxRate(
                            company.getHomeVillageId());
                    tax = footprint * rate;
                }

                totalDue += tax;
            }

            if (totalDue == 0L) continue;

            if (company.getTreasuryBronze() >= totalDue) {
                company.withdrawFromTreasury(totalDue);
                setDirty();
                notifyOwner(level, company,
                        net.minecraft.network.chat.Component.literal(
                                        "Building tax of " + totalDue
                                                + "b collected from "
                                                + company.getName()
                                                + " treasury.")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                notifyOwner(level, company,
                        net.minecraft.network.chat.Component.literal(
                                        "\u26A0 " + company.getName()
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
     * Awards village reputation to the company owner once per pay cycle when
     * all workers were successfully paid. Only fires once per 24 000-tick day
     * to avoid spam (checked against the payroll interval).
     */
    private void tickReputationForWages(ServerLevel level,
                                        Company company,
                                        long currentTick) {
        // Only once per in-game day
        if (currentTick % 24000L != 0) return;

        ServerPlayer owner = level.getServer()
                .getPlayerList()
                .getPlayer(company.getOwnerPlayerId());
        if (owner == null) return; // owner offline — skip, not penalised

        ReputationManager.onCompanyWagesPaid(
                owner, company.getHomeVillageId(), level);
    }

    // =========================================================================
    // Notification helpers
    // =========================================================================

    private void notifyOwnerUnpaid(ServerLevel level,
                                   Company company,
                                   List<UUID> unpaidIds) {
        var player = level.getServer().getPlayerList()
                .getPlayer(company.getOwnerPlayerId());
        if (player != null) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                                    "[" + company.getName()
                                            + "] Insufficient funds to pay "
                                            + unpaidIds.size()
                                            + " worker(s). Add funds to the"
                                            + " company treasury.")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    false);
        }
    }

    private void notifyOwner(ServerLevel level,
                             Company company,
                             net.minecraft.network.chat.Component msg) {
        var player = level.getServer().getPlayerList()
                .getPlayer(company.getOwnerPlayerId());
        if (player != null) player.displayClientMessage(msg, false);
    }
}