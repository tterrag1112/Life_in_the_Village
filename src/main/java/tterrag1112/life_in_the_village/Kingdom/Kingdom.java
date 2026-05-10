package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Kingdom.Charters.Charter;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Intrigue.IntrigueAttempt;
import tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty;
import tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType;
import tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawInstance;
import tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry;
import tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState;
import tterrag1112.life_in_the_village.Kingdom.Laws.ScalarLaw;
import tterrag1112.life_in_the_village.Kingdom.Laws.EnumLaw;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class Kingdom {
    public record KingdomGovernanceData(
            long treasuryBronze,
            double incomeTaxRate,
            long flatUpkeepBronze,
            long lastTaxTick,
            KingdomHistoryData history,
            List<House> houses,
            List<KingdomModifier> modifiers,
            List<Province> provinces,
            long lastProvinceRecomputeTick,
            List<KingdomLawInstance> lawInstances,
            List<Charter> charters,
            List<Treaty> treaties,
            List<IntrigueAttempt> intrigueHistory
    ) {
        public static final Codec<KingdomGovernanceData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.LONG
                                .optionalFieldOf("treasuryBronze", 0L)
                                .forGetter(KingdomGovernanceData::treasuryBronze),
                        Codec.DOUBLE
                                .optionalFieldOf("incomeTaxRate", 0.1)
                                .forGetter(KingdomGovernanceData::incomeTaxRate),
                        Codec.LONG
                                .optionalFieldOf("flatUpkeepBronze", 32L)
                                .forGetter(KingdomGovernanceData::flatUpkeepBronze),
                        Codec.LONG
                                .optionalFieldOf("lastTaxTick", -1L)
                                .forGetter(KingdomGovernanceData::lastTaxTick),
                        KingdomHistoryData.CODEC
                                .optionalFieldOf("history",
                                        new KingdomHistoryData())
                                .forGetter(g -> g.history() != null
                                        ? g.history()
                                        : new KingdomHistoryData()),
                        // Track D3.2a — noble dynasties belonging to this kingdom.
                        House.CODEC.listOf()
                                .optionalFieldOf("houses", new ArrayList<>())
                                .forGetter(g -> g.houses() != null
                                        ? g.houses() : new ArrayList<>()),
                        // Track D3.2a — active stability/legitimacy modifiers.
                        KingdomModifier.CODEC.listOf()
                                .optionalFieldOf("modifiers", new ArrayList<>())
                                .forGetter(g -> g.modifiers() != null
                                        ? g.modifiers() : new ArrayList<>()),
                        // Track D3.3 — political subdivisions (provinces).
                        Province.CODEC.listOf()
                                .optionalFieldOf("provinces", new ArrayList<>())
                                .forGetter(g -> g.provinces() != null
                                        ? g.provinces() : new ArrayList<>()),
                        // Track D3.3 — last weekly polygon recompute tick.
                        Codec.LONG.optionalFieldOf("lastProvinceRecomputeTick", -1L)
                                .forGetter(KingdomGovernanceData::lastProvinceRecomputeTick),
                        // Track D3.4 — kingdom-tier law instances (DRAFT /
                        // PROPOSED / ACTIVE per the typology refactor).
                        // Empty for pre-D3.4 saves; migrated from the
                        // top-level activeLaws (legacy enum) at fromCodec.
                        KingdomLawInstance.CODEC.listOf()
                                .optionalFieldOf("lawInstances", new ArrayList<>())
                                .forGetter(g -> g.lawInstances() != null
                                        ? g.lawInstances() : new ArrayList<>()),
                        // Track D3.4b — persistent charters (TOLL_RIGHTS /
                        // TAX_EXEMPTION / MARKET_MONOPOLY / TITLE_GRANT /
                        // ORDINATION_RIGHTS / LAND_GRANT). Survive ruler
                        // change; revocation has age-scaled cost.
                        Charter.CODEC.listOf()
                                .optionalFieldOf("charters", new ArrayList<>())
                                .forGetter(g -> g.charters() != null
                                        ? g.charters() : new ArrayList<>()),
                        // Track D3.4b — diplomatic treaties (ALLIANCE /
                        // NON_AGGRESSION / TRADE_DEAL / VASSALAGE).
                        // The same treaty id appears on every party's
                        // list. Migration from DiplomaticRelation
                        // happens at fromCodec.
                        Treaty.CODEC.listOf()
                                .optionalFieldOf("treaties", new ArrayList<>())
                                .forGetter(g -> g.treaties() != null
                                        ? g.treaties() : new ArrayList<>()),
                        // Track D3.4b — Spymaster intrigue history. Bounded
                        // rolling buffer; cooldown checks read from this.
                        IntrigueAttempt.CODEC.listOf()
                                .optionalFieldOf("intrigueHistory", new ArrayList<>())
                                .forGetter(g -> g.intrigueHistory() != null
                                        ? g.intrigueHistory() : new ArrayList<>())
                ).apply(i, KingdomGovernanceData::new));
    }

    // =========================================================================
    // CODEC
    // =========================================================================
    public static final Codec<Kingdom> CODEC =
            RecordCodecBuilder.create(instance ->
                    instance.group(
                            Codec.STRING.xmap(UUID::fromString,
                                            UUID::toString)
                                    .fieldOf("id")
                                    .forGetter(Kingdom::getId),
                            Codec.STRING
                                    .fieldOf("name")
                                    .forGetter(Kingdom::getName),
                            Codec.STRING
                                    .fieldOf("culture")
                                    .forGetter(Kingdom::getCulture),
                            Codec.STRING.xmap(UUID::fromString,
                                            UUID::toString).listOf()
                                    .optionalFieldOf("villageIds",
                                            new ArrayList<>())
                                    .forGetter(k -> new ArrayList<>(
                                            k.villageIds)),
                            Codec.STRING.xmap(UUID::fromString,
                                            UUID::toString)
                                    .optionalFieldOf("rulerEntityId")
                                    .forGetter(k -> Optional.ofNullable(
                                            k.rulerEntityId)),
                            Codec.STRING.xmap(UUID::fromString,
                                            UUID::toString)
                                    .optionalFieldOf("rulerPlayerId")
                                    .forGetter(k -> Optional.ofNullable(
                                            k.rulerPlayerId)),
                            Codec.unboundedMap(
                                            Codec.STRING.xmap(
                                                    UUID::fromString,
                                                    UUID::toString),
                                            Codec.STRING.xmap(
                                                    DiplomaticRelation::valueOf,
                                                    DiplomaticRelation::name))
                                    .optionalFieldOf("relations",
                                            new HashMap<>())
                                    .forGetter(k -> k.relations),
                            Codec.STRING.xmap(KingdomLaw::valueOf,
                                            KingdomLaw::name).listOf()
                                    .optionalFieldOf("activeLaws",
                                            new ArrayList<>())
                                    .forGetter(k -> new ArrayList<>(
                                            k.activeLaws)),
                            KingdomClaim.CODEC.optionalFieldOf("territorialClaim")
                                    .forGetter(k -> java.util.Optional.ofNullable(k.territorialClaim)),
                            KingdomGovernanceData.CODEC
                                    .fieldOf("governance")
                                    .forGetter(k -> new KingdomGovernanceData(
                                            k.treasuryBronze,
                                            k.incomeTaxRate,
                                            k.flatUpkeepBronze,
                                            k.lastTaxTick,
                                            k.history != null
                                                    ? k.history
                                                    : new KingdomHistoryData(),
                                            new ArrayList<>(k.houses),
                                            new ArrayList<>(k.modifiers),
                                            new ArrayList<>(k.provinces),
                                            k.lastProvinceRecomputeTick,
                                            new ArrayList<>(k.lawInstances.values()),
                                            new ArrayList<>(k.charters),
                                            new ArrayList<>(k.treaties),
                                            new ArrayList<>(k.intrigueHistory))),
                            tterrag1112.life_in_the_village.Npc.Office.OfficeState.CODEC
                                    .optionalFieldOf("offices")
                                    .forGetter(k -> Optional.ofNullable(k.offices)),
                            // Track D1 — kingdom-tier scalars + heraldry.
                            Codec.INT.optionalFieldOf("stability", 75)
                                    .forGetter(k -> k.stability),
                            Codec.INT.optionalFieldOf("legitimacy", 75)
                                    .forGetter(k -> k.legitimacy),
                            Heraldry.CODEC.optionalFieldOf("heraldry", Heraldry.UNKNOWN)
                                    .forGetter(k -> k.heraldry),
                            // Track D3.1 — capital + founding bookkeeping.
                            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                                    .optionalFieldOf("capitalVillageId")
                                    .forGetter(k -> Optional.ofNullable(k.capitalVillageId)),
                            Codec.LONG.optionalFieldOf("foundingTick", 0L)
                                    .forGetter(k -> k.foundingTick)
                    ).apply(instance, Kingdom::fromCodec));


    private static Kingdom fromCodec(
            UUID id, String name, String culture,
            List<UUID> villageIds,
            Optional<UUID> rulerEntity,
            Optional<UUID> rulerPlayer,
            Map<UUID, DiplomaticRelation> relations,
            List<KingdomLaw> laws,
            Optional<KingdomClaim> claim,
            KingdomGovernanceData governance,
            Optional<tterrag1112.life_in_the_village.Npc.Office.OfficeState> offices,
            int stability, int legitimacy, Heraldry heraldry,
            Optional<UUID> capitalVillageId, long foundingTick) {
        Kingdom k = new Kingdom(id, name, culture);
        k.stability = clampScalar(stability);
        k.legitimacy = clampScalar(legitimacy);
        // Track D1 — back-fill heraldry for pre-Phase-0 kingdoms.
        // The constructor already generated culture-seeded heraldry;
        // accept a stored non-UNKNOWN value over it but keep the
        // generator's output for older saves whose codec missed the
        // field entirely (defaults to UNKNOWN there).
        if (heraldry != null && !heraldry.equals(Heraldry.UNKNOWN)) {
            k.heraldry = heraldry;
        }
        // else: keep the constructor-generated heraldry.
        // Track D3.1 — capital + founding bookkeeping.
        capitalVillageId.ifPresent(cid -> k.capitalVillageId = cid);
        k.foundingTick = foundingTick;
        k.villageIds.addAll(villageIds);
        rulerEntity.ifPresent(rid -> k.rulerEntityId = rid);
        rulerPlayer.ifPresent(pid -> k.rulerPlayerId = pid);
        k.relations.putAll(relations);
        // Track D3.4 — populate lawInstances from governance.lawInstances
        // first; if that's empty AND legacy enum-based activeLaws has
        // entries, migrate the enum values to ACTIVE ToggleLaw instances
        // (one-shot path for pre-D3.4 saves).
        if (governance.lawInstances() != null && !governance.lawInstances().isEmpty()) {
            for (KingdomLawInstance inst : governance.lawInstances()) {
                k.lawInstances.put(inst.lawId(), inst);
            }
        } else if (laws != null && !laws.isEmpty()) {
            for (KingdomLaw legacy : laws) {
                String id = legacy.name().toLowerCase(java.util.Locale.ROOT);
                if (KingdomLawRegistry.find(id).isEmpty()) continue;
                k.lawInstances.put(id, new KingdomLawInstance(
                        id, KingdomLawState.ACTIVE,
                        Optional.empty(), Optional.empty(),
                        0L, Optional.empty(), Optional.empty(),
                        0L));
            }
        }
        k.syncLegacyActiveLaws();
        claim.ifPresent(c -> k.territorialClaim = c);
        k.treasuryBronze   = governance.treasuryBronze();
        k.incomeTaxRate    = governance.incomeTaxRate();
        k.flatUpkeepBronze = governance.flatUpkeepBronze();
        k.lastTaxTick      = governance.lastTaxTick();
        k.history          = governance.history() != null ? governance.history() : new KingdomHistoryData();
        // Track D3.2a — restore houses + modifiers (empty for pre-D3.2a saves).
        if (governance.houses() != null)    k.houses.addAll(governance.houses());
        if (governance.modifiers() != null) k.modifiers.addAll(governance.modifiers());
        // Track D3.3 — restore provinces (empty for pre-D3.3 saves).
        if (governance.provinces() != null) k.provinces.addAll(governance.provinces());
        k.lastProvinceRecomputeTick = governance.lastProvinceRecomputeTick();
        // Track D3.4b — restore charters (empty for pre-D3.4b saves).
        if (governance.charters() != null) k.charters.addAll(governance.charters());
        // Track D3.4b — restore intrigue history (bounded; empty for pre-D3.4b).
        if (governance.intrigueHistory() != null) {
            k.intrigueHistory.addAll(governance.intrigueHistory());
        }
        // Track D3.4b — restore treaties (empty for pre-D3.4b saves);
        // auto-migrate cooperative DiplomaticRelation entries into
        // ALLIANCE / TRADE_DEAL treaties when no treaty list exists.
        if (governance.treaties() != null && !governance.treaties().isEmpty()) {
            k.treaties.addAll(governance.treaties());
        } else if (relations != null && !relations.isEmpty()) {
            for (var entry : relations.entrySet()) {
                UUID other = entry.getKey();
                DiplomaticRelation rel = entry.getValue();
                TreatyType migratedType = switch (rel) {
                    case ALLIANCE -> TreatyType.ALLIANCE;
                    case TRADE    -> TreatyType.TRADE_DEAL;
                    default       -> null;
                };
                if (migratedType == null) continue;
                // Deterministic id for the migrated pair so both
                // sides produce the same UUID independently.
                long high = k.id.getMostSignificantBits()
                        ^ Long.rotateLeft(other.getMostSignificantBits(), 17);
                long low  = k.id.getLeastSignificantBits()
                        ^ Long.rotateLeft(other.getLeastSignificantBits(), 13);
                UUID treatyId = new UUID(high, low);
                k.treaties.add(Treaty.autoMigrated(treatyId, migratedType, k.id, other));
            }
        }
        // Office state: stored value wins; otherwise migrate the legacy
        // ruler fields into a kingdom_king holding.
        if (offices.isPresent()) {
            k.offices = offices.get();
        } else {
            // Constructor already seeded an empty state.
            if (k.rulerPlayerId != null) {
                k.offices.set(
                        tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_KING,
                        tterrag1112.life_in_the_village.Npc.Office.OfficeHolding.heldByPlayer(
                                tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_KING,
                                k.id, k.rulerPlayerId, 0L, 0L,
                                tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.HEREDITARY));
            } else if (k.rulerEntityId != null) {
                k.offices.set(
                        tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_KING,
                        tterrag1112.life_in_the_village.Npc.Office.OfficeHolding.heldByNpc(
                                tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_KING,
                                k.id, k.rulerEntityId, 0L, 0L,
                                tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.HEREDITARY));
            }
        }
        return k;
    }

    // =========================================================================
    // FIELDS
    // =========================================================================

    private final UUID id;
    private String name;
    private String culture;
    private final List<UUID> villageIds        = new ArrayList<>();
    private UUID rulerEntityId                  = null; // NPC ruler
    private UUID rulerPlayerId                  = null; // player ruler
    private long treasuryBronze                 = 0L;
    private final Map<UUID, DiplomaticRelation> relations = new HashMap<>();
    /**
     * Track D3.4 — legacy storage. Mirrors the ACTIVE-state subset
     * of {@link #lawInstances} for legacy enum read APIs and the
     * top-level {@code activeLaws} codec field. Kept in sync by
     * {@link #syncLegacyActiveLaws()}; do not mutate directly.
     */
    private final Set<KingdomLaw> activeLaws    = new HashSet<>();

    /**
     * Track D3.4 — canonical per-kingdom law-instance storage,
     * keyed by law id. Carries DRAFT / PROPOSED / ACTIVE state
     * for every law the kingdom has touched. Drives every
     * accessor below; legacy {@link KingdomLaw} enum APIs are
     * shims over this map.
     */
    private final java.util.LinkedHashMap<String, KingdomLawInstance> lawInstances
            = new java.util.LinkedHashMap<>();

    /**
     * Track D3.4b — persistent charters issued by this kingdom.
     * Survive ruler change (no logic in
     * {@code NobilityEventDispatcher.runSuccession} touches this list).
     * Mutated via {@link #grantCharter}, {@link #revokeCharter},
     * {@link #removeCharter}.
     */
    private final List<Charter> charters = new ArrayList<>();

    /**
     * Track D3.4b — diplomatic treaties this kingdom is party to.
     * Same treaty id mirrors on every party's list. {@code getRelation}
     * derives ALLIANCE / TRADE / VASSAL_OF / OVERLORD_OF from this
     * list ahead of the residual {@code relations} map.
     */
    private final List<Treaty> treaties = new ArrayList<>();

    /**
     * Track D3.4b — Spymaster intrigue history. Bounded rolling
     * buffer (capacity {@link IntrigueAttempt#BUFFER_CAPACITY}).
     * Cooldown lookups read recent entries; full history kept for
     * Phase 5 newsfeed surfacing.
     */
    private final List<IntrigueAttempt> intrigueHistory = new ArrayList<>();
    /**
     * Office state for this kingdom. Phase 0 storage only — see
     * {@code docs/npc_redesign/06-office-framework.md}. Stays in sync with
     * the legacy ruler fields during the migration window; Phase 3 cuts
     * over.
     */
    private tterrag1112.life_in_the_village.Npc.Office.OfficeState offices;
    private double incomeTaxRate                = 0.1; // 10% default
    private long flatUpkeepBronze               = 32L; // 32 bronze per village per day
    private long lastTaxTick                    = -1L;
    private KingdomHistoryData history = new KingdomHistoryData();
    private KingdomClaim territorialClaim = null; // nullable — older saves won't have it

    // Track D1 — kingdom-tier scalars + heraldry. Inert this phase;
    // stability and legitimacy carry no driver yet (D3 wires the
    // decay loops). Heraldry is purely a persistence + display
    // concern at this stage.
    /**
     * Track D1 — 0..100 stability score. Default 75 = SECURE band.
     * Bands: 0–24 CRISIS, 25–49 STRAINED, 50–74 STABLE, 75–100 SECURE.
     * D3 drivers (intent): treasury health, recent crime rate,
     * neighbouring war, succession turbulence.
     */
    private int stability = 75;
    /**
     * Track D1 — 0..100 legitimacy score. Default 75. Same bands as
     * stability. D3 drivers (intent): culturally-correct succession,
     * occupied culture-required offices, holy / oracular endorsement.
     */
    private int legitimacy = 75;
    /**
     * Track D1 — kingdom heraldry. Generated deterministically from
     * (culture, kingdomId, foundingSeed) at kingdom-founding;
     * pre-Phase-0 saves back-fill via the migration with foundingSeed=0.
     * Never null after construction; never null after codec round-trip.
     */
    private Heraldry heraldry = Heraldry.UNKNOWN;

    /**
     * Track D3.1 — UUID of the capital village. Set by
     * {@code CapitalGenerator} at kingdom founding; back-filled for
     * pre-D3.1 saves by {@code KingdomCapitalMigration} from the
     * first entry of {@link #villageIds}. Never null after
     * back-fill; new code paths read this rather than indexing
     * villageIds.
     */
    @javax.annotation.Nullable
    private UUID capitalVillageId = null;

    /**
     * Track D3.1 — server tick at which this kingdom was founded.
     * Pre-D3.1 saves arrive with 0L (the codec default); D3.1
     * generation stamps the actual tick. Used by
     * {@link HeraldryGenerator} as the foundingSeed and by D3
     * stability / legitimacy decay to age the kingdom.
     */
    private long foundingTick = 0L;

    /**
     * Track D3.2a — noble dynasties belonging to this kingdom.
     * Mutated only via {@link #addHouse}, {@link #removeHouse},
     * {@link #replaceHouse}; codec persists the snapshot.
     */
    private final List<House> houses = new ArrayList<>();

    /**
     * Track D3.2a — active stability / legitimacy modifiers. Aspirational
     * D1 hook now wired: D3.2b's tick subsystem decays expiring
     * modifiers. Mutated via {@link #addModifier}, {@link #removeModifier}.
     */
    private final List<KingdomModifier> modifiers = new ArrayList<>();

    /**
     * Track D3.3 — political subdivisions. Mutated via
     * {@link #addProvince}, {@link #removeProvince},
     * {@link #replaceProvince}; rebuilt by
     * {@code ProvinceComputer.recompute} on the weekly cadence + on
     * manor-event invalidation.
     */
    private final List<Province> provinces = new ArrayList<>();

    /**
     * Track D3.3 — server tick of the last province polygon recompute.
     * {@code -1L} signals "never recomputed"; the weekly tick
     * subsystem treats that as eligible immediately.
     */
    private long lastProvinceRecomputeTick = -1L;


    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public Kingdom(UUID id, String name, String culture) {
        this.id      = id;
        this.name    = name;
        this.culture = culture;
        this.offices = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.KINGDOM, this.id);
        // Track D1 — generate heraldry deterministically at founding.
        // Codec round-trip overrides this with the persisted record;
        // pre-Phase-0 saves arrive via fromCodec which sets heraldry
        // explicitly before this default would re-fire (constructor
        // is called once, codec setters once).
        this.heraldry = HeraldryGenerator.generate(
                tterrag1112.life_in_the_village.Cultures.CultureRegistry
                        .getOrDefault(culture),
                id, 0L);
    }

    /** Office state for this kingdom; never {@code null} after construction. */
    public tterrag1112.life_in_the_village.Npc.Office.OfficeState getOffices() {
        return offices;
    }

    public Kingdom(String name, String culture) {
        this(UUID.randomUUID(), name, culture);
    }

    // =========================================================================
    // IDENTITY
    // =========================================================================

    public UUID getId()     { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCulture() { return culture; }
    public void setCulture(String culture) { this.culture = culture; }


    // =========================================================================
    // VILLAGES
    // =========================================================================

    public List<UUID> getVillageIds() { return villageIds; }

    public void addVillage(UUID villageId) {
        if (!villageIds.contains(villageId)) villageIds.add(villageId);
    }

    public void removeVillage(UUID villageId) {
        villageIds.remove(villageId);
    }

    public boolean containsVillage(UUID villageId) {
        return villageIds.contains(villageId);
    }

    /** Combined AABB of all village bounds */
    public Optional<AABB> getTerritory(VillageSavedData data) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE,
                minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE,
                maxZ = -Double.MAX_VALUE;
        boolean found = false;

        for (UUID vid : villageIds) {
            Optional<Village> village = data.getVillageById(vid);
            if (village.isEmpty()) continue;
            Optional<AABB> bounds = village.get().getBounds(data);
            if (bounds.isEmpty()) continue;
            AABB b = bounds.get();
            minX = Math.min(minX, b.minX); minY = Math.min(minY, b.minY);
            minZ = Math.min(minZ, b.minZ); maxX = Math.max(maxX, b.maxX);
            maxY = Math.max(maxY, b.maxY); maxZ = Math.max(maxZ, b.maxZ);
            found = true;
        }

        return found
                ? Optional.of(new AABB(minX, minY, minZ, maxX, maxY, maxZ))
                : Optional.empty();
    }

    public boolean containsPos(net.minecraft.core.BlockPos pos,
                               VillageSavedData data) {
        return getTerritory(data)
                .map(t -> t.contains(pos.getX(), pos.getY(), pos.getZ()))
                .orElse(false);
    }

    // =========================================================================
    // RULER
    // =========================================================================

    public Optional<UUID> getRulerEntityId() {
        return Optional.ofNullable(rulerEntityId);
    }
    public void setRulerEntityId(UUID id) { this.rulerEntityId = id; }

    public Optional<UUID> getRulerPlayerId() {
        return Optional.ofNullable(rulerPlayerId);
    }
    public void setRulerPlayerId(UUID id) { this.rulerPlayerId = id; }

    public boolean hasRuler() {
        return rulerEntityId != null || rulerPlayerId != null;
    }

    public boolean isPlayerRuled() { return rulerPlayerId != null; }

    /**
     * Returns the display name of the current ruler.
     * Checks player ruler first, then NPC ruler,
     * then falls back to "the crown".
     */
    public String getRulerName(ServerLevel level) {
        // Player ruler
        if (rulerPlayerId != null) {
            var player = level.getServer()
                    .getPlayerList()
                    .getPlayer(rulerPlayerId);
            if (player != null) {
                return player.getName().getString();
            }
            // Player offline — we don't have the name stored
            // so fall through to NPC check
        }

        // NPC ruler
        if (rulerEntityId != null) {
            var entity = level.getEntity(rulerEntityId);
            if (entity instanceof tterrag1112.life_in_the_village
                    .Entities.custom.TownspersonMob npc) {
                return npc.getNpcName();
            }
        }

        return "the crown";
    }

    // =========================================================================
    // TREASURY
    // =========================================================================

    public long getTreasuryBronze() { return treasuryBronze; }
    public CurrencyValue getTreasury() { return CurrencyValue.of(treasuryBronze); }

    public void depositToTreasury(long bronze) {
        treasuryBronze += bronze;
    }

    public boolean withdrawFromTreasury(long bronze) {
        if (treasuryBronze < bronze) return false;
        treasuryBronze -= bronze;
        return true;
    }

    // =========================================================================
    // DIPLOMACY
    // =========================================================================

    /**
     * Track D3.4b — derived view: treaties authoritative for
     * cooperation; the residual {@link #relations} map covers WAR
     * and COLD_WAR. Priority order:
     * <ol>
     *   <li>Active VASSALAGE treaty (this is vassal) → ALLIANCE
     *       (overlord obligated to defend; vassal can't war).</li>
     *   <li>Active VASSALAGE treaty (this is overlord) → ALLIANCE.</li>
     *   <li>Active ALLIANCE treaty → ALLIANCE.</li>
     *   <li>Active TRADE_DEAL treaty → TRADE.</li>
     *   <li>Residual WAR in relations map → WAR.</li>
     *   <li>Active NON_AGGRESSION treaty → NEUTRAL (no positive
     *       cooperation, but blocks WAR).</li>
     *   <li>Residual COLD_WAR in relations map → COLD_WAR.</li>
     *   <li>Otherwise NEUTRAL.</li>
     * </ol>
     */
    public DiplomaticRelation getRelation(UUID otherKingdomId) {
        // Treaty-derived views first.
        for (Treaty t : treaties) {
            if (!t.isActive() || !t.involves(otherKingdomId)) continue;
            switch (t.type()) {
                case VASSALAGE -> { return DiplomaticRelation.ALLIANCE; }
                case ALLIANCE  -> { return DiplomaticRelation.ALLIANCE; }
                case TRADE_DEAL -> { return DiplomaticRelation.TRADE; }
                case NON_AGGRESSION -> {
                    // Don't return yet; WAR in residual still wins
                    // over NEUTRAL-from-NON_AGGRESSION.
                }
            }
        }
        // Residual hostility.
        DiplomaticRelation residual = relations.getOrDefault(otherKingdomId,
                DiplomaticRelation.NEUTRAL);
        if (residual == DiplomaticRelation.WAR
                || residual == DiplomaticRelation.COLD_WAR) {
            return residual;
        }
        // NON_AGGRESSION → NEUTRAL.
        for (Treaty t : treaties) {
            if (t.isActive() && t.type() == TreatyType.NON_AGGRESSION
                    && t.involves(otherKingdomId)) {
                return DiplomaticRelation.NEUTRAL;
            }
        }
        return residual;
    }

    /**
     * Sets a residual relation (WAR / COLD_WAR / NEUTRAL).
     * Cooperative relations (ALLIANCE / TRADE) come from treaties
     * now and shouldn't be set here directly — but for back-compat
     * with legacy callers, ALLIANCE / TRADE writes log a warning
     * and create an auto-migrated treaty rather than mutating
     * residual state.
     *
     * <p>Cascade-break rule: setting WAR while an ALLIANCE treaty
     * is active auto-breaks the alliance first (per the user-
     * confirmed precedence) and fires the corresponding
     * TreatyBroken event via {@link #breakTreatyWith}.
     */
    public void setRelation(UUID otherKingdomId, DiplomaticRelation relation) {
        if (relation == DiplomaticRelation.WAR) {
            // Cascade-break any active cooperative treaty.
            for (Treaty t : new ArrayList<>(treaties)) {
                if (!t.isActive() || !t.involves(otherKingdomId)) continue;
                if (t.type() == TreatyType.ALLIANCE
                        || t.type() == TreatyType.TRADE_DEAL
                        || t.type() == TreatyType.NON_AGGRESSION
                        || t.type() == TreatyType.VASSALAGE) {
                    breakTreaty(t.id(), this.id, /*tick*/ 0L,
                            "cascade.declared war on ally");
                }
            }
        }
        if (relation == DiplomaticRelation.NEUTRAL) {
            relations.remove(otherKingdomId);
        } else {
            relations.put(otherKingdomId, relation);
        }
    }

    public Map<UUID, DiplomaticRelation> getAllRelations() {
        return Collections.unmodifiableMap(relations);
    }

    public boolean isAtWarWith(UUID otherKingdomId) {
        return getRelation(otherKingdomId).isAtWar();
    }

    public boolean isAlliedWith(UUID otherKingdomId) {
        return getRelation(otherKingdomId) == DiplomaticRelation.ALLIANCE;
    }

    // =========================================================================
    // LAWS
    // =========================================================================

    public Set<KingdomLaw> getActiveLaws() {
        return Collections.unmodifiableSet(activeLaws);
    }

    /** Legacy enum check: does this kingdom have the named law ACTIVE? */
    public boolean hasLaw(KingdomLaw law) { return activeLaws.contains(law); }

    /**
     * Legacy enum-based enactment. Routes through the new
     * {@link KingdomLawInstance} state machine, going straight to
     * ACTIVE without a Scholar draft (preserves legacy semantics
     * for callers that haven't been refactored to the lifecycle
     * flow). The new GUI / packet verbs use {@link #draftLaw} →
     * {@link #proposeLaw} → {@link #enactLaw(String, long)}.
     */
    public void enactLaw(KingdomLaw law) {
        String id = law.name().toLowerCase(java.util.Locale.ROOT);
        if (KingdomLawRegistry.find(id).isEmpty()) return;
        lawInstances.put(id, new KingdomLawInstance(
                id, KingdomLawState.ACTIVE,
                Optional.empty(), Optional.empty(),
                0L, Optional.empty(), Optional.empty(),
                0L));
        syncLegacyActiveLaws();
    }

    public void repealLaw(KingdomLaw law) {
        String id = law.name().toLowerCase(java.util.Locale.ROOT);
        lawInstances.remove(id);
        syncLegacyActiveLaws();
    }

    // =========================================================================
    // Track D3.4 — id-based law accessors + lifecycle
    // =========================================================================

    /** Snapshot of every law instance the kingdom has touched. */
    public List<KingdomLawInstance> getLawInstances() {
        return List.copyOf(lawInstances.values());
    }

    public Optional<KingdomLawInstance> findLawInstance(String lawId) {
        return Optional.ofNullable(lawInstances.get(lawId));
    }

    /** True iff the named law is in {@link KingdomLawState#ACTIVE}. */
    public boolean hasActiveLaw(String lawId) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        return inst != null && inst.isActive();
    }

    /**
     * Returns the active scalar value for a {@link ScalarLaw} id, or
     * empty when the law is not ACTIVE / not a scalar / not touched.
     */
    public Optional<Double> lawScalar(String lawId) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isActive()) return Optional.empty();
        return inst.scalarValue();
    }

    /**
     * Returns the active enum choice for an {@link EnumLaw} id, or
     * empty when not ACTIVE / not an enum / not touched.
     */
    public Optional<String> lawChoice(String lawId) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isActive()) return Optional.empty();
        return inst.enumChoice();
    }

    /**
     * Begins a {@link KingdomLawState#DRAFT} on {@code lawId}. If a
     * draft / proposed / active instance already exists, returns it
     * unchanged (call repealLaw first to start over).
     */
    public KingdomLawInstance draftLaw(String lawId, Optional<UUID> drafter, long tick) {
        KingdomLawInstance existing = lawInstances.get(lawId);
        if (existing != null) return existing;
        KingdomLaw law = KingdomLawRegistry.byId(lawId);
        KingdomLawInstance fresh = KingdomLawInstance.freshDraft(law, drafter, tick);
        lawInstances.put(lawId, fresh);
        return fresh;
    }

    /**
     * Updates the live scalar value on a DRAFT-state ScalarLaw.
     * No-op for non-DRAFT instances or non-ScalarLaws (callers
     * should validate before calling).
     */
    public void updateDraftScalar(String lawId, double value) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isDraft()) return;
        if (!(KingdomLawRegistry.byId(lawId) instanceof ScalarLaw s)) return;
        lawInstances.put(lawId, inst.withScalar(s.clamp(value)));
    }

    /** Updates the live choice on a DRAFT-state EnumLaw. */
    public void updateDraftChoice(String lawId, String choice) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isDraft()) return;
        if (!(KingdomLawRegistry.byId(lawId) instanceof EnumLaw e)) return;
        if (!e.hasChoice(choice)) return;
        lawInstances.put(lawId, inst.withChoice(choice));
    }

    /** Transitions DRAFT → PROPOSED. No-op when not in DRAFT. */
    public boolean proposeLaw(String lawId, UUID proposerUuid, long tick) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isDraft()) return false;
        lawInstances.put(lawId, inst.withProposer(proposerUuid, tick));
        return true;
    }

    /**
     * Transitions PROPOSED → ACTIVE. Applies enactment cost
     * (treasury / stability / legitimacy debits) and syncs the
     * legacy {@link #activeLaws} set.
     */
    public boolean enactLaw(String lawId, long tick) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null || !inst.isProposed()) return false;
        KingdomLaw law = KingdomLawRegistry.byId(lawId);
        var cost = law.enactmentCost();
        treasuryBronze = Math.max(0L, treasuryBronze - cost.treasuryBronze());
        stability = clampScalar(stability + cost.stabilityDelta());
        legitimacy = clampScalar(legitimacy + cost.legitimacyDelta());
        lawInstances.put(lawId, inst.withState(KingdomLawState.ACTIVE, tick));
        syncLegacyActiveLaws();
        return true;
    }

    /**
     * Removes a law instance entirely (any state). Repeals an
     * ACTIVE law; cancels a DRAFT or PROPOSED. Cost: refunds half
     * the enactment treasury cost, applies the inverse stability
     * delta capped at zero (no free stability rebound).
     */
    public boolean repealLaw(String lawId, long tick) {
        KingdomLawInstance inst = lawInstances.get(lawId);
        if (inst == null) return false;
        if (inst.isActive()) {
            KingdomLaw law = KingdomLawRegistry.byId(lawId);
            var cost = law.enactmentCost();
            treasuryBronze += cost.treasuryBronze() / 2L;
            stability = clampScalar(stability - cost.stabilityDelta() / 2);
        }
        lawInstances.remove(lawId);
        syncLegacyActiveLaws();
        return true;
    }

    /** Mirrors the ACTIVE-state ToggleLaw subset into legacy {@link #activeLaws}. */
    private void syncLegacyActiveLaws() {
        activeLaws.clear();
        for (KingdomLawInstance inst : lawInstances.values()) {
            if (!inst.isActive()) continue;
            try {
                KingdomLaw legacy = KingdomLaw.valueOf(
                        inst.lawId().toUpperCase(java.util.Locale.ROOT));
                activeLaws.add(legacy);
            } catch (IllegalArgumentException ignored) {
                // New laws (Scalar/Enum) without a legacy enum twin
                // are correctly absent from the legacy set.
            }
        }
    }

    // =========================================================================
    // TAXATION
    // =========================================================================

    public double getIncomeTaxRate()   { return incomeTaxRate; }
    public long getFlatUpkeepBronze()  { return flatUpkeepBronze; }
    public long getLastTaxTick()       { return lastTaxTick; }

    public void setIncomeTaxRate(double rate) {
        this.incomeTaxRate = Math.max(0, Math.min(1.0, rate));
    }
    public void setFlatUpkeepBronze(long upkeep) {
        this.flatUpkeepBronze = Math.max(0, upkeep);
    }
    public void setLastTaxTick(long tick) { this.lastTaxTick = tick; }

    /**
     * Compute tax owed by a village for one day.
     * flat upkeep + income tax on village NPC wealth
     */
    public long computeDailyTax(long villageIncomeBronze) {
        long incomeTax = (long)(villageIncomeBronze * incomeTaxRate);
        return flatUpkeepBronze + incomeTax;
    }


    public static class ClientKingdomCache {
        private static List<Kingdom> kingdoms = new ArrayList<>();

        public static void setKingdoms(List<Kingdom> list) {
            kingdoms = new ArrayList<>(list);
        }

        public static List<Kingdom> getKingdoms() {
            return Collections.unmodifiableList(kingdoms);
        }

        public static Optional<Kingdom> getById(UUID id) {
            return kingdoms.stream()
                    .filter(k -> k.getId().equals(id))
                    .findFirst();
        }
    }

    public KingdomHistoryData getHistory() {
        if (history == null) history = new KingdomHistoryData();
        return history;
    }
    public java.util.Optional<KingdomClaim> getTerritorialClaim() {
        return java.util.Optional.ofNullable(territorialClaim);
    }
    public void setTerritorialClaim(KingdomClaim claim) {
        this.territorialClaim = claim;
    }

    // =========================================================================
    // Track D1 — stability / legitimacy / heraldry
    // =========================================================================

    public int getStability()  { return stability; }
    public int getLegitimacy() { return legitimacy; }

    public void setStability(int v)  { this.stability  = clampScalar(v); }
    public void setLegitimacy(int v) { this.legitimacy = clampScalar(v); }

    /**
     * Track D1 — semantic band for either scalar.
     * 0–24 = CRISIS, 25–49 = STRAINED, 50–74 = STABLE, 75–100 = SECURE.
     */
    public enum ScalarBand { CRISIS, STRAINED, STABLE, SECURE }

    public static ScalarBand bandOf(int score) {
        if (score < 25) return ScalarBand.CRISIS;
        if (score < 50) return ScalarBand.STRAINED;
        if (score < 75) return ScalarBand.STABLE;
        return ScalarBand.SECURE;
    }

    public Heraldry getHeraldry()       { return heraldry; }
    public void setHeraldry(Heraldry h) { this.heraldry = h == null ? Heraldry.UNKNOWN : h; }

    // =========================================================================
    // Track D3.1 — capital + founding
    // =========================================================================

    public Optional<UUID> getCapitalVillageId() { return Optional.ofNullable(capitalVillageId); }
    public void setCapitalVillageId(UUID id)     { this.capitalVillageId = id; }

    public long getFoundingTick()         { return foundingTick; }
    public void setFoundingTick(long t)   { this.foundingTick = t; }

    /** Clamps a 0..100 input to the valid stability/legitimacy range. */
    private static int clampScalar(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // =========================================================================
    // Track D3.2a — noble houses
    // =========================================================================

    /** Snapshot of all noble houses belonging to this kingdom. */
    public List<House> getHouses() { return Collections.unmodifiableList(houses); }

    public Optional<House> findHouse(UUID houseId) {
        for (House h : houses) if (h.id().equals(houseId)) return Optional.of(h);
        return Optional.empty();
    }

    public Optional<House> findHouseByName(String name) {
        for (House h : houses) if (h.name().equalsIgnoreCase(name)) return Optional.of(h);
        return Optional.empty();
    }

    public void addHouse(House house) {
        if (house == null) return;
        if (findHouse(house.id()).isPresent()) return;
        houses.add(house);
    }

    public boolean removeHouse(UUID houseId) {
        return houses.removeIf(h -> h.id().equals(houseId));
    }

    /** Replaces an existing house (matched by id) in-place; no-op if missing. */
    public void replaceHouse(House replacement) {
        if (replacement == null) return;
        for (int i = 0; i < houses.size(); i++) {
            if (houses.get(i).id().equals(replacement.id())) {
                houses.set(i, replacement);
                return;
            }
        }
    }

    // =========================================================================
    // Track D3.2a — kingdom modifiers (stability / legitimacy hooks)
    // =========================================================================

    public List<KingdomModifier> getModifiers() {
        return Collections.unmodifiableList(modifiers);
    }

    public void addModifier(KingdomModifier m) {
        if (m != null) modifiers.add(m);
    }

    public boolean removeModifier(String id) {
        return modifiers.removeIf(m -> m.id().equals(id));
    }

    /** Removes any modifier whose {@code expiresAtTick} is &le; the supplied tick. */
    public int pruneExpiredModifiers(long currentTick) {
        int before = modifiers.size();
        modifiers.removeIf(m -> m.expiresAtTick() > 0 && m.expiresAtTick() <= currentTick);
        return before - modifiers.size();
    }

    /** Sum of all stability deltas across active modifiers. */
    public int stabilityModifierSum() {
        int s = 0;
        for (KingdomModifier m : modifiers) s += m.stabilityDelta();
        return s;
    }

    /** Sum of all legitimacy deltas across active modifiers. */
    public int legitimacyModifierSum() {
        int s = 0;
        for (KingdomModifier m : modifiers) s += m.legitimacyDelta();
        return s;
    }

    // =========================================================================
    // Track D3.3 — provinces
    // =========================================================================

    public List<Province> getProvinces() {
        return Collections.unmodifiableList(provinces);
    }

    public Optional<Province> findProvince(UUID provinceId) {
        for (Province p : provinces) if (p.id().equals(provinceId)) return Optional.of(p);
        return Optional.empty();
    }

    public Optional<Province> findProvinceByName(String name) {
        for (Province p : provinces) if (p.name().equalsIgnoreCase(name)) return Optional.of(p);
        return Optional.empty();
    }

    /** Returns the province that contains the given village, or empty. */
    public Optional<Province> findProvinceForVillage(UUID villageId) {
        for (Province p : provinces) if (p.containsVillage(villageId)) return Optional.of(p);
        return Optional.empty();
    }

    /** Returns the province that contains the given atlas cell, or empty. */
    public Optional<Province> findProvinceForCell(long cellKey) {
        for (Province p : provinces) if (p.containsCell(cellKey)) return Optional.of(p);
        return Optional.empty();
    }

    public void addProvince(Province p) {
        if (p == null) return;
        if (findProvince(p.id()).isPresent()) return;
        provinces.add(p);
    }

    public boolean removeProvince(UUID provinceId) {
        return provinces.removeIf(p -> p.id().equals(provinceId));
    }

    public void replaceProvince(Province replacement) {
        if (replacement == null) return;
        for (int i = 0; i < provinces.size(); i++) {
            if (provinces.get(i).id().equals(replacement.id())) {
                provinces.set(i, replacement);
                return;
            }
        }
    }

    /**
     * Atomic swap of the entire province list — used by
     * {@code ProvinceComputer.recompute} after a fresh subdivision
     * pass. Preserves treasury / report buffer / modifiers when the
     * incoming list keeps the same province ids.
     */
    public void replaceAllProvinces(List<Province> incoming) {
        provinces.clear();
        if (incoming != null) provinces.addAll(incoming);
    }

    public long getLastProvinceRecomputeTick() { return lastProvinceRecomputeTick; }
    public void setLastProvinceRecomputeTick(long tick) {
        this.lastProvinceRecomputeTick = tick;
    }

    /** True when a recompute is overdue per the weekly cadence. */
    public boolean isProvinceRecomputeDue(long currentTick, long intervalTicks) {
        if (lastProvinceRecomputeTick < 0L) return true;
        return currentTick - lastProvinceRecomputeTick >= intervalTicks;
    }

    // =========================================================================
    // Track D3.4b — charters
    // =========================================================================

    public List<Charter> getCharters() {
        return Collections.unmodifiableList(charters);
    }

    public Optional<Charter> findCharter(UUID charterId) {
        for (Charter c : charters) if (c.id().equals(charterId)) return Optional.of(c);
        return Optional.empty();
    }

    /** Charters whose grantee.id matches; covers any GranteeKind. */
    public List<Charter> chartersFor(UUID granteeId) {
        List<Charter> out = new ArrayList<>();
        for (Charter c : charters) {
            if (c.grantee().id().equals(granteeId)) out.add(c);
        }
        return out;
    }

    /** Active (non-revoked) charters of the given type. */
    public List<Charter> activeChartersOfType(
            tterrag1112.life_in_the_village.Kingdom.Charters.CharterType type) {
        List<Charter> out = new ArrayList<>();
        for (Charter c : charters) {
            if (c.active() && c.type() == type) out.add(c);
        }
        return out;
    }

    /**
     * Issues a fresh charter. Returns the freshly-created instance
     * so callers can inspect / log it.
     */
    public Charter grantCharter(String name,
                                tterrag1112.life_in_the_village.Kingdom.Charters.GranteeRef grantee,
                                tterrag1112.life_in_the_village.Kingdom.Charters.CharterParams params,
                                Optional<UUID> grantedRulerId, long tick) {
        UUID charterId = UUID.randomUUID();
        Charter charter = Charter.freshGrant(charterId, name, grantee,
                this.id, grantedRulerId, params, tick);
        charters.add(charter);
        return charter;
    }

    /**
     * Revokes a charter — sets active=false, applies revocation cost
     * (legitimacy hit + 7-day stability dip per
     * {@link tterrag1112.life_in_the_village.Kingdom.Charters.CharterType}).
     * Returns true on success.
     */
    public boolean revokeCharter(UUID charterId, long tick) {
        for (int i = 0; i < charters.size(); i++) {
            Charter c = charters.get(i);
            if (c.id().equals(charterId) && c.active()) {
                long ageDays = c.ageInDays(tick);
                int legHit = c.type().legitimacyHit(ageDays);
                int dip    = c.type().stabilityDip();
                this.legitimacy = clampScalar(this.legitimacy - legHit);
                addModifier(KingdomModifier.expiring(
                        "charter.revocation." + c.type().name(),
                        "Revocation of " + c.name(),
                        -dip, 0,
                        tick,
                        24000L * 7));
                charters.set(i, c.revoke(tick));
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a charter entirely. Used by garbage collection of
     * long-revoked charters; not part of the legal revocation flow.
     */
    public boolean removeCharter(UUID charterId) {
        return charters.removeIf(c -> c.id().equals(charterId));
    }

    // =========================================================================
    // Track D3.4b — treaties
    // =========================================================================

    public List<Treaty> getTreaties() {
        return Collections.unmodifiableList(treaties);
    }

    public Optional<Treaty> findTreaty(UUID treatyId) {
        for (Treaty t : treaties) if (t.id().equals(treatyId)) return Optional.of(t);
        return Optional.empty();
    }

    /** Active treaties this kingdom has with the named other kingdom. */
    public List<Treaty> activeTreatiesWith(UUID otherKingdomId) {
        List<Treaty> out = new ArrayList<>();
        for (Treaty t : treaties) {
            if (t.isActive() && t.involves(otherKingdomId)
                    && !this.id.equals(otherKingdomId)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Adds a treaty entry (used by both drafting and mirror-on-other-side). */
    public void addTreaty(Treaty treaty) {
        if (treaty == null) return;
        for (Treaty existing : treaties) {
            if (existing.id().equals(treaty.id())) return; // already present
        }
        treaties.add(treaty);
    }

    /** Replaces a treaty (e.g. after ratification or break) by id. */
    public boolean replaceTreaty(Treaty replacement) {
        for (int i = 0; i < treaties.size(); i++) {
            if (treaties.get(i).id().equals(replacement.id())) {
                treaties.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    /**
     * Records ratification of {@code treatyId} by THIS kingdom's
     * ruler at {@code tick}. Returns the updated treaty when found,
     * or empty when this kingdom isn't a party.
     */
    public Optional<Treaty> ratifyTreaty(UUID treatyId, long tick) {
        for (int i = 0; i < treaties.size(); i++) {
            Treaty t = treaties.get(i);
            if (t.id().equals(treatyId) && t.parties().contains(this.id)) {
                Treaty updated = t.withRatification(this.id, tick);
                treaties.set(i, updated);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    /**
     * Breaks a treaty on this kingdom's copy. Idempotent — calling
     * on an already-broken treaty is a no-op. Applies legitimacy /
     * stability hits per {@link TreatyType}. Caller should mirror
     * the break on the other party's copy (the existing
     * KingdomActionPacket SET_RELATION handler already mirrors via
     * setRelation cascade, so both sides break correctly).
     */
    public boolean breakTreaty(UUID treatyId, UUID breakingPartyId,
                               long tick, String reason) {
        for (int i = 0; i < treaties.size(); i++) {
            Treaty t = treaties.get(i);
            if (t.id().equals(treatyId) && !t.broken()) {
                this.legitimacy = clampScalar(
                        this.legitimacy - t.type().legitimacyHitOnBreak());
                this.stability = clampScalar(
                        this.stability + t.type().stabilityDeltaOnBreak());
                treaties.set(i, t.asBroken(breakingPartyId, tick, reason));
                return true;
            }
        }
        return false;
    }

    /**
     * Track D3.4b — true if this kingdom is a vassal of any other
     * kingdom right now. Disables DECLARE_WAR capability per the
     * user-confirmed VASSALAGE rule.
     */
    public boolean isVassal() {
        for (Treaty t : treaties) {
            if (t.isActive() && t.type() == TreatyType.VASSALAGE
                    && t.vassalOf().filter(this.id::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the overlord kingdom UUID if {@link #isVassal}, else empty. */
    public Optional<UUID> overlordKingdomId() {
        for (Treaty t : treaties) {
            if (t.isActive() && t.type() == TreatyType.VASSALAGE
                    && t.vassalOf().filter(this.id::equals).isPresent()) {
                return t.overlordOf();
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Track D3.4b — intrigue history
    // =========================================================================

    public List<IntrigueAttempt> getIntrigueHistory() {
        return Collections.unmodifiableList(intrigueHistory);
    }

    /**
     * Appends an intrigue attempt to the rolling buffer. Trims to
     * {@link IntrigueAttempt#BUFFER_CAPACITY} oldest-first.
     */
    public void recordIntrigueAttempt(IntrigueAttempt attempt) {
        if (attempt == null) return;
        intrigueHistory.add(attempt);
        while (intrigueHistory.size() > IntrigueAttempt.BUFFER_CAPACITY) {
            intrigueHistory.remove(0);
        }
    }

    /** Last attempt this kingdom launched against {@code targetKingdomId}, or empty. */
    public Optional<IntrigueAttempt> lastAttemptAgainst(UUID targetKingdomId) {
        for (int i = intrigueHistory.size() - 1; i >= 0; i--) {
            IntrigueAttempt a = intrigueHistory.get(i);
            if (a.targetKingdomId().equals(targetKingdomId)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Most recent attempt this kingdom launched against any target, or empty. */
    public Optional<IntrigueAttempt> lastAttempt() {
        if (intrigueHistory.isEmpty()) return Optional.empty();
        return Optional.of(intrigueHistory.get(intrigueHistory.size() - 1));
    }
}