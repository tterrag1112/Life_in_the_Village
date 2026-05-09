// src/main/java/tterrag1112/life_in_the_village/Networking/VillageSavedData.java
package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipTopicHeat;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.ExpansionRequest;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Buildings.PlayerHousingData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Economy.*;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.JobPosting;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class VillageSavedData extends SavedData implements
        VillageDataAccess.VillageView,
        VillageDataAccess.ReputationView,
        VillageDataAccess.EconomyView,
        VillageDataAccess.KingdomView,
        VillageDataAccess.HouseholdView,
        VillageDataAccess.GuildView,
        VillageDataAccess.SimulationView,
        VillageDataAccess.TreasuryView {

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
    //  economyData     — trade routes, sea routes, treasuries, etc.
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
            List<TradeRoute>      tradeRoutes,
            List<SeaRoute>        seaRoutes,
            List<VillageTreasury> treasuries,
            List<FarmBusinessLevel> farmBusinessLevels,
            List<MarketStall>     marketStalls,
            Map<UUID, BuildingEconomy> buildingEconomies

    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);
        public static final Codec<VillageEconomyData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        TradeRoute.CODEC.listOf()
                                .optionalFieldOf("tradeRoutes", new ArrayList<>())
                                .forGetter(VillageEconomyData::tradeRoutes),
                        SeaRoute.CODEC.listOf()
                                .optionalFieldOf("seaRoutes", new ArrayList<>())
                                .forGetter(VillageEconomyData::seaRoutes),
                        VillageTreasury.CODEC.listOf()
                                .optionalFieldOf("treasuries", new ArrayList<>())
                                .forGetter(VillageEconomyData::treasuries),
                        FarmBusinessLevel.CODEC.listOf()
                                .optionalFieldOf("farmBusinessLevels", new ArrayList<>())
                                .forGetter(VillageEconomyData::farmBusinessLevels),
                        MarketStall.CODEC.listOf()
                                .optionalFieldOf("marketStalls", new ArrayList<>())
                                .forGetter(VillageEconomyData::marketStalls),
                        Codec.unboundedMap(UUID_STRING, BuildingEconomy.CODEC)
                                .optionalFieldOf("buildingEconomies", new HashMap<>())
                                .forGetter(VillageEconomyData::buildingEconomies)

                ).apply(i, VillageEconomyData::new));

    }

    // ── 8. Gossip (Phase 2 task 12) ──────────────────────────────────────────

    /**
     * Per-village {@link GossipTopicHeat} keyed by village UUID. Spec
     * {@code 12-gossip-rumor.md} line 250. Persists; channels do not.
     */
    public record VillageGossipData(
            Map<UUID, GossipTopicHeat> topicHeat
    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<VillageGossipData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.unboundedMap(UUID_STRING, GossipTopicHeat.CODEC)
                                .optionalFieldOf("topicHeat", new LinkedHashMap<>())
                                .forGetter(VillageGossipData::topicHeat)
                ).apply(i, VillageGossipData::new));
    }

    // ── 9. Scribal (Phase 2 task 17) ─────────────────────────────────────────

    /**
     * Scribal data: per-SCRIBE_WORKSHOP commission queues and per-LIBRARY
     * book catalogues, both keyed by building UUID. Spec line 336–337.
     */
    public record VillageScribalData(
            Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue> commissionQueues,
            Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue> libraryCatalogues
    ) {
        private static final Codec<UUID> UUID_STRING =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<VillageScribalData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.unboundedMap(UUID_STRING,
                                        tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue.CODEC)
                                .optionalFieldOf("commissionQueues", new LinkedHashMap<>())
                                .forGetter(VillageScribalData::commissionQueues),
                        Codec.unboundedMap(UUID_STRING,
                                        tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue.CODEC)
                                .optionalFieldOf("libraryCatalogues", new LinkedHashMap<>())
                                .forGetter(VillageScribalData::libraryCatalogues)
                ).apply(i, VillageScribalData::new));
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

    // ── 10. Subbuildings ───────────────────────────────────────────────────────

    /**
     * Doc 03 — durable subbuilding records keyed by subBuildingId. The
     * per-parent denormalisation map is rebuilt on load from this list,
     * so only the flat list is persisted.
     */
    public record VillageSubBuildingData(
            List<tterrag1112.life_in_the_village.Village.Decoration
                    .Subbuilding.SubBuilding> subBuildings
    ) {
        public static final Codec<VillageSubBuildingData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        tterrag1112.life_in_the_village.Village.Decoration
                                .Subbuilding.SubBuilding.CODEC.listOf()
                                .optionalFieldOf("subBuildings", List.of())
                                .forGetter(VillageSubBuildingData::subBuildings)
                ).apply(i, VillageSubBuildingData::new));
    }

    // ── 9. Adjunct plots ──────────────────────────────────────────────────────

    /**
     * Doc 02 — durable adjunct plots keyed by plotId. The
     * per-building denormalisation map is rebuilt on load from
     * this list, so only the flat map is persisted.
     */
    public record VillageAdjunctData(
            List<tterrag1112.life_in_the_village.Village.Decoration
                    .Adjunct.AdjunctPlot> plots
    ) {
        public static final Codec<VillageAdjunctData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        tterrag1112.life_in_the_village.Village.Decoration
                                .Adjunct.AdjunctPlot.CODEC.listOf()
                                .optionalFieldOf("plots", List.of())
                                .forGetter(VillageAdjunctData::plots)
                ).apply(i, VillageAdjunctData::new));
    }

    // ── 10. Garden plots (B2.4) ──────────────────────────────────────────────

    /**
     * B2.4 — durable park / garden plots keyed by plotId. Mirrors
     * the {@link VillageAdjunctData} pattern: flat list persists,
     * per-village denormalisation rebuilt on load.
     */
    public record VillageGardenData(
            List<tterrag1112.life_in_the_village.Village.Decoration
                    .Parks.GardenPlot> plots
    ) {
        public static final Codec<VillageGardenData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        tterrag1112.life_in_the_village.Village.Decoration
                                .Parks.GardenPlot.CODEC.listOf()
                                .optionalFieldOf("plots", List.of())
                                .forGetter(VillageGardenData::plots)
                ).apply(i, VillageGardenData::new));
    }

    // ── 11. Farm sectors (B2.5) ──────────────────────────────────────────────

    /**
     * B2.5 — durable farm sectors keyed by sectorId. Mirrors the
     * garden / adjunct pattern.
     */
    public record VillageFarmData(
            List<tterrag1112.life_in_the_village.Village.Farms.FarmSector> sectors
    ) {
        public static final Codec<VillageFarmData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        tterrag1112.life_in_the_village.Village.Farms
                                .FarmSector.CODEC.listOf()
                                .optionalFieldOf("sectors", List.of())
                                .forGetter(VillageFarmData::sectors)
                ).apply(i, VillageFarmData::new));
    }

    // ── 8. Decoration ─────────────────────────────────────────────────────────

    /**
     * Doc 01 — durable decoration placements per village. Flat list;
     * each {@link tterrag1112.life_in_the_village.Village.Decoration
     * .Framework.DecorationPlacement} carries its owning villageId
     * for filtering. Empty until {@code DecorationPass} runs (and
     * stays empty until profiles are registered).
     */
    public record VillageDecorationData(
            List<tterrag1112.life_in_the_village.Village.Decoration
                    .Framework.DecorationPlacement> placements
    ) {
        public static final Codec<VillageDecorationData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        tterrag1112.life_in_the_village.Village.Decoration
                                .Framework.DecorationPlacement.CODEC.listOf()
                                .optionalFieldOf("placements", List.of())
                                .forGetter(VillageDecorationData::placements)
                ).apply(i, VillageDecorationData::new));
    }

    // =========================================================================
    // Top-level codec — 9 sub-records (added VillageDecorationData)
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
                                    new ArrayList<>(d.seaRoutes.values()),
                                    new ArrayList<>(d.treasuries.values()),
                                    new ArrayList<>(d.farmBusinessLevels.values()),
                                    new ArrayList<>(d.marketStalls),
                                    new HashMap<>(d.buildingEconomies))),
                    VillagePropertyData.CODEC
                            .fieldOf("propertyData")
                            .forGetter(d -> new VillagePropertyData(
                                    new ArrayList<>(d.playerProperties),
                                    new HashMap<>(d.propertyTaxRates))),
                    VillageGossipData.CODEC
                            .optionalFieldOf("gossipData", new VillageGossipData(new LinkedHashMap<>()))
                            .forGetter(d -> new VillageGossipData(
                                    new LinkedHashMap<>(d.gossipTopicHeat))),
                    VillageScribalData.CODEC
                            .optionalFieldOf("scribalData",
                                    new VillageScribalData(new LinkedHashMap<>(), new LinkedHashMap<>()))
                            .forGetter(d -> new VillageScribalData(
                                    new LinkedHashMap<>(d.commissionQueues),
                                    new LinkedHashMap<>(d.libraryCatalogues))),
                    VillageDecorationData.CODEC
                            .optionalFieldOf("decorationData",
                                    new VillageDecorationData(List.of()))
                            .forGetter(d -> new VillageDecorationData(
                                    List.copyOf(d.decorationPlacements))),
                    VillageAdjunctData.CODEC
                            .optionalFieldOf("adjunctData",
                                    new VillageAdjunctData(List.of()))
                            .forGetter(d -> new VillageAdjunctData(
                                    List.copyOf(d.adjunctPlots.values()))),
                    VillageSubBuildingData.CODEC
                            .optionalFieldOf("subBuildingData",
                                    new VillageSubBuildingData(List.of()))
                            .forGetter(d -> new VillageSubBuildingData(
                                    List.copyOf(d.subBuildings.values()))),
                    VillageGardenData.CODEC
                            .optionalFieldOf("gardenData",
                                    new VillageGardenData(List.of()))
                            .forGetter(d -> new VillageGardenData(
                                    List.copyOf(d.gardenPlots.values()))),
                    VillageFarmData.CODEC
                            .optionalFieldOf("farmSectorData",
                                    new VillageFarmData(List.of()))
                            .forGetter(d -> new VillageFarmData(
                                    List.copyOf(d.farmSectors.values())))
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
            VillagePropertyData   propertyData,
            VillageGossipData     gossipData,
            VillageScribalData    scribalData,
            VillageDecorationData decorationData,
            VillageAdjunctData    adjunctData,
            VillageSubBuildingData subBuildingData,
            VillageGardenData     gardenData,
            VillageFarmData       farmSectorData) {

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
        economyData.seaRoutes().forEach(s -> data.seaRoutes.put(s.getConnectionId(), s));
        economyData.treasuries().forEach(t  -> data.treasuries.put(t.getVillageId(), t));
        economyData.marketStalls().forEach(s -> {
            data.marketStalls.add(s);
            data.stallIndex.put(s.getStallId(), s);
        });

        // Property
        propertyData.properties().forEach(data.playerProperties::add);
        data.propertyTaxRates.putAll(propertyData.propertyTaxRates());

        // Gossip
        if (gossipData != null) {
            data.gossipTopicHeat.putAll(gossipData.topicHeat());
        }

        // Scribal (Phase 2 task 17)
        if (scribalData != null) {
            data.commissionQueues.putAll(scribalData.commissionQueues());
            data.libraryCatalogues.putAll(scribalData.libraryCatalogues());
        }

        // Decoration (Phase 0b doc 01)
        if (decorationData != null) {
            data.decorationPlacements.addAll(decorationData.placements());
        }

        // Adjunct plots (Phase 0c doc 02). Authoritative store +
        // per-building denormalisation rebuilt from it.
        if (adjunctData != null) {
            for (var plot : adjunctData.plots()) {
                data.adjunctPlots.put(plot.plotId(), plot);
                data.adjunctPlotsByBuilding
                        .computeIfAbsent(plot.parentBuildingId(),
                                k -> new ArrayList<>())
                        .add(plot.plotId());
            }
        }

        // Subbuildings (Phase 0d doc 03). Authoritative store +
        // per-parent denormalisation rebuilt from it.
        if (subBuildingData != null) {
            for (var sub : subBuildingData.subBuildings()) {
                data.subBuildings.put(sub.subBuildingId(), sub);
                data.subBuildingsByParent
                        .computeIfAbsent(sub.parentBuildingId(),
                                k -> new ArrayList<>())
                        .add(sub.subBuildingId());
            }
        }

        // Garden plots (B2.4). Authoritative store + per-village
        // denormalisation rebuilt from it.
        if (gardenData != null) {
            for (var plot : gardenData.plots()) {
                data.gardenPlots.put(plot.plotId(), plot);
                data.gardenPlotsByVillage
                        .computeIfAbsent(plot.villageId(),
                                k -> new ArrayList<>())
                        .add(plot.plotId());
            }
        }

        // Farm sectors (B2.5). Authoritative store + per-village
        // denormalisation rebuilt from it.
        if (farmSectorData != null) {
            for (var sector : farmSectorData.sectors()) {
                data.farmSectors.put(sector.sectorId(), sector);
                data.farmSectorsByVillage
                        .computeIfAbsent(sector.villageId(),
                                k -> new ArrayList<>())
                        .add(sector.sectorId());
            }
        }

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
    private final Map<UUID, SeaRoute> seaRoutes = new HashMap<>();

    private final Map<UUID, VillageTreasury> treasuries = new LinkedHashMap<>();
    private final Map<UUID, FarmBusinessLevel> farmBusinessLevels = new HashMap<>();
    private final List<MarketStall> marketStalls     = new ArrayList<>();
    private final Map<UUID, BuildingEconomy> buildingEconomies = new LinkedHashMap<>();
    private final Map<UUID, MarketStall> stallIndex  = new HashMap<>();



    // Property
    private final List<PlayerHousingData.PlayerProperty> playerProperties = new ArrayList<>();
    private final Map<UUID, Long>                        propertyTaxRates = new HashMap<>();

    // Gossip (Phase 2 task 12)
    private final Map<UUID, GossipTopicHeat> gossipTopicHeat = new LinkedHashMap<>();

    // Scribal (Phase 2 task 17)
    private final Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue>
            commissionQueues = new LinkedHashMap<>();
    private final Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue>
            libraryCatalogues = new LinkedHashMap<>();

    // Decoration placements (Phase 0b — doc 01 §"DecorationPass").
    // Flat list keyed by villageId in each record; queried per-village
    // via getDecorationsForVillage. Persisted alongside the rest of
    // VillageSavedData so a village's decorative content survives
    // save/load.
    private final List<tterrag1112.life_in_the_village.Village.Decoration
            .Framework.DecorationPlacement> decorationPlacements = new ArrayList<>();

    // Adjunct plots (Phase 0c — doc 02). Authoritative store keyed
    // by plotId; the per-building denormalisation map is rebuilt
    // from this on load.
    private final Map<UUID, tterrag1112.life_in_the_village.Village
            .Decoration.Adjunct.AdjunctPlot> adjunctPlots = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> adjunctPlotsByBuilding = new HashMap<>();

    // Subbuildings (Phase 0d — doc 03). Authoritative store keyed by
    // subBuildingId; the per-parent denormalisation map is rebuilt
    // from this on load.
    private final Map<UUID, tterrag1112.life_in_the_village.Village
            .Decoration.Subbuilding.SubBuilding> subBuildings = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> subBuildingsByParent = new HashMap<>();

    // Garden plots (B2.4). Authoritative store keyed by plotId; the
    // per-village denormalisation map is rebuilt from this on load.
    private final Map<UUID, tterrag1112.life_in_the_village.Village
            .Decoration.Parks.GardenPlot> gardenPlots = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> gardenPlotsByVillage = new HashMap<>();

    // Farm sectors (B2.5). Authoritative store keyed by sectorId;
    // per-village denormalisation rebuilt on load.
    private final Map<UUID, tterrag1112.life_in_the_village.Village
            .Farms.FarmSector> farmSectors = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> farmSectorsByVillage = new HashMap<>();

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
        // Doc 02 §"Edge cases" — orphaned adjunct plots cleared
        // when their parent building goes away.
        removeAdjunctPlotsForBuilding(building.getId());
        // Doc 03 §"Edge cases" — orphaned subbuildings cleared
        // alongside their parent.
        removeSubBuildingsForBuilding(building.getId());
        setDirty();
    }
    public void removeVillage(UUID id) {
        villages.removeIf(v -> v.getId().equals(id));
        villageIndex.remove(id);
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
        boolean alreadyTracked = villageIndex.containsKey(village.getId());
        villages.add(village);
        villageIndex.put(village.getId(), village);
        setDirty();
        // Phase 3 task 06: queue a founding-elections pass so the daily
        // tick runs them on the next sweep. Newly founded villages would
        // otherwise wait up to one in-game day for their first leader.
        // Skip when re-adding an existing village (e.g. realisation
        // reload paths) — the saved holdings are already authoritative.
        if (!alreadyTracked) {
            pendingFoundingElections.add(village.getId());
        }
    }

    /**
     * Set of village UUIDs awaiting a founding-elections pass. Drained by
     * {@link tterrag1112.life_in_the_village.Npc.Office.OfficeElection#dailyTick}
     * on the next sweep, or by a same-tick caller via
     * {@link #drainPendingFoundingElections}.
     */
    private final java.util.LinkedHashSet<UUID> pendingFoundingElections = new java.util.LinkedHashSet<>();

    /**
     * Drains and returns the queued founding-election village ids. The
     * caller (typically the office election driver) is responsible for
     * running the elections.
     */
    public java.util.List<UUID> drainPendingFoundingElections() {
        if (pendingFoundingElections.isEmpty()) return java.util.List.of();
        java.util.List<UUID> out = java.util.List.copyOf(pendingFoundingElections);
        pendingFoundingElections.clear();
        return out;
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

    // ── Decoration placements (Phase 0b doc 01) ──────────────────────────

    /** All decoration placements for {@code villageId}. Returns an
     *  unmodifiable view; callers use
     *  {@link #addDecorationPlacement} / {@link
     *  #removeDecorationsForVillage} to mutate. */
    public List<tterrag1112.life_in_the_village.Village.Decoration.Framework
            .DecorationPlacement> getDecorationsForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration.Framework
                .DecorationPlacement> out = new ArrayList<>();
        for (var p : decorationPlacements) {
            if (villageId.equals(p.villageId())) out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    public void addDecorationPlacement(
            tterrag1112.life_in_the_village.Village.Decoration.Framework
                    .DecorationPlacement placement) {
        if (placement == null) return;
        decorationPlacements.add(placement);
        markDirty();
    }

    /** Removes every placement belonging to {@code villageId}. Used
     *  when a village is despawned or its decoration pass re-runs. */
    public void removeDecorationsForVillage(UUID villageId) {
        if (villageId == null) return;
        boolean removed = decorationPlacements.removeIf(
                p -> villageId.equals(p.villageId()));
        if (removed) markDirty();
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration.Framework
            .DecorationPlacement> getAllDecorationPlacements() {
        return Collections.unmodifiableList(decorationPlacements);
    }

    // ── Adjunct plots (Phase 0c doc 02) ──────────────────────────────────

    public void addAdjunctPlot(tterrag1112.life_in_the_village.Village
                                       .Decoration.Adjunct.AdjunctPlot plot) {
        if (plot == null) return;
        adjunctPlots.put(plot.plotId(), plot);
        adjunctPlotsByBuilding
                .computeIfAbsent(plot.parentBuildingId(),
                        k -> new ArrayList<>())
                .add(plot.plotId());
        markDirty();
    }

    public Optional<tterrag1112.life_in_the_village.Village.Decoration
            .Adjunct.AdjunctPlot> getAdjunctPlot(UUID plotId) {
        if (plotId == null) return Optional.empty();
        return Optional.ofNullable(adjunctPlots.get(plotId));
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Adjunct.AdjunctPlot> getAdjunctPlotsForBuilding(UUID buildingId) {
        if (buildingId == null) return List.of();
        List<UUID> ids = adjunctPlotsByBuilding.get(buildingId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .Adjunct.AdjunctPlot> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            var plot = adjunctPlots.get(id);
            if (plot != null) out.add(plot);
        }
        return Collections.unmodifiableList(out);
    }

    public Collection<tterrag1112.life_in_the_village.Village.Decoration
            .Adjunct.AdjunctPlot> getAllAdjunctPlots() {
        return Collections.unmodifiableCollection(adjunctPlots.values());
    }

    public void removeAdjunctPlot(UUID plotId) {
        if (plotId == null) return;
        var removed = adjunctPlots.remove(plotId);
        if (removed != null) {
            List<UUID> ids = adjunctPlotsByBuilding.get(removed.parentBuildingId());
            if (ids != null) {
                ids.remove(plotId);
                if (ids.isEmpty()) {
                    adjunctPlotsByBuilding.remove(removed.parentBuildingId());
                }
            }
            markDirty();
        }
    }

    public void removeAdjunctPlotsForBuilding(UUID buildingId) {
        if (buildingId == null) return;
        List<UUID> ids = adjunctPlotsByBuilding.remove(buildingId);
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) adjunctPlots.remove(id);
        markDirty();
    }

    // ── Subbuildings (Phase 0d doc 03) ───────────────────────────────────

    public void addSubBuilding(tterrag1112.life_in_the_village.Village
                                       .Decoration.Subbuilding.SubBuilding sub) {
        if (sub == null) return;
        subBuildings.put(sub.subBuildingId(), sub);
        subBuildingsByParent
                .computeIfAbsent(sub.parentBuildingId(),
                        k -> new ArrayList<>())
                .add(sub.subBuildingId());
        markDirty();
    }

    public Optional<tterrag1112.life_in_the_village.Village.Decoration
            .Subbuilding.SubBuilding> getSubBuilding(UUID subId) {
        if (subId == null) return Optional.empty();
        return Optional.ofNullable(subBuildings.get(subId));
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Subbuilding.SubBuilding> getSubBuildingsForBuilding(UUID parentId) {
        if (parentId == null) return List.of();
        List<UUID> ids = subBuildingsByParent.get(parentId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .Subbuilding.SubBuilding> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            var sub = subBuildings.get(id);
            if (sub != null) out.add(sub);
        }
        return Collections.unmodifiableList(out);
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Subbuilding.SubBuilding> getSubBuildingsOfType(
                    tterrag1112.life_in_the_village.Village.Decoration
                            .Subbuilding.SubBuildingType type) {
        if (type == null) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .Subbuilding.SubBuilding> out = new ArrayList<>();
        for (var sub : subBuildings.values()) {
            if (sub.type() == type) out.add(sub);
        }
        return Collections.unmodifiableList(out);
    }

    public Collection<tterrag1112.life_in_the_village.Village.Decoration
            .Subbuilding.SubBuilding> getAllSubBuildings() {
        return Collections.unmodifiableCollection(subBuildings.values());
    }

    // ── Garden plots (B2.4 — doc 09) ─────────────────────────────────────

    public void addGardenPlot(tterrag1112.life_in_the_village.Village
                                      .Decoration.Parks.GardenPlot plot) {
        if (plot == null) return;
        gardenPlots.put(plot.plotId(), plot);
        gardenPlotsByVillage
                .computeIfAbsent(plot.villageId(),
                        k -> new ArrayList<>())
                .add(plot.plotId());
        markDirty();
    }

    /** Replaces an existing plot (same {@code plotId}) so the
     *  renderer can update {@code preservedFeatures} /
     *  {@code composedPrimitives} after rendering without a
     *  remove-then-add dance. */
    public void updateGardenPlot(tterrag1112.life_in_the_village.Village
                                         .Decoration.Parks.GardenPlot plot) {
        if (plot == null) return;
        var prior = gardenPlots.get(plot.plotId());
        if (prior == null) {
            addGardenPlot(plot);
            return;
        }
        gardenPlots.put(plot.plotId(), plot);
        markDirty();
    }

    public Optional<tterrag1112.life_in_the_village.Village.Decoration
            .Parks.GardenPlot> getGardenPlot(UUID plotId) {
        if (plotId == null) return Optional.empty();
        return Optional.ofNullable(gardenPlots.get(plotId));
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Parks.GardenPlot> getGardenPlotsForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        List<UUID> ids = gardenPlotsByVillage.get(villageId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .Parks.GardenPlot> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            var plot = gardenPlots.get(id);
            if (plot != null) out.add(plot);
        }
        return Collections.unmodifiableList(out);
    }

    public Collection<tterrag1112.life_in_the_village.Village.Decoration
            .Parks.GardenPlot> getAllGardenPlots() {
        return Collections.unmodifiableCollection(gardenPlots.values());
    }

    public void removeGardenPlotsForVillage(UUID villageId) {
        if (villageId == null) return;
        List<UUID> ids = gardenPlotsByVillage.remove(villageId);
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) gardenPlots.remove(id);
        markDirty();
    }

    // ── Farm sectors (B2.5 — doc 10) ─────────────────────────────────────

    public void addFarmSector(tterrag1112.life_in_the_village.Village
                                      .Farms.FarmSector sector) {
        if (sector == null) return;
        farmSectors.put(sector.sectorId(), sector);
        farmSectorsByVillage
                .computeIfAbsent(sector.villageId(),
                        k -> new ArrayList<>())
                .add(sector.sectorId());
        markDirty();
    }

    public Optional<tterrag1112.life_in_the_village.Village
            .Farms.FarmSector> getFarmSector(UUID sectorId) {
        if (sectorId == null) return Optional.empty();
        return Optional.ofNullable(farmSectors.get(sectorId));
    }

    public List<tterrag1112.life_in_the_village.Village
            .Farms.FarmSector> getFarmSectorsForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        List<UUID> ids = farmSectorsByVillage.get(villageId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<tterrag1112.life_in_the_village.Village
                .Farms.FarmSector> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            var s = farmSectors.get(id);
            if (s != null) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    public Collection<tterrag1112.life_in_the_village.Village
            .Farms.FarmSector> getAllFarmSectors() {
        return Collections.unmodifiableCollection(farmSectors.values());
    }

    public void removeFarmSectorsForVillage(UUID villageId) {
        if (villageId == null) return;
        List<UUID> ids = farmSectorsByVillage.remove(villageId);
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) farmSectors.remove(id);
        markDirty();
    }

    public void removeSubBuilding(UUID subId) {
        if (subId == null) return;
        var removed = subBuildings.remove(subId);
        if (removed != null) {
            List<UUID> ids = subBuildingsByParent.get(removed.parentBuildingId());
            if (ids != null) {
                ids.remove(subId);
                if (ids.isEmpty()) {
                    subBuildingsByParent.remove(removed.parentBuildingId());
                }
            }
            markDirty();
        }
    }

    public void removeSubBuildingsForBuilding(UUID parentId) {
        if (parentId == null) return;
        List<UUID> ids = subBuildingsByParent.remove(parentId);
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) subBuildings.remove(id);
        markDirty();
    }

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

    /**
     * Phase 5 doc 32 archival: drop ENDED / CANCELLED / DISRUPTED events
     * once they're older than 365 in-game days (8.76M ticks). Earlier
     * Phase-3 events (which never recorded {@code completedTick}) fall
     * back to {@code endTick} so legacy data still gets pruned.
     */
    public void pruneOldCompletedEvents(long currentTick) {
        long maxAge = 365L * 24000L;
        boolean changed = events.removeIf(e -> {
            if (e.getStatus() == VillageEvent.EventStatus.ACTIVE) return false;
            if (e.getStatus() == VillageEvent.EventStatus.ANNOUNCED) return false;
            long doneAt = e.getCompletedTick() > 0 ? e.getCompletedTick() : e.getEndTick();
            return currentTick - doneAt > maxAge;
        });
        if (changed) setDirty();
    }

    public Optional<VillageEvent> getEventById(UUID eventId) {
        return events.stream().filter(e -> e.getId().equals(eventId)).findFirst();
    }

    // =========================================================================
    // Guilds
    // =========================================================================

    public void addGuild(GuildData guild) { guilds.add(guild); setDirty(); }

    public List<GuildData> getAllGuilds() { return List.copyOf(guilds); }

    /**
     * Replaces an existing guild record (matched by {@code guildId}) with
     * the supplied one. Used after office mutations on the immutable
     * GuildData record so the change actually persists.
     */
    public void replaceGuild(GuildData updated) {
        for (int i = 0; i < guilds.size(); i++) {
            if (guilds.get(i).guildId().equals(updated.guildId())) {
                guilds.set(i, updated);
                setDirty();
                return;
            }
        }
    }

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
    // Trade routes (land routes are graph-backed; sea routes use SeaRoute)
    // =========================================================================

    public void addTradeRoute(TradeRoute route) { tradeRoutes.put(route.getRouteId(), route); setDirty(); }

    public Optional<TradeRoute> getRouteById(UUID id) { return Optional.ofNullable(tradeRoutes.get(id)); }

    public List<TradeRoute> getAllTradeRoutes() { return new ArrayList<>(tradeRoutes.values()); }

    public Optional<TradeRoute> getRouteBetween(UUID a, UUID b) {
        return tradeRoutes.values().stream().filter(r -> r.connects(a, b)).findFirst();
    }

    public List<TradeRoute> getRoutesForVillage(UUID villageId) {
        return tradeRoutes.values().stream()
                .filter(r -> r.getVillageA().equals(villageId)
                        || r.getVillageB().equals(villageId))
                .collect(Collectors.toList());
    }

    public void removeTradeRoute(UUID id) { tradeRoutes.remove(id); setDirty(); }

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


    // =========================================================================
    // Village treasury
    // =========================================================================

    public Optional<VillageTreasury> getTreasury(UUID villageId) {
        return Optional.ofNullable(treasuries.get(villageId));
    }

    public void putTreasury(VillageTreasury treasury) {
        treasuries.put(treasury.getVillageId(), treasury);
        setDirty();
    }

    public void removeTreasury(UUID villageId) {
        treasuries.remove(villageId);
        setDirty();
    }

    public Collection<VillageTreasury> getAllTreasuries() {
        return Collections.unmodifiableCollection(treasuries.values());
    }
    public FarmBusinessLevel getOrCreateFarmBusinessLevel(UUID farmhouseId) {
        return farmBusinessLevels.computeIfAbsent(farmhouseId, FarmBusinessLevel::new);
    }

    public Optional<FarmBusinessLevel> getFarmBusinessLevel(UUID farmhouseId) {
        return Optional.ofNullable(farmBusinessLevels.get(farmhouseId));
    }

    public void updateFarmBusinessLevel(FarmBusinessLevel level) {
        farmBusinessLevels.put(level.getFarmhouseId(), level);
        setDirty();
    }
    // =========================================================================
// Market stalls
// =========================================================================

    public void addMarketStall(MarketStall stall) {
        marketStalls.add(stall);
        stallIndex.put(stall.getStallId(), stall);
        setDirty();
    }

    public void removeMarketStall(UUID stallId) {
        stallIndex.computeIfPresent(stallId, (k, v) -> {
            marketStalls.remove(v);
            return null;
        });
        setDirty();
    }

    public Optional<MarketStall> getStallById(UUID stallId) {
        return Optional.ofNullable(stallIndex.get(stallId));
    }

    public List<MarketStall> getStallsForMarket(UUID marketBuildingId) {
        return marketStalls.stream()
                .filter(s -> s.getMarketBuildingId().equals(marketBuildingId))
                .toList();
    }

    public List<MarketStall> getStallsForVillage(UUID villageId) {
        return getVillageById(villageId)
                .map(v -> v.getBuildingIds().stream()
                        .flatMap(bid -> getStallsForMarket(bid).stream())
                        .toList())
                .orElse(List.of());
    }

    public Optional<MarketStall> getStallByOwner(UUID ownerUUID) {
        return marketStalls.stream()
                .filter(s -> s.isActive() && s.getOwnerUUID().equals(ownerUUID))
                .findFirst();
    }

    public List<HouseholdData> getHouseholdsForVillage(UUID villageId) {
        return getVillageById(villageId)
                .map(v -> v.getBuildingIds().stream()
                        .map(this::getHouseholdForBuilding)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList())
                .orElse(List.of());
    }
    public Optional<BuildingEconomy> getBuildingEconomy(UUID buildingId) {
        return Optional.ofNullable(buildingEconomies.get(buildingId));
    }
    public BuildingEconomy getOrCreateBuildingEconomy(UUID buildingId) {
        return buildingEconomies.computeIfAbsent(buildingId,
                id -> {
                    // Seed on first creation only (computeIfAbsent
                    // guarantees this lambda runs once). Look up the
                    // building type from the index; degrade to 0L when
                    // the building isn't tracked yet (caller is racing
                    // against placement).
                    Building b = buildingIndex.get(id);
                    long seed = b == null
                            ? 0L
                            : tterrag1112.life_in_the_village.Village.Economy
                                    .BuildingStarterTable.seedFor(b.getType());
                    return BuildingEconomy.create(id, seed);
                });
    }

    public UUID reserveIdleMerchant(UUID villageId, ServerLevel level) {
        var village = getVillageById(villageId).orElse(null);
        if (village == null) return null;

        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob mob)) continue;
            if (mob.getProfession() != Profession.MERCHANT) continue;
            if (mob.isAway()) continue;

            // Village match — check by name since TownspersonMob stores
            // assignedVillageName rather than an ID. Defensive fallback:
            // also check building assignment if the village name differs
            // (e.g. village was renamed).
            String assignedName = mob.getAssignedVillageName().orElse(null);
            boolean byName = assignedName != null && assignedName.equals(village.getName());

            boolean byBuilding = mob.getAssignedBuildingId()
                    .flatMap(this::getBuildingById)
                    .map(b -> village.getBuildingIds().contains(b.getId()))
                    .orElse(false);

            if (byName || byBuilding) {
                return mob.getUUID();
            }
        }
        return null;
    }
    public void addSeaRoute(SeaRoute route) {
        seaRoutes.put(route.getConnectionId(), route);
        setDirty();
    }

    public void removeSeaRoute(UUID id) {
        if (seaRoutes.remove(id) != null) setDirty();
    }

    public Optional<SeaRoute> getSeaRouteById(UUID id) {
        return Optional.ofNullable(seaRoutes.get(id));
    }

    public List<SeaRoute> getAllSeaRoutes() {
        return List.copyOf(seaRoutes.values());
    }

    // =========================================================================
    // Gossip (Phase 2 task 12)
    // =========================================================================

    /** Returns (lazily creating) the heat tracker for the given village. */
    public GossipTopicHeat getOrCreateGossipHeat(UUID villageId) {
        return gossipTopicHeat.computeIfAbsent(villageId, k -> {
            setDirty();
            return new GossipTopicHeat();
        });
    }

    public Optional<GossipTopicHeat> getGossipHeat(UUID villageId) {
        return Optional.ofNullable(gossipTopicHeat.get(villageId));
    }

    public Map<UUID, GossipTopicHeat> getAllGossipHeat() {
        return Collections.unmodifiableMap(gossipTopicHeat);
    }

    // =========================================================================
    // Scribal (Phase 2 task 17)
    // =========================================================================

    public tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue
            getOrCreateCommissionQueue(UUID workshopBuildingId) {
        return commissionQueues.computeIfAbsent(workshopBuildingId, id -> {
            setDirty();
            return new tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue(id);
        });
    }

    public Optional<tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue>
            getCommissionQueue(UUID workshopBuildingId) {
        return Optional.ofNullable(commissionQueues.get(workshopBuildingId));
    }

    public Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue>
            getAllCommissionQueues() {
        return Collections.unmodifiableMap(commissionQueues);
    }

    public tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue
            getOrCreateLibraryCatalogue(UUID libraryBuildingId) {
        return libraryCatalogues.computeIfAbsent(libraryBuildingId, id -> {
            setDirty();
            return new tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue(id);
        });
    }

    public Optional<tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue>
            getLibraryCatalogue(UUID libraryBuildingId) {
        return Optional.ofNullable(libraryCatalogues.get(libraryBuildingId));
    }

    public Map<UUID, tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue>
            getAllLibraryCatalogues() {
        return Collections.unmodifiableMap(libraryCatalogues);
    }

    /**
     * Looks up a trade connection by UUID. Only sea routes carry a connection
     * ID; land routes are addressed by {@link TradeRoute#getEdgeIds()} on the
     * road graph and are not represented here.
     */
    public Optional<TradeConnection> getConnectionById(UUID id) {
        SeaRoute sea = seaRoutes.get(id);
        if (sea != null) return Optional.of(sea);
        return Optional.empty();
    }
}

