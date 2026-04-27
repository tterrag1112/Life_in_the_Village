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
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Event.EventAttendance;
import tterrag1112.life_in_the_village.Village.Event.EventEffects;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Phase 5 doc 32 § Debug commands.
 *
 * <ul>
 *   <li>{@code /event list <village>} — upcoming + active + recent past
 *       events for a village.</li>
 *   <li>{@code /event schedule <village> <type>} — force-schedule an
 *       event for the next minute.</li>
 *   <li>{@code /event start <eventId>} — flip a SCHEDULED event to
 *       ACTIVE immediately.</li>
 *   <li>{@code /event complete <eventId>} — flip an ACTIVE event to
 *       ENDED immediately.</li>
 *   <li>{@code /event cancel <eventId>} — set status to CANCELLED.</li>
 * </ul>
 */
public final class EventDebugCommand {

    private EventDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("event")
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(EventDebugCommand::handleList)))
                .then(Commands.literal("schedule")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (VillageEvent.EventType t : VillageEvent.EventType.values()) {
                                                b.suggest(t.name());
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(EventDebugCommand::handleSchedule))))
                .then(Commands.literal("start")
                        .then(Commands.argument("eventId", UuidArgument.uuid())
                                .executes(EventDebugCommand::handleStart)))
                .then(Commands.literal("complete")
                        .then(Commands.argument("eventId", UuidArgument.uuid())
                                .executes(EventDebugCommand::handleComplete)))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("eventId", UuidArgument.uuid())
                                .executes(EventDebugCommand::handleCancel)))
        );
    }

    // ── /event list <village> ────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "village");
        Village v = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("No village " + name));
            return 0;
        }
        List<VillageEvent> all = VillageSavedData.get(level).getAllEvents().stream()
                .filter(e -> e.getVillageId().equals(v.getId()))
                .toList();
        StringBuilder sb = new StringBuilder("§e=== Events for ")
                .append(v.getName()).append(" (").append(all.size()).append(") ===§r");
        for (VillageEvent e : all) {
            sb.append(String.format(Locale.ROOT, "%n  §a%-8s§r %s §7[%s]§r start=%d end=%d att=%d/%d",
                    e.getStatus().name(), e.getType().name(),
                    e.getCategory().name(),
                    e.getStartTick(), e.getEndTick(),
                    e.getActualAttendees().size(),
                    e.getRequiredAttendees().size() + e.getInvitedAttendees().size()));
            sb.append(" §8id=").append(e.getId());
            e.getPrimarySubjectId().ifPresent(p ->
                    sb.append(" subj=").append(p.toString().substring(0, 8)));
        }
        if (all.isEmpty()) sb.append("\n  §7(no events)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /event schedule <village> <type> ─────────────────────────────

    private static int handleSchedule(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "village");
        String typeName = StringArgumentType.getString(ctx, "type");
        Village v = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("No village " + name));
            return 0;
        }
        VillageEvent.EventType type;
        try {
            type = VillageEvent.EventType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            src.sendFailure(Component.literal("Unknown event type " + typeName));
            return 0;
        }
        long now = level.getGameTime();
        // Invite the village so the event has attendees if no other
        // source populates them.
        List<UUID> invited = EventAttendance.villageNpcIds(level, v,
                VillageSavedData.get(level));
        VillageEvent event = VillageEventScheduler.scheduleLifeEvent(level, v, type,
                now + 1200L, null, List.of(), invited);
        if (event == null) {
            src.sendFailure(Component.literal("Failed to schedule"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aScheduled§r " + type
                + " in " + v.getName() + " (id=" + event.getId() + ")")
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /event start <eventId> ───────────────────────────────────────

    private static int handleStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "eventId");
        VillageEvent event = VillageSavedData.get(level).getEventById(id).orElse(null);
        if (event == null) {
            src.sendFailure(Component.literal("No event " + id));
            return 0;
        }
        if (event.getStatus() != VillageEvent.EventStatus.ANNOUNCED) {
            src.sendFailure(Component.literal("Event is " + event.getStatus()
                    + ", expected ANNOUNCED"));
            return 0;
        }
        Village v = VillageSavedData.get(level).getVillageById(event.getVillageId())
                .orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("Village missing"));
            return 0;
        }
        event.setStatus(VillageEvent.EventStatus.ACTIVE);
        EventEffects.onEventStart(level, event, v, VillageSavedData.get(level));
        VillageSavedData.get(level).setDirty();
        src.sendSuccess(() -> Component.literal("§aStarted§r " + event.getType())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /event complete <eventId> ────────────────────────────────────

    private static int handleComplete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "eventId");
        VillageEvent event = VillageSavedData.get(level).getEventById(id).orElse(null);
        if (event == null) {
            src.sendFailure(Component.literal("No event " + id));
            return 0;
        }
        if (event.getStatus() != VillageEvent.EventStatus.ACTIVE) {
            src.sendFailure(Component.literal("Event is " + event.getStatus()
                    + ", expected ACTIVE"));
            return 0;
        }
        Village v = VillageSavedData.get(level).getVillageById(event.getVillageId())
                .orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("Village missing"));
            return 0;
        }
        event.setStatus(VillageEvent.EventStatus.ENDED);
        event.setCompletedTick(level.getGameTime());
        EventAttendance.clearOverrides(level, event);
        EventEffects.onEventEnd(level, event, v, VillageSavedData.get(level));
        VillageSavedData.get(level).setDirty();
        src.sendSuccess(() -> Component.literal("§aCompleted§r " + event.getType())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /event cancel <eventId> ──────────────────────────────────────

    private static int handleCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "eventId");
        VillageEvent event = VillageSavedData.get(level).getEventById(id).orElse(null);
        if (event == null) {
            src.sendFailure(Component.literal("No event " + id));
            return 0;
        }
        if (event.getStatus() == VillageEvent.EventStatus.ACTIVE) {
            EventAttendance.clearOverrides(level, event);
        }
        event.setStatus(VillageEvent.EventStatus.CANCELLED);
        event.setCompletedTick(level.getGameTime());
        VillageSavedData.get(level).setDirty();
        src.sendSuccess(() -> Component.literal("§aCancelled§r " + event.getType())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }
}
