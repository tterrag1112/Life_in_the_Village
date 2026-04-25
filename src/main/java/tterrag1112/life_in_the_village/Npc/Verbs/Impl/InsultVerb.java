package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Insult verb. Spec lines 86-104. Always available; no cooldown
 * (the player chooses to escalate). isPublic flag is true when at
 * least one other NPC stands within {@link #PUBLIC_RADIUS_BLOCKS}.
 *
 * <p>Public-witness radius: 8 blocks. Spec doesn't specify; documented
 * in {@code 09-player-verbs.md} Revision Notes — open to retuning.</p>
 */
public final class InsultVerb implements PlayerVerb {

    public static final String VERB_ID = "insult";
    /** Distance within which other NPCs count as public witnesses. */
    public static final double PUBLIC_RADIUS_BLOCKS = 8.0;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Insult"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Harsh words. This will be remembered."));
    }
    @Override public int displayOrder() { return 99; } // last per spec line 234
    @Override public int cooldownTicks() { return 0; }

    @Override public boolean isAvailable(VerbContext ctx) { return true; }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        boolean inPublic = hasPublicWitness(ctx);

        NpcLifeEventBus.fire(new NpcLifeEvent.Insulted(
                ctx.npc(), ctx.player().getUUID(), true, inPublic));

        // Spec line 96-97: relationship −15.
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(), -15);

        return VerbResult.success("insult.response.default");
    }

    private static boolean hasPublicWitness(VerbContext ctx) {
        AABB zone = ctx.npc().getBoundingBox().inflate(PUBLIC_RADIUS_BLOCKS);
        java.util.UUID speakerId = ctx.npc().getUUID();
        java.util.UUID playerId = ctx.player().getUUID();
        return ctx.level().getEntitiesOfClass(
                TownspersonMob.class, zone,
                w -> !w.getUUID().equals(speakerId) && !w.getUUID().equals(playerId))
                .size() > 0;
    }
}
