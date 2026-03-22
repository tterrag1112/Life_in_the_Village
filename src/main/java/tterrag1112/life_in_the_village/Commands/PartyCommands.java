// src/main/java/tterrag1112/life_in_the_village/Commands/PartyCommands.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerEquipment;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Commands for managing player adventurer parties.
 *
 * <pre>
 * /party form [name]             — create a new party
 * /party invite                  — invite the nearest adventurer NPC
 * /party dismiss <name>          — remove a member by name
 * /party disband                 — disband the party entirely
 * /party info                    — show current party composition
 * /party setrole <member> <role> — change a member's combat role
 * /party rally                   — set rally point at player position
 * </pre>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class PartyCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("party")

                        // /party form [name]
                        .then(Commands.literal("form")
                                .executes(ctx -> formParty(
                                        ctx.getSource(), "My Party"))
                                .then(Commands.argument("name",
                                                StringArgumentType.greedyString())
                                        .executes(ctx -> formParty(
                                                ctx.getSource(),
                                                StringArgumentType
                                                        .getString(ctx, "name"))))
                        )

                        // /party invite
                        .then(Commands.literal("invite")
                                .executes(ctx -> inviteNearest(
                                        ctx.getSource()))
                        )

                        // /party dismiss <memberName>
                        .then(Commands.literal("dismiss")
                                .then(Commands.argument("member",
                                                StringArgumentType.word())
                                        .executes(ctx -> dismissMember(
                                                ctx.getSource(),
                                                StringArgumentType
                                                        .getString(ctx, "member"))))
                        )

                        // /party disband
                        .then(Commands.literal("disband")
                                .executes(ctx -> disbandParty(
                                        ctx.getSource()))
                        )

                        // /party info
                        .then(Commands.literal("info")
                                .executes(ctx -> partyInfo(
                                        ctx.getSource()))
                        )

                        // /party setrole <member> <role>
                        .then(Commands.literal("setrole")
                                .then(Commands.argument("member",
                                                StringArgumentType.word())
                                        .then(Commands.argument("role",
                                                        StringArgumentType.word())
                                                .executes(ctx -> setRole(
                                                        ctx.getSource(),
                                                        StringArgumentType
                                                                .getString(ctx, "member"),
                                                        StringArgumentType
                                                                .getString(ctx, "role")))))
                        )

                        // /party rally
                        .then(Commands.literal("rally")
                                .executes(ctx -> setRally(ctx.getSource()))
                        )
        );
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int formParty(CommandSourceStack src, String name) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        if (data.playerHasParty(player.getUUID())) {
            src.sendFailure(Component.literal(
                    "You already have a party. Use /party disband first."));
            return 0;
        }

        PlayerParty party = PlayerParty.create(
                player.getUUID(), name, level.getGameTime());
        data.addParty(party);

        src.sendSuccess(() -> Component.literal(
                "Party '" + name + "' formed! Use /party invite "
                        + "near an adventurer NPC to recruit members."), false);
        return 1;
    }

    private static int inviteNearest(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);

        Optional<PlayerParty> partyOpt =
                partyData.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendFailure(Component.literal(
                    "Form a party first with /party form [name]."));
            return 0;
        }

        PlayerParty party = partyOpt.get();
        if (party.isFull()) {
            src.sendFailure(Component.literal(
                    "Your party is full (" + PlayerParty.MAX_MEMBERS
                            + " members maximum)."));
            return 0;
        }

        // Find nearest adventurer NPC within 10 blocks
        List<TownspersonMob> candidates = level.getEntitiesOfClass(
                TownspersonMob.class,
                player.getBoundingBox().inflate(10),
                npc -> npc.getProfession() == Profession.ADVENTURER
                        && !partyData.getPartyContaining(
                        npc.getUUID()).isPresent());

        if (candidates.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No available adventurer nearby. "
                            + "Look for adventurers at a guild hall."));
            return 0;
        }

        TownspersonMob npc = candidates.stream()
                .min(java.util.Comparator.comparingDouble(
                        n -> n.distanceToSqr(player)))
                .orElseThrow();

        AdventurerSavedData adventurerData = AdventurerSavedData.get(level);
        adventurerData.getAllGroups().stream()
                .filter(g -> g.getMemberIds().contains(npc.getUUID())
                        || g.getSpawnedEntityIds().contains(npc.getUUID()))
                .findFirst()
                .ifPresent(group -> {
                    // Remove this NPC from the group's member and entity lists
                    group.getMemberIds().remove(npc.getUUID());
                    group.getSpawnedEntityIds().remove(npc.getUUID());

                    // If the group is now empty, mark it dead
                    if (group.getMemberIds().isEmpty()) {
                        group.setDead(true);
                    }

                    // Clear group ID from the NPC so group goals don't fire
                    npc.setGroupId(null);
                    npc.setIsGroupLeader(false);

                    adventurerData.setDirty();
                });

        // Assign a combat role based on party needs
        CombatRole role = pickNeededRole(party);
        npc.setCombatRole(role);

        // Apply role-specific equipment
        applyRoleEquipment(npc, role, false);

        // Build member record
        UUID homeVillage = npc.getAssignedVillageName()
                .flatMap(vName -> VillageSavedData.get(level)
                        .getVillageByName(vName))
                .map(v -> v.getId())
                .orElse(UUID.randomUUID());

        PlayerParty.PartyMember member = new PlayerParty.PartyMember(
                npc.getUUID(),
                npc.getNpcName(),
                role,
                1, 0, 0, true,
                homeVillage);

        party.addMember(member);
        partyData.setDirty();

        src.sendSuccess(() -> Component.literal(
                        npc.getNpcName() + " joined your party as "
                                + role.symbol + " " + role.getDisplayName() + "!"),
                false);
        return 1;
    }

    private static int dismissMember(CommandSourceStack src,
                                     String memberName) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        Optional<PlayerParty> partyOpt =
                data.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendFailure(Component.literal("You don't have a party."));
            return 0;
        }

        PlayerParty party = partyOpt.get();
        Optional<PlayerParty.PartyMember> memberOpt = party.getMembers()
                .stream()
                .filter(m -> m.name().equalsIgnoreCase(memberName))
                .findFirst();

        if (memberOpt.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No party member named '" + memberName + "'."));
            return 0;
        }

        PlayerParty.PartyMember member = memberOpt.get();

        // Clear combat role from the live NPC entity
        var npcEntity = level.getEntity(member.npcId());
        if (npcEntity instanceof TownspersonMob npc) {
            returnNpcToAdventurerPool(npc, member.homeVillageId(), level);
        }

        party.removeMember(member.npcId());
        data.setDirty();

        src.sendSuccess(() -> Component.literal(
                member.name() + " has left the party."), false);
        return 1;
    }

    private static int disbandParty(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        Optional<PlayerParty> partyOpt =
                data.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendFailure(Component.literal("You don't have a party."));
            return 0;
        }

        PlayerParty party = partyOpt.get();

        // Clear roles from all live NPCs
        for (PlayerParty.PartyMember member : party.getMembers()) {
            var entity = level.getEntity(member.npcId());
            if (entity instanceof TownspersonMob npc) {
                returnNpcToAdventurerPool(npc, member.homeVillageId(), level);
            }
        }

        data.removeParty(party.getPartyId());

        src.sendSuccess(() -> Component.literal(
                        "Party '" + party.getPartyName() + "' disbanded."),
                false);
        return 1;
    }

    private static int partyInfo(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        Optional<PlayerParty> partyOpt =
                data.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                            "You don't have a party. Use /party form to create one."),
                    false);
            return 0;
        }

        PlayerParty party = partyOpt.get();

        src.sendSuccess(() -> Component.literal(
                "=== " + party.getPartyName() + " ==="), false);
        src.sendSuccess(() -> Component.literal(
                "Members: " + party.getAliveCount()
                        + "/" + PlayerParty.MAX_MEMBERS
                        + " alive"), false);
        src.sendSuccess(() -> Component.literal(
                "Quests completed: "
                        + party.getTotalQuestsCompleted()), false);
        src.sendSuccess(() -> Component.literal(
                "XP multiplier: x"
                        + String.format("%.2f",
                        party.getPartyXpMultiplier())), false);

        for (PlayerParty.PartyMember m : party.getMembers()) {
            src.sendSuccess(() -> Component.literal(
                            "  " + m.role().symbol + " "
                                    + m.name()
                                    + " [" + m.role().getDisplayName() + "]"
                                    + " Lv." + m.level()
                                    + " — " + m.kills() + " kills"
                                    + (m.isAlive() ? "" : " [DEAD]")),
                    false);
        }

        // Composition tips
        if (!party.hasRole(CombatRole.HEALER)) {
            src.sendSuccess(() -> Component.literal(
                            "  Tip: No healer — your party is vulnerable."),
                    false);
        }
        if (!party.hasRole(CombatRole.SCOUT)) {
            src.sendSuccess(() -> Component.literal(
                            "  Tip: No scout — you won't be warned of danger."),
                    false);
        }

        return 1;
    }

    private static int setRole(CommandSourceStack src,
                               String memberName, String roleName) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        CombatRole role;
        try {
            role = CombatRole.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(
                    "Invalid role. Valid: SWORDSMAN, ARCHER, MAGE, "
                            + "HEALER, SCOUT"));
            return 0;
        }

        Optional<PlayerParty> partyOpt =
                data.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendFailure(Component.literal(
                    "You don't have a party."));
            return 0;
        }

        PlayerParty party = partyOpt.get();
        Optional<PlayerParty.PartyMember> memberOpt = party.getMembers()
                .stream()
                .filter(m -> m.name().equalsIgnoreCase(memberName))
                .findFirst();

        if (memberOpt.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No member named '" + memberName + "'."));
            return 0;
        }

        PlayerParty.PartyMember oldMember = memberOpt.get();
        // Rebuild member with new role
        PlayerParty.PartyMember updated = new PlayerParty.PartyMember(
                oldMember.npcId(), oldMember.name(), role,
                oldMember.level(), oldMember.kills(),
                oldMember.questsCompleted(), oldMember.isAlive(),
                oldMember.homeVillageId());
        party.updateMember(updated);

        // Update live entity
        var npcEntity = level.getEntity(oldMember.npcId());
        if (npcEntity instanceof TownspersonMob npc) {
            npc.setCombatRole(role);
            applyRoleEquipment(npc, role, oldMember.level() >= 3);
        }

        data.setDirty();
        src.sendSuccess(() -> Component.literal(
                        oldMember.name() + " is now a "
                                + role.symbol + " " + role.getDisplayName()),
                false);
        return 1;
    }

    private static int setRally(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;

        ServerLevel level = src.getLevel();
        PlayerPartySavedData data = PlayerPartySavedData.get(level);

        Optional<PlayerParty> partyOpt =
                data.getPartyForPlayer(player.getUUID());
        if (partyOpt.isEmpty()) {
            src.sendFailure(Component.literal(
                    "You don't have a party."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        partyOpt.get().setRallyPoint(pos);

        src.sendSuccess(() -> Component.literal(
                "Rally point set at " + pos.toShortString()
                        + ". Party will regroup here."), false);
        return 1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Picks the most-needed role for the party — fills gaps first,
     * then defaults to SWORDSMAN for frontline coverage.
     */
    private static CombatRole pickNeededRole(PlayerParty party) {
        // Priority order for filling roles
        CombatRole[] priority = {
                CombatRole.SWORDSMAN,
                CombatRole.HEALER,
                CombatRole.ARCHER,
                CombatRole.SCOUT,
                CombatRole.MAGE
        };
        for (CombatRole role : priority) {
            if (!party.hasRole(role)) return role;
        }
        return CombatRole.SWORDSMAN; // duplicate frontliner if all roles filled
    }

    /**
     * Sets equipment appropriate for the member's role and level.
     */
    public static void applyRoleEquipment(TownspersonMob npc,
                                          CombatRole role,
                                          boolean elite) {
        AdventurerEquipment.applyRoleEquipment(npc,role,elite);
    }
    private static void returnNpcToAdventurerPool(TownspersonMob npc,
                                                  UUID homeVillageId,
                                                  ServerLevel level) {
        AdventurerSavedData adventurerData = AdventurerSavedData.get(level);

        // Create a fresh solo group for this NPC at their current position
        BlockPos pos = npc.blockPosition();
        AdventurerGroup newGroup = AdventurerGroup.create(
                homeVillageId, 1, pos, pos, level.getGameTime());

        // Replace the auto-generated member UUID with the actual NPC UUID
        // so the group correctly identifies this entity on respawn
        newGroup.getMemberIds().clear();
        newGroup.getMemberIds().add(npc.getUUID());

        // Mark as already spawned since the entity exists in the world
        newGroup.getSpawnedEntityIds().add(npc.getUUID());
        newGroup.setSpawned(true);

        adventurerData.addGroup(newGroup);

        // Re-link the NPC to its new group
        npc.setGroupId(newGroup.getGroupId());
        npc.setIsGroupLeader(true);
        npc.setCombatRole(null);
        npc.setCurrentActivity("Back on my own.");
    }
}