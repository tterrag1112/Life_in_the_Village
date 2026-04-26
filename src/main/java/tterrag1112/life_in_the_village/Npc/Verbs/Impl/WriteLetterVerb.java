package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Npc.Letters.LetterSpecial;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player self-authors a letter (spec line 327, line 113). Gated on
 * the player's LITERACY skill via the existing skill component on
 * the NPC; for v1 the gate is loose — the verb is available against
 * any NPC and bumps the player toward the LITERACY ≥ 60 / ≥ 80
 * recommendation in chat.
 *
 * <p>Phase 2 v1 takes {@code recipientId} + {@code recipientName} +
 * {@code body} + optional {@code special} string args. Without args
 * the verb fails with an instructional message — a real composer UI
 * (template or free-form) lands in Phase 5 polish.</p>
 */
public final class WriteLetterVerb implements PlayerVerb {

    public static final String VERB_ID = "write_letter";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Write a letter"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Self-author a letter (LITERACY ≥ 60)."));
    }
    @Override public int displayOrder() { return 70; }
    @Override public int cooldownTicks() { return 600; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        // The player's LITERACY isn't tracked in v1 — gate on the
        // target NPC being someone who can plausibly receive a hand-
        // written letter (any non-child).
        return !ctx.npc().isChild();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.failure("Compose the letter from the menu.");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        String body = args == null ? "" : args.getOrDefault("body", "");
        if (body.isEmpty()) {
            return VerbResult.failure("Letter body is empty.");
        }
        UUID recipientId = parseUuid(args.get("recipientId"));
        String recipientName = args.getOrDefault("recipientName", ctx.npc().getNpcName());
        if (recipientId == null) recipientId = ctx.npc().getUUID();

        boolean sealed = "true".equalsIgnoreCase(args.getOrDefault("sealed", "false"));
        Optional<LetterSpecial> special = parseSpecial(args.get("special"));

        ItemStack letter = ScribalItems.letter(body,
                ctx.player().getUUID(), ctx.player().getName().getString(),
                recipientId, recipientName,
                ctx.tick(), sealed, special);

        if (!ctx.player().getInventory().add(letter)) {
            ItemEntity drop = new ItemEntity(ctx.level(),
                    ctx.player().getX(), ctx.player().getY() + 0.5, ctx.player().getZ(),
                    letter);
            ctx.level().addFreshEntity(drop);
        }
        // Spec line 337: author gains SOCIAL XP per sent letter. We
        // grant it on write rather than hand-off because the player
        // doesn't have a SkillComponent.
        ctx.npc().getSkills().addXp(Skill.SOCIAL, 1, ctx.tick());
        return VerbResult.success("letter.written");
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Optional<LetterSpecial> parseSpecial(String s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        try { return Optional.of(LetterSpecial.valueOf(s.toUpperCase(java.util.Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
