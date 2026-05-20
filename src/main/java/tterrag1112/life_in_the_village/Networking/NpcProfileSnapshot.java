package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-safe snapshot of everything the NPC profile screen needs to render.
 * Built server-side by {@code NpcProfileSnapshotBuilder} and delivered to the
 * client via {@code OpenNpcProfilePacket} / {@code NpcProfileSyncPacket}.
 *
 * <p>All fields are plain, primitive, or string so the record is trivially
 * encoded and never captures live entity references.
 */
public record NpcProfileSnapshot(
        // -------------------------------------------------------------
        // Identity
        // -------------------------------------------------------------
        UUID   npcId,
        String name,
        boolean isMale,
        int    age,
        String lifeStage,
        String professionName,
        String combatRoleName,
        int    skinId,
        int    hairStyle,
        int    hairColor,
        List<String> traits,
        String adventurerTitle,

        // -------------------------------------------------------------
        // Family
        // -------------------------------------------------------------
        String familyRole,
        String spouseName,     // "" = no spouse
        String headName,       // "" = no household head
        int    childrenCount,
        String houseName,      // "" = homeless

        // -------------------------------------------------------------
        // Work
        // -------------------------------------------------------------
        String buildingName,   // "" = unassigned
        String buildingType,
        String villageName,    // shared with Reputation panel
        String currentActivity,
        boolean isWorkTime,
        boolean isSleepTime,
        boolean isSocialTime,

        // -------------------------------------------------------------
        // Reputation
        // -------------------------------------------------------------
        UUID   villageId,              // {@link #NIL_UUID} if no village
        int    villageReputationScore,
        String villageTierName,
        int    personalDelta,

        // -------------------------------------------------------------
        // Dialogue
        // -------------------------------------------------------------
        String dialogueLine,

        // -------------------------------------------------------------
        // Action-bar availability (server-authoritative)
        // -------------------------------------------------------------
        boolean canTrade,
        boolean canOpenGuild,
        boolean canAssignWork,
        boolean canOpenCompanyWorker,
        boolean canShowVillageBook,
        boolean canShowCraftingOrders,
        boolean canRentStall,

        // -------------------------------------------------------------
        // Life goals (Phase 1 task 07) — short labels for active goals,
        // up to {@link LifeGoalSet#MAX_ACTIVE} entries. Phase 5 content
        // pass replaces with richer narrative templates.
        // -------------------------------------------------------------
        List<String> activeGoalLabels,

        // -------------------------------------------------------------
        // Player verbs (Phase 1 task 09) — verb ids for which
        // {@code isAvailable} returns true under this snapshot's
        // implicit context. SocialVerbsPanel filters this against its
        // whitelist of social-only verbs.
        // -------------------------------------------------------------
        List<String> availableVerbIds,
        List<String> verbLabels,

        // -------------------------------------------------------------
        // NPC↔NPC top-5 relationships (Phase 2 task 11). Parallel
        // arrays: ids[i] / scores[i] / modes[i]. Empty for NPCs with
        // no relationships. Profile GUI panel renders these.
        // -------------------------------------------------------------
        List<UUID>   topRelationshipIds,
        List<Integer> topRelationshipScores,
        List<String>  topRelationshipModes,

        // -------------------------------------------------------------
        // Track 5a.5 — dedicated nav-target hint. Replaces the proxy
        // approach (canShowVillageBook / canOpenCompanyWorker as
        // visibility flags). Builder fills this per the Step-5 priority
        // rule with "hide when contextual route already serves"
        // tightening; SocialVerbsPanel renders the nav button iff
        // hasNavTarget && navTargetKind != NONE.
        // -------------------------------------------------------------
        boolean hasNavTarget,
        NavTargetKind navTargetKind
) {

    public enum NavTargetKind {
        NONE,
        OFFICE_SCREEN,
        COMPANY_WORKER,
        PARTY_STATUS,
        PROFESSION_DEFAULT
    }

    /** Sentinel representing "no village association". */
    public static final UUID NIL_UUID = new UUID(0L, 0L);

    // =========================================================================
    // StreamCodec
    // =========================================================================

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcProfileSnapshot> CODEC =
            StreamCodec.of(
                    (buf, s) -> {
                        buf.writeUUID(s.npcId);
                        buf.writeUtf(s.name);
                        buf.writeBoolean(s.isMale);
                        buf.writeVarInt(s.age);
                        buf.writeUtf(s.lifeStage);
                        buf.writeUtf(s.professionName);
                        buf.writeUtf(s.combatRoleName);
                        buf.writeVarInt(s.skinId);
                        buf.writeVarInt(s.hairStyle);
                        buf.writeVarInt(s.hairColor);
                        buf.writeVarInt(s.traits.size());
                        s.traits.forEach(buf::writeUtf);
                        buf.writeUtf(s.adventurerTitle);

                        buf.writeUtf(s.familyRole);
                        buf.writeUtf(s.spouseName);
                        buf.writeUtf(s.headName);
                        buf.writeVarInt(s.childrenCount);
                        buf.writeUtf(s.houseName);

                        buf.writeUtf(s.buildingName);
                        buf.writeUtf(s.buildingType);
                        buf.writeUtf(s.villageName);
                        buf.writeUtf(s.currentActivity);
                        buf.writeBoolean(s.isWorkTime);
                        buf.writeBoolean(s.isSleepTime);
                        buf.writeBoolean(s.isSocialTime);

                        buf.writeUUID(s.villageId);
                        buf.writeVarInt(s.villageReputationScore);
                        buf.writeUtf(s.villageTierName);
                        buf.writeVarInt(s.personalDelta);

                        buf.writeUtf(s.dialogueLine);

                        buf.writeBoolean(s.canTrade);
                        buf.writeBoolean(s.canOpenGuild);
                        buf.writeBoolean(s.canAssignWork);
                        buf.writeBoolean(s.canOpenCompanyWorker);
                        buf.writeBoolean(s.canShowVillageBook);
                        buf.writeBoolean(s.canShowCraftingOrders);
                        buf.writeBoolean(s.canRentStall);

                        buf.writeVarInt(s.activeGoalLabels.size());
                        s.activeGoalLabels.forEach(buf::writeUtf);

                        buf.writeVarInt(s.availableVerbIds.size());
                        s.availableVerbIds.forEach(buf::writeUtf);
                        buf.writeVarInt(s.verbLabels.size());
                        s.verbLabels.forEach(buf::writeUtf);

                        buf.writeVarInt(s.topRelationshipIds.size());
                        s.topRelationshipIds.forEach(buf::writeUUID);
                        buf.writeVarInt(s.topRelationshipScores.size());
                        s.topRelationshipScores.forEach(buf::writeVarInt);
                        buf.writeVarInt(s.topRelationshipModes.size());
                        s.topRelationshipModes.forEach(buf::writeUtf);

                        buf.writeBoolean(s.hasNavTarget);
                        buf.writeUtf(s.navTargetKind.name());
                    },
                    buf -> {
                        UUID   npcId          = buf.readUUID();
                        String name           = buf.readUtf();
                        boolean isMale        = buf.readBoolean();
                        int    age            = buf.readVarInt();
                        String lifeStage      = buf.readUtf();
                        String profession     = buf.readUtf();
                        String combat         = buf.readUtf();
                        int    skinId         = buf.readVarInt();
                        int    hairStyle      = buf.readVarInt();
                        int    hairColor      = buf.readVarInt();
                        int    traitCount     = buf.readVarInt();
                        List<String> traits   = new ArrayList<>(traitCount);
                        for (int i = 0; i < traitCount; i++) traits.add(buf.readUtf());
                        String adventurerTitle = buf.readUtf();

                        String familyRole    = buf.readUtf();
                        String spouseName    = buf.readUtf();
                        String headName      = buf.readUtf();
                        int    childrenCount = buf.readVarInt();
                        String houseName     = buf.readUtf();

                        String buildingName  = buf.readUtf();
                        String buildingType  = buf.readUtf();
                        String villageName   = buf.readUtf();
                        String activity      = buf.readUtf();
                        boolean isWorkTime   = buf.readBoolean();
                        boolean isSleepTime  = buf.readBoolean();
                        boolean isSocialTime = buf.readBoolean();

                        UUID   villageId     = buf.readUUID();
                        int    repScore      = buf.readVarInt();
                        String tierName      = buf.readUtf();
                        int    personalDelta = buf.readVarInt();

                        String dialogueLine  = buf.readUtf();

                        boolean canTrade              = buf.readBoolean();
                        boolean canOpenGuild          = buf.readBoolean();
                        boolean canAssignWork         = buf.readBoolean();
                        boolean canOpenCompanyWorker  = buf.readBoolean();
                        boolean canShowVillageBook    = buf.readBoolean();
                        boolean canShowCraftingOrders = buf.readBoolean();
                        boolean canRentStall          = buf.readBoolean();

                        int    goalCount      = buf.readVarInt();
                        List<String> goals    = new ArrayList<>(goalCount);
                        for (int i = 0; i < goalCount; i++) goals.add(buf.readUtf());

                        int verbCount         = buf.readVarInt();
                        List<String> verbIds  = new ArrayList<>(verbCount);
                        for (int i = 0; i < verbCount; i++) verbIds.add(buf.readUtf());
                        int verbLabelCount    = buf.readVarInt();
                        List<String> verbLab  = new ArrayList<>(verbLabelCount);
                        for (int i = 0; i < verbLabelCount; i++) verbLab.add(buf.readUtf());

                        int relIdCount        = buf.readVarInt();
                        List<UUID> relIds     = new ArrayList<>(relIdCount);
                        for (int i = 0; i < relIdCount; i++) relIds.add(buf.readUUID());
                        int relScoreCount     = buf.readVarInt();
                        List<Integer> relScores = new ArrayList<>(relScoreCount);
                        for (int i = 0; i < relScoreCount; i++) relScores.add(buf.readVarInt());
                        int relModeCount      = buf.readVarInt();
                        List<String> relModes = new ArrayList<>(relModeCount);
                        for (int i = 0; i < relModeCount; i++) relModes.add(buf.readUtf());

                        boolean hasNav = buf.readBoolean();
                        NavTargetKind navKind;
                        try { navKind = NavTargetKind.valueOf(buf.readUtf()); }
                        catch (IllegalArgumentException e) { navKind = NavTargetKind.NONE; }

                        return new NpcProfileSnapshot(
                                npcId, name, isMale, age, lifeStage, profession,
                                combat, skinId, hairStyle, hairColor, traits,
                                adventurerTitle,
                                familyRole, spouseName, headName, childrenCount,
                                houseName,
                                buildingName, buildingType, villageName, activity,
                                isWorkTime, isSleepTime, isSocialTime,
                                villageId, repScore, tierName, personalDelta,
                                dialogueLine,
                                canTrade, canOpenGuild, canAssignWork,
                                canOpenCompanyWorker, canShowVillageBook,
                                canShowCraftingOrders, canRentStall,
                                goals,
                                verbIds, verbLab,
                                relIds, relScores, relModes,
                                hasNav, navKind);
                    });
}
