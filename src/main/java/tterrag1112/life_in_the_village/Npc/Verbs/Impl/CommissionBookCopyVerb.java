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
 * Spec line 345. Player asks a SCRIBE or LIBRARIAN to copy a book.
 * Phase 2 takes a {@code title} and the body in {@code content}; doc 18
 * upgrades the body to lookup-by-bookId from the library catalogue.
 */
public final class CommissionBookCopyVerb implements PlayerVerb {

    public static final String VERB_ID = "commission_book_copy";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Commission a book copy"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Pay to have a book copied."));
    }
    @Override public int displayOrder() { return 62; }
    @Override public int cooldownTicks() { return 1200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        Profession p = ctx.npc().getProfession();
        return (p == Profession.SCRIBE || p == Profession.LIBRARIAN)
                && ctx.npc().getAssignedBuildingId().isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.failure("Pick a book to copy from the menu.");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        String title = args == null ? "" : args.getOrDefault("title", "Untitled");
        String content = args == null ? "" : args.getOrDefault("content", "");
        if (content.isEmpty()) {
            return VerbResult.failure("No source content for the copy.");
        }
        ServerLevel level = ctx.level();
        UUID workshopId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (workshopId == null) return VerbResult.failure("No workshop assigned.");
        // Librarians don't have a workshop in v1, so route via the
        // scribal queue keyed on whatever building they hold.
        CommissionQueue queue = VillageSavedData.get(level).getOrCreateCommissionQueue(workshopId);
        long fee = ScribeCommission.feeFor(ScribeProductType.BOOK_COPY, content.length());
        ScribeCommission c = new ScribeCommission(
                UUID.randomUUID(), ctx.player().getUUID(), true,
                ScribeProductType.BOOK_COPY, title + "\n\n" + content,
                Optional.empty(),
                ctx.tick(), ctx.tick() + 24000L * 7L,
                fee, CommissionStatus.PENDING, 5);
        if (!queue.enqueue(c)) {
            return VerbResult.failure("The queue is full.");
        }
        VillageSavedData.get(level).setDirty();
        return VerbResult.success("scribe.commission.accepted");
    }
}
