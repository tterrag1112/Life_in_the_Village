package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeElection;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Office.Selection.AppointedSelection;
import tterrag1112.life_in_the_village.Npc.Office.Selection.CandidatePool;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Appoint an NPC to an office the player has the authority to fill. Spec
 * line: "'Appoint to office' verb on candidate NPC, only if player holds
 * the appointing office."
 *
 * <p>v1 contract:</p>
 * <ul>
 *   <li>Verb is available iff the player holds at least one office that
 *   appoints another, AND the targeted NPC is eligible for at least one
 *   of those appointable offices.</li>
 *   <li>Invocation seats the candidate via {@link OfficeElection#seatNpc}
 *   so the {@link tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Promoted}
 *   event fires the same way silent elections would.</li>
 *   <li>If multiple appointable offices match, picks the lowest-id
 *   match deterministically and reports back. The full picker UI lands
 *   in a follow-up office-management screen (Phase 3 follow-up).</li>
 * </ul>
 */
public final class AppointToOfficeVerb implements PlayerVerb {

    public static final String VERB_ID = "appoint_to_office";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Appoint to office"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Hand them an office you have the authority to grant."));
    }
    @Override public int displayOrder() { return 75; }
    @Override public int cooldownTicks() { return 0; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return findFirstAppointable(ctx).isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        Optional<Match> matchOpt = findFirstAppointable(ctx);
        if (matchOpt.isEmpty()) {
            return VerbResult.failure("You can't grant any office to them.");
        }
        Match m = matchOpt.get();
        OfficeElection.seatNpc(m.orgType, m.orgId, m.officeId, ctx.npc().getUUID(),
                tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.APPOINTED,
                ctx.level());
        ctx.player().sendSystemMessage(Component.literal(
                "Appointed " + ctx.npc().getNpcName() + " as " + displayName(m.officeId)
                        + " of " + m.orgDisplayName));
        return VerbResult.success("appoint.response.default");
    }

    /** First appointable (officeId, org) pair available to this player. */
    private static Optional<Match> findFirstAppointable(VerbContext ctx) {
        UUID playerId = ctx.player().getUUID();
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        // Restrict to the targeted NPC's village so an off-village leader
        // doesn't accidentally appoint someone elsewhere. The NPC must
        // also be eligible for the office.
        return ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .flatMap(village -> findIn(ctx, OrgType.VILLAGE, village.getId(),
                        village.getName(), village.getOffices(), playerId));
    }

    private static Optional<Match> findIn(VerbContext ctx, OrgType orgType, UUID orgId,
                                          String orgDisplayName, OfficeState state,
                                          UUID playerId) {
        for (Map.Entry<String, OfficeHolding> e : state.snapshot().entrySet()) {
            OfficeDefinition def = OfficeRegistry.get(e.getKey());
            if (def == null) continue;
            if (def.defaultSelection() != tterrag1112.life_in_the_village.Npc.Office.SelectionMethod.APPOINTED) continue;
            String appointer = AppointedSelection.appointerFor(def.id());
            if (appointer == null) continue;
            // Player must hold the appointer office on this org.
            OfficeHolding appointerHolding = state.get(appointer).orElse(null);
            if (appointerHolding == null || !appointerHolding.isHeldBy(playerId)) continue;
            if (!CandidatePool.isEligible(ctx.npc(), def)) continue;
            return Optional.of(new Match(orgType, orgId, orgDisplayName, def.id()));
        }
        return Optional.empty();
    }

    private static String displayName(String officeId) {
        OfficeDefinition def = OfficeRegistry.get(officeId);
        return def == null ? officeId : def.displayName();
    }

    private record Match(OrgType orgType, UUID orgId, String orgDisplayName, String officeId) {}
}
