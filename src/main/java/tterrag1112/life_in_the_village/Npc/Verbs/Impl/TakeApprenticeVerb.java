package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContractFactory;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.DataAttachments.ModData;

import java.util.Optional;

/**
 * Player-as-master takes an NPC apprentice (spec line 256).
 *
 * <p>Phase 2 v1 gate: player has at least one workplace they own,
 * the candidate NPC is an adult with no current contract, and the
 * candidate isn't already involved in any contract. Master-side
 * decision delegates to Phase 5 dialogue; verb just records the
 * acceptance.</p>
 */
public final class TakeApprenticeVerb implements PlayerVerb {

    public static final String VERB_ID = "take_apprentice";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Take apprentice"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Offer to teach this NPC your craft."));
    }
    @Override public int displayOrder() { return 82; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        var npc = ctx.npc();
        if (npc.isChild() || npc.isElderly()) return false;
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(ctx.level());
        if (reg.getByApprentice(npc.getUUID()).isPresent()) return false;
        // Player must own at least one workplace to take an apprentice.
        PlayerProfessionData profData = ctx.player().getData(ModData.PROFESSION_DATA);
        return profData.getAllWorkplaces().values().stream()
                .anyMatch(tterrag1112.life_in_the_village.Profession.PlayerWorkplace.WorkplaceEntry::isOwner);
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(ctx.level());
        var npc = ctx.npc();
        // Pick the first owned workplace as the master's building.
        PlayerProfessionData profData = ctx.player().getData(ModData.PROFESSION_DATA);
        var ownedEntry = profData.getAllWorkplaces().entrySet().stream()
                .filter(e -> e.getValue().isOwner())
                .findFirst().orElse(null);
        if (ownedEntry == null) return VerbResult.failure("You don't own a workplace.");
        PlayerProfession masterProf = ownedEntry.getKey();
        Profession npcProf = mapToNpcProfession(masterProf);

        ApprenticeshipContract contract = new ApprenticeshipContract(
                java.util.UUID.randomUUID(),
                ctx.player().getUUID(), true,
                npc.getUUID(), false,
                ownedEntry.getValue().buildingId(),
                npcProf,
                ctx.tick(),
                ApprenticeshipContract.DEFAULT_DURATION_TICKS,
                tterrag1112.life_in_the_village.Npc.Apprentice.ContractStatus.ACTIVE,
                0, 0, 0L, "",
                ctx.tick(),
                "Player-as-master apprenticeship. 2 years.");
        reg.add(contract);
        ApprenticeshipContractFactory.queueViaScribe(ctx.level(), contract, npc, npc);

        // Apprentice inherits the master's profession + workplace
        // assignment so the existing NPC work-goal + wage halving
        // takes effect.
        // Phase 6.3.3.a — gated, PLAYER source (player verb).
        tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                npc, npcProf,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.APPRENTICESHIP,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.PLAYER);
        npc.assignToBuilding(ownedEntry.getValue().buildingId(),
                npc.getAssignedVillageName().orElse(""));

        ctx.player().displayClientMessage(
                Component.literal(npc.getNpcName() + " is now your apprentice.")
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);
        return VerbResult.success("apprentice.accept");
    }

    /**
     * Maps a {@link PlayerProfession} to the matching {@link Profession}
     * by name. Returns {@code Profession.NONE} when no match — that's
     * fine for v1 since every PlayerProfession we ship has a matching
     * NPC profession.
     */
    private static Profession mapToNpcProfession(PlayerProfession p) {
        try { return Profession.valueOf(p.name()); }
        catch (IllegalArgumentException e) { return Profession.NONE; }
    }
}
