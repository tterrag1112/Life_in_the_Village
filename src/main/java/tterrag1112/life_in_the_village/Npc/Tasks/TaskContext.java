package tterrag1112.life_in_the_village.Npc.Tasks;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Per-evaluation context handed to {@link TaskFilter}s,
 * {@link Fulfillment}s, and {@link TaskExecutor}s. A small, honest façade
 * over existing data — it resolves what the core needs from the acting
 * entity and the world, and does NOT cache or duplicate game state.
 *
 * <p>For an NPC actor the context wraps the acting {@link TownspersonMob}
 * and derives its memberships / skill levels / profession directly from
 * it. The membership/role/workstation hooks read real per-actor state,
 * so they never silently pass — a filter requiring something the actor
 * lacks genuinely excludes it.</p>
 *
 * <p>Player-ready: callers that aren't NPC-backed can construct a
 * context with a {@code null} entity and override the hooks; the core
 * only calls the hooks, never reaches through {@link #npc()} itself.</p>
 */
public final class TaskContext {

    private final ServerLevel level;
    @Nullable private final TownspersonMob npc;
    private final long gameTime;

    public TaskContext(ServerLevel level, @Nullable TownspersonMob npc) {
        this.level = level;
        this.npc = npc;
        this.gameTime = level.getGameTime();
    }

    // ── Raw access ─────────────────────────────────────────────────────────────

    public ServerLevel level()  { return level; }
    public long gameTime()      { return gameTime; }

    /** The acting NPC, when the actor is NPC-backed; empty otherwise. */
    public Optional<TownspersonMob> npc() {
        return Optional.ofNullable(npc);
    }

    // ── Honest filter hooks ─────────────────────────────────────────────────────

    /** Skill level of the acting NPC; 0 when no NPC is bound. */
    public int skillLevel(Skill skill) {
        return npc == null ? 0 : npc.getSkills().getLevel(skill);
    }

    /** Profession of the acting NPC; empty when no NPC is bound. */
    public Optional<Profession> profession() {
        return npc == null ? Optional.empty() : Optional.ofNullable(npc.getProfession());
    }

    /**
     * Role-id of the acting actor. Empty in T0: there is no canonical
     * role-id string surface yet, so role-filtered tasks (none exist in
     * T0) correctly match no one rather than silently passing. Wired in
     * the migration phase that first issues a role-filtered task.
     */
    public Optional<String> roleId() {
        return Optional.empty();
    }

    /**
     * Whether the acting NPC has a usable workstation. T0 approximation:
     * an NPC with an assigned work building is treated as having a
     * workstation. Refined (block-level check) when a workstation-gated
     * task is first issued.
     */
    public boolean hasWorkstation() {
        return npc != null && npc.getAssignedBuildingId().isPresent();
    }

    /**
     * The {@link IssuerRef}s the acting NPC belongs to — the boards it
     * pulls from. Built from real assignment state:
     * <ul>
     *   <li>{@link LevelKind#NPC} keyed by the NPC's own UUID (personal board);</li>
     *   <li>{@link LevelKind#BUSINESS} keyed by its business id, if any;</li>
     *   <li>{@link LevelKind#HOUSEHOLD} keyed by its house id, if any.</li>
     * </ul>
     * VILLAGE / GUILD / KINGDOM / MONASTERY memberships are added by the
     * migration phases that introduce boards at those levels (resolving
     * the owning village/guild/etc. is a cross-system lookup deferred
     * until a consumer needs it — no speculative wiring in T0).
     */
    public Set<IssuerRef> memberships() {
        Set<IssuerRef> out = new LinkedHashSet<>();
        if (npc == null) return out;
        out.add(new IssuerRef(LevelKind.NPC, npc.getUUID()));
        npc.getBusinessId().ifPresent(id -> out.add(new IssuerRef(LevelKind.BUSINESS, id)));
        npc.getHouseId().ifPresent(id -> out.add(new IssuerRef(LevelKind.HOUSEHOLD, id)));
        return out;
    }

    /** True if the acting NPC belongs to {@code ref}. */
    public boolean isMemberOf(IssuerRef ref) {
        return memberships().contains(ref);
    }
}
