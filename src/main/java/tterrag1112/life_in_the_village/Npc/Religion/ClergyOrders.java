package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Religion Rework R3c — religion-specific clergy <em>orders</em>: the
 * "branch/identity" axis of the hybrid clergy model (skill = capability,
 * specialization = order, office = leadership). Each religion has one locked,
 * assignment-driven order (a PRIEST specialization), assigned at the same
 * points an NPC becomes clergy — populator spawn and {@code handleOrdination}.
 *
 * <p>This hub lives in {@code Npc.Religion} (which already depends on
 * {@code Npc.Specialization} via the ordination spec assignment), so it can
 * map religion ids → order {@link SpecializationDef}s without creating a
 * package cycle. The order defs themselves are registered in
 * {@link NpcSpecializationTypes} (the registry idiom); religion-awareness +
 * the map live here, mirroring {@link ReligionContent}.</p>
 *
 * <h3>Sparse-friendly</h3>
 * A religion with no registered order (a future / unknown faith) falls back to
 * the generalist {@code PRIEST_CLERIC} via the existing
 * {@link NpcSpecializationTypes#assignInitialSpawnSpec} route — so adding a
 * religion without an order is a no-op-equivalent, no error.
 *
 * <h3>Orthogonality</h3>
 * Orders are identity (assigned locked, bypassing the skill gate). They are
 * orthogonal to the R1d skill-derived clergy rank (an "Initiate of the
 * Threadkeepers") and to office/rank — none of those are touched here.
 *
 * <h3>One-per-religion foundation</h3>
 * The map is religionId → one order today; it expands cleanly to sub-orders
 * (e.g. a {@code Map<String, List<SpecializationDef>>} + a selection rule)
 * when the multi-sub-order phase needs it. Not built speculatively here.
 */
public final class ClergyOrders {

    private ClergyOrders() {}

    /** religionId → its single clergy order. A religion absent here falls back
     *  to the generalist {@code PRIEST_CLERIC}. */
    private static final Map<String, SpecializationDef> ORDERS = Map.of(
            ReligionRegistry.SUNSTEAD,    NpcSpecializationTypes.PRIEST_DAWN,
            ReligionRegistry.THE_LOOM,    NpcSpecializationTypes.PRIEST_THREADKEEPERS,
            ReligionRegistry.TIDECALL,    NpcSpecializationTypes.PRIEST_TIDEWARDENS,
            ReligionRegistry.FORGE_CREED, NpcSpecializationTypes.PRIEST_ANCESTOR_KEEPERS);

    // ── Assignment ────────────────────────────────────────────────────────

    /**
     * Religion-aware clergy spec assignment. For a PRIEST, assigns the village
     * religion's order (locked, force-assigned — identity, not capability);
     * for a religion with no registered order, delegates to the generalist
     * spawn route. No-op for non-PRIEST. Called at BOTH clergy-assignment
     * points (populator spawn + {@code RiteExecutor.handleOrdination}).
     */
    public static void assignClergyOrder(ServerLevel level, TownspersonMob npc) {
        if (npc == null || npc.getProfession() != Profession.PRIEST) return;
        SpecializationDef order = ORDERS.get(clergyFaith(level, npc));
        if (order == null) {
            // Order-less religion → generalist fallback via the existing route.
            NpcSpecializationTypes.assignInitialSpawnSpec(npc, npc.getProfession());
            return;
        }
        var comp = npc.getSpecializationComponent();
        if (comp.assign(order, npc, true)) comp.setLocked(true);
    }

    /** The display name of {@code npc}'s assigned order, or empty when it is the
     *  generalist / not a clergy order — used for ordination's initiation
     *  flavor. */
    public static Optional<String> assignedOrderName(TownspersonMob npc) {
        if (npc == null) return Optional.empty();
        return npc.getSpecializationComponent().get()
                .filter(def -> def.profession() == Profession.PRIEST)
                .filter(def -> !def.name().equals(NpcSpecializationTypes.PRIEST_CLERIC.name()))
                .map(def -> def.displayName().getString());
    }

    // ── Behavioral signature (rite-claim preference nudge) ────────────────

    /** Each order's focus rites — the ceremonies that most express its faith.
     *  Used as a SECONDARY tiebreak in {@code PriestBehavior.findClaimableRite}
     *  (the R1b building band stays primary). */
    private static final Map<Identifier, Set<Rite>> FOCUS = Map.of(
            NpcSpecializationTypes.PRIEST_DAWN.name(),
            Set.of(Rite.HARVEST_THANKSGIVING, Rite.FEAST_DAY),
            NpcSpecializationTypes.PRIEST_THREADKEEPERS.name(),
            Set.of(Rite.CONFESSION, Rite.PURIFICATION),
            NpcSpecializationTypes.PRIEST_TIDEWARDENS.name(),
            Set.of(Rite.BLESSING),
            NpcSpecializationTypes.PRIEST_ANCESTOR_KEEPERS.name(),
            Set.of(Rite.FUNERAL, Rite.VIGIL));

    /** Whether {@code rite} is a focus rite of the order identified by
     *  {@code orderId} (the priest's spec id). Generalist / unknown → false
     *  (no nudge). */
    public static boolean isFocusRite(Identifier orderId, Rite rite) {
        if (orderId == null || rite == null) return false;
        Set<Rite> focus = FOCUS.get(orderId);
        return focus != null && focus.contains(rite);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * R3e-2 — the faith whose order this priest takes: their assigned religious
     * building's faith ({@code BuildingFaith.clergyFaith}) when they staff one
     * (so a Tidecall shrine in a Sunstead village yields a Tidewarden), else the
     * village dominant. Generalizes the prior dominant-only resolution; no forked
     * path.
     */
    private static String clergyFaith(ServerLevel level, TownspersonMob npc) {
        if (level == null) return ReligionRegistry.SUNSTEAD;
        Village v = npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (v == null) return ReligionRegistry.SUNSTEAD;
        return BuildingFaith.clergyFaith(level, v, npc);
    }
}
