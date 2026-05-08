// src/main/java/tterrag1112/life_in_the_village/Commands/DecorationDebugCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationPass;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationPlacement;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfile;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfileRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationSlot;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationSlotEmitter;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationTag;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P0b-06 — debug surface for the decoration framework.
 *
 * <pre>
 *   /liv decoration list &lt;village&gt;
 *   /liv decoration slots &lt;village&gt;
 *   /liv decoration profiles
 *   /liv decoration replay &lt;village&gt; confirm
 * </pre>
 *
 * <p>Output goes through {@code sendSuccess}; errors use
 * {@code sendFailure}. Permissions follow the project's existing
 * /liv-debug pattern (op-level via the {@code @EventBusSubscriber}
 * + {@code RegisterCommandsEvent} hookup). The {@code replay}
 * subcommand requires a literal {@code confirm} sub-token to guard
 * against accidental destructive runs.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class DecorationDebugCommand {

    private DecorationDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("decoration")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("village",
                                                        StringArgumentType.string())
                                                .executes(ctx -> list(ctx,
                                                        StringArgumentType.getString(ctx, "village")))))
                                .then(Commands.literal("slots")
                                        // B2.9 — bare /liv decoration slots
                                        // resolves the village at the player's
                                        // position; the explicit-name form
                                        // remains for cross-village debugging.
                                        .executes(ctx -> slotsAtPlayer(ctx))
                                        .then(Commands.argument("village",
                                                        StringArgumentType.string())
                                                .executes(ctx -> slots(ctx,
                                                        StringArgumentType.getString(ctx, "village")))))
                                .then(Commands.literal("profiles")
                                        .executes(DecorationDebugCommand::profiles))
                                .then(Commands.literal("replay")
                                        .then(Commands.argument("village",
                                                        StringArgumentType.string())
                                                .executes(ctx -> replayMissingConfirm(ctx,
                                                        StringArgumentType.getString(ctx, "village")))
                                                .then(Commands.literal("confirm")
                                                        .executes(ctx -> replay(ctx,
                                                                StringArgumentType.getString(ctx, "village")))))))
        );
    }

    // ── /liv decoration list <village> ───────────────────────────────────

    private static int list(CommandContext<CommandSourceStack> ctx,
                            String villageName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return failServerOnly(ctx);
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return failUnknownVillage(ctx, villageName);

        List<DecorationPlacement> placements =
                data.getDecorationsForVillage(village.getId());
        if (placements.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + " has no decoration placements.\n"
                            + "(Expected if no DecorationProfiles are registered yet.)"),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        Set<String> distinctPieces = new HashSet<>();
        for (DecorationPlacement p : placements) {
            distinctPieces.add(p.pieceId());
            sb.append("[").append(p.pieceId()).append("] @ (")
                    .append(p.pos().getX()).append(",")
                    .append(p.pos().getY()).append(",")
                    .append(p.pos().getZ()).append(") facing=")
                    .append(p.facing().name())
                    .append(" placedTick=").append(p.placedTick())
                    .append("\n");
        }
        sb.append("Total: ").append(placements.size())
                .append(" placements across ").append(distinctPieces.size())
                .append(" piece types");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return placements.size();
    }

    // ── /liv decoration slots <village> ──────────────────────────────────

    /** B2.9 — bare-form handler. Resolves the village at the
     *  command source's position (Player or block-pos source).
     *  Falls back to a friendly error if the source isn't standing
     *  inside a village. */
    private static int slotsAtPlayer(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return failServerOnly(ctx);
        }
        VillageSavedData data = VillageSavedData.get(level);
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                ctx.getSource().getPosition());
        Village village = data.getVillageAt(pos).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                    "No village at " + pos.getX() + "," + pos.getZ()
                            + ". Provide a village name: /liv decoration slots <name>"));
            return 0;
        }
        return slotsForVillage(ctx, level, data, village);
    }

    private static int slots(CommandContext<CommandSourceStack> ctx,
                             String villageName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return failServerOnly(ctx);
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return failUnknownVillage(ctx, villageName);
        return slotsForVillage(ctx, level, data, village);
    }

    /** Shared body extracted in B2.9 so both bare and explicit-name
     *  forms call the same emitter. */
    private static int slotsForVillage(CommandContext<CommandSourceStack> ctx,
                                       ServerLevel level,
                                       VillageSavedData data,
                                       Village village) {

        // Re-run the emitter without committing. Layout is null
        // because runtime debug commands don't have planning context;
        // road-side slots will be skipped, which is documented behaviour.
        DecorationSlotEmitter.Context emitCtx =
                new DecorationSlotEmitter.Context(village, data, null);
        List<DecorationSlot> emitted = DecorationSlotEmitter.emit(emitCtx);

        StringBuilder sb = new StringBuilder();
        EnumMap<DecorationTag, Integer> tagCounts = new EnumMap<>(DecorationTag.class);
        for (DecorationSlot s : emitted) {
            sb.append("[").append(s.tags().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining(",")))
                    .append("] @ (")
                    .append(s.pos().getX()).append(",")
                    .append(s.pos().getY()).append(",")
                    .append(s.pos().getZ()).append(") facing=")
                    .append(s.facing().name())
                    .append(" quality=").append(s.qualityScore())
                    .append(" budget=").append(s.footprintBudget())
                    .append("\n");
            for (DecorationTag t : s.tags()) {
                tagCounts.merge(t, 1, Integer::sum);
            }
        }
        sb.append("Total: ").append(emitted.size()).append(" slots\n");
        sb.append("Tag breakdown: ");
        if (tagCounts.isEmpty()) {
            sb.append("(none)");
        } else {
            sb.append(tagCounts.entrySet().stream()
                    .map(e -> e.getKey().name() + "=" + e.getValue())
                    .collect(Collectors.joining(", ")));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return emitted.size();
    }

    // ── /liv decoration profiles ─────────────────────────────────────────

    private static int profiles(CommandContext<CommandSourceStack> ctx) {
        List<DecorationProfile> all = DecorationProfileRegistry.INSTANCE.all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No DecorationProfiles registered. (Expected at end of "
                            + "Phase 0b — content subsystems populate the registry "
                            + "in Phase 1 onward.)"),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        Set<String> distinctCultures = new HashSet<>();
        for (DecorationProfile p : all) {
            distinctCultures.add(p.culture());
            sb.append("[").append(p.pieceId()).append("] tags=")
                    .append(p.requiredTags().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining(",")))
                    .append(" footprint=").append(p.footprintSize())
                    .append(" tier=").append(p.primaryWeight())
                    .append("/").append(p.secondaryWeight())
                    .append("/").append(p.backfillWeight())
                    .append("\n");
        }
        sb.append("Total: ").append(all.size())
                .append(" profiles registered, ")
                .append(distinctCultures.size()).append(" distinct cultures.");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return all.size();
    }

    // ── /liv decoration replay <village> [confirm] ───────────────────────

    private static int replayMissingConfirm(CommandContext<CommandSourceStack> ctx,
                                            String villageName) {
        ctx.getSource().sendFailure(Component.literal(
                "Replay is destructive — it removes existing decoration "
                        + "placements for " + villageName + " and re-runs the "
                        + "DecorationPass. Append `confirm` to proceed:\n"
                        + "  /liv decoration replay " + villageName + " confirm"));
        return 0;
    }

    private static int replay(CommandContext<CommandSourceStack> ctx,
                              String villageName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return failServerOnly(ctx);
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return failUnknownVillage(ctx, villageName);

        int before = data.getDecorationsForVillage(village.getId()).size();

        // Note: existing placements' world blocks are NOT removed
        // here. Doc 01 §"Behavior contract" calls out the matcher's
        // "burn the slot on terrain failure" policy; the analogue
        // for replay is "leave any old placed blocks in place — the
        // re-run may overwrite some, but we don't actively dig out
        // the rest". When real profiles ship and stamp non-trivial
        // structures, a future iteration may add a clear-blocks
        // step before re-running. Flagged in revision notes.
        data.removeDecorationsForVillage(village.getId());

        DecorationPass.run(level, village, data, /* layout */ null);

        int after = data.getDecorationsForVillage(village.getId()).size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Replayed DecorationPass for " + villageName
                        + ": " + before + " → " + after + " placements."),
                true);
        return after;
    }

    // ── Failure helpers ──────────────────────────────────────────────────

    private static int failServerOnly(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal(
                "/liv decoration runs server-side; no level available."));
        return 0;
    }

    private static int failUnknownVillage(CommandContext<CommandSourceStack> ctx,
                                          String villageName) {
        ctx.getSource().sendFailure(Component.literal(
                "Unknown village: '" + villageName + "'."));
        return 0;
    }
}
