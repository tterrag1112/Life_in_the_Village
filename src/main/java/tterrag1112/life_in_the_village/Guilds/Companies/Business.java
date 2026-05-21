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
    /**
     * Phase 6.3.3.b.2 — refactored: the (ownerType, ownerId) pair is now
     * a single sealed {@link BusinessOwner}. Codec accepts BOTH layouts:
     * new saves write only {@code "owner"}; legacy saves provide
     * {@code "ownerType" + "ownerId"} which are reconstructed on load.
     */
    public record OwnershipInfo(
            java.util.Optional<BusinessOwner> owner,
            List<UUID> heirs,
            BusinessType businessType,
            SuccessionState successionState,
            long foundedTick,
            long dissolutionWarningTick,
            long undecidedSinceTick
    ) {
        public OwnershipInfo {
            if (owner == null)            owner            = java.util.Optional.empty();
            if (heirs == null)            heirs            = List.of();
            else                          heirs            = List.copyOf(heirs);
            if (businessType == null)     businessType     = BusinessType.STANDARD;
            if (successionState == null) successionState = SuccessionState.ACTIVE;
        }

        public static final OwnershipInfo DEFAULT = new OwnershipInfo(
                java.util.Optional.empty(), List.of(),
                BusinessType.STANDARD, SuccessionState.ACTIVE, 0L, 0L, 0L);

        /**
         * Codec with backward-compat reading of the legacy
         * {@code ownerType} + {@code ownerId} pair. On write, only the
         * new {@code owner} field is emitted (legacy keys absent →
         * future loads won't see them).
         */
        public static final Codec<OwnershipInfo> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        BusinessOwner.CODEC.optionalFieldOf("owner")
                                .forGetter(OwnershipInfo::owner),
                        // Legacy fields: read-only fallback. Getter returns
                        // sentinel so optionalFieldOf drops them on write.
                        OwnerType.CODEC.optionalFieldOf("ownerType", OwnerType.PLAYER)
                                .forGetter(o -> OwnerType.PLAYER),
                        UUIDUtil.CODEC.optionalFieldOf("ownerId")
                                .forGetter(o -> java.util.Optional.empty()),
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
                ).apply(i, (ownerOpt, legacyType, legacyId, heirs,
                            bizType, succ, founded, warn, undec) -> {
                    java.util.Optional<BusinessOwner> resolved = ownerOpt;
                    if (resolved.isEmpty() && legacyId.isPresent()) {
                        // Legacy layout: rebuild a sealed owner from
                        // (ownerType, ownerId).
                        UUID legacyUuid = legacyId.get();
                        resolved = java.util.Optional.of(
                                legacyType == OwnerType.NPC
                                        ? new BusinessOwner.NpcOwner(legacyUuid)
                                        : new BusinessOwner.PlayerOwner(legacyUuid));
                    }
                    return new OwnershipInfo(resolved, heirs, bizType, succ,
                            founded, warn, undec);
                }));
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
        // Phase 6.3.3.b.3 — one-shot migration: legacy saves persisted a
        // BUSINESS_OWNER (formerly company_owner) OfficeHolding that
        // mirrored Business.ownerId. The office state is now derived
        // from this.owner; drop any persisted entry so it can't drift.
        if (c.offices != null) {
            c.offices.remove(
                    tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.BUSINESS_OWNER);
        }
        // Phase 4 doc 26 — apply the ownership sub-record. ownerId
        // falls back to the legacy ownerPlayerId when absent so v1
        // player-only saves keep their owner identity intact.
        OwnershipInfo info = ownership != null ? ownership : OwnershipInfo.DEFAULT;
        // Phase 6.3.3.b.2 — owner is canonical; if absent, fall back to
        // the legacy ownerPlayerId for backward compat.
        if (info.owner().isPresent()) {
            c.owner = info.owner().get();
        } else if (ownerPlayerId != null
                && !ownerPlayerId.equals(new UUID(0L, 0L))) {
            c.owner = new BusinessOwner.PlayerOwner(ownerPlayerId);
        }
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
                java.util.Optional.ofNullable(owner),
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

    // ── Phase 6.3.3.b.2 — sealed ownership ─────────────────────────────
    /**
     * Canonical ownership. Replaces the legacy {@code (ownerType, ownerId)}
     * pair. Initialized in the constructor from {@code ownerPlayerId}
     * (legacy player default); the NPC promotion path overwrites via
     * {@link #setOwnership} / {@link #setNpcOwner}.
     */
    private BusinessOwner owner;
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
        // Phase 6.3.3.b.2 — initial owner is the player from the legacy
        // ownerPlayerId. NPC promotion overwrites via setOwnership /
        // setNpcOwner. Zero-UUID is the sentinel "no owner yet".
        if (ownerPlayerId != null
                && !ownerPlayerId.equals(new UUID(0L, 0L))) {
            this.owner = new BusinessOwner.PlayerOwner(ownerPlayerId);
        }
        this.homeVillageId = homeVillageId;
        this.workSchedule  = schedule;
        this.offices       = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.BUSINESS, this.businessId);
        // Phase 6.3.3.b.3 — office-seed dropped. BUSINESS_OWNER is no
        // longer a persisted office holding; ownership lives canonically
        // in this.owner. Queries that previously walked OfficeState
        // route through Business.isOwner / getOwner instead.
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

    // Phase 6.3.3.b.2 — owner queries derive from the sealed
    // BusinessOwner. Legacy getters (getOwnerType / getOwnerId) keep
    // working for backward compat.
    public BusinessOwner getOwner()              { return owner; }
    public OwnerType getOwnerType() {
        if (owner instanceof BusinessOwner.NpcOwner) return OwnerType.NPC;
        return OwnerType.PLAYER;
    }
    public UUID getOwnerId() {
        return owner != null ? owner.getPrimaryUuid() : null;
    }
    public BusinessType getBusinessType()          { return businessType; }
    public SuccessionState getSuccessionState()  { return successionState; }
    public long getFoundedTick()                 { return foundedTick; }
    public long getDissolutionWarningTick()      { return dissolutionWarningTick; }
    public long getUndecidedSinceTick()          { return undecidedSinceTick; }
    public List<UUID> getHeirs() { return Collections.unmodifiableList(heirs); }

    public boolean isNpcOwned()    { return owner instanceof BusinessOwner.NpcOwner; }
    public boolean isPlayerOwned() { return owner instanceof BusinessOwner.PlayerOwner; }
    public boolean isFamilyOwned() { return owner instanceof BusinessOwner.FamilyOwner; }
    /** True when {@code uuid} matches the canonical owner UUID (irrespective
     *  of owner variant). For FamilyOwner, matches the household id. */
    public boolean isOwner(UUID uuid) {
        return uuid != null && owner != null && uuid.equals(owner.getPrimaryUuid());
    }
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
        setOwnership(new BusinessOwner.NpcOwner(npcId));
    }

    /** Phase 6.3.3.b.2 — convenience wrapper for player ownership. */
    public void setPlayerOwner(UUID playerId) {
        if (playerId == null) return;
        setOwnership(new BusinessOwner.PlayerOwner(playerId));
    }

    /**
     * Phase 6.3.3.b.2 — canonical ownership mutator. Replaces the legacy
     * {@link #setNpcOwner} surface; the legacy method is now a wrapper.
     *
     * <p>Phase 6.3.3.b.3 — office-sync removed. BUSINESS_OWNER is no
     * longer a persisted office holding; the office definition is
     * removed entirely in 6.3.3.b.4. Ownership queries route through
     * {@link #getOwner} / {@link #isOwner}.
     */
    public void setOwnership(BusinessOwner newOwner) {
        if (newOwner == null) return;
        this.owner = newOwner;
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