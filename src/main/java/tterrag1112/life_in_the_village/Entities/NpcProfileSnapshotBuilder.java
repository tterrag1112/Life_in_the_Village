package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade.MerchantBehavior;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gathers everything a client needs for the NPC profile screen into a single
 * {@link NpcProfileSnapshot}. Called server-side whenever the client opens a
 * profile or requests a refresh.
 */
public final class NpcProfileSnapshotBuilder {

    private NpcProfileSnapshotBuilder() {}

    /**
     * Builds a snapshot from the live entity and the requesting player.
     *
     * @param npc    the NPC being inspected
     * @param player the viewing player — used for reputation and action gating
     * @param level  the server level the NPC lives in
     */
    public static NpcProfileSnapshot build(TownspersonMob npc,
                                           ServerPlayer player,
                                           ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // ── Village + building lookups ───────────────────────────────────────
        Optional<Village> villageOpt = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName);
        UUID villageId = villageOpt.map(Village::getId).orElse(NpcProfileSnapshot.NIL_UUID);
        String villageName = villageOpt.map(Village::getName).orElse("");

        Optional<Building> buildingOpt = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById);
        String buildingName = buildingOpt.map(Building::getName).orElse("");
        String buildingType = buildingOpt.map(b -> b.getType().name()).orElse("");

        // ── Reputation ───────────────────────────────────────────────────────
        int repScore;
        String tierName;
        if (!villageId.equals(NpcProfileSnapshot.NIL_UUID)) {
            VillageReputation rep = ReputationManager.getReputation(
                    player.getUUID(), villageId, data);
            repScore = rep.getScore();
            tierName = rep.getTier().displayName;
        } else {
            repScore = 0;
            tierName = VillageReputation.Tier.NEWCOMER.displayName;
        }
        int personalDelta = npc.getRelationshipDelta(player.getUUID());

        // ── Family names ─────────────────────────────────────────────────────
        String spouseName = npc.getSpouseId()
                .flatMap(id -> TownspersonMob.findByUUID(level, id))
                .map(TownspersonMob::getNpcName).orElse("");
        String headName   = npc.getHeadOfHouseholdId()
                .flatMap(id -> TownspersonMob.findByUUID(level, id))
                .map(TownspersonMob::getNpcName).orElse("");
        String houseName  = npc.getHouseId()
                .flatMap(data::getBuildingById)
                .map(Building::getName).orElse("");

        // ── Traits ───────────────────────────────────────────────────────────
        List<String> traitNames = new ArrayList<>();
        for (AppearanceComponent.PersonalityTrait t : npc.getTraits()) {
            traitNames.add(t.name());
        }

        // ── Dialogue line ────────────────────────────────────────────────────
        String dialogueLine = NpcDialogue.getGreeting(npc, player, level);

        // ── Action bar gating ────────────────────────────────────────────────
        Profession prof = npc.getProfession();
        boolean canTrade = prof == Profession.MERCHANT
                && Optional.ofNullable(npc.getBehavior(MerchantBehavior.class))
                .map(MerchantBehavior::isOpenForTrade).orElse(false);
        boolean canOpenGuild = prof == Profession.GUILDWORKER
                && villageOpt.flatMap(v -> data.getGuildForVillage(v.getId())).isPresent();
        boolean canAssignWork = switch (prof) {
            case MERCHANT, GUARD, FARMER, BLACKSMITH, CARPENTER, MINER,
                 STOCKPILE_KEEPER -> true;
            default -> false;
        };
        CompanySavedData compData = CompanySavedData.get(level);
        Optional<Company> companyOpt = compData.getCompanyForWorker(npc.getUUID());
        boolean canOpenCompanyWorker = prof == Profession.COMPANY_WORKER
                && companyOpt.map(c -> c.getOwnerPlayerId().equals(player.getUUID()))
                .orElse(false);
        boolean canShowVillageBook = prof == Profession.VILLAGE_LEADER
                && villageOpt.isPresent();
        boolean canShowCraftingOrders = prof == Profession.VILLAGE_LEADER
                && villageOpt.isPresent();
        boolean canRentStall = prof == Profession.MERCHANT
                && buildingOpt.map(b -> b.getType() == BuildingType.MARKET).orElse(false);

        // Track 5a.5 — nav target selection.
        NpcProfileSnapshot.NavTargetKind navKind = resolveNavKind(npc, player, level);
        boolean hasNav = navKind != NpcProfileSnapshot.NavTargetKind.NONE;

        // ── Life-goal labels (Phase 1 task 07) ───────────────────────────────
        java.util.List<String> goalLabels = new java.util.ArrayList<>();
        for (var goal : npc.getLifeGoals().active()) {
            var def = tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalRegistry
                    .get(goal.type());
            String label = def != null ? def.displayLabel() : goal.type().name();
            goalLabels.add(label);
        }

        // ── Player verbs (Phase 1 task 09) ───────────────────────────────────
        java.util.List<String> verbIds = new java.util.ArrayList<>();
        java.util.List<String> verbLabels = new java.util.ArrayList<>();
        var verbCtx = tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb
                .context(player, npc, level);
        for (var verb : tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry
                .availableFor(verbCtx)) {
            verbIds.add(verb.id());
            verbLabels.add(verb.label().getString());
        }

        // ── Top-5 NPC↔NPC relationships (Phase 2 task 11) ────────────────────
        java.util.List<java.util.UUID> relIds = new java.util.ArrayList<>();
        java.util.List<Integer> relScores = new java.util.ArrayList<>();
        java.util.List<String> relModes = new java.util.ArrayList<>();
        for (var rel : npc.getNpcRelationships().topByMagnitude(5)) {
            relIds.add(rel.otherId());
            relScores.add(rel.score());
            relModes.add(rel.mode().name());
        }

        // ── Assemble ─────────────────────────────────────────────────────────
        return new NpcProfileSnapshot(
                npc.getUUID(),
                npc.getNpcName(),
                npc.isMale(),
                npc.getAge(),
                npc.getLifeStage().name(),
                prof.name(),
                npc.getCombatRole() == null ? "" : npc.getCombatRole().name(),
                npc.getSkinTone(),
                npc.getHairStyle(),
                npc.getHairColor(),
                traitNames,
                npc.getAdventurerTitle(),

                npc.getFamilyRole().name(),
                spouseName,
                headName,
                npc.getChildrenIds().size(),
                houseName,

                buildingName,
                buildingType,
                villageName,
                npc.getCurrentActivity(),
                npc.isWorkTime(),
                npc.isSleepTime(),
                npc.isSocialTime(),

                villageId,
                repScore,
                tierName,
                personalDelta,

                dialogueLine,

                canTrade,
                canOpenGuild,
                canAssignWork,
                canOpenCompanyWorker,
                canShowVillageBook,
                canShowCraftingOrders,
                canRentStall,

                goalLabels,
                verbIds,
                verbLabels,
                relIds, relScores, relModes,

                hasNav, navKind);
    }

    /**
     * Step-5 nav-target selection (with "hide when contextual route
     * already serves" tightening). Order: office → company-worker →
     * adventurer-party → profession-default → NONE.
     */
    private static NpcProfileSnapshot.NavTargetKind resolveNavKind(
            tterrag1112.life_in_the_village.Entities.custom.TownspersonMob npc,
            net.minecraft.server.level.ServerPlayer player,
            net.minecraft.server.level.ServerLevel level) {
        Profession prof = npc.getProfession();

        // Office screens — VILLAGE_LEADER currently the only wired one;
        // KINGDOM_RULER falls through until its book wires up here.
        if (prof == Profession.VILLAGE_LEADER) {
            return NpcProfileSnapshot.NavTargetKind.OFFICE_SCREEN;
        }

        // Owned company worker — hide if a contextual route reaches
        // CompanyWorkerScreen here too (today: no contextual route,
        // so always offer the nav).
        if (prof == Profession.COMPANY_WORKER) {
            boolean owned = CompanySavedData.get(level)
                    .getCompanyForWorker(npc.getUUID())
                    .map(c -> c.getOwnerPlayerId().equals(player.getUUID()))
                    .orElse(false);
            if (owned) return NpcProfileSnapshot.NavTargetKind.COMPANY_WORKER;
        }

        // Party-member adventurer in the player's party.
        if (prof == Profession.ADVENTURER && npc.getCombatRole() != null) {
            var party = tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData
                    .get(level).getPartyContaining(npc.getUUID()).orElse(null);
            if (party != null && party.getLeaderPlayerId().equals(player.getUUID())) {
                return NpcProfileSnapshot.NavTargetKind.PARTY_STATUS;
            }
        }

        // No profession-default screen exists today.
        return NpcProfileSnapshot.NavTargetKind.NONE;
    }
}
