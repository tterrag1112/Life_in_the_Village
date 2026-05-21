package tterrag1112.life_in_the_village.Npc.Roles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Phase 6.3.2.a — unified Role axis on TownspersonMob. Holds the
 * current list of {@link RoleAssignment}s the NPC carries.
 *
 * <p>Identity rule: at most one assignment per RoleType. Re-assigning
 * an existing RoleType overwrites parameters/lifetime (so projection
 * sync points are idempotent).
 *
 * <p>Persistence mirrors {@code VisitorState} / {@code SkillComponent}:
 * save / load via ValueOutput / ValueInput under a single SAVE_KEY.
 *
 * <p>Behaviors should query via this component or {@link NpcRoles}
 * rather than reaching into CaravanSavedData / ApprenticeshipManager
 * directly for role-shape queries.
 */
public final class NpcRoleComponent {

    private static final String SAVE_KEY = "npcRoles";

    private final List<RoleAssignment> assignments = new ArrayList<>();

    public NpcRoleComponent() {}

    private NpcRoleComponent(List<RoleAssignment> initial) {
        if (initial != null) assignments.addAll(initial);
    }

    // ── Queries ──────────────────────────────────────────────────────────

    public boolean hasRole(RoleType type) {
        for (RoleAssignment a : assignments) if (a.type().equals(type)) return true;
        return false;
    }

    public Optional<RoleAssignment> getRole(RoleType type) {
        for (RoleAssignment a : assignments) if (a.type().equals(type)) return Optional.of(a);
        return Optional.empty();
    }

    public List<RoleAssignment> getAllRoles() {
        return Collections.unmodifiableList(assignments);
    }

    // ── Mutators ─────────────────────────────────────────────────────────

    /** Assigns (or replaces) the role of the given type. */
    public void assignRole(RoleAssignment assignment) {
        removeRole(assignment.type());
        assignments.add(assignment);
    }

    /** Removes the role of the given type. Returns true if removed. */
    public boolean removeRole(RoleType type) {
        return assignments.removeIf(a -> a.type().equals(type));
    }

    /** Convenience: update the parameters on an existing role (no-op if absent). */
    public void updateRoleParameters(RoleType type, java.util.Map<String, String> params) {
        for (int i = 0; i < assignments.size(); i++) {
            RoleAssignment a = assignments.get(i);
            if (a.type().equals(type)) {
                assignments.set(i, a.withParameters(params));
                return;
            }
        }
    }

    /** Expires any TIMED roles whose expiry tick has passed. */
    public void tickRoles(long currentTick) {
        assignments.removeIf(a -> a.lifetime().isExpired(currentTick));
    }

    public void clearAll() { assignments.clear(); }

    // ── Persistence ──────────────────────────────────────────────────────

    public static final Codec<NpcRoleComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            RoleAssignment.CODEC.listOf().optionalFieldOf("roles", List.of())
                    .forGetter(c -> List.copyOf(c.assignments))
    ).apply(i, NpcRoleComponent::new));

    public void save(ValueOutput output) { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NpcRoleComponent loaded = read.get();
        assignments.clear();
        assignments.addAll(loaded.assignments);
        return true;
    }
}
