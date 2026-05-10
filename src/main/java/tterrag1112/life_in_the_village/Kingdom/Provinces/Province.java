package tterrag1112.life_in_the_village.Kingdom.Provinces;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.3 — political subdivision of a {@code Kingdom}.
 *
 * <p>Stored on the kingdom via {@code KingdomGovernanceData.provinces}.
 * Computed by per-culture {@code ProvinceSubdivider} implementations
 * (TERRITORIAL = cell-set Voronoi over noble-manor / village-centroid
 * seeds; TRIBAL = grouped by dominant noble-house affiliation;
 * FUNCTIONAL = grouped by derived village role; UNITARY = no
 * subdivisions).
 *
 * <h3>Cell-set polygons</h3>
 * Provinces aren't vertex polygons — they're discrete sets of atlas
 * cell keys, matching the kingdom's existing
 * {@code KingdomClaim.claimedCellKeys} model. Rendering colour-fills
 * each cell on the {@code KingdomMapPanel} via a new
 * {@code ProvinceLayer}.
 *
 * <h3>Stability</h3>
 * Per-province stability scalar (0..100) separate from kingdom
 * stability. {@link #modifiers} stack additive deltas with optional
 * expiry per the same shape as {@link KingdomModifier}.
 *
 * <h3>Reports</h3>
 * Daily {@link ProvincialReport} entries appended by the daily tick;
 * bounded buffer of {@link ProvincialReport#BUFFER_CAPACITY} entries.
 *
 * @param id              stable UUID; matches {@code Kingdom.replaceProvince}.
 * @param name            display name; defaults to "{kingdom} Province {n}"
 *                        on creation, can be edited later.
 * @param kingdomId       owning kingdom.
 * @param governorUuid    current governor NPC, or empty when ungoverned
 *                        (no qualifying noble; see {@code GovernorSelector}).
 * @param villageIds      member villages (centroid lands inside province cells).
 * @param cellKeys        atlas cell keys belonging to this province.
 * @param stability       0..100 baseline stability scalar.
 * @param treasury        bronze accumulated by the governor (province-tier
 *                        treasury; separate from kingdom treasury).
 * @param createdTick     server tick the province first formed.
 * @param modifiers       active stability/legitimacy modifiers (province scope).
 * @param reports         rolling daily-report buffer
 *                        (FIFO, capped at {@link ProvincialReport#BUFFER_CAPACITY}).
 */
public record Province(
        UUID id,
        String name,
        UUID kingdomId,
        Optional<UUID> governorUuid,
        List<UUID> villageIds,
        List<Long> cellKeys,
        int stability,
        long treasury,
        long createdTick,
        List<KingdomModifier> modifiers,
        List<ProvincialReport> reports
) {

    /** Default starting stability for a freshly-formed province. */
    public static final int DEFAULT_STABILITY = 60;

    public Province {
        // Defensive null-handling for codec-driven loads.
        villageIds = villageIds == null ? List.of() : List.copyOf(villageIds);
        cellKeys   = cellKeys   == null ? List.of() : List.copyOf(cellKeys);
        modifiers  = modifiers  == null ? List.of() : List.copyOf(modifiers);
        reports    = reports    == null ? List.of() : List.copyOf(reports);
        if (name == null || name.isEmpty()) name = "Unnamed Province";
        if (governorUuid == null) governorUuid = Optional.empty();
        // Clamp stability to 0..100.
        stability = Math.max(0, Math.min(100, stability));
        if (treasury < 0L) treasury = 0L;
    }

    public static final Codec<Province> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Province::id),
            Codec.STRING.fieldOf("name").forGetter(Province::name),
            UUIDUtil.CODEC.fieldOf("kingdomId").forGetter(Province::kingdomId),
            UUIDUtil.CODEC.optionalFieldOf("governorUuid").forGetter(Province::governorUuid),
            UUIDUtil.CODEC.listOf().optionalFieldOf("villageIds", List.of())
                    .forGetter(p -> List.copyOf(p.villageIds)),
            Codec.LONG.listOf().optionalFieldOf("cellKeys", List.of())
                    .forGetter(p -> List.copyOf(p.cellKeys)),
            Codec.INT.optionalFieldOf("stability", DEFAULT_STABILITY).forGetter(Province::stability),
            Codec.LONG.optionalFieldOf("treasury", 0L).forGetter(Province::treasury),
            Codec.LONG.optionalFieldOf("createdTick", 0L).forGetter(Province::createdTick),
            KingdomModifier.CODEC.listOf().optionalFieldOf("modifiers", List.of())
                    .forGetter(p -> List.copyOf(p.modifiers)),
            ProvincialReport.CODEC.listOf().optionalFieldOf("reports", List.of())
                    .forGetter(p -> List.copyOf(p.reports))
    ).apply(i, Province::new));

    // ── Queries ─────────────────────────────────────────────────────────────

    public boolean isGoverned() { return governorUuid.isPresent(); }
    public boolean containsVillage(UUID villageId) {
        for (UUID v : villageIds) if (v.equals(villageId)) return true;
        return false;
    }
    public boolean containsCell(long cellKey) { return cellKeys.contains(cellKey); }

    /** Sum of all modifier stability deltas. Effective stability = base + this. */
    public int stabilityModifierSum() {
        int s = 0;
        for (KingdomModifier m : modifiers) s += m.stabilityDelta();
        return s;
    }

    // ── Copy-helpers (record is immutable; mutations create copies) ────────

    public Province withGovernor(UUID newGovernor) {
        return new Province(id, name, kingdomId, Optional.ofNullable(newGovernor),
                villageIds, cellKeys, stability, treasury, createdTick,
                modifiers, reports);
    }

    public Province withoutGovernor() {
        return new Province(id, name, kingdomId, Optional.empty(),
                villageIds, cellKeys, stability, treasury, createdTick,
                modifiers, reports);
    }

    public Province withMembership(List<UUID> newVillageIds, List<Long> newCellKeys) {
        return new Province(id, name, kingdomId, governorUuid,
                newVillageIds, newCellKeys, stability, treasury, createdTick,
                modifiers, reports);
    }

    public Province withStability(int newStability) {
        return new Province(id, name, kingdomId, governorUuid,
                villageIds, cellKeys, newStability, treasury, createdTick,
                modifiers, reports);
    }

    public Province withTreasury(long newTreasury) {
        return new Province(id, name, kingdomId, governorUuid,
                villageIds, cellKeys, stability, newTreasury, createdTick,
                modifiers, reports);
    }

    public Province withName(String newName) {
        String resolved = (newName == null || newName.isBlank()) ? this.name : newName;
        return new Province(id, resolved, kingdomId, governorUuid,
                villageIds, cellKeys, stability, treasury, createdTick,
                modifiers, reports);
    }

    /** Appends a daily report; trims oldest entries to keep the buffer bounded. */
    public Province appendReport(ProvincialReport report) {
        if (report == null) return this;
        List<ProvincialReport> updated = new ArrayList<>(reports);
        updated.add(report);
        while (updated.size() > ProvincialReport.BUFFER_CAPACITY) updated.remove(0);
        return new Province(id, name, kingdomId, governorUuid,
                villageIds, cellKeys, stability, treasury, createdTick,
                modifiers, Collections.unmodifiableList(updated));
    }

    public Province withModifiers(List<KingdomModifier> newModifiers) {
        return new Province(id, name, kingdomId, governorUuid,
                villageIds, cellKeys, stability, treasury, createdTick,
                newModifiers == null ? List.of() : List.copyOf(newModifiers),
                reports);
    }

    /** Creates a fresh province with default stability + empty buffers. */
    public static Province fresh(UUID id, String name, UUID kingdomId,
                                 Optional<UUID> governor,
                                 List<UUID> villageIds, List<Long> cellKeys,
                                 long createdTick) {
        return new Province(id, name, kingdomId, governor,
                villageIds, cellKeys,
                DEFAULT_STABILITY, 0L, createdTick,
                List.of(), List.of());
    }
}
