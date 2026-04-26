package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeRank;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData;
import tterrag1112.life_in_the_village.Npc.Apprentice.ContractStatus;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 task 16 debug commands.
 */
public final class ApprenticeDebugCommand {

    private ApprenticeDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("apprentice")
                .then(Commands.literal("list")
                        .executes(ApprenticeDebugCommand::handleList))
                .then(Commands.literal("info")
                        .then(Commands.argument("contractId", UuidArgument.uuid())
                                .executes(ApprenticeDebugCommand::handleInfo)))
                .then(Commands.literal("promote")
                        .then(Commands.argument("apprenticeId", UuidArgument.uuid())
                                .executes(ApprenticeDebugCommand::handlePromote)
                                .then(Commands.argument("milestone",
                                                IntegerArgumentType.integer(0, 4))
                                        .executes(ApprenticeDebugCommand::handlePromoteTo))))
                .then(Commands.literal("masterpiece")
                        .then(Commands.argument("apprenticeId", UuidArgument.uuid())
                                .executes(ApprenticeDebugCommand::handleMasterpiece)))
                .then(Commands.literal("complete")
                        .then(Commands.argument("contractId", UuidArgument.uuid())
                                .executes(ApprenticeDebugCommand::handleComplete)))
                .then(Commands.literal("terminate")
                        .then(Commands.argument("contractId", UuidArgument.uuid())
                                .executes(ApprenticeDebugCommand::handleTerminate)))
        );
    }

    // =========================================================================
    // /apprentice list
    // =========================================================================

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Active apprenticeship contracts ===\n");
        var actives = reg.active();
        if (actives.isEmpty()) {
            sb.append("  §7(none)\n");
        } else {
            for (ApprenticeshipContract c : actives) {
                sb.append(String.format(Locale.ROOT,
                        "  %s %-12s milestone=%d/%4 master=%s apprentice=%s%n",
                        c.contractId().toString().substring(0, 8),
                        c.profession().name(),
                        c.progressMilestones(),
                        c.masterId().toString().substring(0, 8),
                        c.apprenticeId().toString().substring(0, 8)));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /apprentice info
    // =========================================================================

    private static int handleInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "contractId");
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(src.getLevel());
        Optional<ApprenticeshipContract> opt = reg.getById(id);
        if (opt.isEmpty()) {
            src.sendFailure(Component.literal("No contract with id " + id));
            return 0;
        }
        ApprenticeshipContract c = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Contract ").append(c.contractId().toString(), 0, 8).append(" ===\n");
        sb.append("  status: ").append(c.status().name()).append('\n');
        sb.append("  master: ").append(c.masterId())
                .append(c.masterIsPlayer() ? " §6(player)§r" : "").append('\n');
        sb.append("  apprentice: ").append(c.apprenticeId())
                .append(c.apprenticeIsPlayer() ? " §6(player)§r" : "").append('\n');
        sb.append("  building: ").append(c.buildingId()).append('\n');
        sb.append("  profession: ").append(c.profession().name()).append('\n');
        sb.append("  startTick: ").append(c.startTick()).append('\n');
        sb.append("  duration: ").append(c.expectedDurationTicks() / 24000L).append(" days\n");
        sb.append("  milestones: ").append(c.progressMilestones()).append("/4\n");
        if (c.masterpieceAssigned()) {
            sb.append("  masterpiece: ").append(c.masterpieceTarget())
                    .append(" (deadline tick=").append(c.masterpieceDeadlineTick())
                    .append(", attempts=").append(c.masterpieceAttempts()).append(")\n");
        }
        sb.append("  terms: ").append(c.contractTerms()).append('\n');
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /apprentice promote <apprenticeId> [milestone]
    // =========================================================================

    private static int handlePromote(CommandContext<CommandSourceStack> ctx) {
        return promoteTo(ctx, -1);
    }

    private static int handlePromoteTo(CommandContext<CommandSourceStack> ctx) {
        return promoteTo(ctx, IntegerArgumentType.getInteger(ctx, "milestone"));
    }

    private static int promoteTo(CommandContext<CommandSourceStack> ctx, int milestone) {
        CommandSourceStack src = ctx.getSource();
        UUID apprenticeId = UuidArgument.getUuid(ctx, "apprenticeId");
        ServerLevel level = src.getLevel();
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        Optional<ApprenticeshipContract> opt = reg.getByApprentice(apprenticeId);
        if (opt.isEmpty()) {
            src.sendFailure(Component.literal("No active contract for apprentice " + apprenticeId));
            return 0;
        }
        ApprenticeshipContract c = opt.get();
        int target = milestone < 0
                ? Math.min(4, c.progressMilestones() + 1)
                : milestone;
        reg.update(c.withMilestone(target, level.getGameTime()));
        src.sendSuccess(() -> Component.literal(
                "Promoted contract " + c.contractId().toString().substring(0, 8)
                        + " to milestone " + target),
                true);
        return 1;
    }

    // =========================================================================
    // /apprentice masterpiece <apprenticeId>
    // =========================================================================

    private static int handleMasterpiece(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID apprenticeId = UuidArgument.getUuid(ctx, "apprenticeId");
        ServerLevel level = src.getLevel();
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        Optional<ApprenticeshipContract> opt = reg.getByApprentice(apprenticeId);
        if (opt.isEmpty()) {
            src.sendFailure(Component.literal("No active contract for apprentice " + apprenticeId));
            return 0;
        }
        ApprenticeshipContract c = opt.get();
        // Force milestone 4 + assign masterpiece target.
        String target = ApprenticeshipManager.masterpieceTargetFor(c.profession());
        long deadline = level.getGameTime() + ApprenticeshipContract.MASTERPIECE_DEADLINE_TICKS;
        reg.update(c.withMasterpieceAssigned(target, deadline));
        src.sendSuccess(() -> Component.literal(
                "Masterpiece phase forced — target: " + target),
                true);
        return 1;
    }

    // =========================================================================
    // /apprentice complete <contractId>
    // =========================================================================

    private static int handleComplete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "contractId");
        ServerLevel level = src.getLevel();
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        Optional<ApprenticeshipContract> opt = reg.getById(id);
        if (opt.isEmpty()) {
            src.sendFailure(Component.literal("No contract with id " + id));
            return 0;
        }
        ApprenticeshipContract c = opt.get();
        TownspersonMob app = TownspersonMob.findByUUID(level, c.apprenticeId()).orElse(null);
        TownspersonMob mst = TownspersonMob.findByUUID(level, c.masterId()).orElse(null);
        ApprenticeshipManager.graduate(reg, c, app, mst, ApprenticeRank.MASTER,
                level.getGameTime());
        src.sendSuccess(() -> Component.literal(
                "Contract " + id.toString().substring(0, 8) + " forced complete (MASTER)"),
                true);
        return 1;
    }

    // =========================================================================
    // /apprentice terminate <contractId>
    // =========================================================================

    private static int handleTerminate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "contractId");
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(src.getLevel());
        Optional<ApprenticeshipContract> opt = reg.getById(id);
        if (opt.isEmpty()) {
            src.sendFailure(Component.literal("No contract with id " + id));
            return 0;
        }
        reg.update(opt.get().withStatus(ContractStatus.TERMINATED));
        src.sendSuccess(() -> Component.literal(
                "Contract " + id.toString().substring(0, 8) + " terminated"),
                true);
        return 1;
    }
}
