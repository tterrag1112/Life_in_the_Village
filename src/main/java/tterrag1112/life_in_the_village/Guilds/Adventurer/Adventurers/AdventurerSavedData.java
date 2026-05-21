package tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildRank;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;
import java.util.stream.Collectors;

import static tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerEquipment.applyRoleEquipment;

public class AdventurerSavedData extends SavedData {

    private static final int  SPAWN_RADIUS            = 64;
    private static final long TICK_INTERVAL           = 20L;
    /**
     * Maximum wandering adventurers (non-village-assigned) in the world.
     * Village-assigned adventurers who join parties are not counted here —
     * they remain as villagers employed differently.
     */
    private static final int MAX_WANDERING_ADVENTURERS = 12;

    /**
     * Maximum wandering adventurer groups originating from a single village.
     * Kept low — most adventurers should be village residents, not wanderers.
     */
    private static final int MAX_GROUPS_PER_VILLAGE = 2;

    // -------------------------------------------------------------------------
    // SavedDataType — replaces Factory/Info, uses codec for serialization
    // -------------------------------------------------------------------------

    private static final Codec<UUID> UUID_STRING_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final SavedDataType<AdventurerSavedData> TYPE = new SavedDataType<>(
            "adventurer_groups",
            AdventurerSavedData::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    AdventurerGroup.CODEC.listOf()
                            .fieldOf("groups")
                            .forGetter(d -> new ArrayList<>(d.groups.values())),
                    Codec.unboundedMap(
                                    UUID_STRING_CODEC,  // string key — required by DFU
                                    UUID_STRING_CODEC.listOf()
                                            .<Set<UUID>>xmap(
                                                    list -> new HashSet<>(list),
                                                    set -> new ArrayList<>(set)))
                            .optionalFieldOf("playerGroupIndex",
                                    new HashMap<>())
                            .forGetter(d -> d.playerGroupIndex)
            ).apply(instance, AdventurerSavedData::fromCodec))
    );

    // -------------------------------------------------------------------------
    // Codec factory
    // -------------------------------------------------------------------------

    private static AdventurerSavedData fromCodec(
            List<AdventurerGroup> groupList,
            Map<UUID, Set<UUID>> playerGroupIndex) {
        AdventurerSavedData data = new AdventurerSavedData();
        for (AdventurerGroup g : groupList) {
            data.groups.put(g.getGroupId(), g);
        }
        data.playerGroupIndex.putAll(playerGroupIndex);
        return data;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Map<UUID, AdventurerGroup> groups = new HashMap<>();
    private long lastTickTime = 0L;

    private final Map<UUID, Set<UUID>> playerGroupIndex = new HashMap<>();




    // -------------------------------------------------------------------------
    // Get instance
    // -------------------------------------------------------------------------

    public static AdventurerSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tick(ServerLevel level, VillageSavedData villageData) {
        long currentTick = level.getGameTime();
        if (currentTick - lastTickTime < TICK_INTERVAL) return;
        lastTickTime = currentTick;

        boolean dirty = false;
        List<UUID> toRemove = new ArrayList<>();

        for (AdventurerGroup group : groups.values()) {

            // -------------------------------------------------------
            // Remove dead groups — despawn entities first
            // -------------------------------------------------------
            if (group.isDead()) {
                if (group.isSpawned()) {
                    despawnGroupEntities(group, level);
                }
                toRemove.add(group.getGroupId());
                dirty = true;
                continue;
            }

            // -------------------------------------------------------
            // Proximity — spawn or despawn
            // -------------------------------------------------------
            boolean playerNearby = isPlayerNearby(level,
                    group.getCurrentPos(), SPAWN_RADIUS);

            if (playerNearby && !group.isSpawned()) {
                spawnGroupEntities(group, level);
                level.players().forEach(player -> {
                    if (player instanceof ServerPlayer sp
                            && group.getCurrentPos()
                            .closerThan(player.blockPosition(), SPAWN_RADIUS)) {
                        AdventurerReputationManager.applyTierBehavior(
                                sp, group, level, this);
                    }
                });
                dirty = true;
            } else if (!playerNearby && group.isSpawned()) {
                despawnGroupEntities(group, level);
                dirty = true;
            }

            // -------------------------------------------------------
            // Biome check for spawned explore quests
            // -------------------------------------------------------
            if (group.isSpawned()
                    && group.getActiveQuest() != null
                    && group.getActiveQuest().getType()
                    == Quest.QuestType.EXPLORE) {
                AdventurerQuestTracker.checkExploreQuest(group, level, this);
            }

            // -------------------------------------------------------
            // Simulate unspawned groups
            // -------------------------------------------------------

            PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
            boolean allInParty = !group.getMemberIds().isEmpty()
                    && group.getMemberIds().stream()
                    .allMatch(id -> partyData.getPartyContaining(id)
                            .isPresent());
            if (allInParty) continue;
            if (group.tick(currentTick)) dirty = true;

            if (group.getState() == AdventurerGroup.GroupState.AT_GUILD
                    && group.getStateChangeTick() == currentTick
                    && group.isSpawned()) {
                checkRetirements(group, level);
                dirty = true;
            }
        }

        AdventurerReputationManager.tickWarningSpread(
                level, villageData, currentTick);

        // Remove dead groups
        toRemove.forEach(groups::remove);

        assignQuestsToGroups(level, villageData, currentTick);

        if (currentTick % 12000L == 0) {
            trySpawnNewGroups(level, villageData, currentTick);
            dirty = true;
        }

        if (dirty) setDirty();
    }

    // -------------------------------------------------------------------------
    // Spawn / Despawn
    // -------------------------------------------------------------------------

    private void spawnGroupEntities(AdventurerGroup group,
                                   ServerLevel level) {
        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
        VillageSavedData villageData = VillageSavedData.get(level);

        // If ALL members are in a party, this group is fully absorbed —
        // mark dead rather than spawning duplicate entities
        boolean allInParty = !group.getMemberIds().isEmpty()
                && group.getMemberIds().stream()
                .allMatch(id -> partyData
                        .getPartyContaining(id).isPresent());
        if (allInParty) {
            group.setDead(true);
            setDirty();
            return;
        }

        List<UUID> spawnedIds = new ArrayList<>();

        BlockPos spawnCenter =
                group.getState() == AdventurerGroup.GroupState.AT_GUILD
                        ? getGuildHallPos(group, level, villageData)
                        : group.getCurrentPos();

        for (int i = 0; i < group.getMemberCount(); i++) {
            UUID memberId = group.getMemberIds().get(i);

            // Skip members already in a player party — their entity
            // is managed by party goals, not the group spawner
            if (partyData.getPartyContaining(memberId).isPresent()) {
                continue;
            }

            BlockPos spawnPos = spawnCenter.offset(
                    level.getRandom().nextIntBetweenInclusive(-3, 3),
                    0,
                    level.getRandom().nextIntBetweenInclusive(-3, 3));

            int surfaceY = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types
                            .MOTION_BLOCKING_NO_LEAVES,
                    spawnPos.getX(), spawnPos.getZ());

            TownspersonMob npc = new TownspersonMob(
                    ModEntities.TOWNSPERSON.get(), level);

            setupAdventurerNpc(npc, group, i, level);

            npc.setPos(spawnPos.getX() + 0.5,
                    surfaceY,
                    spawnPos.getZ() + 0.5);
            npc.setYRot(level.getRandom().nextFloat() * 360f);
            npc.setXRot(0f);

            if (level.addFreshEntity(npc)) {
                spawnedIds.add(npc.getUUID());
            }
        }

        group.getSpawnedEntityIds().addAll(spawnedIds);
        group.setSpawned(true);
    }
    private BlockPos getGuildOrVillageCenter(AdventurerGroup group,
                                             ServerLevel level,
                                             VillageSavedData villageData) {
        return villageData.getGuildForVillage(group.getHomeVillageId())
                .flatMap(guild -> villageData.getBuildingById(guild.guildId()))  // if guild has a building ref
                .map(b -> b.getShape().getOrigin())
                .or(() -> villageData.getVillageById(group.getHomeVillageId())
                        .flatMap(v -> v.getBounds(villageData))
                        .map(aabb -> BlockPos.containing(aabb.getCenter())))
                .orElse(group.getCurrentPos());
    }
    private BlockPos getGuildHallPos(AdventurerGroup group,
                                     ServerLevel level,
                                     VillageSavedData villageData) {
        return villageData.getVillageById(group.getHomeVillageId())
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(villageData::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.GUILD_HALL)
                        .findFirst())
                .map(b -> b.getShape().getOrigin())
                .or(() -> villageData.getVillageById(group.getHomeVillageId())
                        .flatMap(v -> v.getBounds(villageData))
                        .map(aabb -> BlockPos.containing(aabb.getCenter())))
                .orElse(group.getCurrentPos());
    }

    private void setupAdventurerNpc(TownspersonMob npc,
                                    AdventurerGroup group,
                                    int memberIndex,
                                    ServerLevel level) {
        // ── Group membership ──────────────────────────────────────────
        npc.setGroupId(group.getGroupId());
        npc.setIsGroupLeader(memberIndex == 0);
        // Phase 6.3.2.a — role projection (parallel to entityData for the
        // deferred Goal-side ADVENTURER cluster that still reads getGroupId/
        // isGroupLeader directly).
        npc.getRoles().assignRole(
                tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment.conditional(
                        tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.PARTY_MEMBER,
                        java.util.Map.of(
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_GROUP_ID,
                                group.getGroupId().toString(),
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_IS_LEADER,
                                Boolean.toString(memberIndex == 0))));

        // ── Profession ────────────────────────────────────────────────
        npc.setProfession(Profession.ADVENTURER);

        // ── Combat role — assigned to ALL adventurers ─────────────────
        // Roles are distributed across the group so a 3-person group
        // naturally gets SWORDSMAN, ARCHER, HEALER etc.
        // When not in a party the role governs equipment and idle
        // behaviour but party goals don't fire (no party found in data).
        CombatRole role = assignGroupRole(group, memberIndex);
        // Use the backing field directly to avoid re-registering goals
        // mid-setup — setProfession above already registered them
        npc.setCombatRoleSilent(role);;
        applyRoleEquipment(npc, role,
                group.getGroupRank() == GuildRank.DIAMOND
                        || group.getGroupRank() == GuildRank.PLATINUM);

        // ── Identity ──────────────────────────────────────────────────
        UUID memberId = group.getMemberIds().get(memberIndex);
        String firstName = AdventurerNameGenerator.getFirstName(memberId);
        String surname   = AdventurerNameGenerator.getSurname(memberId);
        String title     = AdventurerNameGenerator.getTitle(memberId);

        npc.setNpcName(firstName + " " + surname);
        npc.setAdventurerTitle(title);
        npc.setCustomNameVisible(true);

        // ── Gender ────────────────────────────────────────────────────
        npc.setMale((memberId.getMostSignificantBits() & 1) == 0);

        // ── Age ───────────────────────────────────────────────────────
        int age = 20 + (int)(Math.abs(
                memberId.getLeastSignificantBits()) % 30);
        npc.setAge(age);

        // ── Appearance ────────────────────────────────────────────────
        int skinTone  = (int)(Math.abs(
                memberId.getMostSignificantBits()) % 6);
        int hairStyle = (int)(Math.abs(
                memberId.getLeastSignificantBits()) % 8);
        int hairColor = (int)(Math.abs(
                memberId.getMostSignificantBits()
                        + memberId.getLeastSignificantBits()) % 7);
        npc.setAppearance(skinTone, hairStyle, hairColor);

        // ── Personality ───────────────────────────────────────────────
        npc.clearTraits();
        npc.addTrait(AppearanceComponent.PersonalityTrait.BRAVE);


        // ── Village assignment ────────────────────────────────────────
        VillageSavedData villageData = VillageSavedData.get(level);
        villageData.getVillageById(group.getHomeVillageId())
                .ifPresent(v -> npc.setAssignedVillageName(v.getName()));

        // ── Activity label ────────────────────────────────────────────
        npc.setCurrentActivity(getActivityLabel(group));
    }

    /**
     * Distributes combat roles across a group so the composition
     * feels natural. The leader (index 0) is always a SWORDSMAN.
     * Remaining slots cycle through a priority order.
     */
    private static CombatRole assignGroupRole(AdventurerGroup group,
                                              int memberIndex) {
        if (memberIndex == 0) return CombatRole.SWORDSMAN;

        // Priority fill order for remaining slots
        CombatRole[] fill = {
                CombatRole.ARCHER,
                CombatRole.HEALER,
                CombatRole.SCOUT,
                CombatRole.MAGE,
                CombatRole.SWORDSMAN // extra frontliner if group > 5
        };

        int fillIdx = (memberIndex - 1) % fill.length;
        return fill[fillIdx];
    }

    private String getActivityLabel(AdventurerGroup group) {
        return switch (group.getState()) {
            case TRAVELLING -> "Travelling...";
            case CAMPING    -> "Making camp";
            case HUNTING    -> group.getActiveQuest() != null
                    ? "Hunting " + formatMobName(group.getActiveQuest().getTargetMob())
                    : "On patrol";
            case EXPLORING  -> "Scouting the area";
            case ESCORTING  -> "Escorting traveller";
            case RETURNING  -> "Returning to guild";
            case AT_GUILD   -> "Resting between contracts";
        };
    }

    private void setupAdventurerEquipment(TownspersonMob npc,
                                          Quest quest,
                                          boolean isLeader) {
        boolean elite = quest != null
                && (quest.getDifficulty() == Quest
                .QuestDifficulty.ELITE
                || quest.getDifficulty() == Quest
                .QuestDifficulty.LEGENDARY);

        // Leader always gets gold helmet regardless of quest difficulty
        npc.setItemSlot(EquipmentSlot.HEAD, new ItemStack(
                isLeader ? Items.GOLDEN_HELMET
                        : elite  ? Items.DIAMOND_HELMET
                        :          Items.IRON_HELMET));

        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                elite ? Items.DIAMOND_SWORD : Items.IRON_SWORD));
        npc.setItemSlot(EquipmentSlot.CHEST, new ItemStack(
                elite ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE));
        npc.setItemSlot(EquipmentSlot.LEGS, new ItemStack(
                elite ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS));
        npc.setItemSlot(EquipmentSlot.FEET, new ItemStack(
                elite ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS));
    }

    private String formatMobName(String resourceLocation) {
        if (resourceLocation == null) return "monsters";
        String name = resourceLocation.contains(":")
                ? resourceLocation.split(":")[1] : resourceLocation;
        return name.replace("_", " ");
    }



    private void despawnGroupEntities(AdventurerGroup group,
                                      ServerLevel level) {
        // Sync position from leader before despawning


        // Remove campfire if one was placed
        group.getCampfirePos().ifPresent(pos -> {
            if (level.getBlockState(pos).is(
                    net.minecraft.world.level.block.Blocks.CAMPFIRE)) {
                level.setBlock(pos,
                        net.minecraft.world.level.block.Blocks.AIR
                                .defaultBlockState(), 3);
            }
            group.setCampfirePos(null);
        });

        // Remove seat armor stands
        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
        group.getSpawnedEntityIds().stream()
                .filter(id -> partyData.getPartyContaining(id).isEmpty()) // ← skip party members
                .map(level::getEntity)
                .filter(Objects::nonNull)
                .forEach(e -> {
                    if (e.getVehicle() instanceof ArmorStand seat) {
                        seat.discard();
                    }
                    e.stopRiding();
                    e.discard();
                });

        group.getSpawnedEntityIds().stream()
                .filter(id -> partyData.getPartyContaining(id).isEmpty())
                .map(level::getEntity)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(e -> group.setCurrentPos(e.blockPosition()));

        group.getSpawnedEntityIds().clear();
        group.setSpawned(false);
    }

    // -------------------------------------------------------------------------
    // Quest assignment
    // -------------------------------------------------------------------------

    private void assignQuestsToGroups(ServerLevel level,
                                      VillageSavedData villageData,
                                      long currentTick) {
        for (AdventurerGroup group : groups.values()) {
            if (group.getActiveQuest() != null) continue;
            if (group.getState() != AdventurerGroup.GroupState.CAMPING
                    && group.getState() != AdventurerGroup.GroupState.TRAVELLING) {
                continue;
            }

            Quest quest =
                    AdventurerQuestGenerator.generateForGroup(
                            group, level, villageData, currentTick);

            BlockPos questTarget = pickQuestLocation(
                    group.getCurrentPos(), level);
            group.setTargetPos(questTarget);
            group.setActiveQuest(quest);
            group.setState(AdventurerGroup.GroupState.TRAVELLING);
            group.setStateChangeTick(currentTick);
        }
    }

    private BlockPos pickQuestLocation(BlockPos origin, ServerLevel level) {
        Random rng = new Random();
        int offsetX = (rng.nextBoolean() ? 1 : -1) * (50 + rng.nextInt(150));
        int offsetZ = (rng.nextBoolean() ? 1 : -1) * (50 + rng.nextInt(150));
        return origin.offset(offsetX, 0, offsetZ);
    }

    // -------------------------------------------------------------------------
    // Group spawning from villages
    // -------------------------------------------------------------------------

    private void trySpawnNewGroups(ServerLevel level,
                                   VillageSavedData villageData,
                                   long currentTick) {
        // Count total wandering (non-village-resident) adventurers
        // across all groups to enforce world cap
        long totalWandering = groups.values().stream()
                .filter(g -> !isVillageResidentGroup(g, villageData))
                .mapToLong(g -> g.getMemberIds().size())
                .sum();

        if (totalWandering >= MAX_WANDERING_ADVENTURERS) return;

        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);

        villageData.getAllVillages().forEach(village -> {
            UUID villageId = village.getId();

            // Only spawn groups for villages with a guild hall
            if (villageData.getGuildForVillage(villageId).isEmpty()) return;

            // Count existing groups originating from this village
            // that are NOT just village residents in a party
            long existing = groups.values().stream()
                    .filter(g -> g.getHomeVillageId().equals(villageId))
                    .filter(g -> !isVillageResidentGroup(g, villageData))
                    .count();

            if (existing >= MAX_GROUPS_PER_VILLAGE) return;

            // Don't spawn if we're already at world cap
            if (totalWandering >= MAX_WANDERING_ADVENTURERS) return;

            var bounds = village.getBounds(villageData);
            if (bounds.isEmpty()) return;
            BlockPos center = BlockPos.containing(
                    bounds.get().getCenter());

            Random rng = new Random();
            int offsetX = (rng.nextBoolean() ? 1 : -1)
                    * (100 + rng.nextInt(200));
            int offsetZ = (rng.nextBoolean() ? 1 : -1)
                    * (100 + rng.nextInt(200));
            BlockPos target = center.offset(offsetX, 0, offsetZ);

            int memberCount = 2 + level.getRandom().nextInt(3);
            AdventurerGroup group = AdventurerGroup.create(
                    villageId, memberCount, center,
                    target, currentTick);
            groups.put(group.getGroupId(), group);
        });
    }

    /**
     * Returns true if all members of this group are assigned to a village
     * (i.e. they are resident adventurers, not wanderers).
     * Village-resident adventurers who join a party are not wandering —
     * they are employed differently and should not count toward the
     * wandering group cap.
     */
    private boolean isVillageResidentGroup(AdventurerGroup group,
                                           VillageSavedData villageData) {
        // A group is "resident" if it has a home village AND
        // all its spawned members are assigned to that village
        if (group.getHomeVillageId() == null) return false;
        return villageData.getVillageById(group.getHomeVillageId())
                .isPresent();
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isPlayerNearby(ServerLevel level,
                                          BlockPos pos, int radius) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(pos, radius)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void addGroup(AdventurerGroup group) {
        groups.put(group.getGroupId(), group);
        setDirty();
    }

    public void removeGroup(UUID groupId) {
        groups.remove(groupId);
        setDirty();
    }

    public Optional<AdventurerGroup> getGroup(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public List<AdventurerGroup> getAllGroups() {
        return new ArrayList<>(groups.values());
    }

    public List<AdventurerGroup> getGroupsForVillage(UUID villageId) {
        return groups.values().stream()
                .filter(g -> g.getHomeVillageId().equals(villageId))
                .collect(Collectors.toList());
    }

    public List<AdventurerGroup> getGroupsNear(BlockPos pos, int radius) {
        return groups.values().stream()
                .filter(g -> g.isNearPosition(pos, radius))
                .collect(Collectors.toList());
    }


    public void addPlayerToGroup(UUID playerId, UUID groupId) {
        playerGroupIndex
                .computeIfAbsent(playerId, k -> new HashSet<>())
                .add(groupId);
        setDirty();
    }

    public void removePlayerFromGroup(UUID playerId, UUID groupId) {
        Set<UUID> groupIds = playerGroupIndex.get(playerId);
        if (groupIds != null) {
            groupIds.remove(groupId);
            if (groupIds.isEmpty()) {
                playerGroupIndex.remove(playerId);
            }
            setDirty();
        }
    }

    public List<AdventurerGroup> getGroupsForPlayer(UUID playerId) {
        return playerGroupIndex
                .getOrDefault(playerId, Set.of())
                .stream()
                .map(groups::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void updateLeaderVisuals(AdventurerGroup group,
                                    ServerLevel level) {
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .forEach(npc -> {
                    boolean isNpcLeader = npc.isGroupLeader()
                            && !group.isPlayerLeadingAny();

                    // Update helmet
                    npc.setItemSlot(EquipmentSlot.HEAD, new ItemStack(
                            isNpcLeader ? Items.GOLDEN_HELMET
                                    : Items.IRON_HELMET));

                    // Update name color
                    String name = npc.getNpcName();
                    npc.setCustomName(Component.literal(name)
                            .withStyle(isNpcLeader
                                    ? net.minecraft.ChatFormatting.GOLD
                                    : net.minecraft.ChatFormatting.WHITE));
                });
    }

    private void checkRetirements(AdventurerGroup group, ServerLevel level) {
        if (group.getMemberIds().isEmpty()) return;

        List<UUID> toRetire = new ArrayList<>();

        for (UUID memberId : group.getMemberIds()) {
            int retirementAge = 55 + (int)(Math.abs(memberId.hashCode()) % 16);

            // Find the spawned entity if present
            TownspersonMob npc = group.getSpawnedEntityIds().stream()
                    .map(level::getEntity)
                    .filter(e -> e instanceof TownspersonMob)
                    .map(e -> (TownspersonMob) e)
                    .filter(e -> e.getUUID().equals(memberId))
                    .findFirst().orElse(null);

            if (npc != null && npc.getAge() >= retirementAge) {
                toRetire.add(memberId);
                npc.setAdventurerTitle(
                        (npc.getAdventurerTitle().isEmpty() ? ""
                                : npc.getAdventurerTitle() + " ") + "(Retired)");
                npc.setProfession(Profession.NONE);
                npc.setGroupId(null);
                // Phase 6.3.2.a — drop role projection on retirement.
                npc.getRoles().removeRole(
                        tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.PARTY_MEMBER);
                npc.setCurrentActivity("Enjoying retirement");
            }
        }

        group.getMemberIds().removeAll(toRetire);

        if (group.getMemberIds().isEmpty()) {
            group.setDead(true);
        }
    }

}