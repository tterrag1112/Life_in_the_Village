package tterrag1112.life_in_the_village.Npc.Tasks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionBridge;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@link TaskContext} specialization for a player actor. Overrides the
 * six honest-filter hooks to derive values from the player's
 * {@link PlayerProfessionData} and owned businesses rather than from an
 * NPC entity.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #npc()} is always empty — no NPC is bound.</li>
 *   <li>{@link #skillLevel(Skill)} returns the player's active profession
 *       level iff the queried skill matches the active profession's primary
 *       skill ({@link PlayerProfessionBridge#primarySkill}); else 0. This
 *       is the capability bridge: a player's "skill" in the task system is
 *       their active-profession level, only for that profession's axis.</li>
 *   <li>{@link #profession()} maps the active profession to its NPC analog
 *       via {@link PlayerProfessionBridge#toNpcProfession}.</li>
 *   <li>{@link #memberships()} returns one {@link IssuerRef} per business
 *       the player owns (v1: owned only, not employed-at) plus a personal
 *       NPC-level board keyed on the player's UUID.</li>
 *   <li>{@link #hasWorkstation()} returns true when the player has a
 *       registered workplace for the active profession.</li>
 *   <li>{@link #roleId()} returns empty — player role-id wiring is deferred
 *       to the phase that first issues a role-filtered task to players.</li>
 * </ul>
 *
 * <p>P0: nothing in live game code constructs or calls this class yet; it is
 * foundation for the P1 dispatcher integration.</p>
 */
public final class PlayerTaskContext extends TaskContext {

    private final ServerPlayer player;
    private final PlayerProfessionData profData;

    /**
     * Constructs a context for {@code player}. Reads profession attachment
     * eagerly so all hook calls are free of attachment lookups per call.
     */
    public PlayerTaskContext(ServerLevel level, ServerPlayer player) {
        super(level, null);
        this.player = player;
        this.profData = player.getData(ModData.PROFESSION_DATA);
    }

    // ── TaskContext hook overrides ────────────────────────────────────────────

    /**
     * Always empty: this context is not NPC-backed.
     */
    @Override
    public Optional<tterrag1112.life_in_the_village.Entities.custom.TownspersonMob> npc() {
        return Optional.empty();
    }

    /**
     * Returns the player's active-profession level if the queried skill
     * matches the active profession's primary skill; else 0.
     *
     * <p>Example: active = MINER, queried skill = MINING → returns miner
     * level (0–4). Active = MINER, queried = FARMING → returns 0.</p>
     */
    @Override
    public int skillLevel(Skill skill) {
        return profData.getActiveProfession()
                .filter(pp -> PlayerProfessionBridge.primarySkill(pp) == skill)
                .map(profData::getLevel)
                .orElse(0);
    }

    /**
     * Maps the active {@link PlayerProfession} to the nearest NPC
     * {@link Profession} via {@link PlayerProfessionBridge}.
     * Empty when no active profession is set or when the active profession
     * has no NPC analog (e.g. {@link PlayerProfession#ROAD_ENGINEER}).
     */
    @Override
    public Optional<Profession> profession() {
        return profData.getActiveProfession()
                .flatMap(PlayerProfessionBridge::toNpcProfession);
    }

    /**
     * Empty in P0 — player role-id wiring is deferred to the first phase
     * that issues a role-filtered task to players.
     */
    @Override
    public Optional<String> roleId() {
        return Optional.empty();
    }

    /**
     * True when the player has a registered workplace for the active
     * profession. Approximation consistent with the NPC "assigned building"
     * check; block-level workstation validation deferred to P-later.
     */
    @Override
    public boolean hasWorkstation() {
        return profData.getActiveProfession()
                .map(profData::hasWorkplace)
                .orElse(false);
    }

    /**
     * Returns the boards this player can pull tasks from:
     * <ul>
     *   <li>One {@link LevelKind#BUSINESS} ref per owned business (v1:
     *       owned only — employed-at businesses deferred to P-later).</li>
     *   <li>One {@link LevelKind#NPC} ref keyed on the player's own UUID
     *       (personal task board).</li>
     * </ul>
     */
    @Override
    public Set<IssuerRef> memberships() {
        Set<IssuerRef> out = new LinkedHashSet<>();
        // Owned businesses
        BusinessSavedData.get(level())
                .getByOwner(player.getUUID())
                .forEach(b -> out.add(new IssuerRef(LevelKind.BUSINESS, b.getBusinessId())));
        // Personal board
        out.add(new IssuerRef(LevelKind.NPC, player.getUUID()));
        return out;
    }

    // ── Player-specific access ────────────────────────────────────────────────

    /** The underlying player entity. Always present in this context type. */
    public Optional<ServerPlayer> player() {
        return Optional.of(player);
    }
}
