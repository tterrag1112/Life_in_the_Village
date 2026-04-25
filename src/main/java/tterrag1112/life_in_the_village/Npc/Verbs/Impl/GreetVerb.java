package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

/**
 * Explicit "Greet" — opens the greeting tree based on context.
 *
 * <p>Spec line 347 ("Open decisions") suggests greeting be implicit
 * (auto-run when the profile opens). Phase 1 ships an explicit verb
 * per the user prompt; the implicit auto-greeting from
 * {@code NpcDialogue.getGreeting} is unchanged. Display order is
 * pushed last in the verb bar so it doesn't crowd specialised verbs.
 * Documented in {@code 09-player-verbs.md} Revision Notes.</p>
 */
public final class GreetVerb implements PlayerVerb {

    @Override public String id() { return "greet"; }
    @Override public Component label() { return Component.literal("Greet"); }
    @Override public int displayOrder() { return 5; } // before trade
    @Override public int cooldownTicks() { return 0; }

    @Override public boolean isAvailable(VerbContext ctx) { return true; }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.success(greetingTreeId(ctx));
    }

    /** Mirrors NpcDialogue.greetingTreeId so the verb bar matches the auto-greeting. */
    private static String greetingTreeId(VerbContext ctx) {
        var prof = ctx.npc().getProfession();
        return switch (prof) {
            case VILLAGE_LEADER, KINGDOM_RULER, CHANCELLOR, HERALD -> "greeting.player.leader";
            case MERCHANT, WANDERING_TRADER, BAKER, BLACKSMITH,
                    MILLER, WEAVER, STONEMASON, CARPENTER, CANDLEMAKER,
                    INNKEEPER, STOCKPILE_KEEPER, BUILDER -> "greeting.player.shopkeeper";
            default -> "greeting.player.default";
        };
    }
}
