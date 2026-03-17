package tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest.QuestDifficulty;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest.QuestType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.Random;
import java.util.UUID;

public class AdventurerQuestGenerator {

    // Mob targets for hunt quests
    private static final String[] HUNT_TARGETS = {
            "minecraft:zombie",
            "minecraft:skeleton",
            "minecraft:spider",
            "minecraft:creeper",
            "minecraft:witch",
            "minecraft:pillager"
    };

    // Biome targets for explore quests
    private static final String[] EXPLORE_TARGETS = {
            "minecraft:forest",
            "minecraft:plains",
            "minecraft:swamp",
            "minecraft:desert",
            "minecraft:taiga",
            "minecraft:jungle"
    };

    /**
     * Generates a quest appropriate for an adventurer group based on
     * their current state and position.
     */
    public static Quest generateForGroup(AdventurerGroup group,
                                         ServerLevel level,
                                         VillageSavedData villageData,
                                         long currentTick) {
        Random rng = new Random();

        // Pick a quest type weighted toward what makes sense
        // for a roaming adventurer group
        QuestType type = pickQuestType(group, rng);
        QuestDifficulty difficulty = pickDifficulty(group, rng);

        UUID questId  = UUID.randomUUID();
        UUID guildId  = getGuildId(group, villageData);
        long expiry   = currentTick + (24000L * 3); // 3 in-game days

        return switch (type) {
            case HUNT    -> buildHuntQuest(questId, guildId, difficulty,
                    expiry, rng);
            case EXPLORE -> buildExploreQuest(questId, guildId, difficulty,
                    expiry, group.getCurrentPos(), level, rng);
            case GATHER  -> buildGatherQuest(questId, guildId, difficulty,
                    expiry, rng);
            default      -> buildHuntQuest(questId, guildId, difficulty,
                    expiry, rng);
        };
    }

    // -------------------------------------------------------------------------
    // Quest builders
    // -------------------------------------------------------------------------

    private static Quest buildHuntQuest(UUID questId, UUID guildId,
                                        QuestDifficulty difficulty,
                                        long expiry, Random rng) {
        String mob = HUNT_TARGETS[rng.nextInt(HUNT_TARGETS.length)];
        String mobName = formatMobName(mob);
        int count = switch (difficulty) {
            case EASY      -> 3 + rng.nextInt(3);   // 3-5
            case MEDIUM    -> 6 + rng.nextInt(5);   // 6-10
            case HARD      -> 10 + rng.nextInt(6);  // 10-15
            case ELITE     -> 15 + rng.nextInt(6);  // 15-20
            case LEGENDARY -> 20 + rng.nextInt(11); // 20-30
        };

        Quest quest = new Quest(
                questId, guildId, QuestType.HUNT, difficulty,
                "Hunt: " + mobName,
                "Eliminate " + count + " " + mobName
                        + "s threatening the region.",
                difficulty.getBaseCoinReward(),
                difficulty.getBaseXp(),
                expiry
        );
        quest.setTargetMob(mob);
        quest.setTargetCount(count);
        return quest;
    }

    private static Quest buildExploreQuest(UUID questId, UUID guildId,
                                           QuestDifficulty difficulty,
                                           long expiry, BlockPos nearPos,
                                           ServerLevel level, Random rng) {
        String biome = EXPLORE_TARGETS[rng.nextInt(EXPLORE_TARGETS.length)];
        String biomeName = formatBiomeName(biome);

        // Try to find an actual position of the target biome
        BlockPos biomePos = findBiomePosition(level, nearPos, biome, rng);

        // Fall back to a random offset if biome not found nearby
        if (biomePos == null) {
            biomePos = nearPos.offset(
                    (rng.nextBoolean() ? 1 : -1) * (100 + rng.nextInt(400)),
                    0,
                    (rng.nextBoolean() ? 1 : -1) * (100 + rng.nextInt(400))
            );
        }

        Quest quest = new Quest(
                questId, guildId, QuestType.EXPLORE, difficulty,
                "Scout: " + biomeName,
                "Explore the " + biomeName
                        + " and report back on conditions.",
                difficulty.getBaseCoinReward(),
                difficulty.getBaseXp(),
                expiry
        );
        quest.setTargetBiome(biome);
        quest.setTargetCount(1);

        // Store the found position so the group navigates correctly
        quest.setTargetPos(biomePos);

        return quest;
    }

