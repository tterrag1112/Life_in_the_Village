package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContractFactory;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Player asks an NPC master to take them on as apprentice
 * (spec lines 102-104, 240-254). Only available when the target
 * NPC has master-tier skill, owns a workshop, and has capacity.
 */
public final class ApprenticeUnderMeVerb implements PlayerVerb {

    public static final String VERB_ID = "apprentice_under_me";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Apprentice under me"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Ask this master to teach you their craft."));
    }
    @Override public int displayOrder() { return 80; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        var npc = ctx.npc();
        if (npc.getAssignedBuildingId().isEmpty()) return false;
        Skill primary = ProfessionSkills.of(npc.getProfession())
                .map(ProfessionSkills::primary).orElse(null);
        if (primary == null) return false;
        if (npc.getSkills().getLevel(primary)
                < ApprenticeshipContract.MASTER_SKILL_THRESHOLD) return false;
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(ctx.level());
        if (reg.getActiveByMaster(npc.getUUID()).size()
                >= ApprenticeshipContract.MAX_APPRENTICES_PER_MASTER) return false;
        // Player not already apprenticed somewhere.
        return reg.getByApprentice(ctx.player().getUUID()).isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(ctx.level());
        var npc = ctx.npc();
        if (reg.getActiveByMaster(npc.getUUID()).size()
                >= ApprenticeshipContract.MAX_APPRENTICES_PER_MASTER) {
            return VerbResult.failure(npc.getNpcName() + " already has apprentices.");
        }
        // Master accept gate (player path): always-yes in v1; spec
        // line 243 calls for an evaluation dialogue. Phase 2 returns
        // success and leaves the dialogue tree for Phase 5.
        ApprenticeshipContract contract = new ApprenticeshipContract(
                java.util.UUID.randomUUID(),
                npc.getUUID(), false,
                ctx.player().getUUID(), true,
                npc.getAssignedBuildingId().orElse(java.util.UUID.randomUUID()),
                npc.getProfession(),
                ctx.tick(),
                ApprenticeshipContract.DEFAULT_DURATION_TICKS,
                tterrag1112.life_in_the_village.Npc.Apprentice.ContractStatus.ACTIVE,
                0, 0, 0L, "",
                ctx.tick(),
                "Player apprenticeship. 2 years.");
        reg.add(contract);
        ApprenticeshipContractFactory.queueViaScribe(ctx.level(), contract, npc, npc);

        ctx.player().displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                                + "] Welcome, apprentice. Stay close to my workshop and learn.")
                        .withStyle(net.minecraft.ChatFormatting.GOLD), false);
        return VerbResult.success("apprentice.accept");
    }
}
