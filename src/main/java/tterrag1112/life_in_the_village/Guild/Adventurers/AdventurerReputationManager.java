package tterrag1112.life_in_the_village.Guild.Adventurers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guild.GuildRank;
import tterrag1112.life_in_the_village.Guild.PlayerGuildData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AdventurerReputationManager {

    // Cache tier per player per group so we can detect changes
    // and apply the short delay before behavior updates.
    // Key: groupId + playerId, Value: current tier
    private static final Map<String, AdventurerReputationTier> tierCache
            = new HashMap<>();

    // Tick when the tier last changed — used for the delay
    private static final Map<String, Long> tierChangeTick = new HashMap<>();

    private static final long TIER_CHANGE_DELAY = 60L; // 3 seconds

    // -------------------------------------------------------------------------
    // Tier resolution
    // -------------------------------------------------------------------------

    public static AdventurerReputationTier getTier(ServerPlayer player,
                                                   AdventurerGroup group,
                                                   ServerLevel level) {
        VillageSavedData villageData = VillageSavedData.get(level);
        PlayerGuildData guildData = PlayerGuildData.get(level);

        // Get village rep from home village
        int villageRep = villageData.getVillageById(group.getHomeVillageId())
                .map(v -> v.getReputation(player.getUUID()))
                .orElse(0);

        // Get guild rank
        GuildRank rank = guildData.getMember(player.getUUID())
                .map(m -> m.currentRank())
                .orElse(GuildRank.BRONZE);

        return AdventurerReputationTier.resolve(villageRep, rank);
    }

    /**
     * Returns the effective tier, respecting the change delay.
     * Returns the OLD tier until the delay has elapsed.
     */
    public static AdventurerReputationTier getEffectiveTier(
            ServerPlayer player, AdventurerGroup group,
            ServerLevel level) {
        String key = cacheKey(player.getUUID(), group.getGroupId());
        AdventurerReputationTier current = getTier(player, group, level);
        AdventurerReputationTier cached  = tierCache.get(key);
        long currentTick = level.getGameTime();

        if (cached == null) {
            // First time seeing this player — no delay needed
            tierCache.put(key, current);
            tierChangeTick.put(key, currentTick);
            return current;
        }

        if (cached != current) {
            // Tier changed — start the delay if not already started
            if (!tierChangeTick.containsKey(key)
                    || tierCache.get(key) == cached) {
                tierChangeTick.put(key, currentTick);
                tierCache.put(key, current);
            }
            // Check if delay has elapsed
            long changed = tierChangeTick.getOrDefault(key, currentTick);
            if (currentTick - changed >= TIER_CHANGE_DELAY) {
                return current;
            }
            return cached; // still showing old tier during delay
        }

        return current;
    }

    // -------------------------------------------------------------------------
    // Behavior application
    // -------------------------------------------------------------------------

    /**
     * Called when a spawned group first detects a nearby player.
     * Applies all appropriate behaviors for the current tier.
     */
    public static void applyTierBehavior(ServerPlayer player,
                                         AdventurerGroup group,
                                         ServerLevel level,
                                         AdventurerSavedData data) {
        AdventurerReputationTier tier = getEffectiveTier(
                player, group, level);

        switch (tier) {
            case HOSTILE -> {
                makeGroupHostile(group, player, level, data);
                triggerVillageWarning(group, player, level);
            }
            case WARY, UNPOPULAR -> {
                // Groups ignore the player — no interaction
                updateGroupActivity(group,
                        "...", level);
            }
            case NEUTRAL -> {
                // Standard greeting already handled by
                // AdventurerInteractGoal
            }
            case FRIENDLY -> {
                if (tier.willAssistInCombat()) {
                    registerCombatAssist(group, player, level);
                }
            }
            case WELL_KNOWN -> {
                recognizePlayer(group, player, level);
                if (tier.willAssistProactively()) {
                    registerCombatAssist(group, player, level);
                }
            }
            case RESPECTED -> {
                recognizePlayer(group, player, level);
                registerCombatAssist(group, player, level);
                applyRespectedBuffs(player, group, level);
            }
            case HONORED -> {
                recognizePlayer(group, player, level);
                registerCombatAssist(group, player, level);
                applyRespectedBuffs(player, group, level);
                offerGroupJoin(group, player, level);
            }
            case LEGEND -> {
                recognizePlayer(group, player, level);
                registerCombatAssist(group, player, level);
                applyRespectedBuffs(player, group, level);
                offerGroupJoin(group, player, level);
                offerLeadership(group, player, level);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Hostile behavior
    // -------------------------------------------------------------------------

    private static void makeGroupHostile(AdventurerGroup group,
                                         ServerPlayer player,
                                         ServerLevel level,
                                         AdventurerSavedData data) {
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .forEach(npc -> {
                    npc.setTarget(player);
                    npc.setCurrentActivity("Stay away!");
                });
    }

    private static void triggerVillageWarning(AdventurerGroup group,
                                              ServerPlayer player,
                                              ServerLevel level) {
        VillageSavedData villageData = VillageSavedData.get(level);

        // Find the home village and start the warning spread
        villageData.getVillageById(group.getHomeVillageId())
                .ifPresent(village -> {
                    villageData.addPlayerWarning(
                            player.getUUID(),
                            village.getId(),
                            level.getGameTime()
                    );
                });
    }

    // -------------------------------------------------------------------------
    // Positive behaviors
    // -------------------------------------------------------------------------

    private static void recognizePlayer(AdventurerGroup group,
                                        ServerPlayer player,
                                        ServerLevel level) {
        // Leader calls out the player by name
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName()
                                + "] Well met, "
                                + player.getName().getString()
                                + "! Your reputation precedes you."),
                        false));
    }

    private static void registerCombatAssist(AdventurerGroup group,
                                             ServerPlayer player,
                                             ServerLevel level) {
        // Store the player UUID on the group so combat goals
        // know to defend them
        group.setAlliedPlayerId(player.getUUID());
    }

    private static void applyRespectedBuffs(ServerPlayer player,
                                            AdventurerGroup group,
                                            ServerLevel level) {
        // Only apply once per encounter — check if already buffed
        if (group.hasBuffedPlayer(player.getUUID())) return;

        AdventurerReputationTier tier = getEffectiveTier(
                player, group, level);

        // RESPECTED — Strength I for 2 minutes
        player.addEffect(new MobEffectInstance(
                MobEffects.STRENGTH, 2400, 0, false, true));

        // HONORED — also Resistance I
        if (tier == AdventurerReputationTier.HONORED
                || tier == AdventurerReputationTier.LEGEND) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.RESISTANCE, 2400, 0,
                    false, true));
        }

        // LEGEND — also Regeneration I
        if (tier == AdventurerReputationTier.LEGEND) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.REGENERATION, 2400, 0,
                    false, true));
        }

        group.markPlayerBuffed(player.getUUID());

        player.displayClientMessage(
                Component.literal("The adventurers' presence emboldens you!"),
                true);
    }

    private static void offerGroupJoin(AdventurerGroup group,
                                       ServerPlayer player,
                                       ServerLevel level) {
        if (group.hasJoinedPlayer()) return;

        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName()
                                + "] You are welcome to travel with us, "
                                + player.getName().getString()
                                + ". Use /adventurer join to join our group."),
                        false));
    }

    private static void offerLeadership(AdventurerGroup group,
                                        ServerPlayer player,
                                        ServerLevel level) {
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName()
                                + "] Your legend is known to all of us. "
                                + "We would be honored to follow your lead. "
                                + "Use /adventurer lead to take command."),
                        false));
    }

    // -------------------------------------------------------------------------
    // Tip building
    // -------------------------------------------------------------------------

    public static String buildTipMessage(ServerPlayer player,
                                         AdventurerGroup group,
                                         ServerLevel level) {
        AdventurerReputationTier tier = getEffectiveTier(
                player, group, level);

        if (tier.willIgnorePlayer()) {
            return "...";
        }

        if (!tier.willShareBasicTips()) {
            return buildNeutralMessage(group);
        }

        if (tier.willShareExclusiveTips()) {
            return buildExclusiveTipMessage(group, level);
        }

        if (tier.willShareFullTips()) {
            return buildFullTipMessage(group);
        }

        return buildBasicTipMessage(group);
    }

    private static String buildNeutralMessage(AdventurerGroup group) {
        return switch (group.getState()) {
            case TRAVELLING -> "We're out on a contract. "
                    + "Safe travels.";
            case CAMPING    -> "Making camp. Move along.";
            case HUNTING    -> "We're busy. Watch yourself "
                    + "out here.";
            case EXPLORING  -> "Scouting the area.";
            case ESCORTING  -> "Busy. Don't interfere.";
            case RETURNING  -> "Heading back. Excuse us.";
        };
    }

    private static String buildBasicTipMessage(AdventurerGroup group) {
        var quest = group.getActiveQuest();
        if (quest == null) return "We're between contracts right now.";
        return "We're on a " + quest.getType().name().toLowerCase()
                + " contract out of "
                + group.getHomeVillageId().toString().substring(0, 8)
                + ". Good hunting out there.";
    }

    private static String buildFullTipMessage(AdventurerGroup group) {
        var quest = group.getActiveQuest();
        if (quest == null) return "Between contracts. Heading back "
                + "to the guild hall.";

        return "Contract: " + quest.getTitle()
                + "\nProgress: " + quest.getCurrentCount()
                + "/" + quest.getTargetCount()
                + "\nDifficulty: " + quest.getDifficulty().name()
                + "\nTarget area: "
                + group.getTargetPos().toShortString()
                + "\nTip: "
                + getDifficultyTip(quest.getDifficulty());
    }

    private static String buildExclusiveTipMessage(AdventurerGroup group,
                                                   ServerLevel level) {
        String base = buildFullTipMessage(group);

        // Add exclusive hint about nearby danger or rare quest
        String hint = generateExclusiveHint(group, level);
        return base + "\n\n[Exclusive] " + hint;
    }

    private static String generateExclusiveHint(AdventurerGroup group,
                                                ServerLevel level) {
        // Hints based on group state and quest
        var quest = group.getActiveQuest();
        if (quest == null) return "The guild master mentioned something "
                + "about unusual activity to the north. Worth investigating.";

        return switch (quest.getDifficulty()) {
            case ELITE     -> "Fair warning — we've seen "
                    + quest.getTargetMob()
                    + " in unusually large numbers. "
                    + "Don't go alone.";
            case LEGENDARY -> "This is the hardest contract "
                    + "we've ever taken. Whatever you do, "
                    + "don't engage " + quest.getTargetMob()
                    + " without preparation.";
            default        -> "The area around "
                    + group.getTargetPos().toShortString()
                    + " has been more dangerous than usual lately.";
        };
    }

    private static String getDifficultyTip(
            tterrag1112.life_in_the_village.Guild.Quest.QuestDifficulty d) {
        return switch (d) {
            case EASY      -> "Straightforward contract, "
                    + "shouldn't be any trouble.";
            case MEDIUM    -> "Moderate risk. Stay alert.";
            case HARD      -> "This one's dangerous. "
                    + "Come prepared.";
            case ELITE     -> "Elite contract. Most groups "
                    + "don't come back from these.";
            case LEGENDARY -> "Legendary. We may not "
                    + "return from this one.";
        };
    }

    // -------------------------------------------------------------------------
    // Village warning spread
    // -------------------------------------------------------------------------

    /**
     * Called from AdventurerSavedData tick — spreads warnings
     * from village to village over time.
     */
    public static void tickWarningSpread(ServerLevel level,
                                         VillageSavedData villageData,
                                         long currentTick) {
        // Every 2 in-game days, spread warnings to adjacent villages
        if (currentTick % 48000L != 0) return;

        villageData.getActiveWarnings().forEach((playerId, warnings) ->
                warnings.forEach((villageId, warnTick) -> {
                    // Find villages within 1000 blocks and spread
                    villageData.getVillageById(villageId)
                            .ifPresent(source -> {
                                BlockPos sourcePos = BlockPos.containing(
                                        source.getBounds(villageData)
                                                .map(b -> b.getCenter())
                                                .orElse(
                                                        net.minecraft.world.phys.Vec3.ZERO));

                                villageData.getAllVillages().stream()
                                        .filter(v -> !v.getId()
                                                .equals(villageId))
                                        .filter(v -> v.getBounds(villageData)
                                                .map(b -> BlockPos.containing(
                                                                b.getCenter())
                                                        .closerThan(sourcePos,
                                                                1000))
                                                .orElse(false))
                                        .filter(v -> !villageData
                                                .hasWarning(playerId,
                                                        v.getId()))
                                        .forEach(v -> villageData
                                                .addPlayerWarning(
                                                        playerId,
                                                        v.getId(),
                                                        currentTick));
                            });
                }));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void updateGroupActivity(AdventurerGroup group,
                                            String activity,
                                            ServerLevel level) {
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .forEach(npc -> npc.setCurrentActivity(activity));
    }

    private static String cacheKey(UUID playerId, UUID groupId) {
        return playerId.toString() + ":" + groupId.toString();
    }
}