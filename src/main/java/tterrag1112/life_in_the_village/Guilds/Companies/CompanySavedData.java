package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

public class CompanySavedData extends SavedData {

    public static final SavedDataType<CompanySavedData> TYPE =
            new SavedDataType<>(
                    "litv_companies",
                    CompanySavedData::new,
                    RecordCodecBuilder.create(i -> i.group(
                            Company.CODEC.listOf()
                                    .optionalFieldOf("companies", new ArrayList<>())
                                    .forGetter(d -> new ArrayList<>(d.companies.values()))
                    ).apply(i, CompanySavedData::fromCodec))
            );

    private static CompanySavedData fromCodec(List<Company> list) {
        CompanySavedData d = new CompanySavedData();
        list.forEach(c -> d.companies.put(c.getCompanyId(), c));
        return d;
    }

    private final Map<UUID, Company> companies = new LinkedHashMap<>();

    public CompanySavedData() {}

    public static CompanySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }


    public void addCompany(Company company) {
        companies.put(company.getCompanyId(), company);
        setDirty();
    }

    public void removeCompany(UUID id) { companies.remove(id); setDirty(); }

    public Optional<Company> getById(UUID id) {
        return Optional.ofNullable(companies.get(id));
    }

    public List<Company> getByOwner(UUID playerId) {
        return companies.values().stream()
                .filter(c -> c.getOwnerPlayerId().equals(playerId))
                .toList();
    }

    private static final long BUILDING_TAX_INTERVAL = 24000L * 7; // weekly
    private static final long BASE_BUILDING_TAX_RATE = 2L;

    public Optional<Company> getCompanyForWorker(UUID npcId) {
        return companies.values().stream()
                .filter(c -> c.getWorker(npcId).isPresent())
                .findFirst();
    }

    public Collection<Company> getAllCompanies() {
        return Collections.unmodifiableCollection(companies.values());
    }

    public void markDirty() { setDirty(); }

    // -------------------------------------------------------------------------
    // Server tick — payroll and worker production
    // -------------------------------------------------------------------------

    private static final long TICK_INTERVAL = 20L;

    public void tick(ServerLevel level, VillageSavedData villageData,
                     long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        for (Company company : companies.values()) {
            if (!company.isActive()) continue;

            // Payroll once per in-game day
            List<UUID> unpaid = company.runPayroll(currentTick, villageData);
            if (!unpaid.isEmpty()) {
                notifyOwnerUnpaid(level, company, unpaid);
            }

            // Worker production — if worker has a task and it's work time
            if (company.getWorkSchedule().isWorkTime(currentTick)) {
                tickWorkerProduction(level, company, currentTick, villageData);
            }

        }

        tickBuildingTax(level, villageData, currentTick); // NEW
        setDirty();
    }

    private void tickWorkerProduction(ServerLevel level, Company company,
                                      long currentTick,
                                      VillageSavedData villageData) {
        for (Company.CompanyWorker worker : company.getWorkers()) {
            if (worker.assignedItemId().isEmpty()) continue;
            if (worker.role() != Company.WorkerRole.PRODUCER) continue;

            // Find the NPC entity
            level.getEntitiesOfClass(
                    tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                    new net.minecraft.world.phys.AABB(
                            -30000000, -2048, -30000000,
                            30000000,  2048,  30000000),
                    npc -> npc.getUUID().equals(worker.npcId())
                            && npc.getCompanyId()
                            .map(id -> id.equals(company.getCompanyId()))
                            .orElse(false)
            ).stream().findFirst().ifPresent(npc -> {
                npc.setCurrentActivity("Working for " + company.getName());
                // Production tick is handled by CompanyWorkerGoal on the NPC
            });
        }
    }

    private void notifyOwnerUnpaid(ServerLevel level, Company company,
                                   List<UUID> unpaidIds) {
        level.getServer().getPlayerList().getPlayer(
                company.getOwnerPlayerId());
        var player = level.getServer().getPlayerList()
                .getPlayer(company.getOwnerPlayerId());
        if (player != null) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                                    "[" + company.getName() + "] Insufficient funds "
                                            + "to pay " + unpaidIds.size()
                                            + " worker(s). Add funds to the company treasury.")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    false);
        }
    }

    private void tickBuildingTax(ServerLevel level,
                                 VillageSavedData vdata,
                                 long currentTick) {
        if (currentTick % BUILDING_TAX_INTERVAL != 0) return;

        for (Company company : companies.values()) {
            if (!company.isActive()) return;

            long totalDue = 0L;
            StringBuilder breakdown = new StringBuilder();

            for (UUID bid : company.getBuildingIds()) {
                var building = vdata.getBuildingById(bid).orElse(null);
                if (building == null) continue;

                var village = vdata.getVillageById(company.getHomeVillageId()).orElse(null);
                if (village == null) continue;

                // Reuse HousePurchaseManager's tax formula for consistency
                long tax = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculateWeeklyTax(building, village, vdata);

                // If PROPERTY_RIGHTS law not active, fall back to base rate × footprint
                if (tax == 0L) {
                    int footprint = building.getShape().getWidth()
                            * building.getShape().getLength();
                    long rate = vdata.getPropertyTaxRate(company.getHomeVillageId());
                    tax = footprint * rate;
                }

                totalDue += tax;
                breakdown.append(building.getName())
                        .append(": ").append(tax).append("b, ");
            }

            if (totalDue == 0L) continue;

            if (company.getTreasuryBronze() >= totalDue) {
                company.withdrawBronze(totalDue);
                setDirty();

                // Notify owner if online
                notifyOwner(level, company,
                        net.minecraft.network.chat.Component.literal(
                                        "Building tax of " + totalDue
                                                + "b collected from " + company.getName()
                                                + " treasury.")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                // Treasury can't cover it — warn owner
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

    private void notifyOwner(ServerLevel level, Company company,
                             net.minecraft.network.chat.Component msg) {
        var player = level.getServer().getPlayerList()
                .getPlayer(company.getOwnerPlayerId());
        if (player != null) player.displayClientMessage(msg, false);
    }
}