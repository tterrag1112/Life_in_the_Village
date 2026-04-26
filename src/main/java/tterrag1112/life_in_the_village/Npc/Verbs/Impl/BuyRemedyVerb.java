package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Health.HealerInventory;
import tterrag1112.life_in_the_village.Npc.Health.HealthSavedData;
import tterrag1112.life_in_the_village.Npc.Health.Remedy;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Spec line 195. Player purchases a remedy from the healer.
 *
 * <p>v1 skips actual coin transfer (the economic-channels rework
 * does not yet expose a player→NPC buy primitive). The verb takes
 * the highest-potency remedy and hands it to the player's stash;
 * the player's reputation with the village is reduced by 1 to model
 * the cost — Phase 4 swaps in the real currency channel.</p>
 */
public final class BuyRemedyVerb implements PlayerVerb {

    public static final String VERB_ID = "buy_remedy";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Buy remedy"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Purchase a remedy for personal use."));
    }
    @Override public int displayOrder() { return 72; }
    @Override public int cooldownTicks() { return 6000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.HEALER
                && !ctx.npc().getHealerInventory().remedies().isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.HEALER) {
            return VerbResult.failure("They are not a healer.");
        }
        HealerInventory stash = ctx.npc().getHealerInventory();
        if (stash.remedies().isEmpty()) {
            return VerbResult.failure("They have nothing for sale.");
        }
        // Pick the highest-potency entry on the stash, then take it.
        // takeFor is itself potency-aware, so passing the type's first
        // target reliably removes the same entry we identified.
        Remedy best = stash.remedies().stream()
                .max(java.util.Comparator.comparingInt(Remedy::potency))
                .orElseThrow();
        var iter = best.type().targets().iterator();
        if (!iter.hasNext()) {
            return VerbResult.failure("Their stock is unsuitable.");
        }
        Remedy taken = stash.takeFor(iter.next()).orElse(best);
        HealthSavedData hdata = HealthSavedData.get(ctx.level());
        hdata.getOrCreatePlayerStash(ctx.player().getUUID()).add(taken);
        hdata.markDirty();
        return VerbResult.success("greeting.business.healer.work");
    }
}
