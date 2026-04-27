package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * World-level registry of {@link ApprenticeshipContract}s. Spec
 * line 65. Standalone {@code SavedData} (matches the spec's
 * "recommend standalone" note).
 *
 * <p>Three indices: by contract id, by apprentice (one active
 * contract per apprentice in v1), and by master (a master can hold
 * up to {@link ApprenticeshipContract#MAX_APPRENTICES_PER_MASTER}
 * apprentices). Indices rebuild from the list on load.</p>
 */
public class ApprenticeshipSavedData extends SavedData {

    public static final SavedDataType<ApprenticeshipSavedData> TYPE = new SavedDataType<>(
            "litv_apprenticeships",
            ApprenticeshipSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    ApprenticeshipContract.CODEC.listOf()
                            .optionalFieldOf("contracts", new ArrayList<>())
                            .forGetter(d -> new ArrayList<>(d.byContractId.values()))
            ).apply(i, ApprenticeshipSavedData::fromCodec))
    );

    private static ApprenticeshipSavedData fromCodec(List<ApprenticeshipContract> loaded) {
        ApprenticeshipSavedData d = new ApprenticeshipSavedData();
        for (ApprenticeshipContract c : loaded) d.indexInsert(c);
        return d;
    }

    // ── Fields / indices ───────────────────────────────────────────────────

    private final Map<UUID, ApprenticeshipContract> byContractId = new LinkedHashMap<>();
    private final Map<UUID, UUID> apprenticeToContract = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> masterToContracts = new LinkedHashMap<>();

    public ApprenticeshipSavedData() {}

    public static ApprenticeshipSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public Optional<ApprenticeshipContract> getById(UUID contractId) {
        return Optional.ofNullable(byContractId.get(contractId));
    }

    /** Active contract for an apprentice; empty if no active contract. */
    public Optional<ApprenticeshipContract> getByApprentice(UUID apprenticeId) {
        UUID cid = apprenticeToContract.get(apprenticeId);
        if (cid == null) return Optional.empty();
        ApprenticeshipContract c = byContractId.get(cid);
        if (c == null || c.status().isTerminal()) return Optional.empty();
        return Optional.of(c);
    }

    public List<ApprenticeshipContract> getByMaster(UUID masterId) {
        List<UUID> ids = masterToContracts.get(masterId);
        if (ids == null) return List.of();
        List<ApprenticeshipContract> out = new ArrayList<>();
        for (UUID cid : ids) {
            ApprenticeshipContract c = byContractId.get(cid);
            if (c != null) out.add(c);
        }
        return out;
    }

    public List<ApprenticeshipContract> getActiveByMaster(UUID masterId) {
        return getByMaster(masterId).stream()
                .filter(ApprenticeshipContract::isActive).toList();
    }

    public List<ApprenticeshipContract> all() {
        return List.copyOf(byContractId.values());
    }

    public List<ApprenticeshipContract> active() {
        return byContractId.values().stream()
                .filter(ApprenticeshipContract::isActive).toList();
    }

    public Map<UUID, ApprenticeshipContract> snapshot() {
        return Collections.unmodifiableMap(byContractId);
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /** Adds a new contract; existing contractId is overwritten. */
    public void add(ApprenticeshipContract contract) {
        if (contract == null) return;
        // Remove any prior index entries for this contract id.
        ApprenticeshipContract prior = byContractId.get(contract.contractId());
        if (prior != null) indexRemove(prior);
        indexInsert(contract);
        setDirty();
    }

    /** Replaces an existing contract; no-op if id unknown. */
    public void update(ApprenticeshipContract contract) {
        if (contract == null) return;
        if (!byContractId.containsKey(contract.contractId())) return;
        ApprenticeshipContract prior = byContractId.get(contract.contractId());
        indexRemove(prior);
        indexInsert(contract);
        setDirty();
    }

    public void remove(UUID contractId) {
        ApprenticeshipContract c = byContractId.remove(contractId);
        if (c == null) return;
        // Re-clean indices.
        if (apprenticeToContract.get(c.apprenticeId()) != null
                && apprenticeToContract.get(c.apprenticeId()).equals(contractId)) {
            apprenticeToContract.remove(c.apprenticeId());
        }
        List<UUID> mc = masterToContracts.get(c.masterId());
        if (mc != null) mc.remove(contractId);
        setDirty();
    }

    private void indexInsert(ApprenticeshipContract c) {
        byContractId.put(c.contractId(), c);
        if (c.isActive()) {
            apprenticeToContract.put(c.apprenticeId(), c.contractId());
        }
        masterToContracts.computeIfAbsent(c.masterId(), k -> new ArrayList<>())
                .add(c.contractId());
    }

    private void indexRemove(ApprenticeshipContract c) {
        byContractId.remove(c.contractId());
        if (apprenticeToContract.get(c.apprenticeId()) != null
                && apprenticeToContract.get(c.apprenticeId()).equals(c.contractId())) {
            apprenticeToContract.remove(c.apprenticeId());
        }
        List<UUID> mc = masterToContracts.get(c.masterId());
        if (mc != null) mc.remove(c.contractId());
    }
}
