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
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresence;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingTypeFlags;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BusinessFrontTracker;
import tterrag1112.life_in_the_village.Npc.BusinessFront.GreeterAssignment;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /business} debug subcommands per spec line 257.
 *
 * <ul>
 *   <li>{@code /business presence} — list every player's current building.</li>
 *   <li>{@code /business greeter <npc>} — force-assign greeter (uses the
 *       command executor as the player target) or clear an existing one.</li>
 *   <li>{@code /business flags <buildingType>} — print the type's flag set.</li>
 * </ul>
 */
public final class BusinessDebugCommand {

    private BusinessDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("business")
                .then(Commands.literal("presence")
                        .executes(BusinessDebugCommand::handlePresence))
                .then(Commands.literal("greeter")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(BusinessDebugCommand::handleGreeterAssign)
                                .then(Commands.literal("clear")
                                        .executes(BusinessDebugCommand::handleGreeterClear))))
                .then(Commands.literal("flags")
                        .then(Commands.argument("buildingType", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (BuildingType t : BuildingType.values()) b.suggest(t.name());
                                    return b.buildFuture();
                                })
                                .executes(BusinessDebugCommand::handleFlags)))
        );
    }

    // ── /business presence ─────────────────────────────────────────────────

    private static int handlePresence(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);
        Map<UUID, BuildingPresence> snap = BuildingPresenceTracker.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Player presences (").append(snap.size()).append(") ===");
        if (snap.isEmpty()) sb.append("\n  §8(no players tracked)");
        for (var e : snap.entrySet()) {
            BuildingPresence p = e.getValue();
            String buildingDesc;
            if (p.buildingId() == null) {
                buildingDesc = "§8outdoors";
            } else {
                Building b = data.getBuildingById(p.buildingId()).orElse(null);
                if (b == null) {
                    buildingDesc = "§7(unknown id " + p.buildingId() + ")";
                } else {
                    buildingDesc = "§a" + b.getType().name()
                            + (BuildingTypeFlags.hasBusinessFront(b.getType()) ? " §7[BUSINESS]" : "")
                            + (BuildingTypeFlags.hasServiceFront(b.getType()) ? " §7[SERVICE]" : "")
                            + (BuildingTypeFlags.isResidence(b.getType())     ? " §7[RES]" : "");
                }
            }
            ServerPlayer player = src.getServer().getPlayerList().getPlayer(p.playerId());
            String name = player == null ? p.playerId().toString().substring(0, 8) : player.getName().getString();
            sb.append(String.format(Locale.ROOT,
                    "%n  §f%-16s§r → %s §7(since tick %d)",
                    name, buildingDesc, p.enteredAtTick()));
        }
        // Per-building business-front state.
        var fronts = BusinessFrontTracker.snapshot();
        if (!fronts.isEmpty()) {
            sb.append("\n§e--- Business-front states ---");
            for (var e : fronts.entrySet()) {
                Building b = data.getBuildingById(e.getKey()).orElse(null);
                String typeStr = b == null ? "?" : b.getType().name();
                var s = e.getValue();
                sb.append(String.format(Locale.ROOT,
                        "%n  §f%-22s§r status=§a%s§r greeter=§f%s",
                        typeStr, s.status().name(),
                        s.greeterNpcId() == null ? "-" : s.greeterNpcId().toString().substring(0, 8)));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /business greeter <npc> [clear] ────────────────────────────────────

    private static int handleGreeterAssign(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with id " + npcId));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("/business greeter requires a player executor."));
            return 0;
        }
        UUID buildingId = npc.getAssignedBuildingId().orElse(null);
        if (buildingId == null) {
            src.sendFailure(Component.literal(npc.getNpcName() + " has no assigned building."));
            return 0;
        }
        Optional<Building> b = VillageSavedData.get(level).getBuildingById(buildingId);
        if (b.isEmpty()) {
            src.sendFailure(Component.literal("Building " + buildingId + " not found."));
            return 0;
        }
        GreeterAssignment.assignFor(player, b.get(), level, VillageSavedData.get(level));
        src.sendSuccess(() -> Component.literal(
                "Forced greeter assignment for §f" + npc.getNpcName()
                        + "§r → player §f" + player.getName().getString()), false);
        return 1;
    }

    private static int handleGreeterClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        GreeterAssignment.clearGreeter(npcId, level);
        src.sendSuccess(() -> Component.literal(
                "Cleared greeter assignment for §f" + npcId), false);
        return 1;
    }

    // ── /business flags <buildingType> ─────────────────────────────────────

    private static int handleFlags(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String typeName = StringArgumentType.getString(ctx, "buildingType");
        BuildingType type;
        try { type = BuildingType.valueOf(typeName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown building type: " + typeName));
            return 0;
        }
        var flags = BuildingTypeFlags.flagsFor(type);
        StringBuilder sb = new StringBuilder();
        sb.append("§e").append(type.name()).append("§r flags: ");
        if (flags.isEmpty()) sb.append("§8(none)");
        else                 sb.append("§a").append(flags);
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }
}
