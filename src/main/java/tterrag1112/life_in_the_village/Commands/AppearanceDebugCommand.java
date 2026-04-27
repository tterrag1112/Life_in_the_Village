package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent;
import tterrag1112.life_in_the_village.Entities.custom.Appearance.AppearanceLayerRegistry;
import tterrag1112.life_in_the_village.Entities.custom.Appearance.AppearanceRebuilder;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Locale;
import java.util.UUID;

/**
 * Phase 5 doc 33 § Debug commands.
 *
 * <ul>
 *   <li>{@code /appearance show <npc>} — print full layer state.</li>
 *   <li>{@code /appearance rebuild <npc>} — force a Layer-1 rebuild.</li>
 *   <li>{@code /appearance set culture <npc> <id>} — debug-override
 *       culture base (testing).</li>
 *   <li>{@code /appearance set variant <npc> <int>} — debug-override
 *       skin tone variant.</li>
 *   <li>{@code /appearance gift <npc> <accessoryId>} — debug-add a
 *       persistent accessory.</li>
 * </ul>
 */
public final class AppearanceDebugCommand {

    private AppearanceDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("appearance")
                .then(Commands.literal("show")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(AppearanceDebugCommand::handleShow)))
                .then(Commands.literal("rebuild")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(AppearanceDebugCommand::handleRebuild)))
                .then(Commands.literal("set")
                        .then(Commands.literal("culture")
                                .then(Commands.argument("npc", UuidArgument.uuid())
                                        .then(Commands.argument("cultureId", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (String id : AppearanceLayerRegistry.allCultureIds()) b.suggest(id);
                                                    return b.buildFuture();
                                                })
                                                .executes(AppearanceDebugCommand::handleSetCulture))))
                        .then(Commands.literal("variant")
                                .then(Commands.argument("npc", UuidArgument.uuid())
                                        .then(Commands.argument("variant", IntegerArgumentType.integer(0))
                                                .executes(AppearanceDebugCommand::handleSetVariant)))))
                .then(Commands.literal("gift")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .then(Commands.argument("accessoryId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (String id : AppearanceLayerRegistry.allAccessoryIds()) b.suggest(id);
                                            return b.buildFuture();
                                        })
                                        .executes(AppearanceDebugCommand::handleGift))))
        );
    }

    // ── /appearance show ─────────────────────────────────────────────

    private static int handleShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        TownspersonMob npc = resolveOrFail(src, UuidArgument.getUuid(ctx, "npc"));
        if (npc == null) return 0;
        AppearanceComponent app = npc.getAppearance();

        StringBuilder sb = new StringBuilder("§e=== Appearance: ")
                .append(npc.getNpcName()).append(" §7(").append(npc.getUUID())
                .append(")§e ===§r");
        sb.append("\n  §7culture base:§f ").append(app.getCultureBaseId().isEmpty()
                ? "(none — falls back to default)" : app.getCultureBaseId());
        sb.append(" §7variant=§f").append(app.getSkinToneVariant());
        AppearanceLayerRegistry.getCultureBase(app.getCultureBaseId().isEmpty()
                ? "default" : app.getCultureBaseId())
                .ifPresent(b -> sb.append(" §8(")
                        .append(b.variantCount()).append(" variants registered)§r"));

        sb.append("\n  §7accessories:§f ");
        if (app.getAccessoryIds().isEmpty()) sb.append("(none)");
        else for (String id : app.getAccessoryIds()) {
            String label = AppearanceLayerRegistry.getAccessory(id)
                    .map(d -> d.shortLabel()).orElse(id);
            sb.append("\n    §a").append(id).append("§r — ").append(label);
        }

        sb.append("\n  §7office marks:§f ");
        if (app.getOfficeMarks().isEmpty()) sb.append("(none)");
        else for (String id : app.getOfficeMarks()) {
            String label = AppearanceLayerRegistry.getOfficeMark(id)
                    .map(m -> m.shortLabel() + " (priority " + m.priority() + ")")
                    .orElse(id);
            sb.append("\n    §a").append(id).append("§r — ").append(label);
        }
        sb.append("\n  §7life-stage decoration:§f posture=")
                .append(app.getLifeStageDecoration().postureOffset())
                .append(" limb=").append(app.getLifeStageDecoration().limbProportion())
                .append(" cane=").append(app.getLifeStageDecoration().usesCane());
        sb.append("\n  §7lastRebuildTick:§f ").append(app.getLastRebuildTick());
        sb.append("\n  §8resolved-culture: ");
        try {
            sb.append(CultureResolver.of(npc).id());
        } catch (RuntimeException ex) {
            sb.append("(unresolvable: ").append(ex.getMessage()).append(")");
        }

        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /appearance rebuild ──────────────────────────────────────────

    private static int handleRebuild(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        TownspersonMob npc = resolveOrFail(src, UuidArgument.getUuid(ctx, "npc"));
        if (npc == null) return 0;
        boolean ok = AppearanceRebuilder.rebuild(npc);
        if (!ok) {
            src.sendFailure(Component.literal("Rebuild failed (NPC not in a server level?)"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aRebuilt§r " + npc.getNpcName()
                + " — " + npc.getAppearance().describeAppearance())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /appearance set culture / variant ────────────────────────────

    private static int handleSetCulture(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        TownspersonMob npc = resolveOrFail(src, UuidArgument.getUuid(ctx, "npc"));
        if (npc == null) return 0;
        String id = StringArgumentType.getString(ctx, "cultureId").toLowerCase(Locale.ROOT);
        if (AppearanceLayerRegistry.getCultureBase(id).isEmpty()) {
            src.sendFailure(Component.literal("Unknown culture base id: " + id));
            return 0;
        }
        npc.getAppearance().setCultureBaseId(id);
        src.sendSuccess(() -> Component.literal("§aSet culture§r → " + id)
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleSetVariant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        TownspersonMob npc = resolveOrFail(src, UuidArgument.getUuid(ctx, "npc"));
        if (npc == null) return 0;
        int variant = IntegerArgumentType.getInteger(ctx, "variant");
        npc.getAppearance().setSkinToneVariant(variant);
        src.sendSuccess(() -> Component.literal("§aSet skin variant§r → " + variant)
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /appearance gift ─────────────────────────────────────────────

    private static int handleGift(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        TownspersonMob npc = resolveOrFail(src, UuidArgument.getUuid(ctx, "npc"));
        if (npc == null) return 0;
        String id = StringArgumentType.getString(ctx, "accessoryId");
        if (AppearanceLayerRegistry.getAccessory(id).isEmpty()) {
            src.sendFailure(Component.literal("Unknown accessory id: " + id));
            return 0;
        }
        boolean added = npc.getAppearance().addAccessory(id);
        if (!added) {
            src.sendFailure(Component.literal("Already wearing " + id));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aGifted§r " + id + " to " + npc.getNpcName())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static TownspersonMob resolveOrFail(CommandSourceStack src, UUID uuid) {
        ServerLevel level = src.getLevel();
        TownspersonMob npc = TownspersonMob.findByUUID(level, uuid).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC " + uuid));
        }
        return npc;
    }
}
