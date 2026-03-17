package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class QuestGenerator {

    private static final long QUEST_EXPIRY_TICKS = 24000L * 3; // 3 days

    public static List<Quest> generateQuestsForGuild(
            ServerLevel level, UUID guildId, Village village,
            VillageSavedData data, long currentTick) {

        List<Quest> quests = new ArrayList<>();
        Random random = new Random(guildId.hashCode() + currentTick);

        // Generate one quest per difficulty tier
        for (Quest.QuestDifficulty diff : Quest.QuestDifficulty.values()) {
            Quest.QuestType type = randomType(random);
            Quest quest = generateQuest(level, guildId, village,
                    data, type, diff, currentTick, random);
            if (quest != null) quests.add(quest);
        }

        return quests;
    }

    private static Quest generateQuest(ServerLevel level, UUID guildId,
                                       Village village, VillageSavedData data,
                                       Quest.QuestType type, Quest.QuestDifficulty diff,
                                       long currentTick, Random random) {

        long expiry = currentTick + QUEST_EXPIRY_TICKS;
        long coinReward = diff.getBaseCoinReward();
        int xpReward = diff.getBaseXp();

        return switch (type) {
            case GATHER  -> generateGatherQuest(guildId, diff,
                    coinReward, xpReward, expiry, random);
            case HUNT    -> generateHuntQuest(guildId, village,
                    diff, coinReward, xpReward, expiry, random);
            case EXPLORE -> generateExploreQuest(guildId, diff,
                    coinReward, xpReward, expiry, random);
            case DELIVER -> generateDeliverQuest(guildId, village,
                    data, diff, coinReward, xpReward, expiry, random);
            case ESCORT  -> generateEscortQuest(guildId, village,
                    diff, coinReward, xpReward, expiry, random);
        };
    }

    private static Quest generateGatherQuest(UUID guildId,
                                             Quest.QuestDifficulty diff, long coins, int xp,
                                             long expiry, Random random) {

        // Items scale with difficulty
        var items = switch (diff) {
            case EASY   -> List.of(Items.OAK_LOG, Items.COBBLESTONE,
                    Items.WHEAT, Items.CARROT);
            case MEDIUM -> List.of(Items.IRON_INGOT, Items.GOLD_INGOT,
                    Items.COAL, Items.BREAD);
            case HARD   -> List.of(Items.DIAMOND, Items.EMERALD,
                    Items.LAPIS_LAZULI, Items.REDSTONE);
            case ELITE  -> List.of(Items.NETHERITE_SCRAP,
                    Items.BLAZE_ROD, Items.ENDER_PEARL);
            case LEGENDARY -> List.of(Items.NETHERITE_INGOT,
                    Items.SHULKER_SHELL, Items.ELYTRA);
        };

        var item = items.get(random.nextInt(items.size()));
        int count = switch (diff) {
            case EASY -> 8 + random.nextInt(8);
            case MEDIUM -> 16 + random.nextInt(16);
            case HARD -> 32 + random.nextInt(16);
            case ELITE -> 48 + random.nextInt(16);
            case LEGENDARY -> 64;
        };

        Quest quest = new Quest(UUID.randomUUID(), guildId,
                Quest.QuestType.GATHER, diff,
                "Gather " + count + " " + item.getDescriptionId(),
                "Collect " + count + " "
                        + item.getDescriptionId()
                        + " and bring them to the guild.",
                coins, xp, expiry);
        quest.setTargetItem(item);
        quest.setTargetCount(count);
        return quest;
    }

    private static Quest generateHuntQuest(UUID guildId,
                                           Village village, Quest.QuestDifficulty diff,
                                           long coins, int xp, long expiry, Random random) {

        var mobs = switch (diff) {
            case EASY   -> List.of("minecraft:zombie", "minecraft:skeleton",
                    "minecraft:spider");
            case MEDIUM -> List.of("minecraft:creeper", "minecraft:witch",
                    "minecraft:pillager");
            case HARD   -> List.of("minecraft:vindicator",
                    "minecraft:ravager", "minecraft:blaze");
            case ELITE  -> List.of("minecraft:elder_guardian",
                    "minecraft:wither_skeleton");
            case LEGENDARY -> List.of("minecraft:wither",
                    "minecraft:ender_dragon");
        };

        String mob = mobs.get(random.nextInt(mobs.size()));
        String mobName = mob.replace("minecraft:", "")
                .replace("_", " ");
        int count = switch (diff) {
            case EASY -> 5 + random.nextInt(5);
            case MEDIUM -> 10 + random.nextInt(5);
            case HARD -> 15 + random.nextInt(5);
            case ELITE -> 5 + random.nextInt(3);
            case LEGENDARY -> 1;
        };

        Quest quest = new Quest(UUID.randomUUID(), guildId,
                Quest.QuestType.HUNT, diff,
                "Hunt " + count + " " + mobName + "(s)",
                "Eliminate " + count + " " + mobName
                        + " threatening the region.",
                coins, xp, expiry);
        quest.setTargetMob(mob);
        quest.setTargetCount(count);
        return quest;
    }

    private static Quest generateExploreQuest(UUID guildId,
                                              Quest.QuestDifficulty diff, long coins, int xp,
                                              long expiry, Random random) {

        var biomes = switch (diff) {
            case EASY   -> List.of("minecraft:plains", "minecraft:forest",
                    "minecraft:birch_forest");
            case MEDIUM -> List.of("minecraft:desert", "minecraft:savanna",
                    "minecraft:swamp");
            case HARD   -> List.of("minecraft:jungle",
                    "minecraft:mushroom_fields",
                    "minecraft:badlands");
            case ELITE  -> List.of("minecraft:deep_dark",
                    "minecraft:nether_wastes",
                    "minecraft:soul_sand_valley");
            case LEGENDARY -> List.of("minecraft:end_barrens",
                    "minecraft:end_highlands");
        };

        String biome = biomes.get(random.nextInt(biomes.size()));
        String biomeName = biome.replace("minecraft:", "")
                .replace("_", " ");

        Quest quest = new Quest(UUID.randomUUID(), guildId,
                Quest.QuestType.EXPLORE, diff,
                "Explore the " + biomeName,
                "Travel to a " + biomeName
                        + " biome and return to report your findings.",
                coins, xp, expiry);
        quest.setTargetBiome(biome);
        return quest;
    }

    private static Quest generateDeliverQuest(UUID guildId,
                                              Village village, VillageSavedData data,
                                              Quest.QuestDifficulty diff, long coins, int xp,
                                              long expiry, Random random) {

        // Find another village to deliver to
        List<tterrag1112.life_in_the_village.Village.Village> others =
                data.getAllVillages().stream()
                        .filter(v -> !v.getId().equals(village.getId()))
                        .toList();

        if (others.isEmpty()) {
            // Fall back to gather if no other villages
            return generateGatherQuest(guildId, diff, coins,
                    xp, expiry, random);
        }

        var target = others.get(random.nextInt(others.size()));
        var items = List.of(Items.WRITTEN_BOOK, Items.MAP,
                Items.PAPER, Items.EMERALD);
        var item = items.get(random.nextInt(items.size()));

        Quest quest = new Quest(UUID.randomUUID(), guildId,
                Quest.QuestType.DELIVER, diff,
                "Deliver to " + target.getName(),
                "Deliver a package to the village of "
                        + target.getName() + ".",
                coins, xp, expiry);
        quest.setTargetItem(item);
        quest.setTargetVillageId(target.getId());
        return quest;
    }

    private static Quest generateEscortQuest(UUID guildId,
                                             Village village, Quest.QuestDifficulty diff,
                                             long coins, int xp, long expiry, Random random) {

        Quest quest = new Quest(UUID.randomUUID(), guildId,
                Quest.QuestType.ESCORT, diff,
                "Escort a Villager",
                "Protect a villager as they travel to their destination. "
                        + "Keep them safe from harm.",
                coins, xp, expiry);
        return quest;
    }

    private static Quest.QuestType randomType(Random random) {
        Quest.QuestType[] types = Quest.QuestType.values();
        return types[random.nextInt(types.length)];
    }
}