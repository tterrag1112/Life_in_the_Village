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
import tterrag1112.life_in_the_village.Guilds.Companies.Ai.AiBusinessManager;
import tterrag1112.life_in_the_village.Guilds.Companies.Ai.MerchantPromotion;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 26 debug subcommands.
 *
 * <ul>
 *   <li>{@code /business list-npc <village>} — every NPC-owned business
 *       in the village.</li>
 *   <li>{@code /business promote <npc>} — force-promote an eligible
 *       merchant; bypasses the 365-day tenure check via
 *       {@link MerchantPromotion#forcePromote}.</li>
 *   <li>{@code /business owner <businessId>} — show owner type + id +
 *       succession state.</li>
 *   <li>{@code /business succeed <businessId>} — force the succession
 *       routine to run as if the owner just died.</li>
 *   <li>{@code /business dispatch <businessId>} — invoke the trading-
 *       business caravan stub; deposits the placeholder profit.</li>
 * </ul>
 *
 * <p>Plural literal {@code /business} chosen instead of extending
 * {@code /businesses} to keep this surface distinct from any future
 * world-state commands.</p>
 */
public final class BusinessAdminCommand {

    private BusinessAdminCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("business")
                .then(Commands.literal("list-npc")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(BusinessAdminCommand::handleListNpc)))
                .then(Commands.literal("promote")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(BusinessAdminCommand::handlePromote)))
                .then(Commands.literal("owner")
                        .then(Commands.argument("businessId", UuidArgument.uuid())
                                .executes(BusinessAdminCommand::handleOwner)))
                .then(Commands.literal("succeed")
                        .then(Commands.argument("businessId", UuidArgument.uuid())
                                .executes(BusinessAdminCommand::handleSucceed)))
                .then(Commands.literal("dispatch")
                        .then(Commands.argument("businessId", UuidArgument.uuid())
                                .executes(BusinessAdminCommand::handleDispatch)))
                // PB1 — debug takeover: /business setowner <businessId> <playerUUID>
                .then(Commands.literal("setowner")
                        .then(Commands.argument("businessId", UuidArgument.uuid())
                                .then(Commands.argument("playerUUID", UuidArgument.uuid())
                                        .executes(BusinessAdminCommand::handleSetOwner))))
        );
    }

    // ── /business list-npc <village> ───────────────────────────────────────

    private static int handleListNpc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        StringBuilder sb = new StringBuilder("§e=== NPC-owned businesses in ")
                .append(village.getName()).append(" ===§r");
        int count = 0;
        for (Business c : BusinessSavedData.get(level).getAllBusinesses()) {
            if (!c.isNpcOwned()) continue;
            if (!village.getId().equals(c.getHomeVillageId())) continue;
            count++;
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-18s§r %s §7(type=%s, state=%s, treasury=%d br, workers=%d)",
                    truncate(c.getName(), 18),
                    c.getBusinessId(),
                    c.getBusinessType().name(),
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

    // ── /business promote <npc> ────────────────────────────────────────────

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
            Business c = MerchantPromotion.forcePromote(level, npc);
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

    // ── /business owner <businessId> ────────────────────────────────────────

    private static int handleOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "businessId");
        Business c = BusinessSavedData.get(level).getById(id).orElse(null);
        if (c == null) {
            src.sendFailure(Component.literal("No business " + id));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== ").append(c.getName()).append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7type:§f %s", c.getBusinessType()));
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

    // ── /business succeed <businessId> ──────────────────────────────────────

    private static int handleSucceed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "businessId");
        Business c = BusinessSavedData.get(level).getById(id).orElse(null);
        if (c == null) {
            src.sendFailure(Component.literal("No business " + id));
            return 0;
        }
        AiBusinessManager.handleSuccession(level, c, level.getGameTime());
        BusinessSavedData.get(level).addBusiness(c);
        src.sendSuccess(() -> Component.literal(
                "§aSuccession run§r — state now §f" + c.getSuccessionState()), false);
        return 1;
    }

    // ── /business dispatch <businessId> ─────────────────────────────────────

    private static int handleDispatch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "businessId");
        Business c = BusinessSavedData.get(level).getById(id).orElse(null);
        if (c == null) { src.sendFailure(Component.literal("No business " + id)); return 0; }
        if (!c.isTradingBusiness()) {
            src.sendFailure(Component.literal("Business is not a TRADING_COMPANY"));
            return 0;
        }
        long profit = AiBusinessManager.dispatchTradingCaravan(level, c);
        BusinessSavedData.get(level).addBusiness(c);
        src.sendSuccess(() -> Component.literal("§aDispatched§r — placeholder profit §f"
                + profit + " br§r → treasury=" + c.getTreasuryBronze()), false);
        return 1;
    }

    // ── /business setowner <businessId> <playerUUID> ──────────────────────
    // PB1 — debug/admin takeover path. Makes a player the sealed PlayerOwner
    // of any existing business regardless of current owner type. Does NOT
    // transfer workers or treasury. This is a testing affordance; the real
    // player-facing acquisition UX (purchase / inheritance) is a later phase.

    private static int handleSetOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID businessId = UuidArgument.getUuid(ctx, "businessId");
        UUID playerUUID = UuidArgument.getUuid(ctx, "playerUUID");

        BusinessSavedData cdata = BusinessSavedData.get(level);
        Business c = cdata.getById(businessId).orElse(null);
        if (c == null) {
            src.sendFailure(Component.literal("No business with id " + businessId));
            return 0;
        }

        String prevOwner = c.getOwner() != null
                ? c.getOwner().kind() + "/" + c.getOwnerId()
                : "none";

        // Route through the canonical setter so the sealed owner is the
        // single source of truth; ownerPlayerId legacy field is not updated
        // (it's final) — all queries now use isOwnedByPlayer / getPlayerOwnerId.
        c.setPlayerOwner(playerUUID);
        cdata.markDirty();

        final String prev = prevOwner;
        src.sendSuccess(() -> Component.literal(
                "§a" + c.getName() + "§r owner set to §fPlayerOwner/"
                        + playerUUID + "§r (was §7" + prev + "§r)"), false);
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
