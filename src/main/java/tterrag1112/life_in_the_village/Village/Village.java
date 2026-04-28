// src/main/java/tterrag1112/life_in_the_village/Village/Village.java
package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Roads.Economy.EdgeTierManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeed;

import java.util.*;

public class Village {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    private static final int REPUTATION_HOSTILE_THRESHOLD = -50;
    private static final int GUARD_POST_THRESHOLD         = 5;

    // =========================================================================
    // CORE FIELDS
    // =========================================================================

    private final UUID   id;
    private String       name;
    private final List<UUID>           buildingIds;
    private final List<BlockPos>       guardPosts;
    private final Map<UUID, Integer>   playerReputations;
    private Map<String, String>        preferredArmor;
    private long                       treasuryBronze = 0L;
    @Nullable private UUID             villageLeaderId = null;

    /**
     * Office state for this village. Phase 0 storage only — see
     * {@code docs/npc_redesign/06-office-framework.md}. Stays in sync with
     * the legacy {@link #villageLeaderId} during the migration window;
     * Phase 3 cuts over.
     */
    private tterrag1112.life_in_the_village.Npc.Office.OfficeState offices;

    /**
     * Per-village law state. Spec {@code 22-village-laws.md}. Lazy-init
     * to an empty {@link tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy}
     * so callers can always {@code village.getPolicy().hasLaw(...)} without
     * a null-check; the codec rebuilds the same field on load.
     */
    private tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy policy =
            new tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy();

    /** Transient: last computed size tier. Null until first access; recomputed after building count changes. */
    @Nullable private transient VillageSizeTier storedSizeTier = null;

    // Needs — recomputed daily, only lastNeedsUpdate persisted
    private Map<NeedCategory, VillageNeed> needs = new EnumMap<>(NeedCategory.class);
    private long lastNeedsUpdate = -1L;
    private VillagePath.PathTier pathTier;

    @Nullable private BlockPos mainGateEndpoint;

    /** Road graph: the UUID of the VILLAGE_DOCK node this village is wired to. Null until Phase 3a seeding. */
    @Nullable private UUID dockNodeId;

    /** True for villages created at or after Phase 3b; the graph connector system routes their connectors. */
    private boolean useGraphConnector = true;

    // =========================================================================
    // LAYOUT METADATA — persisted so the expansion system can use them
    // =========================================================================

    /**
     * The solid-surface Y position at the centre of the village.
     * Equals the origin passed to {@link
     * tterrag1112.life_in_the_village.Village.Planning.VillagePlanner#plan}.
     * Null before first spawn.
     */
    @Nullable private BlockPos villageCentre;

    /**
     * True if the village has been fully realised — buildings placed,
     * NPCs spawned, decorations applied. False for virtual villages
     * that exist in save data but have not yet had their chunks touched.
     */
    private boolean realised = true;

    /**
     * Origin position chosen at plan time. For realised villages this
     * equals {@link #villageCentre}. For unrealised villages this is
     * the only spatial information we have — it's what the realisation
     * trigger uses to test player proximity.
     */
    @Nullable private BlockPos plannedOrigin;

    private String villageType;

    /**
     * Centre of the town square building.
     * Used by expansion paths as the default hub.
     */
    @Nullable private BlockPos townSquarePos;

    /**
     * The hub position that all internal paths radiate from.
     * Usually equals {@link #townSquarePos}; may differ if the
     * square is unavailable.
     */
    @Nullable private BlockPos pathHubPos;

    /**
     * Doc 04 §"Tier scaling" — the plaza's half-extent (so the actual
     * plaza is {@code 2 * townSquareRadius + 1} blocks per side). Set
     * by {@code TownSquareComposer} at decoration time. 0 means the
     * plaza geometry hasn't been resolved yet (legacy saves loaded
     * before Phase 1 land here). Consumers that need the radius
     * should fall through to {@link tterrag1112.life_in_the_village
     * .Village.Decoration.TownSquare.TownSquareTier#RADIUS_HAMLET}
     * when the field reads 0.
     */
    private int townSquareRadius = 0;

    /**
     * Doc 04 §"NPC gathering points" — durable named positions
     * registered by {@code TownSquareComposer} at plaza time and
     * consumed by NPC Phase 2 hobby goals. Empty for legacy saves
     * and for villages whose plaza hasn't been composed yet.
     */
    private final List<tterrag1112.life_in_the_village.Village.Decoration
            .TownSquare.GatheringPoint> gatheringPoints = new ArrayList<>();

