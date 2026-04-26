package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeProductType;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 343. Player asks a SCRIBE to write a letter; the verb
 * enqueues a {@link ScribeCommission} on the workshop's queue.
 *
 * <p>Phase 2 v1 expects {@code content} and {@code recipientId}
 * string args. Without them the verb returns an instructional failure
 * — a real GUI letter-composer lands with doc 18.</p>
 */
public final class CommissionLetterVerb implements PlayerVerb {

    public static final String VERB_ID = "commission_letter";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Commission a letter"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Pay the scribe to write a letter."));
    }
    @Override public int displayOrder() { return 60; }
    @Override public int cooldownTicks() { return 600; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.SCRIBE
                && ctx.npc().getAssignedBuildingId().isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.failure("Use the menu to compose your letter.");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        String content = args == null ? "" : args.getOrDefault("content", "");
        if (content.isEmpty()) {
            return VerbResult.failure("Letter content is empty.");
        }
        UUID recipient = parseUuid(args.get("recipientId"));
        ServerLevel level = ctx.level();
        UUID workshopId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (workshopId == null) return VerbResult.failure("Scribe has no workshop.");

        CommissionQueue queue = VillageSavedData.get(level).getOrCreateCommissionQueue(workshopId);
        long fee = ScribeCommission.feeFor(ScribeProductType.LETTER, content.length());
        ScribeCommission c = new ScribeCommission(
                UUID.randomUUID(),
                ctx.player().getUUID(),
                true,
                ScribeProductType.LETTER,
                content,
                Optional.ofNullable(recipient),
                ctx.tick(),
                ctx.tick() + 24000L * 2L,
                fee,
                CommissionStatus.PENDING,
                10);
        if (!queue.enqueue(c)) {
            return VerbResult.failure("The scribe's queue is full. Try later.");
        }
        VillageSavedData.get(level).setDirty();
        return VerbResult.success("scribe.commission.accepted");
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
