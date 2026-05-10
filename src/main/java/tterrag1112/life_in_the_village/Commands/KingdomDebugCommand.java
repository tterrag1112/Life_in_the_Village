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
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Kingdom.Provinces.ProvinceComputer;
import tterrag1112.life_in_the_village.Kingdom.Provinces.ProvincialReport;
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
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.3 — list provinces of a kingdom.
                                        .then(Commands.literal("provinces")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> provinces(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.3 — force a province polygon recompute.
                                        .then(Commands.literal("province_recompute")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> provinceRecompute(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.3b — list capability gate status for every capability.
                                        .then(Commands.literal("capabilities")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> capabilities(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))
                                        // Track D3.4 — list every law's archetype, state, and effect summary.
                                        .then(Commands.literal("laws")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> laws(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))))
                        // Track D3.3 — top-level /litv province describe <provinceName>.
                        .then(Commands.literal("province")
                                .then(Commands.literal("debug")
                                        .then(Commands.literal("describe")
                                                .then(Commands.argument("name",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> provinceDescribe(ctx,
                                                                StringArgumentType.getString(ctx, "name")))))))
                        // Track D3.4b — top-level /litv charter list/grant/revoke.
                        .then(Commands.literal("charter")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("name",
                                                        StringArgumentType.string())
                                                .executes(ctx -> charterList(ctx,
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("type",
                                                                StringArgumentType.string())
                                                        .then(Commands.argument("granteeUuid",
                                                                        StringArgumentType.string())
                                                                .executes(ctx -> charterGrant(ctx,
                                                                        StringArgumentType.getString(ctx, "kingdom"),
                                                                        StringArgumentType.getString(ctx, "type"),
                                                                        StringArgumentType.getString(ctx, "granteeUuid")))))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("charterUuid",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> charterRevoke(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "charterUuid")))))))
                        // Track D3.4b — top-level /litv treaty list/propose/break.
                        .then(Commands.literal("treaty")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("name",
                                                        StringArgumentType.string())
                                                .executes(ctx -> treatyList(ctx,
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("propose")
                                        .then(Commands.argument("typeName",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("kingdomA",
                                                                StringArgumentType.string())
                                                        .then(Commands.argument("kingdomB",
                                                                        StringArgumentType.string())
                                                                .executes(ctx -> treatyPropose(ctx,
                                                                        StringArgumentType.getString(ctx, "typeName"),
                                                                        StringArgumentType.getString(ctx, "kingdomA"),
                                                                        StringArgumentType.getString(ctx, "kingdomB")))))))
                                .then(Commands.literal("break")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("treatyUuid",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> treatyBreak(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "treatyUuid")))))))
                        // Track D3.4b — top-level /litv intrigue test_sow.
                        .then(Commands.literal("intrigue")
                                .then(Commands.literal("test_sow")
                                        .then(Commands.argument("source",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("target",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> intrigueTestSow(ctx,
                                                                StringArgumentType.getString(ctx, "source"),
                                                                StringArgumentType.getString(ctx, "target")))))))
                        // Track D3.5A — top-level /litv petition list/submit_grievance/approve/deny.
                        .then(Commands.literal("petition")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .executes(ctx -> petitionList(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom")))))
                                .then(Commands.literal("submit_grievance")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("text",
                                                                StringArgumentType.greedyString())
                                                        .executes(ctx -> petitionSubmitGrievance(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "text"))))))
                                .then(Commands.literal("approve")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("petitionUuid",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> petitionResolve(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "petitionUuid"),
                                                                true)))))
                                .then(Commands.literal("deny")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("petitionUuid",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> petitionResolve(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "petitionUuid"),
                                                                false))))))
                        // Track D3.5C — top-level /litv ennoblement list/grant.
                        .then(Commands.literal("ennoblement")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .executes(ctx -> ennoblementList(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom")))))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("rank",
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .integer(0, 8))
                                                        .executes(ctx -> ennoblementGrant(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .getInteger(ctx, "rank")))))))
                        // Track D3.6.5 — religion debug: /litv kingdom debug sanctify <kingdom>.
                        .then(Commands.literal("sanctify")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.string())
                                        .executes(ctx -> sanctifyDebug(ctx,
                                                StringArgumentType.getString(ctx, "kingdom")))))
                        // Track D3.6.5 — /litv kingdom debug convert <kingdom> <provinceName>.
                        .then(Commands.literal("convert")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.string())
                                        .then(Commands.argument("provinceName",
                                                        StringArgumentType.string())
                                                .executes(ctx -> convertDebug(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom"),
                                                        StringArgumentType.getString(ctx, "provinceName"))))))
                        // Track D3.6.6 — /litv kingdom debug age_cycle <kingdom> <state>.
                        .then(Commands.literal("age_cycle")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.string())
                                        .then(Commands.argument("state",
                                                        StringArgumentType.string())
                                                .executes(ctx -> ageCycleDebug(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom"),
                                                        StringArgumentType.getString(ctx, "state"))))))
                        // Track D3.6.4 — top-level /litv kingdom debug war_declare/war_resolve/war_list.
                        .then(Commands.literal("war_declare")
                                .then(Commands.argument("attacker",
                                                StringArgumentType.string())
                                        .then(Commands.argument("defender",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("casusBelli",
                                                                StringArgumentType.string())
                                                        .then(Commands.argument("goalsCsv",
                                                                        StringArgumentType.greedyString())
                                                                .executes(ctx -> warDeclareDebug(ctx,
                                                                        StringArgumentType.getString(ctx, "attacker"),
                                                                        StringArgumentType.getString(ctx, "defender"),
                                                                        StringArgumentType.getString(ctx, "casusBelli"),
                                                                        StringArgumentType.getString(ctx, "goalsCsv")))))))
                                .then(Commands.argument("attacker_simple",
                                                StringArgumentType.string())
                                        .executes(ctx -> warDeclareDebug(ctx,
                                                StringArgumentType.getString(ctx, "attacker_simple"),
                                                "", "TERRITORIAL_CLAIM", ""))))
                        .then(Commands.literal("war_resolve")
                                .then(Commands.argument("warUuid",
                                                StringArgumentType.string())
                                        .then(Commands.argument("status",
                                                        StringArgumentType.string())
                                                .executes(ctx -> warResolveDebug(ctx,
                                                        StringArgumentType.getString(ctx, "warUuid"),
                                                        StringArgumentType.getString(ctx, "status"))))))
                        .then(Commands.literal("war_list")
                                .executes(ctx -> warListDebug(ctx)))
                        // Track D3.6 — top-level /litv kingdom debug rebel/collapse/merge.
                        .then(Commands.literal("rebel")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.string())
                                        .then(Commands.argument("provinceName",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("choice",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> rebelDebug(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "provinceName"),
                                                                StringArgumentType.getString(ctx, "choice")))))))
                        .then(Commands.literal("collapse")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.string())
                                        .executes(ctx -> collapseDebug(ctx,
                                                StringArgumentType.getString(ctx, "kingdom")))))
                        .then(Commands.literal("merge")
                                .then(Commands.argument("survivor",
                                                StringArgumentType.string())
                                        .then(Commands.argument("mergedAway",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("path",
                                                                StringArgumentType.string())
                                                        .executes(ctx -> mergeDebug(ctx,
                                                                StringArgumentType.getString(ctx, "survivor"),
                                                                StringArgumentType.getString(ctx, "mergedAway"),
                                                                StringArgumentType.getString(ctx, "path")))))))
                        // Track D3.5C — top-level /litv newsfeed list <kingdom>.
                        .then(Commands.literal("newsfeed")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .executes(ctx -> newsfeedList(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom"))))))
                        // Track D3.5A — top-level /litv standing get/set.
                        .then(Commands.literal("standing")
                                .then(Commands.literal("get")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .executes(ctx -> standingGet(ctx,
                                                        StringArgumentType.getString(ctx, "kingdom")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("kingdom",
                                                        StringArgumentType.string())
                                                .then(Commands.argument("delta",
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .integer(-100, 100))
                                                        .executes(ctx -> standingAdjust(ctx,
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .getInteger(ctx, "delta")))))))
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
        send(ctx, "── Charters / Treaties / Intrigue (D3.4b) ──");
        long activeCharters = k.getCharters().stream().filter(c -> c.active()).count();
        long activeTreaties = k.getTreaties().stream().filter(t -> t.isActive()).count();
        send(ctx, "  charters: " + activeCharters + " active / "
                + k.getCharters().size() + " total");
        send(ctx, "  treaties: " + activeTreaties + " active / "
                + k.getTreaties().size() + " total");
        if (k.isVassal()) {
            send(ctx, "  vassal of: " + k.overlordKingdomId()
                    .map(id -> id.toString().substring(0, 8)).orElse("?"));
        }
        send(ctx, "  intrigue history: " + k.getIntrigueHistory().size()
                + " entries");
        send(ctx, "── Audience / Standing (D3.5A) ──");
        long pendingPet = k.getPetitions().stream().filter(p -> p.isPending()).count();
        send(ctx, "  petitions: " + pendingPet + " pending / "
                + k.getPetitions().size() + " total");
        send(ctx, "  player standings tracked: " + k.getAllPlayerStandings().size());
        send(ctx, "  player nobles: " + k.getAllPlayerNobles().size()
                + ", newsfeed entries: " + k.getHistory().getNewsfeed().size());
        send(ctx, "  active wars (initiated): " + k.getActiveWars().size()
                + " / total " + k.getWars().size());
        send(ctx, "── Religion / Age (D3.6 S3) ──");
        var ageState = tterrag1112.life_in_the_village.Kingdom.AgeCycle.AgeCycleDriver
                .currentState(k);
        send(ctx, "  age cycle: " + (ageState != null ? ageState.name() : "fresh"));
        send(ctx, "  successions: " + k.getHistory().getSuccessionCount());
        var officialOpt = tterrag1112.life_in_the_village.Kingdom.Religion
                .ReligionAuthorityEngine.officialReligion(k);
        send(ctx, "  official religion: "
                + officialOpt.orElse("(none declared)"));
        send(ctx, "  ruler sanctified: "
                + k.hasModifierWithId(tterrag1112.life_in_the_village.Kingdom.Religion
                        .ReligionAuthorityEngine.SANCTIFIED_MODIFIER_ID));
        send(ctx, "── Provinces (D3.3) ──");
        if (k.getProvinces().isEmpty()) {
            send(ctx, "  (none — UNITARY, ≤3 villages, or not yet computed)");
        } else {
            for (Province p : k.getProvinces()) {
                send(ctx, "  " + p.name() + " — "
                        + p.villageIds().size() + " villages, "
                        + p.cellKeys().size() + " cells, "
                        + (p.isGoverned() ? "governor "
                                + p.governorUuid().get().toString().substring(0, 8)
                                : "ungoverned")
                        + ", stab=" + p.stability());
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

    /**
     * Track D3.4 — lists every registered KingdomLaw with its
     * archetype, current per-kingdom state (DRAFT / PROPOSED /
     * ACTIVE / available), parameter value where applicable, and
     * the satisfier capability for tooltips.
     */
    private static int laws(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug laws must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        send(ctx, "── Laws of " + name + " ──");
        var registered = tterrag1112.life_in_the_village.Kingdom.Laws
                .KingdomLawRegistry.all();
        int active = 0;
        int touched = 0;
        for (var law : registered) {
            var inst = k.findLawInstance(law.id()).orElse(null);
            String state = (inst == null) ? "available" : inst.state().name();
            String param = "";
            if (inst != null) {
                if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.ScalarLaw s) {
                    param = " value=" + inst.scalarValue().orElse(s.defaultValue())
                            + " " + s.unit();
                } else if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.EnumLaw e) {
                    param = " choice=" + inst.enumChoice().orElse(e.defaultChoice());
                }
                touched++;
                if (inst.isActive()) active++;
            }
            send(ctx, String.format("  [%s] %s (%s, %s)%s — gate=%s",
                    state, law.displayName(), law.archetype(), law.category().name(),
                    param, law.enactmentCapability().name()));
        }
        send(ctx, "  " + active + " active, " + touched + " touched, "
                + registered.size() + " total");
        return active;
    }

    /**
     * Track D3.3b — evaluates every {@link tterrag1112.life_in_the_village
     * .Kingdom.Capabilities.KingdomCapability} against the named kingdom
     * and lists the satisfier office state for each. Useful for
     * debugging "why is this button greyed out".
     */
    private static int capabilities(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug capabilities must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        send(ctx, "── Capabilities of " + name + " ──");
        int allowed = 0;
        for (var cap : tterrag1112.life_in_the_village.Kingdom.Capabilities
                .KingdomCapability.values()) {
            var result = tterrag1112.life_in_the_village.Kingdom.Capabilities
                    .KingdomCapabilityEvaluator.evaluate(level, data, k, cap);
            String tag = result.allowed() ? "ALLOW" : "DENY ";
            send(ctx, "  " + tag + " " + cap.name() + " — " + result.reason());
            if (result.allReports() != null) {
                for (var r : result.allReports()) {
                    send(ctx, "      [" + r.officeId() + "] " + r.reason());
                }
            }
            if (result.allowed()) allowed++;
        }
        send(ctx, "  " + allowed + "/"
                + tterrag1112.life_in_the_village.Kingdom.Capabilities
                        .KingdomCapability.values().length
                + " capabilities currently exercisable.");
        return allowed;
    }

    /**
     * Track D3.3 — lists provinces of a kingdom with governor +
     * stability summaries.
     */
    private static int provinces(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug provinces must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        var ps = k.getProvinces();
        if (ps.isEmpty()) {
            send(ctx, "Kingdom '" + name + "' has no provinces (UNITARY culture, ≤3 villages, or pre-D3.3).");
            return 0;
        }
        send(ctx, "── Provinces of " + name + " (" + ps.size() + ") ──");
        send(ctx, "  last recompute tick: " + k.getLastProvinceRecomputeTick());
        for (Province p : ps) {
            send(ctx, "  " + p.name() + " [" + p.id().toString().substring(0, 8) + "]");
            send(ctx, "    villages : " + p.villageIds().size()
                    + ", cells : " + p.cellKeys().size());
            send(ctx, "    governor : " + p.governorUuid()
                    .map(uid -> uid.toString().substring(0, 8))
                    .orElse("(ungoverned)"));
            send(ctx, "    stability: " + p.stability()
                    + " (effective " + (p.stability() + p.stabilityModifierSum()) + ")");
            send(ctx, "    treasury : " + p.treasury() + " bronze");
        }
        return ps.size();
    }

    /**
     * Track D3.3 — forces a province polygon recompute on the named
     * kingdom, bypassing the weekly cadence. Useful for testing
     * subdivision changes without waiting in-game.
     */
    private static int provinceRecompute(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv kingdom debug province_recompute must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No kingdom named '" + name + "'."));
            return 0;
        }
        ProvinceComputer.recompute(level, data, k, level.getGameTime());
        data.setDirty();
        send(ctx, "Recomputed " + k.getProvinces().size()
                + " province(s) for kingdom '" + name + "'.");
        return k.getProvinces().size();
    }

    /**
     * Track D3.3 — describes a single province by name across all
     * kingdoms. Shows governor, members, stability + modifiers, and
     * the most recent rolling-buffer reports.
     */
    private static int provinceDescribe(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/litv province debug describe must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Province found = null;
        Kingdom owner = null;
        for (Kingdom k : data.getAllKingdoms()) {
            var p = k.findProvinceByName(name).orElse(null);
            if (p != null) { found = p; owner = k; break; }
        }
        if (found == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No province named '" + name + "' in any kingdom."));
            return 0;
        }
        send(ctx, "── Province: " + found.name() + " ──");
        send(ctx, "  id        : " + found.id().toString().substring(0, 8));
        send(ctx, "  kingdom   : " + owner.getName());
        send(ctx, "  governor  : " + found.governorUuid()
                .map(uid -> uid.toString().substring(0, 8))
                .orElse("(ungoverned)"));
        send(ctx, "  villages  : " + found.villageIds().size());
        send(ctx, "  cells     : " + found.cellKeys().size());
        send(ctx, "  stability : " + found.stability()
                + " (mod sum " + signed(found.stabilityModifierSum()) + ")");
        send(ctx, "  treasury  : " + found.treasury() + " bronze");
        send(ctx, "  created   : tick " + found.createdTick());
        if (!found.modifiers().isEmpty()) {
            send(ctx, "── Modifiers ──");
            for (KingdomModifier m : found.modifiers()) {
                send(ctx, "  " + m.id() + ": stab" + signed(m.stabilityDelta())
                        + (m.isPermanent() ? " permanent"
                                : " expires@" + m.expiresAtTick()));
            }
        }
        var reports = found.reports();
        send(ctx, "── Recent reports (" + reports.size() + " / "
                + ProvincialReport.BUFFER_CAPACITY + ") ──");
        int show = Math.min(5, reports.size());
        for (int i = reports.size() - show; i < reports.size(); i++) {
            ProvincialReport r = reports.get(i);
            send(ctx, "  tick " + r.tickGenerated() + " — " + r.summary());
        }
        return 1;
    }

    // =========================================================================
    // Track D3.4b — charter / treaty / intrigue debug handlers
    // =========================================================================

    private static int charterList(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom '" + name + "'.")); return 0; }
        var list = k.getCharters();
        send(ctx, "── Charters of " + name + " (" + list.size() + ") ──");
        for (var c : list) {
            send(ctx, "  [" + (c.active() ? "active" : "revoked")
                    + "] " + c.type().name() + " '" + c.name() + "' "
                    + c.id().toString().substring(0, 8)
                    + " → " + c.grantee().kind().name() + " "
                    + c.grantee().id().toString().substring(0, 8)
                    + " (granted tick " + c.grantedTick() + ")");
        }
        return list.size();
    }

    /**
     * Test-grants a charter. Defaults the params per type so the
     * command stays terse (debug; production paths set real
     * params via the GUI / packet flow).
     */
    private static int charterGrant(CommandContext<CommandSourceStack> ctx,
                                    String kingdom, String typeStr, String granteeUuid) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        java.util.UUID gid;
        try { gid = java.util.UUID.fromString(granteeUuid); }
        catch (Exception e) { ctx.getSource().sendFailure(Component.literal("Bad UUID.")); return 0; }
        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType type;
        try { type = tterrag1112.life_in_the_village.Kingdom.Charters.CharterType
                .valueOf(typeStr.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Bad type. Valid: "
                    + java.util.Arrays.toString(
                        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.values())));
            return 0;
        }
        // Default params per type for the debug grant.
        var params = switch (type) {
            case TOLL_RIGHTS       -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.TollRights(gid, 0.05);
            case TAX_EXEMPTION     -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.TaxExemption(0.5);
            case MARKET_MONOPOLY   -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.MarketMonopoly("MARKET", tterrag1112.life_in_the_village
                    .Kingdom.Charters.CharterParams.MarketMonopoly.Scope.KINGDOM,
                    java.util.Optional.empty());
            case TITLE_GRANT       -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.TitleGrant(1);
            case ORDINATION_RIGHTS -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.OrdinationRights(gid);
            case LAND_GRANT        -> new tterrag1112.life_in_the_village.Kingdom.Charters
                    .CharterParams.LandGrant(0, 0, 4);
        };
        var grantee = tterrag1112.life_in_the_village.Kingdom.Charters
                .GranteeRef.ofNpc(gid);
        var charter = k.grantCharter("[debug] " + type.name(), grantee, params,
                java.util.Optional.empty(), level.getGameTime());
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .CharterGranted(k.getId(), charter.id(), type.name(),
                        gid, grantee.kind().name(), level.getGameTime()));
        data.setDirty();
        send(ctx, "Granted " + type.name() + " " + charter.id().toString().substring(0, 8));
        return 1;
    }

    private static int charterRevoke(CommandContext<CommandSourceStack> ctx,
                                     String kingdom, String charterUuid) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        java.util.UUID cid;
        try { cid = java.util.UUID.fromString(charterUuid); }
        catch (Exception e) { ctx.getSource().sendFailure(Component.literal("Bad UUID.")); return 0; }
        var charter = k.findCharter(cid).orElse(null);
        if (charter == null) {
            ctx.getSource().sendFailure(Component.literal("No charter."));
            return 0;
        }
        long age = charter.ageInDays(level.getGameTime());
        if (!k.revokeCharter(cid, level.getGameTime())) {
            ctx.getSource().sendFailure(Component.literal("Already revoked."));
            return 0;
        }
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .CharterRevoked(k.getId(), cid, charter.type().name(),
                        charter.grantee().id(), age, level.getGameTime()));
        data.setDirty();
        send(ctx, "Revoked " + charter.type().name() + " (age " + age
                + "d, legitimacy hit " + charter.type().legitimacyHit(age)
                + ", stability dip " + charter.type().stabilityDip() + ")");
        return 1;
    }

    private static int treatyList(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var list = k.getTreaties();
        send(ctx, "── Treaties of " + name + " (" + list.size() + ") ──");
        for (var t : list) {
            String state = t.broken() ? "broken" : t.isActive() ? "active" : "drafted";
            send(ctx, "  [" + state + "] " + t.type().name() + " "
                    + t.id().toString().substring(0, 8)
                    + " (parties: " + t.parties().size() + ")"
                    + (t.broken() ? " reason='" + t.brokenReason() + "'" : ""));
        }
        return list.size();
    }

    private static int treatyPropose(CommandContext<CommandSourceStack> ctx,
                                     String typeName, String kingdomA, String kingdomB) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom a = data.getKingdomByName(kingdomA).orElse(null);
        Kingdom b = data.getKingdomByName(kingdomB).orElse(null);
        if (a == null || b == null) {
            ctx.getSource().sendFailure(Component.literal("Both kingdoms required."));
            return 0;
        }
        tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType type;
        try { type = tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType
                .valueOf(typeName.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Bad type. Valid: "
                    + java.util.Arrays.toString(
                        tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType.values())));
            return 0;
        }
        long tick = level.getGameTime();
        java.util.UUID treatyId = java.util.UUID.randomUUID();
        var treaty = tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty.freshDraft(
                treatyId, type, java.util.List.of(a.getId(), b.getId()),
                java.util.Optional.empty(),
                "[debug] " + type.name() + " between " + a.getName() + " + " + b.getName(),
                tick);
        // Ratify both immediately (debug fast-path).
        treaty = treaty.withRatification(a.getId(), tick).withRatification(b.getId(), tick);
        a.addTreaty(treaty);
        b.addTreaty(treaty);
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .TreatyDrafted(treatyId, type.name(),
                        java.util.List.of(a.getId(), b.getId()), null, tick));
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .TreatyRatified(treatyId, type.name(),
                        java.util.List.of(a.getId(), b.getId()),
                        java.util.List.of(a.getId(), b.getId()), tick));
        data.setDirty();
        send(ctx, "Drafted + ratified " + type.name() + " "
                + treatyId.toString().substring(0, 8));
        return 1;
    }

    private static int treatyBreak(CommandContext<CommandSourceStack> ctx,
                                   String kingdom, String treatyUuid) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        java.util.UUID tid;
        try { tid = java.util.UUID.fromString(treatyUuid); }
        catch (Exception e) { ctx.getSource().sendFailure(Component.literal("Bad UUID.")); return 0; }
        var t = k.findTreaty(tid).orElse(null);
        if (t == null) {
            ctx.getSource().sendFailure(Component.literal("No treaty."));
            return 0;
        }
        // Break on every party's copy.
        for (java.util.UUID partyId : t.parties()) {
            data.getKingdomById(partyId).ifPresent(p ->
                    p.breakTreaty(tid, k.getId(), level.getGameTime(),
                            "[debug] manual break"));
        }
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .TreatyBroken(tid, t.type().name(), k.getId(),
                        "[debug] manual break", level.getGameTime()));
        data.setDirty();
        send(ctx, "Broke " + t.type().name() + " " + tid.toString().substring(0, 8)
                + " on " + t.parties().size() + " party copies");
        return 1;
    }

    private static int intrigueTestSow(CommandContext<CommandSourceStack> ctx,
                                       String source, String target) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom src = data.getKingdomByName(source).orElse(null);
        Kingdom tgt = data.getKingdomByName(target).orElse(null);
        if (src == null || tgt == null) {
            ctx.getSource().sendFailure(Component.literal("Both kingdoms required."));
            return 0;
        }
        // Pick first province if available.
        var province = tgt.getProvinces().isEmpty()
                ? null : tgt.getProvinces().get(0);
        var result = tterrag1112.life_in_the_village.Kingdom.Intrigue
                .IntrigueDriver.sowDiscontent(level, data, src, tgt, province,
                        level.getGameTime());
        if (!result.attempted()) {
            ctx.getSource().sendFailure(Component.literal("Denied: " + result.reason()));
            return 0;
        }
        send(ctx, "Sow attempt: success=" + result.success()
                + ", discovered=" + result.discovered()
                + ", attemptId=" + result.attemptId().toString().substring(0, 8));
        data.setDirty();
        return 1;
    }

    // =========================================================================
    // Track D3.5A — petition / standing debug handlers
    // =========================================================================

    private static int petitionList(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var list = k.getPetitions();
        send(ctx, "── Petitions of " + kingdom + " (" + list.size() + ") ──");
        for (var p : list) {
            send(ctx, "  [" + p.status().name() + "] " + p.kind().name()
                    + " " + p.id().toString().substring(0, 8)
                    + " by " + p.playerUuid().toString().substring(0, 8)
                    + " @ tick " + p.submittedTick()
                    + (p.isResolved() ? " note='" + p.resolutionNote() + "'" : ""));
        }
        return list.size();
    }

    private static int petitionSubmitGrievance(CommandContext<CommandSourceStack> ctx,
                                               String kingdom, String text) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var payload = new tterrag1112.life_in_the_village.Kingdom.Audience
                .PetitionPayload.AudienceGrievance(text);
        java.util.UUID petitionId = tterrag1112.life_in_the_village.Kingdom.Audience
                .AudienceDriver.submit(level, data, k, player.getUUID(), payload, level.getGameTime());
        if (petitionId == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Rate-limited; wait before submitting again."));
            return 0;
        }
        data.setDirty();
        send(ctx, "Submitted grievance " + petitionId.toString().substring(0, 8)
                + " to " + kingdom);
        return 1;
    }

    private static int petitionResolve(CommandContext<CommandSourceStack> ctx,
                                       String kingdom, String petitionUuid, boolean approve) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        java.util.UUID pid;
        try { pid = java.util.UUID.fromString(petitionUuid); }
        catch (Exception e) { ctx.getSource().sendFailure(Component.literal("Bad UUID.")); return 0; }
        var entity = ctx.getSource().getEntity();
        java.util.UUID actor = (entity != null) ? entity.getUUID() : null;
        var result = approve
                ? tterrag1112.life_in_the_village.Kingdom.Audience.AudienceDriver.approve(
                    level, data, k, pid, actor, level.getGameTime(), "[debug] approved")
                : tterrag1112.life_in_the_village.Kingdom.Audience.AudienceDriver.deny(
                    level, data, k, pid, actor, level.getGameTime(), "[debug] denied");
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal("Failed: " + result.reason()));
            return 0;
        }
        data.setDirty();
        send(ctx, (approve ? "Approved" : "Denied") + " " + result.reason()
                + " (standing " + (result.standingDelta() >= 0 ? "+" : "")
                + result.standingDelta() + " → " + result.newStanding() + ")");
        return 1;
    }

    private static int ennoblementList(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var nobles = k.getAllPlayerNobles();
        send(ctx, "── Player nobles of " + kingdom + " (" + nobles.size() + ") ──");
        nobles.forEach((puid, pn) -> {
            String land = pn.hasLandGrant()
                    ? " · manor " + pn.landGrantBlockX().get() + ","
                            + pn.landGrantBlockZ().get() + " ("
                            + pn.landGrantSize().get() + ")"
                    : "";
            send(ctx, "  " + puid.toString().substring(0, 8)
                    + " rank " + pn.rankIndex() + land);
        });
        return nobles.size();
    }

    private static int ennoblementGrant(CommandContext<CommandSourceStack> ctx,
                                        String kingdom, int rank) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        // Build a fast-path TITLE_GRANT charter directly so debug
        // doesn't have to round-trip through audience approval.
        var params = new tterrag1112.life_in_the_village.Kingdom.Charters
                .CharterParams.TitleGrant(rank);
        var grantee = tterrag1112.life_in_the_village.Kingdom.Charters
                .GranteeRef.ofPlayer(player.getUUID());
        var charter = k.grantCharter("[debug] TITLE rank " + rank, grantee, params,
                java.util.Optional.empty(), level.getGameTime());
        var noble = k.ennoblePlayer(player.getUUID(), rank, charter.id(),
                level.getGameTime());
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .PlayerEnnobled(k.getId(), player.getUUID(), rank,
                        charter.id(), level.getGameTime()));
        tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed.append(
                k, "player.ennobled", "Ennobled player at rank " + rank,
                level.getGameTime());
        data.setDirty();
        send(ctx, "Ennobled " + player.getUUID().toString().substring(0, 8)
                + " at rank " + rank + " (charter "
                + charter.id().toString().substring(0, 8) + ")");
        return rank;
    }

    // =========================================================================
    // Track D3.6 — fragmentation debug handlers
    // =========================================================================

    // =========================================================================
    // Track D3.6.5 + 6.6 — religion + age-cycle debug handlers
    // =========================================================================

    private static int sanctifyDebug(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        UUID rulerId = k.getRulerEntityId().orElse(k.getRulerPlayerId().orElse(null));
        // Strip the prior sanctified marker so the call rolls fresh.
        k.removeModifier(tterrag1112.life_in_the_village.Kingdom.Religion
                .ReligionAuthorityEngine.SANCTIFIED_MODIFIER_ID);
        tterrag1112.life_in_the_village.Kingdom.Religion.ReligionAuthorityEngine
                .attemptSanctification(level, data, k, rulerId, level.getGameTime());
        data.setDirty();
        boolean granted = k.hasModifierWithId(tterrag1112.life_in_the_village.Kingdom.Religion
                .ReligionAuthorityEngine.SANCTIFIED_MODIFIER_ID);
        send(ctx, "Sanctification " + (granted ? "GRANTED" : "REFUSED")
                + " for ruler of " + kingdom);
        return granted ? 1 : 0;
    }

    private static int convertDebug(CommandContext<CommandSourceStack> ctx,
                                    String kingdom, String provinceName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var province = k.getProvinces().stream()
                .filter(p -> p.name().equalsIgnoreCase(provinceName))
                .findFirst().orElse(null);
        if (province == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No province '" + provinceName + "'."));
            return 0;
        }
        var result = tterrag1112.life_in_the_village.Kingdom.Religion
                .ConvertProvinceDriver.launch(level, data, k, province.id(),
                        level.getGameTime());
        if (!result.started()) {
            ctx.getSource().sendFailure(Component.literal("Failed: " + result.reason()));
            return 0;
        }
        data.setDirty();
        send(ctx, "Conversion campaign launched in " + provinceName);
        return 1;
    }

    private static int ageCycleDebug(CommandContext<CommandSourceStack> ctx,
                                     String kingdom, String stateStr) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        tterrag1112.life_in_the_village.Kingdom.AgeCycle.KingdomAgeState target;
        try {
            target = tterrag1112.life_in_the_village.Kingdom.AgeCycle.KingdomAgeState
                    .valueOf(stateStr.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bad state. Valid: FOUNDING_ERA / MATURE / DECADENT"));
            return 0;
        }
        // Strip every existing age-cycle modifier and stamp the target.
        for (var s : tterrag1112.life_in_the_village.Kingdom.AgeCycle.KingdomAgeState.values()) {
            k.removeModifier(s.modifierId());
        }
        k.addModifier(tterrag1112.life_in_the_village.Kingdom.KingdomModifier.permanent(
                target.modifierId(), target.description(),
                target.stabilityDelta(), target.legitimacyDelta(),
                level.getGameTime()));
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .AgeCycleTransition(k.getId(), "DEBUG", target.name(),
                        level.getGameTime()));
        data.setDirty();
        send(ctx, "Age cycle of " + kingdom + " forced to " + target);
        return 1;
    }

    // =========================================================================
    // Track D3.6.4 — war debug handlers
    // =========================================================================

    private static int warDeclareDebug(CommandContext<CommandSourceStack> ctx,
                                       String attackerName, String defenderName,
                                       String cbStr, String goalsCsv) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom attacker = data.getKingdomByName(attackerName).orElse(null);
        Kingdom defender = defenderName.isEmpty() ? null
                : data.getKingdomByName(defenderName).orElse(null);
        if (attacker == null || defender == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Both kingdom names required."));
            return 0;
        }
        tterrag1112.life_in_the_village.Kingdom.War.CasusBelli cb;
        try {
            cb = tterrag1112.life_in_the_village.Kingdom.War.CasusBelli
                    .valueOf(cbStr.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bad casusBelli. Valid: "
                            + java.util.Arrays.toString(
                                    tterrag1112.life_in_the_village.Kingdom.War
                                            .CasusBelli.values())));
            return 0;
        }
        java.util.List<tterrag1112.life_in_the_village.Kingdom.War.WarGoal> goals =
                new java.util.ArrayList<>();
        for (String tok : goalsCsv.split(",")) {
            String t = tok.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.isEmpty()) continue;
            switch (t) {
                case "VASSALIZE" -> goals.add(
                        new tterrag1112.life_in_the_village.Kingdom.War.WarGoal.Vassalize());
                case "REGIME_CHANGE" -> goals.add(
                        new tterrag1112.life_in_the_village.Kingdom.War.WarGoal.RegimeChange());
                case "TRIBUTE" -> goals.add(
                        new tterrag1112.life_in_the_village.Kingdom.War.WarGoal.Tribute(
                                500L, 0));
                case "TERRITORY" -> goals.add(
                        new tterrag1112.life_in_the_village.Kingdom.War.WarGoal.Territory(
                                java.util.List.copyOf(defender.getVillageIds())));
                default -> { /* ignore */ }
            }
        }
        var result = tterrag1112.life_in_the_village.Kingdom.War.WarEngine.declareWar(
                level, data, attacker, defender, cb, goals, level.getGameTime());
        if (!result.declared()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Declaration failed: " + result.reason()));
            return 0;
        }
        data.setDirty();
        send(ctx, "Declared war " + result.warId().toString().substring(0, 8)
                + " (" + cb + ", goals=" + goals.size() + ")");
        return 1;
    }

    private static int warResolveDebug(CommandContext<CommandSourceStack> ctx,
                                       String warUuidStr, String statusStr) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        java.util.UUID warId;
        try { warId = java.util.UUID.fromString(warUuidStr); }
        catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Bad UUID."));
            return 0;
        }
        tterrag1112.life_in_the_village.Kingdom.War.War.Status finalStatus;
        try {
            finalStatus = tterrag1112.life_in_the_village.Kingdom.War.War.Status
                    .valueOf(statusStr.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bad status. Valid: WON / LOST / WHITE_PEACE / STALEMATE"));
            return 0;
        }
        for (Kingdom k : data.getAllKingdoms()) {
            var w = k.findWar(warId).orElse(null);
            if (w != null && w.isActive()) {
                tterrag1112.life_in_the_village.Kingdom.War.WarEngine.resolveWar(
                        level, data, k, w, finalStatus, level.getGameTime(),
                        "[debug] forced " + finalStatus.name());
                data.setDirty();
                send(ctx, "Resolved war " + warUuidStr.substring(0, 8) + " as " + finalStatus);
                return 1;
            }
        }
        ctx.getSource().sendFailure(Component.literal("No active war with that id."));
        return 0;
    }

    private static int warListDebug(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        int total = 0;
        send(ctx, "── Active wars ──");
        for (Kingdom k : data.getAllKingdoms()) {
            for (var w : k.getActiveWars()) {
                Kingdom def = data.getKingdomById(w.defenderKingdomId()).orElse(null);
                send(ctx, "  " + w.id().toString().substring(0, 8)
                        + " " + k.getName() + " vs "
                        + (def != null ? def.getName() : "?")
                        + " [" + w.casusBelli() + "] score=" + w.attackerScore()
                        + " battles=" + w.battles().size());
                total++;
            }
        }
        send(ctx, "Total: " + total);
        return total;
    }

    private static int rebelDebug(CommandContext<CommandSourceStack> ctx,
                                  String kingdom, String provinceName, String choiceStr) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var province = k.getProvinces().stream()
                .filter(p -> p.name().equalsIgnoreCase(provinceName))
                .findFirst().orElse(null);
        if (province == null) {
            ctx.getSource().sendFailure(Component.literal("No province '" + provinceName + "'."));
            return 0;
        }
        tterrag1112.life_in_the_village.Kingdom.Rebellion.RebellionEngine.Choice choice;
        try {
            choice = tterrag1112.life_in_the_village.Kingdom.Rebellion
                    .RebellionEngine.Choice.valueOf(choiceStr.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bad choice. Valid: NEGOTIATE / CRUSH / ACCEPT"));
            return 0;
        }
        // Force-drop province stability below threat threshold so the
        // engine processes it on the next dailyTick — but for an
        // immediate effect we fire the resolution path directly.
        if (choice == tterrag1112.life_in_the_village.Kingdom.Rebellion.RebellionEngine.Choice.ACCEPT) {
            tterrag1112.life_in_the_village.Kingdom.Rebellion.SecessionExecutor.secede(
                    level, data, k, province, level.getGameTime(), "[debug] accept");
            send(ctx, "Forced secession of " + province.name());
        } else {
            // For NEGOTIATE / CRUSH, apply the effects directly.
            if (choice == tterrag1112.life_in_the_village.Kingdom.Rebellion.RebellionEngine.Choice.NEGOTIATE) {
                k.depositToTreasury(-tterrag1112.life_in_the_village.Kingdom.Rebellion
                        .RebellionEngine.NEGOTIATE_TREASURY_COST);
                k.replaceProvinceStability(province.id(),
                        Math.max(province.stability(),
                                tterrag1112.life_in_the_village.Kingdom.Rebellion
                                        .RebellionEngine.NEGOTIATE_STABILITY_FLOOR));
                send(ctx, "Negotiated peace in " + province.name());
            } else {
                k.applyLegitimacyDelta(-tterrag1112.life_in_the_village.Kingdom.Rebellion
                        .RebellionEngine.CRUSH_LEGITIMACY_MALUS);
                k.replaceProvinceStability(province.id(),
                        Math.max(province.stability(),
                                tterrag1112.life_in_the_village.Kingdom.Rebellion
                                        .RebellionEngine.CRUSH_STABILITY_FLOOR));
                send(ctx, "Crushed rebellion in " + province.name());
            }
        }
        data.setDirty();
        return 1;
    }

    private static int collapseDebug(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        tterrag1112.life_in_the_village.Kingdom.Rebellion.CollapseEngine.collapse(
                level, data, k, level.getGameTime(), "[debug] force");
        send(ctx, "Forced collapse of " + kingdom);
        return 1;
    }

    private static int mergeDebug(CommandContext<CommandSourceStack> ctx,
                                  String survivorName, String mergedAwayName, String pathStr) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom survivor = data.getKingdomByName(survivorName).orElse(null);
        Kingdom mergedAway = data.getKingdomByName(mergedAwayName).orElse(null);
        if (survivor == null || mergedAway == null) {
            ctx.getSource().sendFailure(Component.literal("Both kingdoms required."));
            return 0;
        }
        String path = pathStr.toLowerCase(java.util.Locale.ROOT);
        if (!path.equals("marriage_union") && !path.equals("voluntary_union")
                && !path.equals("conquest")) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bad path. Valid: marriage_union / voluntary_union / conquest"));
            return 0;
        }
        UUID result;
        if (path.equals("marriage_union")) {
            result = tterrag1112.life_in_the_village.Kingdom.Merger.MergerEngine
                    .triggerMarriageUnion(level, data, survivor, mergedAway, level.getGameTime());
        } else if (path.equals("conquest")) {
            result = tterrag1112.life_in_the_village.Kingdom.Merger.MergerEngine
                    .triggerConquestMerger(level, data, survivor, mergedAway, level.getGameTime());
        } else {
            tterrag1112.life_in_the_village.Kingdom.Merger.MergerEngine
                    .absorbInto(level, data, survivor, mergedAway, level.getGameTime(),
                            "voluntary_union");
            result = survivor.getId();
        }
        if (result == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Merger blocked (active war or already-collapsed kingdom)."));
            return 0;
        }
        data.setDirty();
        send(ctx, "Merged " + mergedAwayName + " into " + survivorName + " (" + path + ")");
        return 1;
    }

    private static int newsfeedList(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var feed = k.getHistory().getNewsfeed();
        send(ctx, "── Newsfeed of " + kingdom + " (" + feed.size() + ") ──");
        int n = feed.size();
        // Most recent last — show newest first.
        for (int i = n - 1; i >= Math.max(0, n - 20); i--) {
            var e = feed.get(i);
            send(ctx, "  [" + e.tag() + "] @ tick " + e.tick() + " — "
                    + e.summary());
        }
        return n;
    }

    private static int standingGet(CommandContext<CommandSourceStack> ctx, String kingdom) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var ps = k.getPlayerStanding(player.getUUID(), level.getGameTime());
        String band = ps.isTrusted() ? "TRUSTED" : ps.isHostile() ? "HOSTILE" : "neutral";
        send(ctx, "Standing with " + kingdom + ": " + ps.score()
                + " [" + band + "] (favour " + ps.lifetimeFavour()
                + " / wrath " + ps.lifetimeWrath() + ")");
        return ps.score();
    }

    private static int standingAdjust(CommandContext<CommandSourceStack> ctx,
                                      String kingdom, int delta) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Server only.")); return 0;
        }
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(kingdom).orElse(null);
        if (k == null) { ctx.getSource().sendFailure(Component.literal("No kingdom.")); return 0; }
        var ps = k.adjustPlayerStanding(player.getUUID(), delta, level.getGameTime());
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .StandingChanged(k.getId(), player.getUUID(), delta,
                        ps.score(), level.getGameTime()));
        data.setDirty();
        send(ctx, "Standing → " + ps.score() + " (delta " + (delta >= 0 ? "+" : "")
                + delta + ")");
        return ps.score();
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
