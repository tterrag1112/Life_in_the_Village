// src/main/java/tterrag1112/life_in_the_village/Networking/VillageSavedData.java
package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.ExpansionRequest;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Buildings.PlayerHousingData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.JobPosting;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class VillageSavedData extends SavedData {

    // =========================================================================
    // Embedded codec records
    //
    // Seven top-level fields — well under DFU's 16-field group() limit and
    // with room for future expansion. Each record bundles thematically
    // related lists so the structure of the saved file mirrors the logical
    // structure of the mod.
    //
    //  structureData   — buildings, villages, farm plots
    //  workData        — job postings, expansion requests, village paths
    //  governanceData  — kingdoms, events, guilds
    //  socialData      — households, reputations, crafting orders  (new)
    //  housingData     — rented rooms, rented beds
    //  economyData     — trade routes, trade roads
    //  propertyData    — player properties, tax rates
    // =========================================================================

    // ── 1. Structure ──────────────────────────────────────────────────────────

    public record VillageStructureData(
            List<Building> buildings,
            List<Village>  villages,
            List<FarmPlot> farmPlots
    ) {
        public static final Codec<VillageStructureData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Building.CODEC.listOf()
                                .optionalFieldOf("buildings", new ArrayList<>())
                                .forGetter(VillageStructureData::buildings),
                        Village.CODEC.listOf()
                                .optionalFieldOf("villages", new ArrayList<>())
                                .forGetter(VillageStructureData::villages),
                        FarmPlot.CODEC.listOf()
                                .optionalFieldOf("farmPlots", new ArrayList<>())
                                .forGetter(VillageStructureData::farmPlots)
                ).apply(i, VillageStructureData::new));
    }

    // ── 2. Work ───────────────────────────────────────────────────────────────

    public record VillageWorkData(
            List<JobPosting>       jobPostings,
            List<ExpansionRequest> expansionRequests,
            List<VillagePath>      villagePaths
    ) {
        public static final Codec<VillageWorkData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        JobPosting.CODEC.listOf()
                                .optionalFieldOf("jobPostings", new ArrayList<>())
                                .forGetter(VillageWorkData::jobPostings),
                        ExpansionRequest.CODEC.listOf()
                                .optionalFieldOf("expansionRequests", new ArrayList<>())
                                .forGetter(VillageWorkData::expansionRequests),
                        VillagePath.CODEC.listOf()
                                .optionalFieldOf("villagePaths", new ArrayList<>())
                                .forGetter(VillageWorkData::villagePaths)
                ).apply(i, VillageWorkData::new));
    }

    // ── 3. Governance ─────────────────────────────────────────────────────────

    public record VillageGovernanceData(
            List<Kingdom>      kingdoms,
            List<VillageEvent> events,
            List<GuildData>    guilds,
            List<VillageSimData> simSnapshots
    ) {
        public static final Codec<VillageGovernanceData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Kingdom.CODEC.listOf()
                                .optionalFieldOf("kingdoms", new ArrayList<>())
                                .forGetter(VillageGovernanceData::kingdoms),
                        VillageEvent.CODEC.listOf()
                                .optionalFieldOf("events", new ArrayList<>())
                                .forGetter(VillageGovernanceData::events),
                        GuildData.CODEC.listOf()
                                .optionalFieldOf("guilds", new ArrayList<>())
                                .forGetter(VillageGovernanceData::guilds),
                        VillageSimData.CODEC.listOf().optionalFieldOf("simSnapshots", new ArrayList<>())
                                .forGetter(VillageGovernanceData::simSnapshots)
                ).apply(i, VillageGovernanceData::new));
    }

    // ── 4. Social (new) ───────────────────────────────────────────────────────

    public record VillageSocialData(
            List<HouseholdData>     households,
            List<VillageReputation> reputations,
            List<CraftingOrder>     craftingOrders
    ) {
        public static final Codec<VillageSocialData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        HouseholdData.CODEC.listOf()
                                .optionalFieldOf("households", new ArrayList<>())
                                .forGetter(VillageSocialData::households),
                        VillageReputation.CODEC.listOf()
                                .optionalFieldOf("reputations", new ArrayList<>())
                                .forGetter(VillageSocialData::reputations),
                        CraftingOrder.CODEC.listOf()
                                .optionalFieldOf("craftingOrders", new ArrayList<>())
                                .forGetter(VillageSocialData::craftingOrders)
                ).apply(i, VillageSocialData::new));
    }

    // ── 5. Housing ────────────────────────────────────────────────────────────

    public record VillageHousingData(
            Map<UUID, Long>     rentedRooms,
            Map<UUID, BlockPos> rentedBeds
    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<VillageHousingData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.unboundedMap(UUID_STRING, Codec.LONG)
                                .optionalFieldOf("rentedRooms", new HashMap<>())
                                .forGetter(VillageHousingData::rentedRooms),
                        Codec.unboundedMap(UUID_STRING, BlockPos.CODEC)
                                .optionalFieldOf("rentedBeds", new HashMap<>())
                                .forGetter(VillageHousingData::rentedBeds)
                ).apply(i, VillageHousingData::new));
    }

    // ── 6. Economy ────────────────────────────────────────────────────────────

    public record VillageEconomyData(
            List<TradeRoute> tradeRoutes,
            List<TradeRoad>  tradeRoads
    ) {
        public static final Codec<VillageEconomyData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        TradeRoute.CODEC.listOf()
                                .optionalFieldOf("tradeRoutes", List.of())
                                .forGetter(VillageEconomyData::tradeRoutes),
                        TradeRoad.CODEC.listOf()
                                .optionalFieldOf("tradeRoads", List.of())
                                .forGetter(VillageEconomyData::tradeRoads)
                ).apply(i, VillageEconomyData::new));
    }

    // ── 7. Property ───────────────────────────────────────────────────────────

    public record VillagePropertyData(
            List<PlayerHousingData.PlayerProperty> properties,
            Map<UUID, Long>                        propertyTaxRates
    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<VillagePropertyData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        PlayerHousingData.PlayerProperty.CODEC.listOf()
                                .optionalFieldOf("properties", List.of())
                                .forGetter(VillagePropertyData::properties),
                        Codec.unboundedMap(UUID_STRING, Codec.LONG)
                                .optionalFieldOf("propertyTaxRates", new HashMap<>())
                                .forGetter(VillagePropertyData::propertyTaxRates)
                ).apply(i, VillagePropertyData::new));
    }

    // =========================================================================
    // Top-level codec — 7 fields
    // =========================================================================

    public static final Codec<VillageSavedData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    VillageStructureData.CODEC
                            .fieldOf("structureData")
                            .forGetter(d -> new VillageStructureData(
                                    d.buildings, d.villages, d.farmPlots)),
                    VillageWorkData.CODEC
                            .fieldOf("workData")
                            .forGetter(d -> new VillageWorkData(
                                    d.jobPostings, d.expansionRequests, d.villagePaths)),
                    VillageGovernanceData.CODEC
                            .fieldOf("governanceData")
                            .forGetter(d -> new VillageGovernanceData(
                                    d.kingdoms, d.events, d.guilds, new ArrayList<>(d.simData.values()))),
                    VillageSocialData.CODEC
                            .fieldOf("socialData")
                            .forGetter(d -> new VillageSocialData(
                                    new ArrayList<>(d.households.values()),
                                    new ArrayList<>(d.reputations.values()),
                                    d.craftingOrders)),
                    VillageHousingData.CODEC
                            .fieldOf("housingData")
                            .forGetter(d -> new VillageHousingData(
                                    d.rentedRooms, d.rentedBeds)),
                    VillageEconomyData.CODEC
                            .fieldOf("economyData")
                            .forGetter(d -> new VillageEconomyData(
                                    new ArrayList<>(d.tradeRoutes.values()),
                                    new ArrayList<>(d.tradeRoads.values()))),
                    VillagePropertyData.CODEC
                            .fieldOf("propertyData")
                            .forGetter(d -> new VillagePropertyData(
                                    new ArrayList<>(d.playerProperties),
                                    new HashMap<>(d.propertyTaxRates)))
            ).apply(instance, VillageSavedData::fromCodec));

    // =========================================================================
    // fromCodec
    // =========================================================================

    public static VillageSavedData fromCodec(
            VillageStructureData  structureData,
            VillageWorkData       workData,
            VillageGovernanceData governanceData,
            VillageSocialData     socialData,
            VillageHousingData    housingData,
            VillageEconomyData    economyData,
            VillagePropertyData   propertyData) {

        VillageSavedData data = new VillageSavedData();

        // Structure
        data.buildings.addAll(structureData.buildings());
        data.villages.addAll(structureData.villages());
        data.farmPlots.addAll(structureData.farmPlots());

        // Work
        data.jobPostings.addAll(workData.jobPostings());
        data.expansionRequests.addAll(workData.expansionRequests());
        data.villagePaths.addAll(workData.villagePaths());

        // Governance
        data.kingdoms.addAll(governanceData.kingdoms());
        data.events.addAll(governanceData.events());
        data.guilds.addAll(governanceData.guilds());
        governanceData.simSnapshots().forEach(s -> data.simData.put(s.getVillageId(), s));

        // Social
        socialData.households().forEach(h ->
                data.households.put(h.getBuildingId(), h));
        socialData.reputations().forEach(r ->
                data.reputations.put(repKey(r.getPlayerId(), r.getVillageId()), r));
        data.craftingOrders.addAll(socialData.craftingOrders());

        // Housing
        data.rentedRooms.putAll(housingData.rentedRooms());
        data.rentedBeds.putAll(housingData.rentedBeds());

        // Economy
        economyData.tradeRoutes().forEach(r -> data.tradeRoutes.put(r.getRouteId(), r));
        economyData.tradeRoads().forEach(r  -> data.tradeRoads.put(r.getRoadId(), r));

        // Property
        propertyData.properties().forEach(data.playerProperties::add);
        data.propertyTaxRates.putAll(propertyData.propertyTaxRates());

        // Rebuild indices
        data.buildings.forEach(b -> data.buildingIndex.put(b.getId(), b));
        data.villages.forEach(v  -> data.villageIndex.put(v.getId(), v));
        data.kingdoms.forEach(k  -> data.kingdomIndex.put(k.getId(), k));
        data.farmPlots.forEach(p -> data.farmPlotIndex.put(p.getId(), p));

        return data;
    }

    // =========================================================================
    // SavedDataType
    // =========================================================================

    public static final SavedDataType<VillageSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_buildings",
            VillageSavedData::new,
            CODEC
    );

    // =========================================================================
    // Fields
    // =========================================================================

    // Structure
    private final List<Building>          buildings         = new ArrayList<>();
    private final List<Village>           villages          = new ArrayList<>();
    private final List<FarmPlot>          farmPlots         = new ArrayList<>();

    // Work
    private final List<JobPosting>        jobPostings       = new ArrayList<>();
    private final List<ExpansionRequest>  expansionRequests = new ArrayList<>();
    private final List<VillagePath>       villagePaths      = new ArrayList<>();

    // Governance
    private final List<Kingdom>           kingdoms          = new ArrayList<>();
    private final Map<UUID, VillageSimData> simData = new LinkedHashMap<>();
    private final List<VillageEvent>      events            = new ArrayList<>();
    private final List<GuildData>         guilds            = new ArrayList<>();

    // Social (new)
    private final Map<UUID, HouseholdData>       households     = new LinkedHashMap<>();
    private final Map<String, VillageReputation> reputations    = new HashMap<>();
    private final List<CraftingOrder>            craftingOrders = new ArrayList<>();

    // Housing
    private final Map<UUID, Long>      rentedRooms = new HashMap<>();
    private final Map<UUID, BlockPos>  rentedBeds  = new HashMap<>();

    // Economy
    private final Map<UUID, TradeRoute> tradeRoutes = new HashMap<>();
    private final Map<UUID, TradeRoad>  tradeRoads  = new HashMap<>();

    // Property
    private final List<PlayerHousingData.PlayerProperty> playerProperties = new ArrayList<>();
    private final Map<UUID, Long>                        propertyTaxRates = new HashMap<>();

    // Warnings (runtime only — not persisted; rebuilt from player events)
    private final Map<UUID, Map<UUID, Long>> playerWarnings = new HashMap<>();

    // Indices
    private final Map<UUID, Building>  buildingIndex = new HashMap<>();
    private final Map<UUID, Village>   villageIndex  = new HashMap<>();
    private final Map<UUID, Kingdom>   kingdomIndex  = new HashMap<>();
    private final Map<UUID, FarmPlot>  farmPlotIndex = new HashMap<>();

    // =========================================================================
    // Constructor & accessor
    // =========================================================================

    public VillageSavedData() {}

    public static VillageSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Exposes setDirty() to external systems (e.g. ReputationManager). */
    public void markDirty() { setDirty(); }

    // =========================================================================
    // Buildings
    // =========================================================================

    public void addBuilding(Building building) {
        buildings.add(building);
        buildingIndex.put(building.getId(), building);
        setDirty();
    }

    public void removeBuilding(Building building) {
        buildings.remove(building);
        buildingIndex.remove(building.getId());
        setDirty();
    }

    public List<Building>            getAllBuildings()              { return List.copyOf(buildings); }
    public Optional<Building>        getBuildingById(UUID id)       { return Optional.ofNullable(buildingIndex.get(id)); }

    public Optional<Building> getBuildingAt(BlockPos pos) {
        return buildings.stream().filter(b -> b.getShape().contains(pos)).findFirst();
    }

    public Optional<Building> getBuildingByName(String name) {
        return buildings.stream().filter(b -> b.getName().equals(name)).findFirst();
    }

    public List<Building> getBuildingsByType(BuildingType type) {
        return buildings.stream().filter(b -> b.getType() == type).toList();
    }

    // =========================================================================
    // Expansion requests
    // =========================================================================

    public void addExpansionRequest(ExpansionRequest request) { expansionRequests.add(request); setDirty(); }

    public List<ExpansionRequest> getExpansionRequests() {
        return Collections.unmodifiableList(expansionRequests);
    }

    public Optional<ExpansionRequest> getPendingExpansionForVillage(UUID villageId) {
        return expansionRequests.stream()
                .filter(r -> r.getVillageId().equals(villageId) && r.isPending())
                .findFirst();
    }

    public void removeExpansionRequest(UUID id) {
        expansionRequests.removeIf(r -> r.getId().equals(id));
        setDirty();
    }

    // =========================================================================
    // Villages
    // =========================================================================

    public void addVillage(Village village) {
        villages.add(village);
        villageIndex.put(village.getId(), village);
        setDirty();
    }

    public List<Village>      getAllVillages()             { return List.copyOf(villages); }
    public Optional<Village>  getVillageById(UUID id)     { return Optional.ofNullable(villageIndex.get(id)); }

    public Optional<Village> getVillageByName(String name) {
        return villages.stream().filter(v -> v.getName().equals(name)).findFirst();
    }

    public Optional<Village> getVillageAt(BlockPos pos) {
        return villages.stream().filter(v -> v.contains(pos, this)).findFirst();
    }

    // =========================================================================
    // Farm plots
    // =========================================================================

    public void addFarmPlot(FarmPlot plot) {
        farmPlots.add(plot);
        farmPlotIndex.put(plot.getId(), plot);
        setDirty();
    }

    public Optional<FarmPlot> getFarmPlotById(UUID id)     { return Optional.ofNullable(farmPlotIndex.get(id)); }

    public Optional<FarmPlot> getFarmPlotByName(String name) {
        return farmPlots.stream().filter(p -> p.getName().equals(name)).findFirst();
    }

    public List<FarmPlot> getFarmPlotsForFarmhouse(UUID farmhouseId) {
        return farmPlots.stream()
                .filter(p -> farmhouseId.equals(p.getFarmhouseId()))
                .collect(Collectors.toList());
    }

    public List<FarmPlot> getFarmPlotsInVillage(Village village, VillageSavedData data) {
        return farmPlots.stream()
                .filter(p -> village.getBounds(data)
                        .map(b -> b.contains(p.getOrigin().getX(),
                                p.getOrigin().getY(), p.getOrigin().getZ()))
                        .orElse(false))
                .collect(Collectors.toList());
    }

    public List<FarmPlot> getFarmPlotsForVillage(UUID villageId) {
        return getVillageById(villageId)
                .map(v -> getFarmPlotsInVillage(v, this))
                .orElse(Collections.emptyList());
    }

    public boolean removeFarmPlot(UUID id) {
        boolean removed = farmPlots.removeIf(p -> p.getId().equals(id));
        if (removed) { farmPlotIndex.remove(id); setDirty(); }
        return removed;
    }

    public List<FarmPlot> getAllFarmPlots() { return Collections.unmodifiableList(farmPlots); }

    // =========================================================================
    // Job postings
    // =========================================================================

    public void addJobPosting(JobPosting posting) {
        if (jobPostings.stream().anyMatch(p -> p.getId().equals(posting.getId()))) return;
        jobPostings.add(posting);
        setDirty();
    }

    public Optional<JobPosting> getJobPostingById(UUID id) {
        return jobPostings.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public List<JobPosting> getOpenPostingsForVillage(Village village) {
        Set<UUID> ids = new HashSet<>(village.getBuildingIds());
        return jobPostings.stream()
                .filter(p -> ids.contains(p.getFarmhouseBuildingId()))
                .filter(p -> p.getStatus() == JobPosting.PostingStatus.OPEN
                        || p.getStatus() == JobPosting.PostingStatus.REVIEWING)
                .collect(Collectors.toList());
    }

    public List<JobPosting> getPostingsByPoster(UUID posterEntityId) {
        return jobPostings.stream()
                .filter(p -> p.getPosterEntityId().equals(posterEntityId))
                .collect(Collectors.toList());
    }

    public void removeJobPosting(UUID id) { jobPostings.removeIf(p -> p.getId().equals(id)); setDirty(); }
    public List<JobPosting> getAllJobPostings() { return Collections.unmodifiableList(jobPostings); }

    // =========================================================================
    // Village paths
    // =========================================================================

    public void addVillagePath(VillagePath path) { villagePaths.add(path); setDirty(); }

    public List<VillagePath> getPathsForVillage(UUID villageId) {
        return villagePaths.stream()
                .filter(p -> p.getVillageId().equals(villageId))
                .collect(Collectors.toList());
    }

    public List<VillagePath> getAllVillagePaths() { return Collections.unmodifiableList(villagePaths); }

    public void removeVillagePath(UUID id) { villagePaths.removeIf(p -> p.getId().equals(id)); setDirty(); }

    public Optional<BlockPos> getNearestPathNode(BlockPos pos, UUID villageId) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (VillagePath path : getPathsForVillage(villageId)) {
            for (BlockPos block : path.getBlocks()) {
                double d = block.distSqr(pos);
                if (d < bestDistSq) { bestDistSq = d; best = block; }
            }
        }
        return Optional.ofNullable(best);
    }

    // =========================================================================
    // Kingdoms
    // =========================================================================

    public void addKingdom(Kingdom kingdom) {
        kingdoms.add(kingdom);
        kingdomIndex.put(kingdom.getId(), kingdom);
        setDirty();
    }

    public Optional<Kingdom> getKingdomById(UUID id)      { return Optional.ofNullable(kingdomIndex.get(id)); }
    public List<Kingdom>     getAllKingdoms()               { return Collections.unmodifiableList(kingdoms); }

    public Optional<Kingdom> getKingdomByName(String name) {
        return kingdoms.stream().filter(k -> k.getName().equals(name)).findFirst();
    }

    public Optional<Kingdom> getKingdomForVillage(UUID villageId) {
        return kingdoms.stream().filter(k -> k.containsVillage(villageId)).findFirst();
    }

    public void removeKingdom(UUID id) {
        kingdoms.removeIf(k -> k.getId().equals(id));
        kingdomIndex.remove(id);
        setDirty();
    }

    // =========================================================================
    // Events
    // =========================================================================

    public void addEvent(VillageEvent event) { events.add(event); setDirty(); }
    public List<VillageEvent> getAllEvents()  { return Collections.unmodifiableList(events); }

    public List<VillageEvent> getActiveEventsForVillage(UUID villageId) {
        return events.stream()
                .filter(e -> e.getVillageId().equals(villageId)
                        && (e.isActive() || e.isAnnounced()))
                .collect(Collectors.toList());
    }

    public Optional<VillageEvent> getActiveEventForVillage(UUID villageId) {
        return events.stream()
                .filter(e -> e.getVillageId().equals(villageId) && e.isActive())
                .findFirst();
    }

    public void removeEndedEvents() {
        events.removeIf(e -> e.getStatus() == VillageEvent.EventStatus.ENDED);
        setDirty();
    }

    // =========================================================================
    // Guilds
    // =========================================================================

    public void addGuild(GuildData guild) { guilds.add(guild); setDirty(); }

    public Optional<GuildData> getGuildForVillage(UUID villageId) {
        return guilds.stream().filter(g -> g.villageId().equals(villageId)).findFirst();
    }

    public Optional<GuildData> getGuildById(UUID guildId) {
        return guilds.stream().filter(g -> g.guildId().equals(guildId)).findFirst();
    }

    public void updateGuild(GuildData updated) {
        guilds.removeIf(g -> g.guildId().equals(updated.guildId()));
        guilds.add(updated);
        setDirty();
    }

    // =========================================================================
    // Housing (rented rooms / beds)
    // =========================================================================

    public void rentRoom(UUID playerId, long expiryTick) { rentedRooms.put(playerId, expiryTick); setDirty(); }

    public boolean hasRentedRoom(UUID playerId, long currentTick) {
        Long expiry = rentedRooms.get(playerId);
        if (expiry == null) return false;
        if (currentTick > expiry) { rentedRooms.remove(playerId); setDirty(); return false; }
        return true;
    }

    public Optional<BlockPos> getRentedBed(UUID playerId) { return Optional.ofNullable(rentedBeds.get(playerId)); }

    public void assignBed(UUID playerId, BlockPos bedPos) { rentedBeds.put(playerId, bedPos); setDirty(); }

    // =========================================================================
    // Player warnings (runtime only — not persisted)
    // =========================================================================

    public void addPlayerWarning(ServerLevel level, UUID playerId, UUID villageId, long tick) {
        playerWarnings.computeIfAbsent(playerId, k -> new HashMap<>()).put(villageId, tick);


        // Trigger GUARD_INSPIRE for any guard-profession players nearby
        this.getVillageById(villageId).ifPresent(village ->
                ProfessionPerkManager.onVillageAlarm(level, village));

        setDirty();
    }

    public boolean hasWarning(UUID playerId, UUID villageId) {
        return playerWarnings.getOrDefault(playerId, Map.of()).containsKey(villageId);
    }

    public Map<UUID, Map<UUID, Long>> getActiveWarnings() {
        return Collections.unmodifiableMap(playerWarnings);
    }

    public void clearWarning(UUID playerId, UUID villageId) {
        Map<UUID, Long> w = playerWarnings.get(playerId);
        if (w != null) {
            w.remove(villageId);
            if (w.isEmpty()) playerWarnings.remove(playerId);
            setDirty();
        }
    }

    // =========================================================================
    // Trade routes & roads
    // =========================================================================

    public void addTradeRoute(TradeRoute route) { tradeRoutes.put(route.getRouteId(), route); setDirty(); }
    public void addTradeRoad(TradeRoad road)    { tradeRoads.put(road.getRoadId(), road);     setDirty(); }

    public Optional<TradeRoute> getRouteById(UUID id) { return Optional.ofNullable(tradeRoutes.get(id)); }
    public Optional<TradeRoad>  getRoadById(UUID id)  { return Optional.ofNullable(tradeRoads.get(id));  }

    public List<TradeRoute> getAllTradeRoutes() { return new ArrayList<>(tradeRoutes.values()); }
    public List<TradeRoad>  getAllTradeRoads()  { return new ArrayList<>(tradeRoads.values());  }

    public Optional<TradeRoute> getRouteBetween(UUID a, UUID b) {
        return tradeRoutes.values().stream().filter(r -> r.connects(a, b)).findFirst();
    }

    public Optional<TradeRoad> getRoadBetween(UUID a, UUID b) {
        return tradeRoutes.values().stream()
                .filter(r -> r.connects(a, b))
                .map(r -> tradeRoads.get(r.getRoadId()))
                .filter(Objects::nonNull)
                .findFirst();
    }

    public List<TradeRoute> getRoutesForVillage(UUID villageId) {
        return tradeRoutes.values().stream()
                .filter(r -> r.getVillageA().equals(villageId)
                        || r.getVillageB().equals(villageId))
                .collect(Collectors.toList());
    }

    public void removeTradeRoute(UUID id) { tradeRoutes.remove(id); setDirty(); }
    public void removeTradeRoad(UUID id)  { tradeRoads.remove(id);  setDirty(); }

    // =========================================================================
    // Player property
    // =========================================================================

    public void addPlayerProperty(PlayerHousingData.PlayerProperty property) {
        playerProperties.add(property);
        setDirty();
    }

    public List<PlayerHousingData.PlayerProperty> getPropertiesForPlayer(UUID playerId) {
        return playerProperties.stream()
                .filter(p -> p.playerId().equals(playerId))
                .collect(Collectors.toList());
    }

    public Optional<PlayerHousingData.PlayerProperty> getPropertyForBuilding(UUID buildingId) {
        return playerProperties.stream()
                .filter(p -> p.buildingId().equals(buildingId))
                .findFirst();
    }

    public boolean isPlayerOwned(UUID buildingId) { return getPropertyForBuilding(buildingId).isPresent(); }

    public void removeProperty(UUID buildingId) {
        playerProperties.removeIf(p -> p.buildingId().equals(buildingId));
        setDirty();
    }

    public long getPropertyTaxRate(UUID villageId)            { return propertyTaxRates.getOrDefault(villageId, 2L); }
    public void setPropertyTaxRate(UUID villageId, long rate) { propertyTaxRates.put(villageId, rate); setDirty(); }

    public List<PlayerHousingData.PlayerProperty> getAllPlayerProperties() {
        return Collections.unmodifiableList(playerProperties);
    }

    public void updatePropertyTaxTick(UUID buildingId, long tick) {
        for (int i = 0; i < playerProperties.size(); i++) {
            var p = playerProperties.get(i);
            if (p.buildingId().equals(buildingId)) {
                playerProperties.set(i, new PlayerHousingData.PlayerProperty(
                        p.playerId(), p.buildingId(), p.villageId(), p.type(),
                        p.purchaseTick(), tick, p.purchasePrice()));
                setDirty();
                return;
            }
        }
    }

    // =========================================================================
    // Crafting orders
    // =========================================================================

    public void addCraftingOrder(CraftingOrder order) { craftingOrders.add(order); setDirty(); }

    public Optional<CraftingOrder> getCraftingOrderById(UUID id) {
        return craftingOrders.stream().filter(o -> o.getOrderId().equals(id)).findFirst();
    }

    public List<CraftingOrder> getOrdersForVillage(UUID villageId) {
        return craftingOrders.stream()
                .filter(o -> o.getVillageId().equals(villageId))
                .collect(Collectors.toList());
    }

    public List<CraftingOrder> getAllCraftingOrders() { return Collections.unmodifiableList(craftingOrders); }

    public void pruneCraftingOrders() {
        craftingOrders.removeIf(o ->
                o.getStatus() == CraftingOrder.OrderStatus.FULFILLED
                        || o.getStatus() == CraftingOrder.OrderStatus.EXPIRED
                        || o.getStatus() == CraftingOrder.OrderStatus.CANCELLED);
        setDirty();
    }

    // =========================================================================
    // Household data
    // =========================================================================

    public void addHousehold(HouseholdData household) { households.put(household.getBuildingId(), household); setDirty(); }

    public void removeHousehold(UUID householdId) {
        households.values().removeIf(h -> h.getHouseholdId().equals(householdId));
        setDirty();
    }

    public Optional<HouseholdData> getHouseholdForBuilding(UUID buildingId) { return Optional.ofNullable(households.get(buildingId)); }

    public Optional<HouseholdData> getHouseholdForNpc(UUID npcId) {
        return households.values().stream().filter(h -> h.hasMember(npcId)).findFirst();
    }

    public Collection<HouseholdData> getAllHouseholds() { return Collections.unmodifiableCollection(households.values()); }

    // =========================================================================
    // Village reputation
    // =========================================================================

    public Optional<VillageReputation> getReputation(UUID playerId, UUID villageId) {
        return Optional.ofNullable(reputations.get(repKey(playerId, villageId)));
    }

    public void addReputation(VillageReputation rep) {
        String key = repKey(rep.getPlayerId(), rep.getVillageId());
        if (reputations.containsKey(key)) return;
        reputations.put(key, rep);
        setDirty();
    }

    private static String repKey(UUID playerId, UUID villageId) {
        return playerId.toString() + ":" + villageId.toString();
    }
    public Map<UUID, BlockPos> getAllRentedBeds() {
        return Collections.unmodifiableMap(rentedBeds);
    }
    public Optional<VillageSimData> getSimData(UUID villageId) {
        return Optional.ofNullable(simData.get(villageId));
    }

    public void putSimData(VillageSimData sim) {
        simData.put(sim.getVillageId(), sim);
        setDirty();
    }

    public Collection<VillageSimData> getAllSimData() {
        return Collections.unmodifiableCollection(simData.values());
    }
}