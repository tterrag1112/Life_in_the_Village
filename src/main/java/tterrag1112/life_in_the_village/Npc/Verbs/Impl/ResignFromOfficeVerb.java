package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeElection;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resign from an office the player currently holds. Spec brief lists this
 * as "Resign from office on self" — v1 routes it through any NPC dialogue
 * surface in the org the player serves (e.g. you tell the village herald
 * you're stepping down). Lists every office the player holds in the
 * targeted NPC's village, vacates the first one, and runs an immediate
 * election to refill.
 *
 * <p>For multiple held offices a follow-up screen would let the player
 * choose; v1 picks deterministically by office id.</p>
 */
public final class ResignFromOfficeVerb implements PlayerVerb {

    public static final String VERB_ID = "resign_from_office";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Resign from office"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Step down from an office you currently hold."));
    }
    @Override public int displayOrder() { return 80; }
    @Override public int cooldownTicks() { return 0; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return !findResignableHere(ctx).isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        List<OfficeRegistry.Match> matches = findResignableHere(ctx);
        if (matches.isEmpty()) return VerbResult.failure("You hold no office here to resign.");
        // Only vacate the matched office on this village; calling
        // vacateAllHeldBy would also clear offices on other orgs, which
        // is too aggressive for a village-scoped dialogue verb.
        OfficeRegistry.Match m = matches.get(0);
        // Re-seat as vacant via seatNpc-style path: easier to just
        // vacate then run an election. The driver's seatNpc takes a
        // specific NPC, so we go through OfficeElection.runElection
        // after a manual vacate.
        var state = VillageSavedData.get(ctx.level()).getVillageById(m.orgId())
                .map(v -> v.getOffices()).orElse(null);
        if (state != null) {
            state.vacate(m.holding().officeId());
            VillageSavedData.get(ctx.level()).markDirty();
            // recordedAs comes from the office's recorded selection so the
            // refill uses the spec'd method, not the default.
            OfficeElection.runElection(m.orgType(), m.orgId(),
                    m.holding().officeId(), ctx.level());
        }
        ctx.player().sendSystemMessage(Component.literal(
                "You step down from " + displayName(m.holding().officeId())
                        + " of " + m.orgDisplayName()));
        return VerbResult.success("resign.response.default");
    }

    /**
     * Player offices on the same village as the target NPC. Restricting
     * to the targeted village is what makes this a contextual verb
     * rather than "list every office anywhere." Empty when the NPC has
     * no assigned village.
     */
    private static List<OfficeRegistry.Match> findResignableHere(VerbContext ctx) {
        UUID playerId = ctx.player().getUUID();
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Optional<UUID> targetVillage = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .map(v -> v.getId());
        if (targetVillage.isEmpty()) return List.of();
        return OfficeRegistry.findOfficesHeldBy(playerId, ctx.level()).stream()
                .filter(m -> m.orgType() == OrgType.VILLAGE
                        && targetVillage.get().equals(m.orgId()))
                .toList();
    }

    private static String displayName(String officeId) {
        var def = OfficeRegistry.get(officeId);
        return def == null ? officeId : def.displayName();
    }
}
