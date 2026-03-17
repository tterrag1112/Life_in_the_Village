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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeed;

import javax.annotation.Nullable;
import java.util.*;


public class Village {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    private static final int REPUTATION_HOSTILE_THRESHOLD = -50;
    private static final int GUARD_POST_THRESHOLD = 5;

    // =========================================================================
    // FIELDS
    // =========================================================================

    private final UUID id;
    private String name;
    private final List<UUID> buildingIds;
    private final List<BlockPos> guardPosts;
    private final Map<UUID, Integer> playerReputations;
    private Map<String, String> preferredArmor;
    private long treasuryBronze = 0L;
    @Nullable
    private UUID villageLeaderId = null;



    // Needs — recomputed daily, only lastNeedsUpdate persisted
    private Map<NeedCategory, VillageNeed> needs = new EnumMap<>(NeedCategory.class);
    private long lastNeedsUpdate = -1L;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public Village(String name, UUID id, List<UUID> buildingIds, List<BlockPos> guardPosts,
                   Map<UUID, Integer> playerReputations, Map<String, String> preferredArmor) {
        this.name = name;
        this.id = id;
        this.buildingIds = new ArrayList<>(buildingIds);
        this.guardPosts = new ArrayList<>(guardPosts);
        this.playerReputations = new HashMap<>(playerReputations);
        this.preferredArmor = new HashMap<>(preferredArmor);
        this.needs = new EnumMap<>(NeedCategory.class);
        this.lastNeedsUpdate = -1L;
    }

    public Village(String name) {
        this(name, UUID.randomUUID(), new ArrayList<>(), new ArrayList<>(),
                new HashMap<>(), new HashMap<>());
    }

    // =========================================================================
    // CODEC
    // =========================================================================

    public static final Codec<Village> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING
                            .fieldOf("name").forGetter(Village::getName),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(Village::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("buildingIds", new ArrayList<>())
                            .forGetter(v -> new ArrayList<>(v.buildingIds)),
                    BlockPos.CODEC.listOf()
                            .optionalFieldOf("guardPosts", new ArrayList<>())
                            .forGetter(v -> new ArrayList<>(v.guardPosts)),
                    Codec.unboundedMap(
                                    Codec.STRING.xmap(UUID::fromString, UUID::toString),
                                    Codec.INT
                            ).optionalFieldOf("playerReputations", new HashMap<>())
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
                            .forGetter(v -> Optional.ofNullable(v.villageLeaderId))

            ).apply(instance, (name, id, buildingIds, guardPosts, reputations, armor, lastNeedsUpdate, treasuryBronze, villageLeaderId) -> {
                Village village = new Village(name, id,
                        new ArrayList<>(buildingIds), new ArrayList<>(guardPosts),
                        new HashMap<>(reputations), new HashMap<>(armor));
                village.setLastNeedsUpdate(lastNeedsUpdate);
                village.treasuryBronze = treasuryBronze;
                villageLeaderId.ifPresent(village::setVillageLeaderId);


                return village;
            })
    );

    // =========================================================================
    // IDENTITY
    // =========================================================================

    public UUID getId()   { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Optional<UUID> getVillageLeaderId() {
        return Optional.ofNullable(villageLeaderId);
    }
    public void setVillageLeaderId(UUID id) { this.villageLeaderId = id; }


    // =========================================================================
    // BUILDINGS
    // =========================================================================

    /** Mutable list — use for adding/removing. */
    public List<UUID> getBuildingIds() { return buildingIds; }

    /** Read-only view for safe external iteration. */
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
    // SPATIAL
    // =========================================================================

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
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))
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

    public long getLastNeedsUpdate()          { return lastNeedsUpdate; }
    public void setLastNeedsUpdate(long tick) { this.lastNeedsUpdate = tick; }

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

    public void depositToTreasury(long bronze) {
        treasuryBronze += bronze;
    }

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

    public void removeGuardPost(BlockPos pos) {
        guardPosts.remove(pos);
    }

    public Optional<BlockPos> assignGuardPost(ServerLevel level, UUID guardId) {
        List<TownspersonMob> allGuards = getVillageGuards(level);
        if (allGuards.size() < GUARD_POST_THRESHOLD || guardPosts.isEmpty()) {
            return Optional.empty();
        }

        for (BlockPos post : guardPosts) {
            boolean occupied = allGuards.stream()
                    .anyMatch(g -> g.getAssignedPost()
                            .map(p -> p.equals(post))
                            .orElse(false));
            if (!occupied) return Optional.of(post);
        }

        return Optional.empty();
    }

    public List<TownspersonMob> getVillageGuards(ServerLevel level) {
        return getBounds(VillageSavedData.get(level))
                .map(bounds -> level.getEntitiesOfClass(
                        TownspersonMob.class, bounds.inflate(16),
                        e -> e.getProfession() == TownspersonMob.Profession.GUARD
                                && e.getAssignedVillageName()
                                .map(v -> v.equals(this.name))
                                .orElse(false)
                ))
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
                        .map(h -> h.value())
                        .orElse(null)
        );
    }
}

