package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-organization office container. One {@code OfficeState} lives on
 * each {@code Village}, {@code GuildData}, {@code Company}, and
 * {@code Kingdom}.
 *
 * <p>Single-seat-per-id in v1: the spec lists {@code kingdom_council_seat}
 * as a multi-seat office, but flags it as "stubbed in v1". Phase 3
 * extends multi-seat by mangling per-seat ids
 * (e.g. {@code kingdom_council_seat:&lt;villageId&gt;}) or by promoting
 * the value type to {@code List<OfficeHolding>}; either is a non-breaking
 * change because nothing reads council seats in Phase 0.</p>
 *
 * <p>See {@code docs/npc_redesign/06-office-framework.md}.</p>
 */
public final class OfficeState {

    private final OrgType orgType;
    /** Insertion order preserved so debug listings are stable. */
    private final Map<String, OfficeHolding> holdings = new LinkedHashMap<>();

    public OfficeState(OrgType orgType) {
        this.orgType = orgType;
    }

    public OrgType orgType() { return orgType; }

    /**
     * Returns an OfficeState pre-populated with vacant holdings for every
     * office definition belonging to the given org type. Used at org
     * creation time.
     */
    public static OfficeState emptyFor(OrgType orgType, UUID orgId) {
        OfficeState state = new OfficeState(orgType);
        for (OfficeDefinition def : OfficeRegistry.forOrgType(orgType)) {
            state.holdings.put(def.id(), OfficeHolding.vacant(def.id(), orgId, def.defaultSelection()));
        }
        return state;
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public Optional<OfficeHolding> get(String officeId) {
        return Optional.ofNullable(holdings.get(officeId));
    }

    /** Spec compatibility: {@code getHolders} returns 0 or 1 entries in v1. */
    public List<OfficeHolding> getHolders(String officeId) {
        OfficeHolding h = holdings.get(officeId);
        if (h == null || h.isVacant()) return List.of();
        return List.of(h);
    }

    public boolean isVacant(String officeId) {
        OfficeHolding h = holdings.get(officeId);
        return h == null || h.isVacant();
    }

    public Set<String> allOffices() {
        return Collections.unmodifiableSet(holdings.keySet());
    }

    public Map<String, OfficeHolding> snapshot() {
        return Collections.unmodifiableMap(holdings);
    }

    public boolean isHeldBy(UUID uuid) {
        if (uuid == null) return false;
        for (OfficeHolding h : holdings.values()) {
            if (h.isHeldBy(uuid)) return true;
        }
        return false;
    }

    /** Returns every holding currently held by the given UUID. */
    public List<OfficeHolding> holdingsHeldBy(UUID uuid) {
        if (uuid == null) return List.of();
        return holdings.values().stream()
                .filter(h -> h.isHeldBy(uuid))
                .toList();
    }

    public long timeUntilTermEnd(String officeId, long currentTick) {
        OfficeHolding h = holdings.get(officeId);
        if (h == null || h.termEndTick() == 0L) return -1L;
        return Math.max(0L, h.termEndTick() - currentTick);
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    public void set(String officeId, OfficeHolding holding) {
        holdings.put(officeId, holding);
    }

    /**
     * Spec method {@code vacate(officeId)}: replaces the holding with a
     * vacant version. Selection method is preserved so Phase 3 selection
     * logic can re-fill using the right algorithm.
     */
    public void vacate(String officeId) {
        OfficeHolding existing = holdings.get(officeId);
        if (existing == null) return;
        holdings.put(officeId,
                OfficeHolding.vacant(officeId, existing.orgId(), existing.actualSelection()));
    }

    /** Vacate only when the current holder matches {@code holderId}. */
    public boolean vacateIfHeldBy(String officeId, UUID holderId) {
        OfficeHolding existing = holdings.get(officeId);
        if (existing == null || !existing.isHeldBy(holderId)) return false;
        vacate(officeId);
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<OfficeState> CODEC = RecordCodecBuilder.create(i -> i.group(
            OrgType.CODEC.fieldOf("orgType").forGetter(s -> s.orgType),
            OfficeHolding.CODEC.listOf().optionalFieldOf("holdings", List.of())
                    .forGetter(s -> List.copyOf(s.holdings.values()))
    ).apply(i, OfficeState::fromCodec));

    public static OfficeState fromCodec(OrgType orgType, List<OfficeHolding> holdings) {
        OfficeState s = new OfficeState(orgType);
        for (OfficeHolding h : holdings) {
            s.holdings.put(h.officeId(), h);
        }
        return s;
    }
}
