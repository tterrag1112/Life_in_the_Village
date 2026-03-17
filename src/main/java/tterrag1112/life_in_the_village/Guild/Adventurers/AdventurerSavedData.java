package tterrag1112.life_in_the_village.Guild.Adventurers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.PersonalityTrait;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;
import java.util.stream.Collectors;

public class AdventurerSavedData extends SavedData {

    private static final int  SPAWN_RADIUS            = 64;
    private static final int  MAX_GROUPS_PER_VILLAGE  = 3;
    private static final long TICK_INTERVAL           = 20L;

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
                    == tterrag1112.life_in_the_village.Guild.Quest.QuestType.EXPLORE) {
                AdventurerQuestTracker.checkExploreQuest(group, level, this);
            }

            // -------------------------------------------------------
            // Simulate unspawned groups
            // -------------------------------------------------------
            if (group.tick(currentTick)) dirty = true;
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

    private void spawnGroupEntities(AdventurerGroup group, ServerLevel level) {
        List<UUID> spawnedIds = new ArrayList<>();

        for (int i = 0; i < group.getMemberCount(); i++) {
            BlockPos spawnPos = group.getCurrentPos().offset(
                    level.getRandom().nextIntBetweenInclusive(-3, 3),
                    0,
                    level.getRandom().nextIntBetweenInclusive(-3, 3)
            );

            int surfaceY = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types
                            .MOTION_BLOCKING_NO_LEAVES,
                    spawnPos.getX(), spawnPos.getZ());

            TownspersonMob npc = new TownspersonMob(
                    ModEntities.TOWNSPERSON.get(), level);

            // All data setup in one place
            setupAdventurerNpc(npc, group, i, level);

            npc.setPos(
                    spawnPos.getX() + 0.5,
                    (double) surfaceY,
                    spawnPos.getZ() + 0.5
            );
            npc.setYRot(level.getRandom().nextFloat() * 360f);
            npc.setXRot(0f);

            if (level.addFreshEntity(npc)) {
                spawnedIds.add(npc.getUUID());
            }
        }

        group.getSpawnedEntityIds().addAll(spawnedIds);
        group.setSpawned(true);
    }

    private void setupAdventurerNpc(TownspersonMob npc, AdventurerGroup group,
                                        int memberIndex, ServerLevel level) {

        // -------------------------------------------------------
        // Group membership
        // -------------------------------------------------------
        npc.setGroupId(group.getGroupId());
        npc.setIsGroupLeader(memberIndex == 0);

        // -------------------------------------------------------
        // Profession — triggers goal registration automatically
        // -------------------------------------------------------
        npc.setProfession(TownspersonMob.Profession.ADVENTURER);

        // -------------------------------------------------------
        // Identity — use stable name from member UUID
        // -------------------------------------------------------
        UUID memberId = group.getMemberIds().get(memberIndex);
        String name = AdventurerNameGenerator.getName(memberId);
        npc.setNpcName(name);

        boolean isLeader = memberIndex == 0;
        if (isLeader) {
            npc.setCustomName(Component.literal(name)
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        } else {
            npc.setCustomName(Component.literal(name)
                    .withStyle(net.minecraft.ChatFormatting.WHITE));
        }
        npc.setCustomNameVisible(true);

        // -------------------------------------------------------
        // Gender — stable from UUID
        // -------------------------------------------------------
        npc.setMale((memberId.getMostSignificantBits() & 1) == 0);

        // -------------------------------------------------------
        // Age — adventurers are adults, stable range from UUID
        // -------------------------------------------------------
        int age = 20 + (int)(Math.abs(memberId.getLeastSignificantBits()) % 30);
        npc.setAge(age);

        // -------------------------------------------------------
        // Appearance — stable from UUID so consistent on respawn
        // -------------------------------------------------------
        int skinTone  = (int)(Math.abs(memberId.getMostSignificantBits())  % 6);
        int hairStyle = (int)(Math.abs(memberId.getLeastSignificantBits()) % 8);
        int hairColor = (int)(Math.abs(memberId.getMostSignificantBits()
                + memberId.getLeastSignificantBits()) % 7);
        npc.setAppearance(skinTone, hairStyle, hairColor);

        // -------------------------------------------------------
        // Personality — give adventurers fitting traits
        // -------------------------------------------------------
        npc.clearTraits();
        npc.addTrait(PersonalityTrait.BRAVE); // all adventurers are brave
        if (memberIndex == 0) {
            // Leader gets an extra trait
            long seed = memberId.getMostSignificantBits();
            PersonalityTrait[] all = PersonalityTrait.values();
            PersonalityTrait extra = all[(int)(Math.abs(seed) % all.length)];
            if (!extra.conflictsWith(PersonalityTrait.BRAVE)) {
                npc.addTrait(extra);
            }
        }

        // -------------------------------------------------------
        // Village assignment — adventurers belong to home village
        // -------------------------------------------------------
        VillageSavedData villageData = VillageSavedData.get(level);
        villageData.getVillageById(group.getHomeVillageId())
                .ifPresent(v -> npc.setAssignedVillageName(v.getName()));

        // -------------------------------------------------------
        // Equipment based on quest difficulty
        // -------------------------------------------------------
        setupAdventurerEquipment(npc, group.getActiveQuest(), memberIndex == 0);

        // -------------------------------------------------------
        // Activity label — shows under name tag
        // -------------------------------------------------------
        npc.setCurrentActivity(getActivityLabel(group));
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
        };
    }

    private void setupAdventurerEquipment(TownspersonMob npc,
                                          tterrag1112.life_in_the_village.Guild.Quest quest,
                                          boolean isLeader) {
        boolean elite = quest != null
                && (quest.getDifficulty() == tterrag1112.life_in_the_village.Guild.Quest
                .QuestDifficulty.ELITE
                || quest.getDifficulty() == tterrag1112.life_in_the_village.Guild.Quest
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
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(e -> group.setCurrentPos(e.blockPosition()));

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
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(Objects::nonNull)
                .forEach(e -> {
                    if (e.getVehicle() instanceof ArmorStand seat) {
                        seat.discard();
                    }
                    e.stopRiding();
                    e.discard();
                });

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

            tterrag1112.life_in_the_village.Guild.Quest quest =
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
        villageData.getAllVillages().forEach(village -> {
            UUID villageId = village.getId();

            long existing = groups.values().stream()
                    .filter(g -> g.getHomeVillageId().equals(villageId))
                    .count();
            if (existing >= MAX_GROUPS_PER_VILLAGE) return;

            if (villageData.getGuildForVillage(villageId).isEmpty()) return;

            var bounds = village.getBounds(villageData);
            if (bounds.isEmpty()) return;
            BlockPos center = BlockPos.containing(bounds.get().getCenter());


            if (center == null) return;

            Random rng = new Random();
            int offsetX = (rng.nextBoolean() ? 1 : -1) * (100 + rng.nextInt(200));
            int offsetZ = (rng.nextBoolean() ? 1 : -1) * (100 + rng.nextInt(200));
            BlockPos target = center.offset(offsetX, 0, offsetZ);

            int memberCount = 2 + level.getRandom().nextInt(3);
            AdventurerGroup group = AdventurerGroup.create(
                    villageId, memberCount, center, target, currentTick);

            groups.put(group.getGroupId(), group);
        });
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

}