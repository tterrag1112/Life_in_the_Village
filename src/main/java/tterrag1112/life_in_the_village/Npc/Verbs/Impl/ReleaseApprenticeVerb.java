package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Player-as-master releases an apprentice (spec line 200). Or the
 * player-as-apprentice quits when the verb is targeted at their
 * own master.
 */
public final class ReleaseApprenticeVerb implements PlayerVerb {

    public static final String VERB_ID = "release_apprenticeship";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Release from apprenticeship"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("End the current apprenticeship contract."));
    }
    @Override public int displayOrder() { return 84; }
    @Override public int cooldownTicks() { return 600; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return findRelevantContract(ctx).isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ApprenticeshipContract c = findRelevantContract(ctx).orElse(null);
        if (c == null) return VerbResult.failure("No active contract to release.");

        if (c.masterIsPlayer() && c.apprenticeId().equals(ctx.npc().getUUID())) {
            // Master dismisses NPC apprentice.
            ApprenticeshipManager.masterDismisses(ctx.level(), c);
            ctx.player().displayClientMessage(
                    Component.literal(ctx.npc().getNpcName()
                                    + " is dismissed from your workshop.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        } else if (c.apprenticeIsPlayer() && c.masterId().equals(ctx.npc().getUUID())) {
            // Player walks away from NPC master.
            ApprenticeshipManager.apprenticeAbandons(ctx.level(), c);
            ctx.player().displayClientMessage(
                    Component.literal("You walk away from " + ctx.npc().getNpcName()
                                    + "'s workshop.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        } else {
            return VerbResult.failure("That contract isn't between you two.");
        }
        return VerbResult.success("apprentice.terminate");
    }

    private static Optional<ApprenticeshipContract> findRelevantContract(VerbContext ctx) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(ctx.level());
        // Master view: NPC apprentice's contract under the player.
        Optional<ApprenticeshipContract> apprenticesContract =
                reg.getByApprentice(ctx.npc().getUUID());
        if (apprenticesContract.isPresent()
                && apprenticesContract.get().masterId().equals(ctx.player().getUUID())
                && apprenticesContract.get().masterIsPlayer()) {
            return apprenticesContract;
        }
        // Apprentice view: player is apprentice under this NPC master.
        Optional<ApprenticeshipContract> playerContract =
                reg.getByApprentice(ctx.player().getUUID());
        if (playerContract.isPresent()
                && playerContract.get().masterId().equals(ctx.npc().getUUID())
                && !playerContract.get().masterIsPlayer()) {
            return playerContract;
        }
        return Optional.empty();
    }
}