    /** Inner ring radius used by the planner. 0 = not yet set. */
    private int ring1Radius = 0;

    /** Outer ring radius used by the planner. 0 = not yet set. */
    private int ring2Radius = 0;

    private final List<BlockPos> capitalGatePositions = new ArrayList<>();

    public void addGatePosition(BlockPos pos) {
        if (!capitalGatePositions.contains(pos))
            capitalGatePositions.add(pos);
    }

    public List<BlockPos> getCapitalGatePositions() {
        return Collections.unmodifiableList(capitalGatePositions);
    }

    public boolean hasCapitalGates() {
        return !capitalGatePositions.isEmpty();
    }

    public BlockPos nearestCapitalGate(int x, int z) {
        BlockPos best  = null;
        double   bestD = Double.MAX_VALUE;
        for (BlockPos gate : capitalGatePositions) {
            double dx = gate.getX() - x, dz = gate.getZ() - z;
            double d  = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = gate; }
        }
        return best;
    }

    /**
     * The village level (1–10) used at spawn time.
     * Incremented by {@link
     * tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager}
     * as the village grows.
     */
    private int currentLevel = 1;

    // ── Nested layout record ─────────────────────────────────────────────────
    // Groups all planner-generated metadata into one codec entry,
    // freeing up RecordCodecBuilder slots and keeping layout fields together.
    private record VillageLayoutMeta(
            Optional<BlockPos> villageCentre,
            Optional<BlockPos> townSquarePos,
            Optional<BlockPos> pathHubPos,
            int  ring1Radius,
            int  ring2Radius,
            int  currentLevel,
            List<BlockPos> patrolWaypoints,
            long lastLevelUpTick,
            List<BlockPos> capitalGatePositions,
            boolean realised,
            Optional<BlockPos> plannedOrigin,
            Optional<BlockPos> mainGateEndpoint,
            int townSquareRadius,
            List<tterrag1112.life_in_the_village.Village.Decoration
                    .TownSquare.GatheringPoint> gatheringPoints
    ) {
        static final Codec<VillageLayoutMeta> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC
                                .optionalFieldOf("villageCentre")
                                .forGetter(VillageLayoutMeta::villageCentre),
                        BlockPos.CODEC
                                .optionalFieldOf("townSquarePos")
                                .forGetter(VillageLayoutMeta::townSquarePos),
                        BlockPos.CODEC
                                .optionalFieldOf("pathHubPos")
                                .forGetter(VillageLayoutMeta::pathHubPos),
                        Codec.INT
                                .optionalFieldOf("ring1Radius", 0)
                                .forGetter(VillageLayoutMeta::ring1Radius),
                        Codec.INT
                                .optionalFieldOf("ring2Radius", 0)
                                .forGetter(VillageLayoutMeta::ring2Radius),
                        Codec.INT
                                .optionalFieldOf("currentLevel", 1)
                                .forGetter(VillageLayoutMeta::currentLevel),
                        BlockPos.CODEC.listOf()
                                .optionalFieldOf("patrolWaypoints",
                                        new ArrayList<>())
                                .forGetter(VillageLayoutMeta::patrolWaypoints),
                        Codec.LONG
                                .optionalFieldOf("lastLevelUpTick", 0L)
                                .forGetter(VillageLayoutMeta::lastLevelUpTick),
                        BlockPos.CODEC.listOf()
                                .optionalFieldOf("capitalGatePositions",
                                        new ArrayList<>())
                                .forGetter(VillageLayoutMeta::capitalGatePositions),
                        Codec.BOOL
                                .optionalFieldOf("realised", true)
                                .forGetter(VillageLayoutMeta::realised),
                        BlockPos.CODEC
                                .optionalFieldOf("plannedOrigin")
                                .forGetter(VillageLayoutMeta::plannedOrigin),
                        BlockPos.CODEC
                                .optionalFieldOf("mainGateEndpoint")
                                .forGetter(VillageLayoutMeta::mainGateEndpoint),
                        Codec.INT
                                .optionalFieldOf("townSquareRadius", 0)
                                .forGetter(VillageLayoutMeta::townSquareRadius),
                        tterrag1112.life_in_the_village.Village.Decoration
                                .TownSquare.GatheringPoint.CODEC.listOf()
                                .optionalFieldOf("gatheringPoints",
                                        new ArrayList<>())
                                .forGetter(VillageLayoutMeta::gatheringPoints)
                ).apply(i, VillageLayoutMeta::new));

        static VillageLayoutMeta empty() {
            return new VillageLayoutMeta(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    0, 0, 1,
                    new ArrayList<>(), 0L, new ArrayList<>(),
                    true, Optional.empty(), Optional.empty(),
                    0, new ArrayList<>());
        }

        static VillageLayoutMeta from(Village v) {
            return new VillageLayoutMeta(
                    Optional.ofNullable(v.villageCentre),
                    Optional.ofNullable(v.townSquarePos),
                    Optional.ofNullable(v.pathHubPos),
                    v.ring1Radius,
                    v.ring2Radius,
                    v.currentLevel,
                    new ArrayList<>(v.patrolWaypoints),
                    v.lastLevelUpTick,
                    new ArrayList<>(v.capitalGatePositions),
                    v.realised,
                    Optional.ofNullable(v.plannedOrigin),
                    Optional.ofNullable(v.mainGateEndpoint),
                    v.townSquareRadius,
                    new ArrayList<>(v.gatheringPoints));
        }

        void applyTo(Village v) {
            villageCentre.ifPresent(p -> v.villageCentre = p);
            townSquarePos.ifPresent(p -> v.townSquarePos  = p);
            pathHubPos.ifPresent(p    -> v.pathHubPos     = p);
            v.ring1Radius    = ring1Radius;
            v.ring2Radius    = ring2Radius;
            v.currentLevel   = currentLevel;
            if (!patrolWaypoints.isEmpty()) {
                v.patrolWaypoints.addAll(patrolWaypoints);
            }
            v.lastLevelUpTick = lastLevelUpTick;
            if (!capitalGatePositions.isEmpty()) {
                v.capitalGatePositions.addAll(capitalGatePositions);
            }
            v.realised      = realised;
            v.plannedOrigin = plannedOrigin.orElse(null);
            mainGateEndpoint.ifPresent(p -> v.mainGateEndpoint = p);
            v.townSquareRadius = townSquareRadius;
            if (!gatheringPoints.isEmpty()) {
                v.gatheringPoints.clear();
                v.gatheringPoints.addAll(gatheringPoints);
            }
        }
    }

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public Village(String name, UUID id,
                   List<UUID> buildingIds, List<BlockPos> guardPosts,
                   Map<UUID, Integer> playerReputations,
                   Map<String, String> preferredArmor, String villageType) {
        this.name              = name;
        this.id                = id;
        this.buildingIds       = new ArrayList<>(buildingIds);
        this.guardPosts        = new ArrayList<>(guardPosts);
        this.playerReputations = new HashMap<>(playerReputations);
        this.preferredArmor    = new HashMap<>(preferredArmor);
        this.needs             = new EnumMap<>(NeedCategory.class);
        this.lastNeedsUpdate   = -1L;
        this.villageType       = villageType;
        this.offices           = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.VILLAGE, this.id);
    }

    public Village(String name, String villageType) {
        this(name, UUID.randomUUID(),
                new ArrayList<>(), new ArrayList<>(),
                new HashMap<>(), new HashMap<>(), villageType);
    }

    /**
     * Convenience constructor — creates a village with the default type.
     * Used by the planning system, which sets the real type immediately
     * after via {@link #setVillageType}.
     */
    public Village(String name) {
        this(name, "default");
    }

    // =========================================================================
    // CODEC
    // =========================================================================

    public static final Codec<Village> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING
                            .fieldOf("name")
                            .forGetter(Village::getName),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id")
                            .forGetter(Village::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("buildingIds", new ArrayList<>())
                            .forGetter(v -> new ArrayList<>(v.buildingIds)),
                    BlockPos.CODEC.listOf()
                            .optionalFieldOf("guardPosts", new ArrayList<>())
                            .forGetter(v -> new ArrayList<>(v.guardPosts)),
                    Codec.unboundedMap(
                                    Codec.STRING.xmap(UUID::fromString, UUID::toString),
                                    Codec.INT)
                            .optionalFieldOf("playerReputations", new HashMap<>())
                            .forGetter(v -> v.playerReputations),
                    Codec.unboundedMap(Codec.STRING, Codec.STRING)
                            .optionalFieldOf("preferredArmor", new HashMap<>())
                            .forGetter(v -> v.preferredArmor),
                    Codec.LONG
                            .optionalFieldOf("lastNeedsUpdate", -1L)
                            .forGetter(Village::getLastNeedsUpdate),
                    Codec.LONG
                            .optionalFieldOf("treasuryBronze", 0L)
                            .forGetter(Village::getTreasuryBronze),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("villageLeaderId")
                            .forGetter(v -> Optional.ofNullable(v.villageLeaderId)),
                    // All layout/patrol/aging fields grouped into one entry
                    VillageLayoutMeta.CODEC
                            .optionalFieldOf("layoutMeta", VillageLayoutMeta.empty())
                            .forGetter(VillageLayoutMeta::from),
                    Codec.STRING
                            .optionalFieldOf("villageType", "default")
                            .forGetter(Village::getVillageType),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("dockNodeId")
                            .forGetter(v -> Optional.ofNullable(v.dockNodeId)),
                    Codec.BOOL
                            .optionalFieldOf("useGraphConnector", false)
                            .forGetter(Village::useGraphConnector),
                    tterrag1112.life_in_the_village.Npc.Office.OfficeState.CODEC
                            .optionalFieldOf("offices")
                            .forGetter(v -> Optional.ofNullable(v.offices)),
                    tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy.CODEC
                            .optionalFieldOf("villageLaws", new tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy())
                            .forGetter(v -> v.policy != null
                                    ? v.policy
                                    : new tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy())
            ).apply(instance, (name, id, buildingIds, guardPosts,
                               reputations, armor, lastNeedsUpdate,
                               treasuryBronze, villageLeaderId,
                               layoutMeta, villageType, dockNodeId,
                               useGraphConnector, offices, policy) -> {
                Village v = new Village(name, id,
                        new ArrayList<>(buildingIds),
                        new ArrayList<>(guardPosts),
                        new HashMap<>(reputations),
                        new HashMap<>(armor), villageType);
                v.setLastNeedsUpdate(lastNeedsUpdate);
                v.treasuryBronze = treasuryBronze;
                villageLeaderId.ifPresent(v::setVillageLeaderId);
                layoutMeta.applyTo(v);
                dockNodeId.ifPresent(v::setDockNodeId);
                v.useGraphConnector = useGraphConnector;
                // OfficeState load: stored value wins; otherwise migrate the
                // legacy villageLeaderId into a vacant-by-default state.
                if (offices.isPresent()) {
                    v.offices = offices.get();
                } else {
                    v.offices = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                            .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.VILLAGE, v.id);
                    if (v.villageLeaderId != null) {
                        v.offices.set(
                                tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_LEADER,
                                tterrag1112.life_in_the_village.Npc.Office.OfficeHolding.heldByNpc(
                                        tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_LEADER,
                                        v.id, v.villageLeaderId, 0L, 0L,
                                        tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.ASCENSION));
                    }
                }
                v.policy = policy != null ? policy : new tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy();
                return v;
            })
    );

    /** Office state for this village; never {@code null} after construction. */
    public tterrag1112.life_in_the_village.Npc.Office.OfficeState getOffices() {
        return offices;
    }

    /** Per-village laws; never {@code null} after construction. */
    public tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy getPolicy() {
        return policy;
    }

    // =========================================================================
    // IDENTITY
    // =========================================================================

    public UUID   getId()   { return id;   }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Optional<UUID> getVillageLeaderId() {
        return Optional.ofNullable(villageLeaderId);
    }
    public void setVillageLeaderId(UUID id) { this.villageLeaderId = id; }

    // =========================================================================
    // LAYOUT METADATA
    // =========================================================================

    public @Nullable BlockPos getVillageCentre()  { return villageCentre; }
    public @Nullable BlockPos getTownSquarePos()  { return townSquarePos; }
    public @Nullable BlockPos getPathHubPos()     { return pathHubPos;    }
    public int getRing1Radius()                   { return ring1Radius;   }
    public int getRing2Radius()                   { return ring2Radius;   }
    public int getCurrentLevel()                  { return currentLevel;  }

    public void setVillageCentre(BlockPos pos)    { this.villageCentre = pos; }
    public void setTownSquarePos(BlockPos pos)    { this.townSquarePos  = pos; }
    public void setPathHubPos(BlockPos pos)       { this.pathHubPos     = pos; }
    public void setRing1Radius(int r)             { this.ring1Radius    = r;   }
    public void setRing2Radius(int r)             { this.ring2Radius    = r;   }
    public void setCurrentLevel(int level)        { this.currentLevel   = level; }

    public boolean isRealised() { return realised; }
    public void setRealised(boolean r) { this.realised = r; }

    @Nullable public BlockPos getPlannedOrigin() { return plannedOrigin; }
    public void setPlannedOrigin(@Nullable BlockPos pos) { this.plannedOrigin = pos; }
    /**
     * The outward endpoint of the main road — the village's "front gate."
     * Trade routes prefer this as the handoff point instead of the
     * village centre. Null for villages that don't have a main road
     * (e.g. clustered layouts where the trade route simply enters the
     * nearest perimeter path).
     */
    @Nullable public BlockPos getMainGateEndpoint() { return mainGateEndpoint; }
    public void setMainGateEndpoint(@Nullable BlockPos pos) { this.mainGateEndpoint = pos; }

    // ── Plaza geometry + gathering points (Phase 1 doc 04) ──────────────

    public int getTownSquareRadius() { return townSquareRadius; }
    public void setTownSquareRadius(int r) { this.townSquareRadius = r; }

    public void addGatheringPoint(tterrag1112.life_in_the_village.Village
                                          .Decoration.TownSquare.GatheringPoint gp) {
        if (gp == null) return;
        gatheringPoints.add(gp);
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .TownSquare.GatheringPoint> getGatheringPoints() {
        return Collections.unmodifiableList(gatheringPoints);
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .TownSquare.GatheringPoint> getGatheringPointsByKind(
                    tterrag1112.life_in_the_village.Village.Decoration
                            .TownSquare.GatheringPointKind kind) {
        if (kind == null) return List.of();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .TownSquare.GatheringPoint> out = new ArrayList<>();
        for (var gp : gatheringPoints) {
            if (gp.kind() == kind) out.add(gp);
        }
        return Collections.unmodifiableList(out);
    }

    public boolean removeGatheringPoint(UUID id) {
        if (id == null) return false;
        return gatheringPoints.removeIf(gp -> gp.id().equals(id));
    }

    public void clearGatheringPoints() { gatheringPoints.clear(); }

    public Optional<UUID> getDockNodeId() { return Optional.ofNullable(dockNodeId); }
    public void setDockNodeId(UUID id) { this.dockNodeId = id; }

    public boolean useGraphConnector() { return useGraphConnector; }

    /**
     * Returns the best known position for this village — the realised
     * centre if available, otherwise the planned origin. Useful for
     * distance checks that need to work on both planned and realised
     * villages (e.g. minimum-separation tests during seeding).
     */
    @Nullable
    public BlockPos getAnchorPos() {
        return villageCentre != null ? villageCentre : plannedOrigin;
    }

    /**
     * Convenience: set all layout fields from a completed
     * {@link tterrag1112.life_in_the_village.Village.Planning.VillageLayout}.
     * Called by {@link
     * tterrag1112.life_in_the_village.Village.VillageSpawner} after planning.
     */
    public void applyLayout(
            tterrag1112.life_in_the_village.Village.Planning.VillageLayout layout,
            int villageLevel) {
        this.villageCentre = layout.getCenter();
        this.townSquarePos = layout.getTownSquarePos();
        this.pathHubPos    = layout.getTownSquarePos() != null
                ? layout.getTownSquarePos()
                : layout.getCenter();
        this.ring1Radius   = layout.getDensity().getRing1Radius();
        this.ring2Radius   = layout.getDensity().getRing2Radius();
        this.currentLevel  = villageLevel;
        this.mainGateEndpoint = layout.getMainGateEndpoint();
        // Doc 04 §"Tier scaling" — carry the layout-time plaza
        // half-extent onto the persisted Village so DecorationPass
        // can size sub-slots without round-tripping through
        // VillageLayout.
        this.townSquareRadius = layout.getTownSquareRadius();
        for (BlockPos gp : layout.getGatePositions()) {
            if (!capitalGatePositions.contains(gp)) {
                capitalGatePositions.add(gp);
            }
        }
    }

    /**
     * Returns the best hub position for routing expansion paths.
     * Prefers the town square; falls back to village centre; falls back
     * to the village AABB centre if neither is recorded.
     */
    public BlockPos getEffectivePathHub(VillageSavedData data) {
        if (pathHubPos != null) return pathHubPos;
        if (townSquarePos != null) return townSquarePos;
        if (villageCentre != null) return villageCentre;
        return getBounds(data)
                .map(b -> BlockPos.containing(b.getCenter()))
                .orElse(BlockPos.ZERO);
    }

    // =========================================================================
    // BUILDINGS
    // =========================================================================

    public List<UUID> getBuildingIds() { return buildingIds; }

    public List<UUID> getBuildingIdsView() {
        return Collections.unmodifiableList(buildingIds);
    }

    public void addBuilding(Building building) {
        if (!buildingIds.contains(building.getId())) {
            buildingIds.add(building.getId());
        }
    }

    public void removeBuilding(Building building) {
        buildingIds.remove(building.getId());
    }

    // =========================================================================
    // SIZE TIER — lazy transient cache + tier-change hook
    // =========================================================================

    /** Returns the current size tier, computed from the current building count. */
    public VillageSizeTier getSizeTier() {
        if (storedSizeTier == null) {
            storedSizeTier = VillageSizeTier.fromBuildingCount(buildingIds.size());
        }
        return storedSizeTier;
    }

    /**
     * Call this whenever the building count changes and a ServerLevel is available.
     * Recomputes the size tier and, if it changed, fires {@link #onSizeTierChanged}.
     */
    public void checkAndFireTierChangeHook(ServerLevel level) {
        VillageSizeTier newTier = VillageSizeTier.fromBuildingCount(buildingIds.size());
        VillageSizeTier oldTier = getSizeTier();
        storedSizeTier = newTier;
        if (oldTier != newTier) {
            onSizeTierChanged(oldTier, newTier, level);
        }
    }

    private void onSizeTierChanged(VillageSizeTier oldTier, VillageSizeTier newTier, ServerLevel level) {
        VillageSavedData vData   = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        java.util.List<tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge> changed =
                EdgeTierManager.reconcileEdgesForVillage(this, rData.getGraph(), vData);
        if (!changed.isEmpty()) {
            rData.markDirty();
        }
        System.out.println("[TierPromotion] Village " + getName()
                + " size " + oldTier + " → " + newTier
                + "; " + changed.size() + " edge(s) updated");
    }

    // =========================================================================
    // SPATIAL
    // =========================================================================

    private long lastLevelUpTick = 0L;

    public long getLastLevelUpTick()            { return lastLevelUpTick; }
    public void setLastLevelUpTick(long tick)   { this.lastLevelUpTick = tick; }

    public Optional<AABB> getBounds(VillageSavedData data) {
        List<Building> buildings = buildingIds.stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return Optional.empty();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Building b : buildings) {
            BlockPos min = b.getShape().getMin();
            BlockPos max = b.getShape().getMax();
            minX = Math.min(minX, min.getX());
            minY = Math.min(minY, min.getY());
            minZ = Math.min(minZ, min.getZ());
            maxX = Math.max(maxX, max.getX());
            maxY = Math.max(maxY, max.getY());
            maxZ = Math.max(maxZ, max.getZ());
        }

        return Optional.of(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
    }

    public boolean contains(BlockPos pos, VillageSavedData data) {
        return getBounds(data)
                .map(aabb -> aabb.contains(
                        pos.getX() + 0.5,
                        pos.getY() + 0.5,
                        pos.getZ() + 0.5))
                .orElse(false);
    }

    // =========================================================================
    // NEEDS
    // =========================================================================

    public Map<NeedCategory, VillageNeed> getNeeds() {
        return Collections.unmodifiableMap(needs);
    }
    public void setNeeds(Map<NeedCategory, VillageNeed> needs) {
        this.needs = new EnumMap<>(needs);
    }
    public long getLastNeedsUpdate()           { return lastNeedsUpdate; }
    public void setLastNeedsUpdate(long tick)  { this.lastNeedsUpdate = tick; }

    public NeedLevel getNeedLevel(NeedCategory category) {
        VillageNeed need = needs.get(category);
        return need == null ? NeedLevel.SATISFIED : need.getLevel();
    }
    public boolean isFoodCritical() {
        return getNeedLevel(NeedCategory.FOOD).isCritical();
    }
    public boolean isFoodLow() {
        return getNeedLevel(NeedCategory.FOOD).isDeficient();
    }

    // =========================================================================
    // TREASURY
    // =========================================================================

    public long getTreasuryBronze() { return treasuryBronze; }
    public CurrencyValue getTreasury() { return CurrencyValue.of(treasuryBronze); }

    public void depositToTreasury(long bronze) { treasuryBronze += bronze; }

    public boolean withdrawFromTreasury(long bronze) {
        if (treasuryBronze < bronze) return false;
        treasuryBronze -= bronze;
        return true;
    }

    // =========================================================================
    // GUARD POSTS
    // =========================================================================

    public List<BlockPos> getGuardPosts() { return List.copyOf(guardPosts); }

    public void addGuardPost(BlockPos pos) {
        if (!guardPosts.contains(pos)) guardPosts.add(pos);
    }
    public void removeGuardPost(BlockPos pos) { guardPosts.remove(pos); }

    public Optional<BlockPos> assignGuardPost(ServerLevel level, UUID guardId) {
        List<TownspersonMob> allGuards = getVillageGuards(level);
        if (allGuards.size() < GUARD_POST_THRESHOLD || guardPosts.isEmpty())
            return Optional.empty();
        for (BlockPos post : guardPosts) {
            boolean occupied = allGuards.stream()
                    .anyMatch(g -> g.getAssignedPost()
                            .map(p -> p.equals(post)).orElse(false));
            if (!occupied) return Optional.of(post);
        }
        return Optional.empty();
    }

    public List<TownspersonMob> getVillageGuards(ServerLevel level) {
        return getBounds(VillageSavedData.get(level))
                .map(bounds -> level.getEntitiesOfClass(
                        TownspersonMob.class, bounds.inflate(16),
                        e -> e.getProfession() == Profession.GUARD
                                && e.getAssignedVillageName()
                                .map(n -> n.equals(this.name))
                                .orElse(false)))
                .orElse(List.of());
    }

    // =========================================================================
    // REPUTATION
    // =========================================================================

    public int getReputation(UUID playerId) {
        return playerReputations.getOrDefault(playerId, 0);
    }
    public void modifyReputation(UUID playerId, int delta) {
        int current = playerReputations.getOrDefault(playerId, 0);
        playerReputations.put(playerId,
                Math.max(-1000, Math.min(1000, current + delta)));
    }
    public boolean isHostile(UUID playerId) {
        return getReputation(playerId) <= REPUTATION_HOSTILE_THRESHOLD;
    }

    // =========================================================================
    // PREFERRED ARMOR
    // =========================================================================

    public Map<String, String> getPreferredArmor() {
        return Map.copyOf(preferredArmor);
    }
    public void setPreferredArmorPiece(EquipmentSlot slot, Item item) {
        preferredArmor.put(slot.name(),
                BuiltInRegistries.ITEM.getKey(item).toString());
    }
    public Optional<Item> getPreferredArmorPiece(EquipmentSlot slot) {
        String key = preferredArmor.get(slot.name());
        if (key == null) return Optional.empty();
        return Optional.ofNullable(
                BuiltInRegistries.ITEM.get(Identifier.parse(key))
                        .map(h -> h.value()).orElse(null));
    }

    // =========================================================================
    // PATROLS
    // =========================================================================

    private final List<BlockPos> patrolWaypoints = new ArrayList<>();

    public List<BlockPos> getPatrolWaypoints() {
        return Collections.unmodifiableList(patrolWaypoints);
    }

    public void setPatrolWaypoints(List<BlockPos> waypoints) {
        patrolWaypoints.clear();
        patrolWaypoints.addAll(waypoints);
    }

    public void addPatrolWaypoint(BlockPos pos) {
        patrolWaypoints.add(pos);
    }

    // =========================================================================
    // VILLAGE TYPE & PATH TIER
    // =========================================================================

    public VillagePath.PathTier getPathTier() {
        return VillagePath.PathTier.DIRT;
    }

    public static void setPathTier(VillagePath.PathTier tier) {
        // TODO: per-village persistence
    }

    public String getVillageType()                 { return villageType; }
    public void   setVillageType(String type)      { this.villageType = type; }
}