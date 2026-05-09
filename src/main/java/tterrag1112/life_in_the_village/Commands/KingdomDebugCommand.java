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
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
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
                                                .executes(KingdomDebugCommand::eventsStats))
                                        // Track D3.2a — list noble houses for a kingdom.
                                        .then(Commands.literal("houses")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> houses(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.2a — list active stability/legitimacy modifiers.
                                        .then(Commands.literal("modifiers")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> modifiers(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.2b — preview the fealty chain (lord-of-village).
                                        .then(Commands.literal("fealty")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> fealty(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))))
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
        send(ctx, "  id           : " + k.getId().toString().substring(0, 8));
        send(ctx, "  culture      : " + k.getCulture());
        send(ctx, "  founding tick: " + k.getFoundingTick());
        send(ctx, "  capital      : " + k.getCapitalVillageId()
                .map(cid -> data.getVillageById(cid)
                        .map(v -> v.getName() + " (" + cid.toString().substring(0, 8) + ")")
                        .orElse("(missing village " + cid.toString().substring(0, 8) + ")"))
                .orElse("(unset)"));
        send(ctx, "  stability    : " + k.getStability()
                + " (" + Kingdom.bandOf(k.getStability()) + ")");
        send(ctx, "  legitimacy   : " + k.getLegitimacy()
                + " (" + Kingdom.bandOf(k.getLegitimacy()) + ")");
        send(ctx, "  heraldry     : " + k.getHeraldry().describe());
        send(ctx, "  treasury     : " + k.getTreasuryBronze() + " bronze");
        send(ctx, "── Culture defaults ──");
        send(ctx, "  succession    : " + kd.successionRule().name());
        send(ctx, "  subdivision   : " + kd.subdivisionModel().name());
        send(ctx, "  noble ranks   : " + kd.nobilityRanks());
        send(ctx, "  upkeep mix    : " + kd.upkeepMix());
        send(ctx, "  required offc : " + kd.requiredOffices());
        // Track D1.5 — kingdom-wide settings.
        send(ctx, "  claim budget  : " + kd.claimBudgetHint());
        send(ctx, "  claim resist  : " + kd.claimResistance());
        send(ctx, "  vassal-eligible: " + kd.vassalEligibleCultures());
        send(ctx, "  hostile       : " + kd.hostileCultures());
        send(ctx, "  min nobility  : " + kd.minNobilityTier());
        send(ctx, "  prov-seat thr : " + kd.provinceSeatThreshold());
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
        // Track D3.2a — house + modifier summary.
        send(ctx, "── Noble houses (D3.2a) ──");
        if (k.getHouses().isEmpty()) {
            send(ctx, "  (none)");
        } else {
            for (House h : k.getHouses()) {
                send(ctx, "  " + h.name() + " [" + h.id().toString().substring(0, 8) + "] "
                        + (h.isExtinct() ? "(extinct)"
                                : "head=" + h.headUuid().get().toString().substring(0, 8))
                        + " prestige=" + h.prestige());
            }
        }
        send(ctx, "── Active modifiers (D3.2a) ──");
        if (k.getModifiers().isEmpty()) {
            send(ctx, "  (none)");
        } else {
            for (KingdomModifier m : k.getModifiers()) {
                send(ctx, "  " + m.id() + ": stab" + signed(m.stabilityDelta())
                        + " leg" + signed(m.legitimacyDelta())
                        + (m.isPermanent() ? " (permanent)"
                                : " expires@" + m.expiresAtTick()));
            }
        }
        return 1;
    }

    private static String signed(int v) { return v >= 0 ? "+" + v : Integer.toString(v); }

    private static int houses(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug houses must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        if (k.getHouses().isEmpty()) {
            send(ctx, "Kingdom '" + name + "' has no noble houses.");
            return 0;
        }
        send(ctx, "── Houses of " + name + " (" + k.getHouses().size() + ") ──");
        for (House h : k.getHouses()) {
            send(ctx, "  " + h.name() + " [" + h.id().toString().substring(0, 8) + "]");
            send(ctx, "    founder    : " + h.founderUuid().toString().substring(0, 8)
                    + " @ tick " + h.foundingTick());
            send(ctx, "    head       : " + (h.isExtinct() ? "(extinct)"
                    : h.headUuid().get().toString().substring(0, 8)));
            send(ctx, "    heraldry   : " + h.heraldry().describe());
            send(ctx, "    prestige   : " + h.prestige());
            if (!h.motto().isEmpty()) send(ctx, "    motto      : " + h.motto());
        }
        return k.getHouses().size();
    }

    private static int modifiers(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug modifiers must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        send(ctx, "── Modifiers on " + name + " ──");
        send(ctx, "  base stability  : " + k.getStability()
                + "   modifier sum: " + signed(k.stabilityModifierSum()));
        send(ctx, "  base legitimacy : " + k.getLegitimacy()
                + "   modifier sum: " + signed(k.legitimacyModifierSum()));
        if (k.getModifiers().isEmpty()) {
            send(ctx, "  (no active modifiers)");
            return 0;
        }
        for (KingdomModifier m : k.getModifiers()) {
            send(ctx, "  " + m.id());
            if (!m.description().isEmpty()) send(ctx, "    desc       : " + m.description());
            send(ctx, "    deltas     : stab" + signed(m.stabilityDelta())
                    + ", leg" + signed(m.legitimacyDelta()));
            send(ctx, "    applied@   : " + m.appliedAtTick());
            send(ctx, "    expires@   : " + (m.isPermanent() ? "permanent"
                    : Long.toString(m.expiresAtTick())));
        }
        return k.getModifiers().size();
    }

    /**
     * Track D3.2b — prints the lord-of-village resolution result for
     * each village in the kingdom. Useful for verifying that the
     * fealty chain finds a noble overlord (or correctly falls
     * through to "no lord — direct flow" for villages without one).
     */
    private static int fealty(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug fealty must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        send(ctx, "── Fealty chain for " + name + " ──");
        send(ctx, "  default skim rate: "
                + tterrag1112.life_in_the_village.Npc.Nobility.FealtyChain.DEFAULT_LORD_SKIM_RATE);
        int villagesWithLord = 0;
        for (UUID vid : k.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            var lord = tterrag1112.life_in_the_village.Npc.Nobility.FealtyChain
                    .lordOfVillage(level, data, v);
            if (lord.isPresent()) {
                villagesWithLord++;
                var npc = lord.get();
                send(ctx, "  " + v.getName() + " → " + npc.getNpcName()
                        + " (rank=" + npc.getNobility().getRankIndex()
                        + ", prestige=" + npc.getNobility().getPrestige()
                        + ", house=" + npc.getNobility().getDynastyHouseId()
                                .map(id -> id.toString().substring(0, 8))
                                .orElse("none") + ")");
            } else {
                send(ctx, "  " + v.getName() + " → (no lord; direct flow)");
            }
        }
        send(ctx, "  " + villagesWithLord + "/" + k.getVillageIds().size()
                + " villages have a noble overlord");
        return villagesWithLord;
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
            String capital = k.getCapitalVillageId()
                    .flatMap(data::getVillageById)
                    .map(Village::getName)
                    .orElse("(unset)");
            String kingHeld = k.getOffices() == null ? "?"
                    : (k.getOffices().isVacant(
                            tterrag1112.life_in_the_village.Npc.Office
                                    .OfficeRegistry.KINGDOM_KING) ? "vacant" : "seated");
            send(ctx, "  " + k.getName() + "  [" + k.getCulture()
                    + "]  capital=" + capital
                    + "  king=" + kingHeld
                    + "  stab=" + k.getStability()
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