    private static BlockPos findBiomePosition(ServerLevel level,
                                              BlockPos origin,
                                              String biomeId,
                                              Random rng) {
        try {
            Identifier id = Identifier.parse(biomeId);

            // Use Minecraft's built-in biome finder
            var biomeRegistry = level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);

            var biomeKey = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.BIOME, id);

            var biomeHolder = biomeRegistry.get(biomeKey).orElse(null);
            if (biomeHolder == null) return null;

            // Search up to 6400 blocks away, checking every 32 blocks
            var result = level.findClosestBiome3d(
                    b -> b.is(biomeKey),
                    origin,
                    6400,
                    32,
                    64
            );

            if (result == null) return null;

            // Add slight random offset so groups don't all go to
            // the exact same spot
            return result.getFirst().offset(
                    rng.nextInt(64) - 32,
                    0,
                    rng.nextInt(64) - 32
            );

        } catch (Exception e) {
            // Biome not found or registry error — caller handles null
            return null;
        }
    }

    private static Quest buildGatherQuest(UUID questId, UUID guildId,
                                          QuestDifficulty difficulty,
                                          long expiry, Random rng) {
        // Simple gather quests for now — expand with more items later
        String[] items = {
                "minecraft:leather",
                "minecraft:bone",
                "minecraft:spider_eye",
                "minecraft:gunpowder",
                "minecraft:blaze_rod"
        };
        String item = items[rng.nextInt(items.length)];
        String itemName = formatItemName(item);
        int count = switch (difficulty) {
            case EASY      -> 5  + rng.nextInt(6);  // 5-10
            case MEDIUM    -> 10 + rng.nextInt(11); // 10-20
            case HARD      -> 20 + rng.nextInt(11); // 20-30
            case ELITE     -> 30 + rng.nextInt(21); // 30-50
            case LEGENDARY -> 50 + rng.nextInt(51); // 50-100
        };

        Quest quest = new Quest(
                questId, guildId, QuestType.GATHER, difficulty,
                "Gather: " + itemName,
                "Collect " + count + " " + itemName
                        + " from the surrounding area.",
                difficulty.getBaseCoinReward(),
                difficulty.getBaseXp(),
                expiry
        );
        quest.setTargetCount(count);
        return quest;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static QuestType pickQuestType(AdventurerGroup group,
                                           Random rng) {
        // Weight quest types based on group state
        return switch (group.getState()) {
            case HUNTING   -> QuestType.HUNT;
            case EXPLORING -> QuestType.EXPLORE;
            default        -> {
                // Random weighted pick for other states
                int roll = rng.nextInt(10);
                yield roll < 5 ? QuestType.HUNT
                        : roll < 8 ? QuestType.GATHER
                        : QuestType.EXPLORE;
            }
        };
    }

    private static QuestDifficulty pickDifficulty(AdventurerGroup group,
                                                  Random rng) {
        // Larger groups tackle harder quests
        int size = group.getMemberCount();
        if (size >= 4) {
            int roll = rng.nextInt(10);
            return roll < 4 ? QuestDifficulty.MEDIUM
                    : roll < 7 ? QuestDifficulty.HARD
                    : roll < 9 ? QuestDifficulty.ELITE
                    : QuestDifficulty.LEGENDARY;
        } else if (size == 3) {
            int roll = rng.nextInt(10);
            return roll < 4 ? QuestDifficulty.EASY
                    : roll < 8 ? QuestDifficulty.MEDIUM
                    : QuestDifficulty.HARD;
        } else {
            return rng.nextBoolean()
                    ? QuestDifficulty.EASY
                    : QuestDifficulty.MEDIUM;
        }
    }

    private static UUID getGuildId(AdventurerGroup group,
                                   VillageSavedData villageData) {
        return villageData
                .getGuildForVillage(group.getHomeVillageId())
                .map(g -> g.guildId())
                .orElse(group.getHomeVillageId()); // fallback
    }

    private static String formatMobName(String resourceLocation) {
        String name = resourceLocation.contains(":")
                ? resourceLocation.split(":")[1]
                : resourceLocation;
        return Character.toUpperCase(name.charAt(0))
                + name.substring(1).replace("_", " ");
    }

    private static String formatBiomeName(String resourceLocation) {
        return formatMobName(resourceLocation); // same logic
    }

    private static String formatItemName(String resourceLocation) {
        return formatMobName(resourceLocation); // same logic
    }
}