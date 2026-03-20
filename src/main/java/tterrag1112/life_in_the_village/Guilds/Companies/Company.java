package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

public class Company {

    // -------------------------------------------------------------------------
    // Worker roles
    // -------------------------------------------------------------------------

    public enum WorkerRole {
        PRODUCER,    // crafts or grows items
        SELLER,      // mans a market stall, sells goods
        COURIER;     // moves items between company buildings

        public static final Codec<WorkerRole> CODEC =
                Codec.STRING.xmap(WorkerRole::valueOf, WorkerRole::name);
    }

    public enum ProducerType {
        GENERIC,     // no specific role — hand-produces items
        FARMER,      // requires company farmhouse — follows FarmerGoal pattern
        MINER,       // requires company mine
        BLACKSMITH,  // requires company blacksmith
        CARPENTER,   // requires company carpentry
        LUMBERJACK;  // requires woodcutter

        /** Which BuildingType must the company own for this type to be available */
        public BuildingType requiredBuilding() {
            return switch (this) {
                case FARMER     -> BuildingType.FARMHOUSE;
                case MINER      -> BuildingType.MINE;
                case BLACKSMITH -> BuildingType.BLACKSMITH;
                case CARPENTER  -> BuildingType.CARPENTRY;
                case LUMBERJACK -> BuildingType.WOODCUTTER;
                case GENERIC    -> null; // no building required
            };
        }

        public static final Codec<ProducerType> CODEC =
                Codec.STRING.xmap(ProducerType::valueOf, ProducerType::name);
    }  // requires woodcutter

    // -------------------------------------------------------------------------
    // A single hired worker
    // -------------------------------------------------------------------------

