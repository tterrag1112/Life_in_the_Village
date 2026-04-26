package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.List;
import java.util.Optional;

/**
 * Petition the targeted NPC to consider you for an office. Spec
 * {@code 06-office-framework.md} → "Player verb additions":
 *
 * <pre>
 * "Petition for office" on incumbent or selection body
 * (relationship ≥ +20 gate).
 * </pre>
 *
 * <p>v1 surfaces a confirmation message and seeds a small petition memory
 * — the actual office-grant flow is the appointer's prerogative
 * (handled by the {@code AppointToOfficeVerb} from the appointer's side
 * or by the periodic election sweep). The verb is gated to incumbents
 * and council/selection-body members so it doesn't appear on every NPC
 * the player meets.</p>
 */
public final class PetitionForOfficeVerb implements PlayerVerb {

    public static final String VERB_ID = "petition_for_office";
    /** Per-NPC personal-relationship gate per spec. */
    public static final int RELATIONSHIP_GATE = 20;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Petition for office"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Ask them to consider you for an office they can fill."));
    }
    @Override public int displayOrder() { return 70; }
    @Override public int cooldownTicks() { return 24000; } // once per in-game day

    @Override
    public boolean isAvailable(VerbContext ctx) {
        if (ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) < RELATIONSHIP_GATE) {
            return false;
        }
        return !officesPetitionableThrough(ctx).isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) < RELATIONSHIP_GATE) {
            return VerbResult.failure("They don't trust you that much yet.");
        }
        List<String> targets = officesPetitionableThrough(ctx);
        if (targets.isEmpty()) {
            return VerbResult.failure("They have no office to grant you.");
        }
        // v1 effect: just acknowledge the petition. The appointer's daily
        // cycle factors relationship into the meritocratic / appointed
        // engine pass; a higher player-relationship nudge could land in a
        // follow-up patch (Phase 5 wiring of "player as candidate" in
        // the engine's pool).
        ctx.player().sendSystemMessage(Component.literal(
                "You petition " + ctx.npc().getNpcName() + " for: " + String.join(", ", targets)));
        return VerbResult.success("petition.response.default");
    }

    /**
     * Returns the office ids this NPC can plausibly grant the player —
     * either because they hold the office (incumbent) or because they
     * appoint someone who does. v1 limits to village offices for
     * practicality; the COMPANY/KINGDOM scopes plug in identically.
     */
    private static List<String> officesPetitionableThrough(VerbContext ctx) {
        java.util.UUID npcId = ctx.npc().getUUID();
        List<String> out = new java.util.ArrayList<>();
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .ifPresent(village -> {
                    var state = village.getOffices();
                    state.holdingsHeldBy(npcId).forEach(h -> {
                        // Incumbent of an office: can resign + pass it on
                        // (debug-grant level surface; the actual transfer
                        // is via /office grant from the dialogue side).
                        out.add(h.officeId());
                    });
                    // Appointer surface: if this NPC holds an office
                    // listed as "appointer" of another, list those too.
                    OfficeRegistry.all().forEach(def -> {
                        String appointer = tterrag1112.life_in_the_village.Npc.Office.Selection.AppointedSelection
                                .appointerFor(def.id());
                        if (appointer == null) return;
                        if (state.get(appointer)
                                .filter(h -> h.isHeldBy(npcId))
                                .isPresent()) {
                            out.add(def.id());
                        }
                    });
                });
        return out;
    }
}
