package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Guild.GuildData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Village.*;
import tterrag1112.life_in_the_village.Village.Buildings.ExpansionRequest;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Decoration.VillagePath;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;

import java.util.*;
import java.util.stream.Collectors;

public class VillageSavedData extends SavedData {

    // Add these nested records inside VillageSavedData

    public record VillageEconomyData(
            List<TradeRoute> tradeRoutes,
            List<TradeRoad> tradeRoads
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

    public record VillageHousingData(
            Map<UUID, Long> rentedRooms,
            Map<UUID, BlockPos> rentedBeds
    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<VillageHousingData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.unboundedMap(UUID_STRING, Codec.LONG)
                                .optionalFieldOf("rentedRooms",
                                        new HashMap<>())
                                .forGetter(VillageHousingData::rentedRooms),
                        Codec.unboundedMap(UUID_STRING, BlockPos.CODEC)
                                .optionalFieldOf("rentedBeds",
                                        new HashMap<>())
                                .forGetter(VillageHousingData::rentedBeds)
                ).apply(i, VillageHousingData::new));
    }
    public static final Codec<VillageSavedData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Building.CODEC.listOf()
                            .optionalFieldOf("buildings",
                                    new ArrayList<>())
                            .forGetter(d -> d.buildings),
                    Village.CODEC.listOf()
                            .optionalFieldOf("villages",
                                    new ArrayList<>())
                            .forGetter(d -> d.villages),
                    FarmPlot.CODEC.listOf()
                            .optionalFieldOf("farmPlots",
                                    new ArrayList<>())
                            .forGetter(d -> d.farmPlots),
                    JobPosting.CODEC.listOf()
                            .optionalFieldOf("jobPostings",
                                    new ArrayList<>())
                            .forGetter(d -> d.jobPostings),
                    ExpansionRequest.CODEC.listOf()
                            .optionalFieldOf("expansionRequests",
                                    new ArrayList<>())
                            .forGetter(d -> d.expansionRequests),
                    VillagePath.CODEC.listOf()
                            .optionalFieldOf("villagePaths",
                                    new ArrayList<>())
                            .forGetter(d -> d.villagePaths),
                    Kingdom.CODEC.listOf()
                            .optionalFieldOf("kingdoms",
                                    new ArrayList<>())
                            .forGetter(d -> d.kingdoms),
                    VillageEvent.CODEC.listOf()
                            .optionalFieldOf("events",
                                    new ArrayList<>())
                            .forGetter(d -> d.events),
                    GuildData.CODEC.listOf()
                            .optionalFieldOf("guilds",
                                    new ArrayList<>())
                            .forGetter(d -> d.guilds),
                    VillageHousingData.CODEC
                            .fieldOf("housingData")
                            .forGetter(d -> new VillageHousingData(
                                    d.rentedRooms, d.rentedBeds)),
                    VillageEconomyData.CODEC
                            .fieldOf("economyData")
                            .forGetter(d -> new VillageEconomyData(
                                    new ArrayList<>(d.tradeRoutes.values()),
                                    new ArrayList<>(d.tradeRoads.values())))
            ).apply(instance, VillageSavedData::fromCodec));

    public static VillageSavedData fromCodec(
            List<Building> buildings, List<Village> villages,
            List<FarmPlot> farmPlots, List<JobPosting> jobPostings,
            List<ExpansionRequest> expansionRequests,
            List<VillagePath> villagePaths,
            List<Kingdom> kingdoms,
            List<VillageEvent> events,
            List<GuildData> guilds,
            VillageHousingData housingData,
            VillageEconomyData economyData) {

        VillageSavedData data = new VillageSavedData();
        data.buildings.addAll(buildings);
        data.villages.addAll(villages);
        data.farmPlots.addAll(farmPlots);
        data.jobPostings.addAll(jobPostings);
        data.expansionRequests.addAll(expansionRequests);
        data.villagePaths.addAll(villagePaths);
        data.kingdoms.addAll(kingdoms);
        data.events.addAll(events);
        data.guilds.addAll(guilds);

        // Housing
        data.rentedRooms.putAll(housingData.rentedRooms());
        data.rentedBeds.putAll(housingData.rentedBeds());

        // Economy
        economyData.tradeRoutes().forEach(r ->
                data.tradeRoutes.put(r.getRouteId(), r));
        economyData.tradeRoads().forEach(r ->
                data.tradeRoads.put(r.getRoadId(), r));

        return data;
    }

    public static final SavedDataType<VillageSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_buildings",
            VillageSavedData::new,
            CODEC
    );


    private final List<Building> buildings;
    private final List<Village> villages;
    private final List<FarmPlot> farmPlots;
    private final List<JobPosting> jobPostings;
    private final List<ExpansionRequest> expansionRequests = new ArrayList<>();
    private final List<VillagePath> villagePaths = new ArrayList<>();
    private final List<Kingdom> kingdoms = new ArrayList<>();
    private final List<VillageEvent> events = new ArrayList<>();
    private final List<GuildData> guilds = new ArrayList<>();

    private final Map<UUID, Map<UUID, Long>> playerWarnings = new HashMap<>();
    private final Map<UUID, TradeRoute> tradeRoutes = new HashMap<>();
    private final Map<UUID, TradeRoad> tradeRoads   = new HashMap<>();











    // Used when creating a fresh instance (no saved data exists yet)

    public VillageSavedData() {
        this.buildings = new ArrayList<>();
        this.villages = new ArrayList<>();
        this.farmPlots = new ArrayList<>();
        this.jobPostings = new ArrayList<>();
    }



    // --- Access point ---

    public static VillageSavedData get(ServerLevel level) {
        VillageSavedData data = level.getDataStorage().computeIfAbsent(TYPE);

        return data;
    }

    // --- Building management ---

    public void addBuilding(Building building) {
        buildings.add(building);
        setDirty();
    }

    public void addExpansionRequest(ExpansionRequest request) {
        expansionRequests.add(request);
        setDirty();
    }

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

    public void removeBuilding(Building building) {
        buildings.remove(building);
        setDirty();
    }

    public List<Building> getAllBuildings() {
        return List.copyOf(buildings);
    }

    public Optional<Building> getBuildingById(UUID id) {
        return buildings.stream()
                .filter(b -> b.getId().equals(id))
                .findFirst();
    }

    // Keep getBuildingAt for position-based lookup
    public Optional<Building> getBuildingAt(BlockPos pos) {
        return buildings.stream()
                .filter(b -> b.getShape().contains(pos))
                .findFirst();
    }

    public List<Building> getBuildingsByType(Building.BuildingType type) {
        return buildings.stream()
                .filter(b -> b.getType() == type)
                .toList();
    }

    public void addVillage(Village village) {
        villages.add(village);
        setDirty();
    }

    public List<Village> getAllVillages() {
        return List.copyOf(villages);
    }

    public Optional<Village> getVillageByName(String name) {
        return villages.stream()
                .filter(v -> v.getName().equals(name))
                .findFirst();
    }

    public Optional<Village> getVillageAt(BlockPos pos) {
        return villages.stream()
                .filter(v -> v.contains(pos, this))
                .findFirst();
    }

    // Used by Village.getBounds to resolve building names to objects
    public Optional<Building> getBuildingByName(String name) {
        return buildings.stream()
                .filter(b -> b.getName().equals(name))
                .findFirst();
    }

    // Update the codec to include villages




    public void addFarmPlot(FarmPlot plot) {
        farmPlots.add(plot);
        setDirty();
    }

    public Optional<FarmPlot> getFarmPlotById(UUID id) {
        return farmPlots.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public Optional<FarmPlot> getFarmPlotByName(String name) {
        return farmPlots.stream().filter(p -> p.getName().equals(name)).findFirst();
    }

    public List<FarmPlot> getFarmPlotsForFarmhouse(UUID farmhouseId) {
        return farmPlots.stream()
                .filter(p -> farmhouseId.equals(p.getFarmhouseId()))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<FarmPlot> getFarmPlotsInVillage(Village village, VillageSavedData data) {
        return farmPlots.stream()
                .filter(p -> village.getBounds(data)
                        .map(bounds -> bounds.contains(
                                p.getOrigin().getX(),
                                p.getOrigin().getY(),
                                p.getOrigin().getZ()
                        ))
                        .orElse(false))
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean removeFarmPlot(UUID id) {
        boolean removed = farmPlots.removeIf(p -> p.getId().equals(id));
        if (removed) setDirty();
        return removed;
    }

    public List<FarmPlot> getAllFarmPlots() {
        return Collections.unmodifiableList(farmPlots);
    }

    public void addJobPosting(JobPosting posting) {
        // Check for duplicate
        boolean exists = jobPostings.stream()
                .anyMatch(p -> p.getId().equals(posting.getId()));
        if (exists) {
            System.out.println("WARNING: duplicate job posting detected: " + posting.getId()
                    + " existing instances: " + jobPostings.stream()
                    .filter(p -> p.getId().equals(posting.getId()))
                    .map(p -> System.identityHashCode(p) + " applicants=" + p.getApplicantIds().size())
                    .collect(java.util.stream.Collectors.joining(", ")));
            return; // don't add duplicate
        }
        jobPostings.add(posting);
        setDirty();
    }

    public Optional<JobPosting> getJobPostingById(UUID id) {
        return jobPostings.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst();
    }

    public List<JobPosting> getOpenPostingsForVillage(Village village) {
        Set<UUID> villageBuildingIds = new HashSet<>(village.getBuildingIds());
        return jobPostings.stream()
                .filter(p -> villageBuildingIds.contains(p.getFarmhouseBuildingId()))
                .filter(p -> p.getStatus() == JobPosting.PostingStatus.OPEN
                        || p.getStatus() == JobPosting.PostingStatus.REVIEWING)
                .collect(java.util.stream.Collectors.toList());
    }

    public List<JobPosting> getPostingsByPoster(UUID posterEntityId) {
        return jobPostings.stream()
                .filter(p -> p.getPosterEntityId().equals(posterEntityId))
                .collect(java.util.stream.Collectors.toList());
    }

    public void removeJobPosting(UUID id) {
        jobPostings.removeIf(p -> p.getId().equals(id));
        setDirty();
    }

    public List<JobPosting> getAllJobPostings() {
        return Collections.unmodifiableList(jobPostings);
    }

    public void addVillagePath(VillagePath path) {
        villagePaths.add(path);
        setDirty();
    }

    public List<VillagePath> getPathsForVillage(UUID villageId) {
        return villagePaths.stream()
                .filter(p -> p.getVillageId().equals(villageId))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<VillagePath> getAllVillagePaths() {
        return Collections.unmodifiableList(villagePaths);
    }

    public void removeVillagePath(UUID id) {
        villagePaths.removeIf(p -> p.getId().equals(id));
        setDirty();
    }


    private final Map<UUID, Long> rentedRooms = new HashMap<>();

    public void rentRoom(UUID playerId, long expiryTick) {
        rentedRooms.put(playerId, expiryTick);
        setDirty();
    }

    public boolean hasRentedRoom(UUID playerId, long currentTick) {
        Long expiry = rentedRooms.get(playerId);
        if (expiry == null) return false;
        if (currentTick > expiry) {
            rentedRooms.remove(playerId);
            setDirty();
            return false;
        }
        return true;
    }

    public Optional<BlockPos> getRentedBed(UUID playerId) {
        return Optional.ofNullable(rentedBeds.get(playerId));
    }

    public final Map<UUID, BlockPos> rentedBeds = new HashMap<>();

    public void assignBed(UUID playerId, BlockPos bedPos) {
        rentedBeds.put(playerId, bedPos);
        setDirty();
    }

    // Add getVillageById since Kingdom needs it
    public Optional<Village> getVillageById(UUID id) {
        return villages.stream()
                .filter(v -> v.getId().equals(id))
                .findFirst();
    }

    // Kingdom methods
    public void addKingdom(Kingdom kingdom) {
        kingdoms.add(kingdom);
        setDirty();
    }

    public Optional<Kingdom> getKingdomById(UUID id) {
        return kingdoms.stream()
                .filter(k -> k.getId().equals(id))
                .findFirst();
    }

    public Optional<Kingdom> getKingdomByName(String name) {
        return kingdoms.stream()
                .filter(k -> k.getName().equals(name))
                .findFirst();
    }

    public Optional<Kingdom> getKingdomForVillage(UUID villageId) {
        return kingdoms.stream()
                .filter(k -> k.containsVillage(villageId))
                .findFirst();
    }

    public List<Kingdom> getAllKingdoms() {
        return Collections.unmodifiableList(kingdoms);
    }

    public void removeKingdom(UUID id) {
        kingdoms.removeIf(k -> k.getId().equals(id));
        setDirty();
    }



    // Events methods



    public void addEvent(VillageEvent event) {
        events.add(event);
        setDirty();
    }

    public List<VillageEvent> getAllEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<VillageEvent> getActiveEventsForVillage(UUID villageId) {
        return events.stream()
                .filter(e -> e.getVillageId().equals(villageId)
                        && (e.isActive() || e.isAnnounced()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Optional<VillageEvent> getActiveEventForVillage(UUID villageId) {
        return events.stream()
                .filter(e -> e.getVillageId().equals(villageId)
                        && e.isActive())
                .findFirst();
    }

    public void removeEndedEvents() {
        events.removeIf(e ->
                e.getStatus() == VillageEvent.EventStatus.ENDED);
        setDirty();
    }



    public void addGuild(GuildData guild) {
        guilds.add(guild);
        setDirty();
    }

    public Optional<GuildData> getGuildForVillage(UUID villageId) {
        return guilds.stream()
                .filter(g -> g.villageId().equals(villageId))
                .findFirst();
    }

    public Optional<GuildData> getGuildById(UUID guildId) {
        return guilds.stream()
                .filter(g -> g.guildId().equals(guildId))
                .findFirst();
    }

    public void updateGuild(GuildData updated) {
        guilds.removeIf(g -> g.guildId().equals(updated.guildId()));
        guilds.add(updated);
        setDirty();
    }

    public void addPlayerWarning(UUID playerId, UUID villageId, long tick) {
        playerWarnings
                .computeIfAbsent(playerId, k -> new HashMap<>())
                .put(villageId, tick);
        setDirty();
    }

    public boolean hasWarning(UUID playerId, UUID villageId) {
        return playerWarnings
                .getOrDefault(playerId, Map.of())
                .containsKey(villageId);
    }

    public Map<UUID, Map<UUID, Long>> getActiveWarnings() {
        return Collections.unmodifiableMap(playerWarnings);
    }

    public void clearWarning(UUID playerId, UUID villageId) {
        Map<UUID, Long> warnings = playerWarnings.get(playerId);
        if (warnings != null) {
            warnings.remove(villageId);
            if (warnings.isEmpty()) {
                playerWarnings.remove(playerId);
            }
            setDirty();
        }
    }

    public void addTradeRoute(TradeRoute route) {
        tradeRoutes.put(route.getRouteId(), route);
        setDirty();
    }

    public void addTradeRoad(TradeRoad road) {
        tradeRoads.put(road.getRoadId(), road);
        setDirty();
    }

    public Optional<TradeRoute> getRouteById(UUID id) {
        return Optional.ofNullable(tradeRoutes.get(id));
    }

    public Optional<TradeRoad> getRoadById(UUID id) {
        return Optional.ofNullable(tradeRoads.get(id));
    }

    public List<TradeRoute> getAllTradeRoutes() {
        return new ArrayList<>(tradeRoutes.values());
    }

    public List<TradeRoad> getAllTradeRoads() {
        return new ArrayList<>(tradeRoads.values());
    }

    public Optional<TradeRoute> getRouteBetween(UUID a, UUID b) {
        return tradeRoutes.values().stream()
                .filter(r -> r.connects(a, b))
                .findFirst();
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

    public void removeTradeRoute(UUID id) {
        tradeRoutes.remove(id);
        setDirty();
    }

    public void removeTradeRoad(UUID id) {
        tradeRoads.remove(id);
        setDirty();
    }



}