    public record CompanyWorker(
            UUID npcId,
            WorkerRole role,
            ProducerType producerType,   // NEW
            UUID assignedBuildingId,
            long wagePerDay,
            long lastPaidTick,
            String assignedItemId,
            int dailyTargetCount
    ) {
        public static final Codec<CompanyWorker> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("npcId")
                                .forGetter(CompanyWorker::npcId),
                        WorkerRole.CODEC.fieldOf("role")
                                .forGetter(CompanyWorker::role),
                        ProducerType.CODEC.fieldOf("producerType")
                                        .forGetter(CompanyWorker::producerType),
                        UUIDUtil.CODEC.fieldOf("assignedBuildingId")
                                .forGetter(CompanyWorker::assignedBuildingId),
                        Codec.LONG.fieldOf("wagePerDay")
                                .forGetter(CompanyWorker::wagePerDay),
                        Codec.LONG.fieldOf("lastPaidTick")
                                .forGetter(CompanyWorker::lastPaidTick),
                        Codec.STRING.optionalFieldOf("assignedItemId", "")
                                .forGetter(CompanyWorker::assignedItemId),
                        Codec.INT.optionalFieldOf("dailyTargetCount", 8)
                                .forGetter(CompanyWorker::dailyTargetCount)
                ).apply(i, CompanyWorker::new));

        public CompanyWorker withWage(long wage) {
            return new CompanyWorker(npcId, role, producerType, assignedBuildingId,
                    wage, lastPaidTick, assignedItemId, dailyTargetCount);
        }
        public CompanyWorker withTask(String itemId, int count) {
            return new CompanyWorker(npcId, role, producerType, assignedBuildingId,
                    wagePerDay, lastPaidTick, itemId, count);
        }
        public CompanyWorker withLastPaidTick(long tick) {
            return new CompanyWorker(npcId, role, producerType, assignedBuildingId,
                    wagePerDay, tick, assignedItemId, dailyTargetCount);
        }
        public CompanyWorker withProducerType(ProducerType type) {
            return new CompanyWorker(npcId, role, type, assignedBuildingId,
                    wagePerDay, lastPaidTick, assignedItemId, dailyTargetCount);
        }
    }

    // -------------------------------------------------------------------------
    // Work schedule
    // -------------------------------------------------------------------------

    public record WorkSchedule(int startHour, int endHour) {
        public static final WorkSchedule DEFAULT =
                new WorkSchedule(6, 18);

        public static final Codec<WorkSchedule> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.INT.fieldOf("startHour")
                                .forGetter(WorkSchedule::startHour),
                        Codec.INT.fieldOf("endHour")
                                .forGetter(WorkSchedule::endHour)
                ).apply(i, WorkSchedule::new));

        /** Is the given in-game hour within work hours? */
        public boolean isWorkTime(long gameTick) {
            // MC day: 0=dawn(6am), 6000=noon, 12000=dusk(6pm)
            // Hour 0–23 mapped: hour = (tick % 24000) / 1000
            int hour = (int)((gameTick % 24000L) / 1000L);
            return hour >= startHour && hour < endHour;
        }
    }

    // -------------------------------------------------------------------------
    // Sell price override — player sets custom prices per item
    // -------------------------------------------------------------------------

    public record PriceOverride(String itemId, long pricePerUnit) {
        public static final Codec<PriceOverride> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.fieldOf("itemId")
                                .forGetter(PriceOverride::itemId),
                        Codec.LONG.fieldOf("pricePerUnit")
                                .forGetter(PriceOverride::pricePerUnit)
                ).apply(i, PriceOverride::new));
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<Company> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("companyId")
                            .forGetter(Company::getCompanyId),
                    Codec.STRING.fieldOf("name")
                            .forGetter(Company::getName),
                    UUIDUtil.CODEC.fieldOf("ownerPlayerId")
                            .forGetter(Company::getOwnerPlayerId),
                    UUIDUtil.CODEC.fieldOf("homeVillageId")
                            .forGetter(Company::getHomeVillageId),
                    // Buildings this company operates in (may span villages)
                    UUIDUtil.CODEC.listOf()
                            .optionalFieldOf("buildingIds", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.buildingIds)),
                    CompanyWorker.CODEC.listOf()
                            .optionalFieldOf("workers", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.workers.values())),
                    WorkSchedule.CODEC.fieldOf("workSchedule")
                            .forGetter(Company::getWorkSchedule),
                    PriceOverride.CODEC.listOf()
                            .optionalFieldOf("priceOverrides", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.priceOverrides.values())),
                    Codec.LONG.optionalFieldOf("treasuryBronze", 0L)
                            .forGetter(Company::getTreasuryBronze),
                    Codec.BOOL.optionalFieldOf("isActive", true)
                            .forGetter(Company::isActive)
            ).apply(i, Company::fromCodec));

    private static Company fromCodec(UUID companyId, String name,
                                     UUID ownerPlayerId, UUID homeVillageId,
                                     List<UUID> buildingIds, List<CompanyWorker> workers,
                                     WorkSchedule schedule, List<PriceOverride> prices,
                                     long treasury, boolean active) {
        Company c = new Company(companyId, name, ownerPlayerId,
                homeVillageId, schedule);
        c.buildingIds.addAll(buildingIds);
        workers.forEach(w -> c.workers.put(w.npcId(), w));
        prices.forEach(p -> c.priceOverrides.put(p.itemId(), p));
        c.treasuryBronze = treasury;
        c.isActive = active;
        return c;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID companyId;
    private String name;
    private final UUID ownerPlayerId;
    private final UUID homeVillageId;
    private final List<UUID> buildingIds         = new ArrayList<>();
    private final Map<UUID, CompanyWorker> workers = new LinkedHashMap<>();
    private WorkSchedule workSchedule;
    private final Map<String, PriceOverride> priceOverrides = new LinkedHashMap<>();
    private long treasuryBronze = 0L;
    private boolean isActive = true;
    public static final UUID NO_BUILDING = UUID.fromString("00000000-0000-0000-0000-000000000000");


    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public Company(UUID companyId, String name, UUID ownerPlayerId,
                   UUID homeVillageId, WorkSchedule schedule) {
        this.companyId     = companyId;
        this.name          = name;
        this.ownerPlayerId = ownerPlayerId;
        this.homeVillageId = homeVillageId;
        this.workSchedule  = schedule;
    }

    public static Company create(String name, UUID ownerPlayerId,
                                 UUID homeVillageId) {
        return new Company(UUID.randomUUID(), name, ownerPlayerId,
                homeVillageId, WorkSchedule.DEFAULT);
    }

    // -------------------------------------------------------------------------
    // Workers
    // -------------------------------------------------------------------------

    public void addWorker(CompanyWorker worker) {
        workers.put(worker.npcId(), worker);
    }

    public void removeWorker(UUID npcId) {
        workers.remove(npcId);
    }

    public Optional<CompanyWorker> getWorker(UUID npcId) {
        return Optional.ofNullable(workers.get(npcId));
    }

    public Collection<CompanyWorker> getWorkers() {
        return Collections.unmodifiableCollection(workers.values());
    }

    public void updateWorker(CompanyWorker updated) {
        workers.put(updated.npcId(), updated);
    }

    // -------------------------------------------------------------------------
    // Prices
    // -------------------------------------------------------------------------

    public void setPriceOverride(String itemId, long price) {
        priceOverrides.put(itemId,
                new PriceOverride(itemId, Math.max(1, price)));
    }

    public Optional<Long> getPriceOverride(String itemId) {
        return Optional.ofNullable(priceOverrides.get(itemId))
                .map(PriceOverride::pricePerUnit);
    }

    public Collection<PriceOverride> getAllPriceOverrides() {
        return Collections.unmodifiableCollection(priceOverrides.values());
    }

    // -------------------------------------------------------------------------
    // Buildings
    // -------------------------------------------------------------------------

    public void addBuilding(UUID buildingId) {
        if (!buildingIds.contains(buildingId))
            buildingIds.add(buildingId);
    }

    public List<UUID> getBuildingIds() {
        return Collections.unmodifiableList(buildingIds);
    }

    public boolean hasAssignedBuilding(CompanyWorker worker) {
        return !worker.assignedBuildingId().equals(NO_BUILDING);
    }

    // -------------------------------------------------------------------------
    // Wage enforcement
    // -------------------------------------------------------------------------

    /**
     * Returns the effective minimum wage per day for this company,
     * accounting for active kingdom laws.
     * MINIMUM_WAGE law enforces 8 bronze/day floor.
     */
    public long getEffectiveMinWage(
            tterrag1112.life_in_the_village.Networking.VillageSavedData vdata) {
        boolean lawActive = vdata.getKingdomForVillage(homeVillageId)
                .map(k -> k.hasLaw(
                        tterrag1112.life_in_the_village.Kingdom.KingdomLaw.MINIMUM_WAGE))
                .orElse(false);
        return lawActive ? 8L : 1L;
    }

    // -------------------------------------------------------------------------
    // Payroll — called on server tick
    // -------------------------------------------------------------------------

    private static final long PAY_INTERVAL = 24000L; // once per in-game day

    /**
     * Pays all workers from the company treasury.
     * Called by CompanySavedData.tick().
     * Returns list of NPC IDs that could not be paid (insufficient funds).
     */
    public List<UUID> runPayroll(long currentTick,
                                 tterrag1112.life_in_the_village.Networking.VillageSavedData vdata) {
        List<UUID> unpaid = new ArrayList<>();
        long minWage = getEffectiveMinWage(vdata);

        for (CompanyWorker worker : new ArrayList<>(workers.values())) {
            if (currentTick - worker.lastPaidTick() < PAY_INTERVAL) continue;
            long wage = Math.max(worker.wagePerDay(), minWage);
            if (treasuryBronze < wage) {
                unpaid.add(worker.npcId());
            } else {
                treasuryBronze -= wage;
                workers.put(worker.npcId(), worker.withLastPaidTick(currentTick));
            }
        }
        return unpaid;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getCompanyId()          { return companyId; }
    public String getName()             { return name; }
    public void setName(String n)       { this.name = n; }
    public UUID getOwnerPlayerId()      { return ownerPlayerId; }
    public UUID getHomeVillageId()      { return homeVillageId; }
    public WorkSchedule getWorkSchedule() { return workSchedule; }
    public void setWorkSchedule(WorkSchedule s) { this.workSchedule = s; }
    public long getTreasuryBronze()     { return treasuryBronze; }
    public void depositBronze(long amt) { treasuryBronze += amt; }
    public boolean isActive()           { return isActive; }
    public void setActive(boolean b)    { this.isActive = b; }


}