// src/main/java/tterrag1112/life_in_the_village/Village/Travel/Roster.java
package tterrag1112.life_in_the_village.Village.Travel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The persistent roster of an expedition — a list of who and what is
 * on a trade caravan, boat caravan, or future army/expedition.
 *
 * <h3>Phase 7c scope</h3>
 * Only the {@code principalId} is currently a "real" pooled villager.
 * The principal is drawn from the origin village's market and is
 * tracked across spawn/despawn cycles by UUID. The same villager is
 * always the same person on every realisation of the same caravan.
 *
 * <p>Other fields ({@code escortIds}, {@code transientCarrierIds},
 * {@code cargo}, {@code originBuildingId}) are scaffolded but not
 * fully populated yet:
 * <ul>
 *   <li>Escorts are still spawned fresh at realisation time. The list
 *       exists for future use when {@link
 *       tterrag1112.life_in_the_village.Village.Buildings.BuildingType#BARRACKS}
 *       inventories are added in Phase 7d.</li>
 *   <li>Transient carriers (llamas, boats) are still spawned fresh.
 *       Phase 7d will replace this with persistent {@code Animal} and
 *       {@code Vessel} records pulled from stables and docks.</li>
 *   <li>Cargo is the existing item list. No change in this phase.</li>
 * </ul>
 *
 * <h3>Why a class, not a record</h3>
 * Even though the persistent state is small, the transient lists
 * (carriers, currently-spawned escorts) need mutation. Records would
 * force every change to allocate a new instance. A class is easier
 * to reason about for the migration.
 */
public class Roster {

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<Roster> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    UUIDUtil.CODEC
                            .optionalFieldOf("principalId")
                            .forGetter(r -> java.util.Optional.ofNullable(r.principalId)),
                    UUIDUtil.CODEC.listOf()
                            .optionalFieldOf("escortIds", new ArrayList<>())
                            .forGetter(r -> r.escortIds),
                    UUIDUtil.CODEC
                            .optionalFieldOf("originBuildingId")
                            .forGetter(r -> java.util.Optional.ofNullable(r.originBuildingId))
            ).apply(i, Roster::fromCodec));

    private static Roster fromCodec(java.util.Optional<UUID> principal,
                                    List<UUID> escorts,
                                    java.util.Optional<UUID> origin) {
        Roster r = new Roster();
        r.principalId      = principal.orElse(null);
        r.escortIds.addAll(escorts);
        r.originBuildingId = origin.orElse(null);
        return r;
    }

    // =========================================================================
    // Persistent state
    // =========================================================================

    /** The pooled villager driving this expedition. Persists across spawns. */
    private UUID principalId;

    /**
     * Escort villager UUIDs. Empty in Phase 7c — escorts are still
     * spawned fresh from the building at realisation time. Phase 7d
     * fills this from barracks/guild hall inventories.
     */
    private final List<UUID> escortIds = new ArrayList<>();

    /** Building of origin (market for caravans, dock for boats). */
    private UUID originBuildingId;

    // =========================================================================
    // Transient state — rebuilt on each spawn
    // =========================================================================

    /** Currently-spawned escort entity UUIDs. Cleared on despawn. */
    private final transient List<UUID> spawnedEscortIds = new ArrayList<>();

    /** Currently-spawned carrier entity UUIDs (llama, boat, etc.). */
    private final transient List<UUID> spawnedCarrierIds = new ArrayList<>();

    // =========================================================================
    // API
    // =========================================================================

    public UUID getPrincipalId() { return principalId; }
    public void setPrincipalId(UUID id) { this.principalId = id; }

    public List<UUID> getEscortIds() { return escortIds; }
    public void addEscortId(UUID id) { if (!escortIds.contains(id)) escortIds.add(id); }
    public void removeEscortId(UUID id) { escortIds.remove(id); }

    public UUID getOriginBuildingId() { return originBuildingId; }
    public void setOriginBuildingId(UUID id) { this.originBuildingId = id; }

    public List<UUID> getSpawnedEscortIds() { return spawnedEscortIds; }
    public List<UUID> getSpawnedCarrierIds() { return spawnedCarrierIds; }

    public void clearSpawned() {
        spawnedEscortIds.clear();
        spawnedCarrierIds.clear();
    }
}