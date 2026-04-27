package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Ai.AiCompanyManager;
import tterrag1112.life_in_the_village.Guilds.Companies.Ai.MerchantPromotion;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 26 debug subcommands.
 *
 * <ul>
 *   <li>{@code /company list-npc <village>} — every NPC-owned company
 *       in the village.</li>
 *   <li>{@code /company promote <npc>} — force-promote an eligible
 *       merchant; bypasses the 365-day tenure check via
 *       {@link MerchantPromotion#forcePromote}.</li>
 *   <li>{@code /company owner <companyId>} — show owner type + id +
 *       succession state.</li>
 *   <li>{@code /company succeed <companyId>} — force the succession
 *       routine to run as if the owner just died.</li>
 *   <li>{@code /company dispatch <companyId>} — invoke the trading-
 *       company caravan stub; deposits the placeholder profit.</li>
 * </ul>
 *
 * <p>Plural literal {@code /company} chosen instead of extending
 * {@code /companies} to keep this surface distinct from any future
 * world-state commands.</p>
 */
public final class CompanyDebugCommand {

    private CompanyDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("company")
                .then(Commands.literal("list-npc")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(CompanyDebugCommand::handleListNpc)))
                .then(Commands.literal("promote")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(CompanyDebugCommand::handlePromote)))
                .then(Commands.literal("owner")
                        .then(Commands.argument("companyId", UuidArgument.uuid())
                                .executes(CompanyDebugCommand::handleOwner)))
                .then(Commands.literal("succeed")
                        .then(Commands.argument("companyId", UuidArgument.uuid())
                                .executes(CompanyDebugCommand::handleSucceed)))
                .then(Commands.literal("dispatch")
                        .then(Commands.argument("companyId", UuidArgument.uuid())
                                .executes(CompanyDebugCommand::handleDispatch)))
        );
    }

    // ── /company list-npc <village> ───────────────────────────────────────

    private static int handleListNpc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        StringBuilder sb = new StringBuilder("§e=== NPC-owned companies in ")
                .append(village.getName()).append(" ===§r");
        int count = 0;
        for (Company c : CompanySavedData.get(level).getAllCompanies()) {
            if (!c.isNpcOwned()) continue;
            if (!village.getId().equals(c.getHomeVillageId())) continue;
            count++;
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-18s§r %s §7(type=%s, state=%s, treasury=%d br, workers=%d)",
                    truncate(c.getName(), 18),
                    c.getCompanyId(),
                    c.getCompanyType().name(),
                    c.getSuccessionState().name(),
                    c.getTreasuryBronze(),
                    c.getWorkers().size()));
            sb.append(String.format(Locale.ROOT,
                    "%n    §7owner:§f %s§7  founded:§f tick %d",
                    c.getOwnerId(), c.getFoundedTick()));
        }
        if (count == 0) sb.append("\n  §7(none)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /company promote <npc> ────────────────────────────────────────────

    private static int handlePromote(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC " + npcId));
            return 0;
        }
        try {
            Company c = MerchantPromotion.forcePromote(level, npc);
            src.sendSuccess(() -> Component.literal(
                    "§aPromoted §f" + npc.getNpcName()
                            + "§r to TRADING_COMPANY §f" + c.getName()
                            + "§r (treasury=" + c.getTreasuryBronze() + ")"), false);
            return 1;
        } catch (IllegalStateException e) {
            src.sendFailure(Component.literal("Promotion failed: " + e.getMessage()));
            return 0;
        }
    }

    // ── /company owner <companyId> ────────────────────────────────────────

    private static int handleOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "companyId");
        Company c = CompanySavedData.get(level).getById(id).orElse(null);
        if (c == null) {
            src.sendFailure(Component.literal("No company " + id));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== ").append(c.getName()).append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7type:§f %s", c.getCompanyType()));
        sb.append(String.format(Locale.ROOT, "%n  §7ownerType:§f %s", c.getOwnerType()));
        sb.append(String.format(Locale.ROOT, "%n  §7ownerId:§f %s", c.getOwnerId()));
        sb.append(String.format(Locale.ROOT, "%n  §7successionState:§f %s",
                c.getSuccessionState()));
        sb.append(String.format(Locale.ROOT, "%n  §7heirs:§f %s",
                c.getHeirs().isEmpty() ? "(none)" : c.getHeirs()));
        sb.append(String.format(Locale.ROOT, "%n  §7treasury:§f %d br", c.getTreasuryBronze()));
        sb.append(String.format(Locale.ROOT, "%n  §7foundedTick:§f %d", c.getFoundedTick()));
        sb.append(String.format(Locale.ROOT, "%n  §7dissolutionWarning:§f %d",
                c.getDissolutionWarningTick()));
        sb.append(String.format(Locale.ROOT, "%n  §7undecidedSince:§f %d",
                c.getUndecidedSinceTick()));
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /company succeed <companyId> ──────────────────────────────────────

    private static int handleSucceed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "companyId");
        Company c = CompanySavedData.get(level).getById(id).orElse(null);
        if (c == null) {
            src.sendFailure(Component.literal("No company " + id));
            return 0;
        }
        AiCompanyManager.handleSuccession(level, c, level.getGameTime());
        CompanySavedData.get(level).addCompany(c);
        src.sendSuccess(() -> Component.literal(
                "§aSuccession run§r — state now §f" + c.getSuccessionState()), false);
        return 1;
    }

    // ── /company dispatch <companyId> ─────────────────────────────────────

    private static int handleDispatch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "companyId");
        Company c = CompanySavedData.get(level).getById(id).orElse(null);
        if (c == null) { src.sendFailure(Component.literal("No company " + id)); return 0; }
        if (!c.isTradingCompany()) {
            src.sendFailure(Component.literal("Company is not a TRADING_COMPANY"));
            return 0;
        }
        long profit = AiCompanyManager.dispatchTradingCaravan(level, c);
        CompanySavedData.get(level).addCompany(c);
        src.sendSuccess(() -> Component.literal("§aDispatched§r — placeholder profit §f"
                + profit + " br§r → treasury=" + c.getTreasuryBronze()), false);
        return 1;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Village villageOrFail(CommandContext<CommandSourceStack> ctx,
                                         ServerLevel level, CommandSourceStack src) {
        String name = StringArgumentType.getString(ctx, "village");
        Optional<Village> v = VillageSavedData.get(level).getVillageByName(name);
        if (v.isEmpty()) {
            src.sendFailure(Component.literal("No village " + name));
            return null;
        }
        return v.get();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
