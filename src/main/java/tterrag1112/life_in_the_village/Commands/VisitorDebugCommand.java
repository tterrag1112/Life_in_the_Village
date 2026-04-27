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
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Visitor.VillageVisitorCapacity;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorFluxEngine;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorItinerary;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorState;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 29 debug surface.
 *
 * <ul>
 *   <li>{@code /visitor spawn <village> <type>} — force-spawn an
 *       arrival outside the daily roll.</li>
 *   <li>{@code /visitor list <village>} — currently-active visitors
 *       in the village with type, arrival tick, current itinerary
 *       step.</li>
 *   <li>{@code /visitor capacity <village>} — the computed
 *       {@link VillageVisitorCapacity} table.</li>
 *   <li>{@code /visitor itinerary <visitorId>} — full plan for one
 *       visitor.</li>
 * </ul>
 */
public final class VisitorDebugCommand {

    private VisitorDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("visitor")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (VisitorType t : VisitorType.values()) b.suggest(t.name());
                                            return b.buildFuture();
                                        })
                                        .executes(VisitorDebugCommand::handleSpawn))))
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(VisitorDebugCommand::handleList)))
                .then(Commands.literal("capacity")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(VisitorDebugCommand::handleCapacity)))
                .then(Commands.literal("itinerary")
                        .then(Commands.argument("visitorId", UuidArgument.uuid())
                                .executes(VisitorDebugCommand::handleItinerary)))
        );
    }

    // ── /visitor spawn ────────────────────────────────────────────────────

    private static int handleSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        VisitorType type;
        try {
            type = VisitorType.valueOf(
                    StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown visitor type"));
            return 0;
        }
        Optional<TownspersonMob> spawned = VisitorFluxEngine
                .spawnVisitor(level, village, VillageSavedData.get(level), type);
        if (spawned.isEmpty()) {
            src.sendFailure(Component.literal("Spawn failed (no spawn position)"));
            return 0;
        }
        UUID id = spawned.get().getUUID();
        src.sendSuccess(() -> Component.literal(
                "§aSpawned§r " + type + " §f" + id), false);
        return 1;
    }

    // ── /visitor list ─────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        AABB bounds = village.getBounds(VillageSavedData.get(level)).orElse(null);
        StringBuilder sb = new StringBuilder("§e=== Visitors in ")
                .append(village.getName()).append(" ===§r");
        int count = 0;
        if (bounds != null) {
            for (TownspersonMob m : level.getEntitiesOfClass(
                    TownspersonMob.class, bounds.inflate(16),
                    e -> e.isAlive() && e.isVisitor())) {
                count++;
                VisitorState s = m.getVisitorState();
                sb.append(String.format(Locale.ROOT,
                        "%n  §a%-18s§r %s §7(arrived=%d, stop=%d/%d, wallet=%d br)",
                        s.type().map(VisitorType::name).orElse("?"),
                        m.getUUID(),
                        s.arrivalTick(), s.currentItineraryIndex() + 1,
                        s.itinerary().size(),
                        m.getWallet().toBronze()));
            }
        }
        if (count == 0) sb.append("\n  §7(none)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /visitor capacity ─────────────────────────────────────────────────

    private static int handleCapacity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        VillageVisitorCapacity cap = VillageVisitorCapacity.compute(
                village, VillageSavedData.get(level));
        StringBuilder sb = new StringBuilder("§e=== Visitor capacity: ")
                .append(village.getName()).append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7maxConcurrent:§f %d", cap.maxConcurrent()));
        sb.append(String.format(Locale.ROOT, "%n  §7arrivalRatePerDay:§f %.2f",
                cap.arrivalRatePerDay()));
        sb.append("\n  §7typeWeights:");
        if (cap.typeWeights().isEmpty()) {
            sb.append(" §8(none)");
        } else {
            for (Map.Entry<VisitorType, Float> e : cap.typeWeights().entrySet()) {
                sb.append(String.format(Locale.ROOT, "%n    §a%-19s§f %.2f",
                        e.getKey().name(), e.getValue()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /visitor itinerary ────────────────────────────────────────────────

    private static int handleItinerary(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID visitorId = UuidArgument.getUuid(ctx, "visitorId");
        TownspersonMob m = TownspersonMob.findByUUID(level, visitorId).orElse(null);
        if (m == null) { src.sendFailure(Component.literal("No NPC " + visitorId)); return 0; }
        VisitorState s = m.getVisitorState();
        if (!s.isVisitor()) {
            src.sendFailure(Component.literal("NPC is not currently a visitor"));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== Itinerary: ")
                .append(s.type().map(VisitorType::name).orElse("?"))
                .append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7arrivalTick:§f %d", s.arrivalTick()));
        sb.append(String.format(Locale.ROOT, "%n  §7expectedDeparture:§f %d",
                s.expectedDepartureTick()));
        sb.append(String.format(Locale.ROOT, "%n  §7currentStop:§f %d/%d",
                s.currentItineraryIndex() + 1, s.itinerary().size()));
        for (int i = 0; i < s.itinerary().size(); i++) {
            VisitorItinerary v = s.itinerary().get(i);
            String marker = i == s.currentItineraryIndex() ? "§e▶" : "  ";
            sb.append(String.format(Locale.ROOT, "%n  %s §a%-15s§r dur=%d  building=%s",
                    marker, v.activity().name(), v.expectedDurationTicks(), v.buildingId()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
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
}
