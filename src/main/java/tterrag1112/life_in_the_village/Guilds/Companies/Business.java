package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

public class Business {

    // -------------------------------------------------------------------------
    // Worker roles
    // -------------------------------------------------------------------------

    public enum WorkerRole {
        PRODUCER,           // crafts or grows items
        SELLER,             // mans a market stall, sells goods
        COURIER,            // moves items between business buildings
        CARAVAN_ATTENDANT;  // travels with trading-business caravans (Phase 4 doc 26)

        public static final Codec<WorkerRole> CODEC =
                Codec.STRING.xmap(WorkerRole::valueOf, WorkerRole::name);
    }

    /**
     * Phase 4 doc 26 — distinguishes a player-owned business from an
     * NPC-owned one. Existing player-only saves migrate to PLAYER at
     * load time via the codec's optionalFieldOf default.
     */
    public enum OwnerType {
        PLAYER,
        NPC;

        public static final Codec<OwnerType> CODEC =
                Codec.STRING.xmap(OwnerType::valueOf, OwnerType::name);
    }

    /**
     * Phase 4 doc 26 — trading businesses unlock long-haul caravans
     * (3x village-merchant range), CARAVAN_ATTENDANT hires, and
     * inter-village request-board posts.
     */
    public enum BusinessType {
        STANDARD,
        TRADING_COMPANY;

        public static final Codec<BusinessType> CODEC =
                Codec.STRING.xmap(BusinessType::valueOf, BusinessType::name);
    }

    /**
     * Phase 4 doc 26 — owner-succession state machine. Most businesses
     * sit in {@link #ACTIVE}; on owner death without an heir or with
     * a profession-loss demotion, they transition to {@link #UNDECIDED}
     * for 30 days while family members can claim. Failure to resolve
     * dissolves the business.
     */
    public enum SuccessionState {
        ACTIVE,
        UNDECIDED,
        DISSOLVED;

        public static final Codec<SuccessionState> CODEC =
                Codec.STRING.xmap(SuccessionState::valueOf, SuccessionState::name);
    }

