package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.3.g.1 — SavedData for {@link BuildingRoster} persistence.
 *
 * <p>Activated from 6.3.3.d's in-memory scaffold. Each Business
 * roster (chickens in a coop, sheep in a pen, bees in a hive) persists
 * across save/load and across chunk unload/reload — that's the
 * defining purpose of the two-level pattern.
 *
 * <p>Per-level SavedData; rosters live alongside the level they
 * belong to. The {@code litv_rosters} storage key is fresh — no
 * legacy data to migrate.
 */
public class RosterSavedData extends SavedData {

    public static final SavedDataType<RosterSavedData> TYPE = new SavedDataType<>(
            "litv_rosters",
            RosterSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    PerBuilding.CODEC.listOf()
                            .optionalFieldOf("rosters", new ArrayList<>())
                            .forGetter(d -> snapshot(d.rosters))
            ).apply(i, RosterSavedData::fromCodec))
    );

    public static RosterSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── State ─────────────────────────────────────────────────────────

    /** Keyed by (buildingId, rosterDefinitionId). */
    private final Map<UUID, Map<Identifier, BuildingRoster>> rosters = new HashMap<>();

    public RosterSavedData() {}

    private static RosterSavedData fromCodec(List<PerBuilding> entries) {
        RosterSavedData d = new RosterSavedData();
        for (PerBuilding pb : entries) {
            Map<Identifier, BuildingRoster> perBuilding = new HashMap<>();
            for (BuildingRoster r : pb.rosters()) {
                perBuilding.put(r.rosterDefinitionId(), r);
            }
            d.rosters.put(pb.buildingId(), perBuilding);
        }
        return d;
    }

    private static List<PerBuilding> snapshot(Map<UUID, Map<Identifier, BuildingRoster>> in) {
        List<PerBuilding> out = new ArrayList<>(in.size());
        for (var entry : in.entrySet()) {
            out.add(new PerBuilding(entry.getKey(), new ArrayList<>(entry.getValue().values())));
        }
        return out;
    }

    /**
     * Codec helper: pairs a buildingId with its list of rosters. Codec
     * stores the outer map as a list of these so the codec stays
     * flat (no nested-map codec needed).
     */
    record PerBuilding(UUID buildingId, List<BuildingRoster> rosters) {
        public static final Codec<PerBuilding> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("buildingId").forGetter(PerBuilding::buildingId),
                BuildingRoster.CODEC.listOf().fieldOf("rosters").forGetter(PerBuilding::rosters)
        ).apply(i, PerBuilding::new));
    }

    // ── Public API ────────────────────────────────────────────────────

    public Optional<BuildingRoster> getRoster(UUID buildingId, Identifier definitionId) {
        var perBuilding = rosters.get(buildingId);
        if (perBuilding == null) return Optional.empty();
        return Optional.ofNullable(perBuilding.get(definitionId));
    }

    public void putRoster(BuildingRoster roster) {
        if (roster == null) return;
        rosters.computeIfAbsent(roster.buildingId(), k -> new HashMap<>())
                .put(roster.rosterDefinitionId(), roster);
        setDirty();
    }

    /** Phase 6.3.3.k.4 — exposes {@link #setDirty} to external systems
     *  (matches the {@code VillageSavedData.markDirty} idiom). The
     *  chunk-load + entity-death event handlers in
     *  {@code RosterChunkLoadHandler} need to flag persistence after
     *  realize / derealize / slot-removal mutations they apply to
     *  rosters held in this store. */
    public void markDirty() { setDirty(); }

    public void removeRoster(UUID buildingId, Identifier definitionId) {
        var perBuilding = rosters.get(buildingId);
        if (perBuilding == null) return;
        if (perBuilding.remove(definitionId) != null) setDirty();
        if (perBuilding.isEmpty()) rosters.remove(buildingId);
    }

    public Collection<BuildingRoster> getRostersForBuilding(UUID buildingId) {
        var perBuilding = rosters.get(buildingId);
        return perBuilding == null
                ? List.of()
                : Collections.unmodifiableCollection(perBuilding.values());
    }

    public Map<UUID, Map<Identifier, BuildingRoster>> getAllRosters() {
        return Collections.unmodifiableMap(rosters);
    }
}
