package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryLending;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 344. Player asks a LIBRARIAN to lend a book. Phase 2 v1
 * takes a {@code bookId} string arg. The librarian's catalogue
 * surface is the source of truth — the verb just records the loan;
 * the physical item delivery comes from the librarian's work goal in
 * a future polish pass (Phase 5).
 */
public final class BorrowBookVerb implements PlayerVerb {

    public static final String VERB_ID = "borrow_book";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Borrow a book"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Take a book home for a few days."));
    }
    @Override public int displayOrder() { return 64; }
    @Override public int cooldownTicks() { return 200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.LIBRARIAN
                && ctx.npc().getAssignedBuildingId().isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.failure("Pick a book from the catalogue.");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        UUID libraryId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (libraryId == null) return VerbResult.failure("No library assigned.");
        ServerLevel level = ctx.level();
        LibraryCatalogue cat = VillageSavedData.get(level).getOrCreateLibraryCatalogue(libraryId);
        UUID bookId = parseUuid(args == null ? null : args.get("bookId"));
        if (bookId == null) {
            return VerbResult.failure("Pick a book from the catalogue.");
        }
        Optional<LibraryLending> lent = cat.lend(
                bookId, ctx.player().getUUID(), true, ctx.tick());
        VillageSavedData.get(level).setDirty();
        return lent.isPresent()
                ? VerbResult.success("library.lend.accepted")
                : VerbResult.failure("That book isn't available right now.");
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
