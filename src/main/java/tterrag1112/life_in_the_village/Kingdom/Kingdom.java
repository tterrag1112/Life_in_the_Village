package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
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
            KingdomHistoryData history
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
                                        : new KingdomHistoryData())
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
                                                    : new KingdomHistoryData())),
                            tterrag1112.life_in_the_village.Npc.Office.OfficeState.CODEC
                                    .optionalFieldOf("offices")
                                    .forGetter(k -> Optional.ofNullable(k.offices))
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
            Optional<tterrag1112.life_in_the_village.Npc.Office.OfficeState> offices) {
        Kingdom k = new Kingdom(id, name, culture);
        k.villageIds.addAll(villageIds);
        rulerEntity.ifPresent(rid -> k.rulerEntityId = rid);
        rulerPlayer.ifPresent(pid -> k.rulerPlayerId = pid);
        k.relations.putAll(relations);
        k.activeLaws.addAll(laws);
        claim.ifPresent(c -> k.territorialClaim = c);
        k.treasuryBronze   = governance.treasuryBronze();
        k.incomeTaxRate    = governance.incomeTaxRate();
        k.flatUpkeepBronze = governance.flatUpkeepBronze();
        k.lastTaxTick      = governance.lastTaxTick();
        k.history          = governance.history() != null ? governance.history() : new KingdomHistoryData();
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
    private final Set<KingdomLaw> activeLaws    = new HashSet<>();
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


    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public Kingdom(UUID id, String name, String culture) {
        this.id      = id;
        this.name    = name;
        this.culture = culture;
        this.offices = tterrag1112.life_in_the_village.Npc.Office.OfficeState
                .emptyFor(tterrag1112.life_in_the_village.Npc.Office.OrgType.KINGDOM, this.id);
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

    public DiplomaticRelation getRelation(UUID otherKingdomId) {
        return relations.getOrDefault(otherKingdomId, DiplomaticRelation.NEUTRAL);
    }

    public void setRelation(UUID otherKingdomId, DiplomaticRelation relation) {
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

    public boolean hasLaw(KingdomLaw law) { return activeLaws.contains(law); }

    public void enactLaw(KingdomLaw law)  { activeLaws.add(law); }
    public void repealLaw(KingdomLaw law) { activeLaws.remove(law); }

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

}