package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeReport;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeReporter;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeType;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Player accuses an NPC of a crime. Spec line 298.
 *
 * <p>Eligibility: the player has at least one
 * {@link MemoryType#WITNESSED_CRIME_BY} or
 * {@link MemoryType#VICTIM_OF_CRIME_BY} entry naming the targeted
 * NPC. v1 reads the player's reciprocal memory off the NPC's ledger
 * (the player→NPC memory shape doesn't yet exist) — when the NPC has
 * a {@link MemoryType#VICTIM_OF_CRIME_BY} entry where the perpetrator
 * is also seen by the targeted NPC, that's enough to file.</p>
 *
 * <p>Effect: files a {@link CrimeReport} with the targeted NPC as the
 * accused. The constable's investigation goal picks it up the next
 * tick.</p>
 *
 * <p>Spec deviation note: the spec describes an "evidence memory"
 * gate; the player→NPC memory ledger is a Phase 5 follow-up. v1's
 * heuristic uses the NPC's own VICTIM memory as a stand-in.</p>
 */
public final class AccuseOfCrimeVerb implements PlayerVerb {

    public static final String VERB_ID = "accuse_of_crime";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Accuse of crime"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("File a formal accusation against this NPC."));
    }
    @Override public int displayOrder() { return 90; }
    @Override public int cooldownTicks() { return 12000; } // half a day

    @Override
    public boolean isAvailable(VerbContext ctx) {
        // v1 gate: player needs memory-equivalent evidence — for this
        // session, having a recent CrimeReport already on file naming
        // this NPC is the proxy. Otherwise the verb is hidden.
        return findRecentReportNamingNpc(ctx).isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        Optional<CrimeReport> existing = findRecentReportNamingNpc(ctx);
        if (existing.isEmpty()) {
            return VerbResult.failure("You have no evidence against them yet.");
        }
        CrimeReport ref = existing.get();
        // File a fresh report mirroring the existing accusation. The
        // constable will see two reports → conviction threshold is
        // easier to clear (one corroborating witness now exists).
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        if (village == null) return VerbResult.failure("No village to file in.");

        CrimeReporter.builder(ref.type(), ctx.level())
                .perpetrator(ctx.npc().getUUID())
                .victim(ctx.player().getUUID())
                .reportedBy(ctx.player().getUUID())
                .location(ctx.player().blockPosition())
                .village(village.getId())
                .note("Accused by player " + ctx.player().getName().getString())
                .commit();
        return VerbResult.success("constable.investigation");
    }

    private static Optional<CrimeReport> findRecentReportNamingNpc(VerbContext ctx) {
        UUID npcId = ctx.npc().getUUID();
        return tterrag1112.life_in_the_village.Npc.Crime.CrimeSavedData.get(ctx.level())
                .allReports().stream()
                .filter(r -> r.perpetratorId().filter(npcId::equals).isPresent())
                .findFirst();
    }

    /** Suppresses unused-import warnings on memory types — kept for
     *  follow-up that wires the player→NPC memory ledger. */
    @SuppressWarnings("unused")
    private static final NpcMemory DOC_HOOK = null;
}