    /**
     * Phase 4 doc 26 — packs the eight ownership / type / succession
     * fields into a single sub-record so the main {@link Business}
     * codec stays under DFU's 16-field {@code RecordCodecBuilder} cap.
     * The previous flat layout pushed the codec to 19 fields and the
     * lambda type inference fell back to {@code Object}, breaking
     * every getter on the inner builder.
     *
     * <p>Backward-compat: v1 saves without an {@code ownership} entry
     * read {@link #DEFAULT}; {@link #ownerId} stays {@code Optional.empty()}
     * and the {@code Business.fromCodec} path falls back to
     * {@code ownerPlayerId} so existing player owners keep their
     * identity.</p>
     */
    public record OwnershipInfo(
            OwnerType ownerType,
            java.util.Optional<UUID> ownerId,
            List<UUID> heirs,
            BusinessType businessType,
            SuccessionState successionState,
            long foundedTick,
            long dissolutionWarningTick,
            long undecidedSinceTick
    ) {
        public OwnershipInfo {
            if (ownerType == null) ownerType = OwnerType.PLAYER;
            if (ownerId == null)   ownerId   = java.util.Optional.empty();
            if (heirs == null)     heirs     = List.of();
            else                   heirs     = List.copyOf(heirs);
            if (businessType == null)     businessType     = BusinessType.STANDARD;
            if (successionState == null) successionState = SuccessionState.ACTIVE;
        }

        public static final OwnershipInfo DEFAULT = new OwnershipInfo(
                OwnerType.PLAYER, java.util.Optional.empty(), List.of(),
                BusinessType.STANDARD, SuccessionState.ACTIVE, 0L, 0L, 0L);

        public static final Codec<OwnershipInfo> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        OwnerType.CODEC.optionalFieldOf("ownerType", OwnerType.PLAYER)
                                .forGetter(OwnershipInfo::ownerType),
                        UUIDUtil.CODEC.optionalFieldOf("ownerId")
                                .forGetter(OwnershipInfo::ownerId),
                        UUIDUtil.CODEC.listOf().optionalFieldOf("heirs", List.of())
                                .forGetter(OwnershipInfo::heirs),
                        BusinessType.CODEC.optionalFieldOf("businessType", BusinessType.STANDARD)
                                .forGetter(OwnershipInfo::businessType),
                        SuccessionState.CODEC.optionalFieldOf("successionState", SuccessionState.ACTIVE)
                                .forGetter(OwnershipInfo::successionState),
                        Codec.LONG.optionalFieldOf("foundedTick", 0L)
                                .forGetter(OwnershipInfo::foundedTick),
                        Codec.LONG.optionalFieldOf("dissolutionWarningTick", 0L)
                                .forGetter(OwnershipInfo::dissolutionWarningTick),
                        Codec.LONG.optionalFieldOf("undecidedSinceTick", 0L)
                                .forGetter(OwnershipInfo::undecidedSinceTick)
                ).apply(i, OwnershipInfo::new));
    }

    public enum ProducerType {
        GENERIC,     // no specific role — hand-produces items
        FARMER,      // requires business farmhouse — follows FarmerGoal pattern
        MINER,       // requires business mine
        BLACKSMITH,  // requires business blacksmith
        CARPENTER,   // requires business carpentry
        LUMBERJACK;  // requires woodcutter

        /** Which BuildingType must the business own for this type to be available */
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

    public record BusinessWorker(
            UUID npcId,
            WorkerRole role,
            ProducerType producerType,   // NEW
            UUID assignedBuildingId,
            long wagePerDay,
            long lastPaidTick,
            String assignedItemId,
            int dailyTargetCount
    ) {
        public static final Codec<BusinessWorker> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("npcId")
                                .forGetter(BusinessWorker::npcId),
                        WorkerRole.CODEC.fieldOf("role")
                                .forGetter(BusinessWorker::role),
                        ProducerType.CODEC.fieldOf("producerType")
                                        .forGetter(BusinessWorker::producerType),
                        UUIDUtil.CODEC.fieldOf("assignedBuildingId")
                                .forGetter(BusinessWorker::assignedBuildingId),
                        Codec.LONG.fieldOf("wagePerDay")
                                .forGetter(BusinessWorker::wagePerDay),
                        Codec.LONG.fieldOf("lastPaidTick")
                                .forGetter(BusinessWorker::lastPaidTick),
                        Codec.STRING.optionalFieldOf("assignedItemId", "")
                                .forGetter(BusinessWorker::assignedItemId),
                        Codec.INT.optionalFieldOf("dailyTargetCount", 8)
                                .forGetter(BusinessWorker::dailyTargetCount)
                ).apply(i, BusinessWorker::new));

        public BusinessWorker withWage(long wage) {
            return new BusinessWorker(npcId, role, producerType, assignedBuildingId,
                    wage, lastPaidTick, assignedItemId, dailyTargetCount);
        }
        public BusinessWorker withTask(String itemId, int count) {
            return new BusinessWorker(npcId, role, producerType, assignedBuildingId,
                    wagePerDay, lastPaidTick, itemId, count);
        }
        public BusinessWorker withLastPaidTick(long tick) {
            return new BusinessWorker(npcId, role, producerType, assignedBuildingId,
                    wagePerDay, tick, assignedItemId, dailyTargetCount);
        }
        public BusinessWorker withProducerType(ProducerType type) {
            return new BusinessWorker(npcId, role, type, assignedBuildingId,
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
            int hour = (int)(((gameTick % 24000L) / 1000L) + 6) % 24;
            if (startHour < endHour) {
                return hour >= startHour && hour < endHour;
            } else {
                // Overnight schedule (e.g. 22–6)
                return hour >= startHour || hour < endHour;
            }
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

    public static final Codec<Business> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("businessId")
                            .forGetter(Business::getBusinessId),
                    Codec.STRING.fieldOf("name")
                            .forGetter(Business::getName),
                    UUIDUtil.CODEC.fieldOf("ownerPlayerId")
                            .forGetter(Business::getOwnerPlayerId),
                    UUIDUtil.CODEC.fieldOf("homeVillageId")
                            .forGetter(Business::getHomeVillageId),
                    UUIDUtil.CODEC.listOf()
                            .optionalFieldOf("buildingIds", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.buildingIds)),
                    BusinessWorker.CODEC.listOf()
                            .optionalFieldOf("workers", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.workers.values())),
                    WorkSchedule.CODEC.fieldOf("workSchedule")
                            .forGetter(Business::getWorkSchedule),
                    PriceOverride.CODEC.listOf()
                            .optionalFieldOf("priceOverrides", new ArrayList<>())
                            .forGetter(c -> new ArrayList<>(c.priceOverrides.values())),
                    Codec.LONG.optionalFieldOf("treasuryBronze", 0L)
                            .forGetter(Business::getTreasuryBronze),
                    Codec.BOOL.optionalFieldOf("isActive", true)
                            .forGetter(Business::isActive),
                    tterrag1112.life_in_the_village.Npc.Office.OfficeState.CODEC
                            .optionalFieldOf("offices")
                            .forGetter(c -> java.util.Optional.ofNullable(c.offices)),
                    // ── Phase 4 doc 26 additions ─────────────────────────
                    // Packed into a single sub-record to keep the main
                    // codec under DFU's 16-field RecordCodecBuilder cap.
                    // v1 saves with no "ownership" entry read DEFAULT
                    // and the fromCodec fallback resolves ownerId to
                    // ownerPlayerId for backward compat.
                    OwnershipInfo.CODEC.optionalFieldOf("ownership", OwnershipInfo.DEFAULT)
                            .forGetter(Business::snapshotOwnership)
            ).apply(i, Business::fromCodec));

    private static Business fromCodec(UUID businessId, String name,
                                     UUID ownerPlayerId, UUID homeVillageId,
                                     List<UUID> buildingIds, List<BusinessWorker> workers,
                                     WorkSchedule schedule, List<PriceOverride> prices,
                                     long treasury, boolean active,
                                     java.util.Optional<tterrag1112.life_in_the_village.Npc.Office.OfficeState> offices,
                                     OwnershipInfo ownership) {
        Business c = new Business(businessId, name, ownerPlayerId,
                homeVillageId, schedule);
        c.buildingIds.addAll(buildingIds);
        workers.forEach(w -> c.workers.put(w.npcId(), w));
        prices.forEach(p -> c.priceOverrides.put(p.itemId(), p));
        c.treasuryBronze = treasury;
        c.isActive = active;
        offices.ifPresent(s -> c.offices = s);
        // Phase 4 doc 26 — apply the ownership sub-record. ownerId
        // falls back to the legacy ownerPlayerId when absent so v1
        // player-only saves keep their owner identity intact.
        OwnershipInfo info = ownership != null ? ownership : OwnershipInfo.DEFAULT;
        c.ownerType       = info.ownerType();
        c.ownerId         = info.ownerId().orElse(ownerPlayerId);
        c.heirs.addAll(info.heirs());
        c.businessType     = info.businessType();
        c.successionState = info.successionState();
        c.foundedTick            = info.foundedTick();
        c.dissolutionWarningTick = info.dissolutionWarningTick();
        c.undecidedSinceTick     = info.undecidedSinceTick();
        return c;
    }

    /** Builds an {@link OwnershipInfo} reflecting the current Business
     *  state for the codec write path. */
    private OwnershipInfo snapshotOwnership() {
        return new OwnershipInfo(
                ownerType,
                java.util.Optional.ofNullable(ownerId),
                List.copyOf(heirs),
                businessType,
                successionState,
                foundedTick,
                dissolutionWarningTick,
                undecidedSinceTick);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID businessId;
    private String name;
    private final UUID ownerPlayerId;
    private final UUID homeVillageId;
    private final List<UUID> buildingIds         = new ArrayList<>();
    private final Map<UUID, BusinessWorker> workers = new LinkedHashMap<>();
    private WorkSchedule workSchedule;
    private final Map<String, PriceOverride> priceOverrides = new LinkedHashMap<>();
    private long treasuryBronze = 0L;
    private boolean isActive = true;

    // ── Phase 4 doc 26 — ownership extension ─────────────────────────────
    /**
     * Whether the {@link #ownerId} field refers to a player or an NPC.
     * Existing saves migrate to PLAYER on load (codec default).
     */
    private OwnerType ownerType = OwnerType.PLAYER;
    /**
     * UUID of the actual owner — player or NPC. Decoupled from
     * {@link #ownerPlayerId} so NPC-owned businesses can flag
     * the player getter as the sentinel zero-UUID without
     * dropping the legacy save key.
     */
    private UUID ownerId;
    /** Ordered succession chain. Defaults to oldest-adult-child of the
     *  owner; spec line 132. Empty for player-owned businesses. */
    private final List<UUID> heirs = new ArrayList<>();
    private BusinessType businessType = BusinessType.STANDARD;
    private SuccessionState successionState = SuccessionState.ACTIVE;
    /** When the business was founded — used for "founder of X" history
     *  lines and to gate certain spec-line-256 edge cases. */
    private long foundedTick = 0L;
    /** Tick at which the bankruptcy warning first fired. 0 means no
     *  warning active. Spec "Open decisions" — 14 days below 50 br
     *  warns; 30 more days dissolves. */
    private long dissolutionWarningTick = 0L;
    /** Tick at which the business entered UNDECIDED. 0 when ACTIVE. */
    private long undecidedSinceTick = 0L;
    /**
     * Office state for this business. Phase 0 storage only — see
     * {@code docs/npc_redesign/06-office-framework.md}. Stays in sync with
     * {@link #ownerPlayerId} during the migration window; Phase 3 cuts over.
     */
    private tterrag1112.life_in_the_village.Npc.Office.OfficeState offices;
    public static final UUID NO_BUILDING = UUID.fromString("00000000-0000-0000-0000-000000000000");


    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public Business(UUID businessId, String name, UUID ownerPlayerId,
                   UUID homeVillageId, WorkSchedule schedule) {
        this.businessId     = businessId;
        this.name          = name;
        this.ownerPlayerId = ownerPlayerId;
        // Default ownership is PLAYER with ownerId = ownerPlayerId.
        // The NPC promotion path overwrites both via setNpcOwner().
        this.ownerId       = ownerPlayerId;
        this.homeVillageId = homeVillageId;
        this.workSchedule  = schedule;
        this.offices       = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.BUSINESS, this.businessId);
        // Seed company_owner with the player owner (businesses are
        // owner-by-investment per spec).
        if (ownerPlayerId != null
                && !ownerPlayerId.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
            this.offices.set(
                    tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.BUSINESS_OWNER,
                    tterrag1112.life_in_the_village.Npc.Office.OfficeHolding.heldByPlayer(
                            tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.BUSINESS_OWNER,
                            this.businessId, ownerPlayerId, 0L, 0L,
                            tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.HEREDITARY));
        }
    }

    /** Office state for this business; never {@code null} after construction. */
    public tterrag1112.life_in_the_village.Npc.Office.OfficeState getOffices() {
        return offices;
    }

    public static Business create(String name, UUID ownerPlayerId,
                                 UUID homeVillageId) {
        return new Business(UUID.randomUUID(), name, ownerPlayerId,
                homeVillageId, WorkSchedule.DEFAULT);
    }

    // -------------------------------------------------------------------------
    // Workers
    // -------------------------------------------------------------------------

    public void addWorker(BusinessWorker worker) {
        workers.put(worker.npcId(), worker);
    }

    public void removeWorker(UUID npcId) {
        workers.remove(npcId);
    }

    public Optional<BusinessWorker> getWorker(UUID npcId) {
        return Optional.ofNullable(workers.get(npcId));
    }

    public Collection<BusinessWorker> getWorkers() {
        return Collections.unmodifiableCollection(workers.values());
    }

    public void updateWorker(BusinessWorker updated) {
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

    public boolean hasAssignedBuilding(BusinessWorker worker) {
        return !worker.assignedBuildingId().equals(NO_BUILDING);
    }

    // -------------------------------------------------------------------------
    // Wage enforcement
    // -------------------------------------------------------------------------

    /**
     * Returns the effective minimum wage per day for this business,
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
     * Pays all workers from the business treasury.
     * Called by BusinessSavedData.tick().
     * Returns list of NPC IDs that could not be paid (insufficient funds).
     */
    public List<UUID> runPayroll(long currentTick,
                                 tterrag1112.life_in_the_village.Networking.VillageSavedData vdata) {
        List<UUID> unpaid = new ArrayList<>();
        long minWage = getEffectiveMinWage(vdata);

        for (BusinessWorker worker : new ArrayList<>(workers.values())) {
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

    public boolean withdrawBronze(long amount) {
        if (treasuryBronze < amount) return false;
        treasuryBronze -= amount;
        return true;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getBusinessId()          { return businessId; }
    public String getName()             { return name; }
    public void setName(String n)       { this.name = n; }
    public UUID getOwnerPlayerId()      { return ownerPlayerId; }
    public UUID getHomeVillageId()      { return homeVillageId; }
    public WorkSchedule getWorkSchedule() { return workSchedule; }
    public void setWorkSchedule(WorkSchedule s) { this.workSchedule = s; }
    public long getTreasuryBronze()     { return treasuryBronze; }
    public void withdrawFromTreasury(long amount) {
        treasuryBronze = Math.max(0L, treasuryBronze - amount);
    }
    public void depositBronze(long amt) { treasuryBronze += amt; }
    public boolean isActive()           { return isActive; }
    public void setActive(boolean b)    { this.isActive = b; }

    public void removePriceOverride(String itemId) {
        priceOverrides.remove(itemId);
    }

    // -------------------------------------------------------------------------
    // Phase 4 doc 26 — NPC ownership / business type / succession
    // -------------------------------------------------------------------------

    public OwnerType getOwnerType()              { return ownerType; }
    public UUID      getOwnerId()                { return ownerId; }
    public BusinessType getBusinessType()          { return businessType; }
    public SuccessionState getSuccessionState()  { return successionState; }
    public long getFoundedTick()                 { return foundedTick; }
    public long getDissolutionWarningTick()      { return dissolutionWarningTick; }
    public long getUndecidedSinceTick()          { return undecidedSinceTick; }
    public List<UUID> getHeirs() { return Collections.unmodifiableList(heirs); }

    public boolean isNpcOwned()    { return ownerType == OwnerType.NPC; }
    public boolean isPlayerOwned() { return ownerType == OwnerType.PLAYER; }
    public boolean isTradingBusiness() { return businessType == BusinessType.TRADING_COMPANY; }

    public void setBusinessType(BusinessType type) { if (type != null) this.businessType = type; }
    public void setSuccessionState(SuccessionState state) {
        if (state != null) this.successionState = state;
    }
    public void setDissolutionWarningTick(long tick) { this.dissolutionWarningTick = tick; }
    public void setUndecidedSinceTick(long tick)     { this.undecidedSinceTick = tick; }
    public void setFoundedTick(long tick)            { this.foundedTick = tick; }

    /**
     * Promotes the business to NPC ownership. Called by the
     * merchant-promotion path; overwrites {@link #ownerId} with the
     * NPC UUID and replaces the company_owner office entry. The
     * {@link #ownerPlayerId} legacy field stays at its prior value
     * (typically the zero-UUID) so existing player-only consumers
     * read it as "no player owner".
     */
    public void setNpcOwner(UUID npcId) {
        if (npcId == null) return;
        this.ownerType = OwnerType.NPC;
        this.ownerId   = npcId;
        // Replace the company_owner office holding (legacy seed put a
        // player-held entry; npc-held wins).
        this.offices.set(
                tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.BUSINESS_OWNER,
                tterrag1112.life_in_the_village.Npc.Office.OfficeHolding.heldByNpc(
                        tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.BUSINESS_OWNER,
                        this.businessId, npcId, 0L, 0L,
                        tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.HEREDITARY));
    }

    public void addHeir(UUID heirId) {
        if (heirId == null || heirs.contains(heirId)) return;
        heirs.add(heirId);
    }

    public void removeHeir(UUID heirId) { heirs.remove(heirId); }

    public void setHeirs(List<UUID> newHeirs) {
        heirs.clear();
        if (newHeirs != null) heirs.addAll(newHeirs);
    }
}