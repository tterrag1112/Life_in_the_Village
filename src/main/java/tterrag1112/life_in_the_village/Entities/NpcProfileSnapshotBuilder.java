package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
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
                && Optional.ofNullable(npc.getGoal(MerchantGoal.class))
                .map(MerchantGoal::isOpenForTrade).orElse(false);
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
                verbLabels);
    }
}
