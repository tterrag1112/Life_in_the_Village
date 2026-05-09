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
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Track D1 (Phase 0) — `/litv kingdom debug describe &lt;name&gt;` —
 * prints the kingdom-tier data introduced by D1 for the named
 * kingdom: stability, legitimacy, heraldry, member villages by the
 * new {@code kingdomId} field, and the culture's kingdom-tier
 * defaults.
 *
 * <p>Read-only; no behaviour change. Used to verify the migration
 * back-filled correctly and to introspect the new fields without
 * needing in-game UI.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class KingdomDebugCommand {

    private KingdomDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("litv")
                        .then(Commands.literal("kingdom")
                                .then(Commands.literal("debug")
                                        .then(Commands.literal("describe")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> describe(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        .then(Commands.literal("list")
                                                .executes(KingdomDebugCommand::listAll))
                                        .then(Commands.literal("events_stats")
                                                .executes(KingdomDebugCommand::eventsStats))))
        );
    }

    private static int describe(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug describe must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'. Try /litv kingdom debug list."));
            return 0;
        }

        Culture culture = CultureRegistry.getOrDefault(k.getCulture());
        CultureBundles.CultureKingdomDefaults kd = culture.kingdomDefaults();

        send(ctx, "── Kingdom: " + k.getName() + " ──");
        send(ctx, "  id        : " + k.getId().toString().substring(0, 8));
        send(ctx, "  culture   : " + k.getCulture());
        send(ctx, "  stability : " + k.getStability()
                + " (" + Kingdom.bandOf(k.getStability()) + ")");
        send(ctx, "  legitimacy: " + k.getLegitimacy()
                + " (" + Kingdom.bandOf(k.getLegitimacy()) + ")");
        send(ctx, "  heraldry  : " + k.getHeraldry().describe());
        send(ctx, "  treasury  : " + k.getTreasuryBronze() + " bronze");
        send(ctx, "── Culture defaults ──");
        send(ctx, "  succession    : " + kd.successionRule().name());
        send(ctx, "  subdivision   : " + kd.subdivisionModel().name());
        send(ctx, "  noble ranks   : " + kd.nobilityRanks());
        send(ctx, "  upkeep mix    : " + kd.upkeepMix());
        send(ctx, "  required offc : " + kd.requiredOffices());
        send(ctx, "── Offices held ──");
        if (k.getOffices() != null && !k.getOffices().snapshot().isEmpty()) {
            k.getOffices().snapshot().forEach((officeId, holding) ->
                    send(ctx, "  " + officeId + " → "
                            + (holding.isVacant()
                                    ? "(vacant)"
                                    : holding.holderUuid()
                                            .map(uid -> uid.toString().substring(0, 8))
                                            .orElse("?"))
                            + " (" + holding.actualSelection() + ")"));
        } else {
            send(ctx, "  (none recorded)");
        }
        send(ctx, "── Member villages (legacy list) ──");
        for (UUID vid : k.getVillageIds()) {
            String vname = data.getVillageById(vid)
                    .map(Village::getName).orElse("(missing)");
            send(ctx, "  " + vid.toString().substring(0, 8) + "  " + vname);
        }
        send(ctx, "── Member villages (Track D1 reverse pointer) ──");
        int cnt = 0;
        for (Village v : data.getAllVillages()) {
            UUID vKid = v.getKingdomId().orElse(null);
            if (vKid != null && vKid.equals(k.getId())) {
                send(ctx, "  " + v.getName() + " (id=" + v.getId().toString().substring(0, 8) + ")");
                cnt++;
            }
        }
        send(ctx, "  total: " + cnt);
        return 1;
    }

    private static int listAll(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug list must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        var kingdoms = data.getAllKingdoms();
        if (kingdoms.isEmpty()) {
            send(ctx, "No kingdoms registered.");
            return 0;
        }
        send(ctx, "── Kingdoms (" + kingdoms.size() + ") ──");
        for (Kingdom k : kingdoms) {
            send(ctx, "  " + k.getName() + "  [" + k.getCulture()
                    + "]  stab=" + k.getStability()
                    + " leg=" + k.getLegitimacy()
                    + " villages=" + k.getVillageIds().size());
        }
        return kingdoms.size();
    }

    private static int eventsStats(CommandContext<CommandSourceStack> ctx) {
        var counts = tterrag1112.life_in_the_village.Kingdom.Events
                .KingdomEventBus.allCounts();
        if (counts.isEmpty()) {
            send(ctx, "── KingdomEventBus ── no dispatchers registered (D1: bus is live, no subscribers).");
            return 0;
        }
        send(ctx, "── KingdomEventBus dispatcher counts ──");
        counts.forEach((dispatcher, count) ->
                send(ctx, "  " + dispatcher + " : " + count));
        return counts.size();
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
    }
}
