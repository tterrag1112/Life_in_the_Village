package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Health.HealerInventory;
import tterrag1112.life_in_the_village.Npc.Health.Remedy;
import tterrag1112.life_in_the_village.Npc.Health.RemedyType;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Spec line 193. Player gifts the healer ingredients (abstracted in
 * v1 — no item check; Phase 5 will gate on actual herbs / honey /
 * cloth in the player's inventory).
 *
 * <p>Effect: produces one extra remedy in the healer's stash and
 * bumps the player→healer relationship +5.</p>
 */
public final class DonateHerbsVerb implements PlayerVerb {

    public static final String VERB_ID = "donate_herbs";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Donate herbs"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Give the healer ingredients for remedies."));
    }
    @Override public int displayOrder() { return 71; }
    @Override public int cooldownTicks() { return 12000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.HEALER
                && !ctx.npc().getHealerInventory().isFull();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.HEALER) {
            return VerbResult.failure("They are not a healer.");
        }
        HealerInventory stash = ctx.npc().getHealerInventory();
        if (stash.isFull()) return VerbResult.failure("Their stash is full.");
        // Donate-driven remedies always come out as HERBAL_POULTICE
        // (the most generic), potency 2.
        stash.add(Remedy.create(RemedyType.HERBAL_POULTICE, 2,
                ctx.level().getGameTime()));
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(), 5);
        return VerbResult.success("greeting.business.healer.work");
    }
}
