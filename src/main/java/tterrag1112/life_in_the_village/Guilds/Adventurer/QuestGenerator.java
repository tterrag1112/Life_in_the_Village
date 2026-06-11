package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Quests.Objective;
import tterrag1112.life_in_the_village.Quests.Quest;
import tterrag1112.life_in_the_village.Quests.QuestDifficulty;
import tterrag1112.life_in_the_village.Quests.QuestGiver;
import tterrag1112.life_in_the_village.Quests.QuestReward;
import tterrag1112.life_in_the_village.Quests.QuestStatus;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * F2b-2 — guild quest generation re-seated onto the F2 base. Produces one {@link Quest}
 * per difficulty tier for a guild's OFFERED pool (giver {@code GUILD:<guildId>}). The
 * difficulty-scaled flavour tables (items / mobs / biomes / counts) are preserved from
 * the legacy generator; only the output type changed (legacy {@code Guilds.Adventurer.Quest}
 * → F2 {@link Quest}). Coin/XP magnitudes ride on {@link QuestDifficulty} and are paid at
 * turn-in ({@link GuildQuests#turnIn}); the rewards list stays empty so the engine's
 * auto-reward path never double-pays a guild quest.
 */
public class QuestGenerator {

    static final long QUEST_EXPIRY_TICKS = 24000L * 3; // 3 days

    /** The five guild difficulty tiers (F2 {@link QuestDifficulty} minus {@code NONE}). */
    private static final QuestDifficulty[] TIERS = {
            QuestDifficulty.EASY, QuestDifficulty.MEDIUM, QuestDifficulty.HARD,
            QuestDifficulty.ELITE, QuestDifficulty.LEGENDARY
    };

    /** The guild quest kinds the generator can mint (mirrors the legacy QuestType set). */
    private enum Kind { GATHER, HUNT, EXPLORE, DELIVER, ESCORT }

    /**
     * Generates one OFFERED quest per difficulty tier for {@code guildId}'s village. The
     * caller posts them into the guild pool via {@link tterrag1112.life_in_the_village.Quests.QuestSavedData#offerGuildQuest}.
     */
    public static List<Quest> generateForGuild(
            ServerLevel level, UUID guildId, Village village,
            VillageSavedData data, long currentTick) {

        List<Quest> quests = new ArrayList<>();
        Random random = new Random(guildId.hashCode() + currentTick);
        long deadline = currentTick + QUEST_EXPIRY_TICKS;

        for (QuestDifficulty diff : TIERS) {
            Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
            Quest quest = generateQuest(guildId, village, data, kind, diff, deadline, random);
            if (quest != null) quests.add(quest);
        }
        return quests;
    }

    private static Quest generateQuest(UUID guildId, Village village, VillageSavedData data,
                                       Kind kind, QuestDifficulty diff, long deadline,
                                       Random random) {
        return switch (kind) {
            case GATHER  -> gather(guildId, diff, deadline, random);
            case HUNT    -> hunt(guildId, diff, deadline, random);
            case EXPLORE -> explore(guildId, diff, deadline, random);
            case DELIVER -> deliver(guildId, village, data, diff, deadline, random);
            case ESCORT  -> escort(guildId, village, diff, deadline);
        };
    }

    // ── Kind builders ────────────────────────────────────────────────────────

    private static Quest gather(UUID guildId, QuestDifficulty diff, long deadline, Random random) {
        List<Item> items = switch (diff) {
            case EASY   -> List.of(Items.OAK_LOG, Items.COBBLESTONE, Items.WHEAT, Items.CARROT);
            case MEDIUM -> List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COAL, Items.BREAD);
            case HARD   -> List.of(Items.DIAMOND, Items.EMERALD, Items.LAPIS_LAZULI, Items.REDSTONE);
            case ELITE  -> List.of(Items.NETHERITE_SCRAP, Items.BLAZE_ROD, Items.ENDER_PEARL);
            default     -> List.of(Items.NETHERITE_INGOT, Items.SHULKER_SHELL, Items.ELYTRA);
        };
        Item item = items.get(random.nextInt(items.size()));
        int count = switch (diff) {
            case EASY -> 8 + random.nextInt(8);
            case MEDIUM -> 16 + random.nextInt(16);
            case HARD -> 32 + random.nextInt(16);
            case ELITE -> 48 + random.nextInt(16);
            default -> 64;
        };
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        String label = item.getDescriptionId();
        return build(guildId, diff, deadline,
                new Objective.Gather(itemId, count),
                "Gather " + count + " " + label,
                "Collect " + count + " " + label + " and bring them to the guild.");
    }

    private static Quest hunt(UUID guildId, QuestDifficulty diff, long deadline, Random random) {
        List<String> mobs = switch (diff) {
            case EASY   -> List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:spider");
            case MEDIUM -> List.of("minecraft:creeper", "minecraft:witch", "minecraft:pillager");
            case HARD   -> List.of("minecraft:vindicator", "minecraft:ravager", "minecraft:blaze");
            case ELITE  -> List.of("minecraft:elder_guardian", "minecraft:wither_skeleton");
            default     -> List.of("minecraft:wither", "minecraft:ender_dragon");
        };
        String mob = mobs.get(random.nextInt(mobs.size()));
        String mobName = mob.replace("minecraft:", "").replace("_", " ");
        int count = switch (diff) {
            case EASY -> 5 + random.nextInt(5);
            case MEDIUM -> 10 + random.nextInt(5);
            case HARD -> 15 + random.nextInt(5);
            case ELITE -> 5 + random.nextInt(3);
            default -> 1;
        };
        return build(guildId, diff, deadline,
                new Objective.Hunt(mob, 0, count),
                "Hunt " + count + " " + mobName + "(s)",
                "Eliminate " + count + " " + mobName + " threatening the region.");
    }

    private static Quest explore(UUID guildId, QuestDifficulty diff, long deadline, Random random) {
        List<String> biomes = switch (diff) {
            case EASY   -> List.of("minecraft:plains", "minecraft:forest", "minecraft:birch_forest");
            case MEDIUM -> List.of("minecraft:desert", "minecraft:savanna", "minecraft:swamp");
            case HARD   -> List.of("minecraft:jungle", "minecraft:mushroom_fields", "minecraft:badlands");
            case ELITE  -> List.of("minecraft:deep_dark", "minecraft:nether_wastes", "minecraft:soul_sand_valley");
            default     -> List.of("minecraft:end_barrens", "minecraft:end_highlands");
        };
        String biome = biomes.get(random.nextInt(biomes.size()));
        String biomeName = biome.replace("minecraft:", "").replace("_", " ");
        return build(guildId, diff, deadline,
                new Objective.Explore(biome),
                "Explore the " + biomeName,
                "Travel to a " + biomeName + " biome and return to report your findings.");
    }

    private static Quest deliver(UUID guildId, Village village, VillageSavedData data,
                                 QuestDifficulty diff, long deadline, Random random) {
        List<Village> others = data.getAllVillages().stream()
                .filter(v -> !v.getId().equals(village.getId()))
                .toList();
        if (others.isEmpty()) return gather(guildId, diff, deadline, random);  // fall back

        Village target = others.get(random.nextInt(others.size()));
        List<Item> items = List.of(Items.WRITTEN_BOOK, Items.MAP, Items.PAPER, Items.EMERALD);
        Item item = items.get(random.nextInt(items.size()));
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        return build(guildId, diff, deadline,
                new Objective.Deliver(itemId, target.getId().toString()),
                "Deliver to " + target.getName(),
                "Deliver a package to the village of " + target.getName() + ".");
    }

    private static Quest escort(UUID guildId, Village village, QuestDifficulty diff, long deadline) {
        // Legacy escort was a return-to-the-guild turn-in; the F2 Escort objective is
        // satisfied by an ESCORT_ARRIVED notify whose subject is the destination village
        // (fired by GuildWorkerBehavior when the player stands in that village).
        return build(guildId, diff, deadline,
                new Objective.Escort("", village.getId().toString(), false),
                "Escort a Villager",
                "Protect a villager as they travel to their destination. Keep them safe from harm.");
    }

    // ── Shared assembly ────────────────────────────────────────────────────────

    private static Quest build(UUID guildId, QuestDifficulty diff, long deadline,
                               Objective objective, String title, String description) {
        return new Quest(
                UUID.randomUUID(),
                new QuestGiver(QuestGiver.Type.GUILD, guildId.toString()),
                title, description,
                List.of(objective),
                QuestStatus.OFFERED,
                List.<QuestReward>of(),     // rewards paid at turn-in (GuildQuests.turnIn)
                Quest.Scope.PLAYER,
                deadline,
                diff,
                Optional.of(diff.minRank()));
    }
}
