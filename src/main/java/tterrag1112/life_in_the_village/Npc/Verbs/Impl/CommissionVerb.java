package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Commission-work verb. Spec lines 176-194. Eligibility: NPC has a
 * profession that can produce something AND is at their workplace
 * during work time.
 *
 * <p>Phase 1 implementation opens the {@code trade.open.default} tree
 * with an OPEN_SCREEN effect routing the actual commission UI through
 * the existing crafting-order infrastructure. Spec line 357 ("Open
 * decisions") notes this needs a {@code PlayerCommission} variant of
 * the existing crafting order — not in scope for this session;
 * documented in {@code 09-player-verbs.md} Revision Notes.</p>
 */
public final class CommissionVerb implements PlayerVerb {

    public static final String VERB_ID = "commission";

    /** Professions that can produce something on commission. */
    private static final Set<Profession> COMMISSIONABLE = EnumSet.of(
            Profession.BLACKSMITH, Profession.CARPENTER, Profession.STONEMASON,
            Profession.WEAVER, Profession.CANDLEMAKER, Profession.BAKER,
            Profession.MILLER, Profession.SCHOLAR);

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Commission work"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Pay them to craft or produce something specific."));
    }
    @Override public int displayOrder() { return 60; }
    @Override public int cooldownTicks() { return 0; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        if (!COMMISSIONABLE.contains(ctx.npc().getProfession())) return false;
        // Must be at work — close to the workplace AND in the work phase.
        return ctx.npc().isWorkTime();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (!COMMISSIONABLE.contains(ctx.npc().getProfession())) {
            return VerbResult.failure("They don't take commissions.");
        }
        if (!ctx.npc().isWorkTime()) {
            return VerbResult.failure("Come back during their work hours.");
        }
        // Phase 1: open the (existing) crafting-orders screen via the
        // legacy NpcProfileActionPacket.SHOW_CRAFTING_ORDERS path; the
        // verb result tells the caller to route there. Fully-fledged
        // PlayerCommission flow lands in a follow-up.
        return VerbResult.opensScreen("crafting_orders");
    }
}
