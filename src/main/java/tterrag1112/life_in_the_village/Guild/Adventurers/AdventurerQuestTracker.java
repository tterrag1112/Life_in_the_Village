package tterrag1112.life_in_the_village.Guild.Adventurers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guild.Quest;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.UUID;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class AdventurerQuestTracker {

    // -------------------------------------------------------------------------
    // Single death event handler — covers all cases
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        LivingEntity killed = event.getEntity();

        // Case 1 — an adventurer NPC died
        if (killed instanceof TownspersonMob npc
                && npc.isAdventurerGroupMember()) {
            handleAdventurerDeath(npc, level);
            return;
        }

        // Case 2 — a mob was killed, check if it counts for a hunt quest
        String killedId = killed.getType().builtInRegistryHolder()
                .key().registry().toString();

        if (event.getSource().getEntity() instanceof TownspersonMob adventurer
                && adventurer.isAdventurerGroupMember()) {
            handleAdventurerKill(adventurer, killedId, level);
            return;
        }

        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            handlePlayerKillNearGroup(player, killedId, level);
        }
    }

    // -------------------------------------------------------------------------
    // Adventurer death
    // -------------------------------------------------------------------------

    private static void handleAdventurerDeath(TownspersonMob npc,
                                              ServerLevel level) {
        UUID groupId = npc.getGroupId().orElse(null);
        if (groupId == null) return;

        AdventurerSavedData data = AdventurerSavedData.get(level);
        AdventurerGroup group = data.getGroup(groupId).orElse(null);
        if (group == null) return;

        group.getSpawnedEntityIds().remove(npc.getUUID());

        // Issue warning if a player killed this adventurer
        if (npc.getLastHurtByMob() instanceof ServerPlayer killer) {
            VillageWarningSystem.issueWarning(
                    killer.getUUID(),
                    group.getHomeVillageId(),
                    level,
                    VillageSavedData.get(level));
        }

        long livingMembers = group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .filter(e -> ((LivingEntity) e).isAlive())
                .count();

        if (livingMembers == 0) {
            failQuestSpawned(group, data, level);
        } else {
            group.setSuccessChanceModifier(
                    Math.max(0.1, group.getSuccessChanceModifier() - 0.15));
            updateGroupActivity(group,
                    "Lost a member! (" + livingMembers + " remaining)",
                    level);
            data.setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Mob kill hooks
    // -------------------------------------------------------------------------

    private static void handleAdventurerKill(TownspersonMob adventurer,
                                             String killedTypeId,
                                             ServerLevel level) {
        UUID groupId = adventurer.getGroupId().orElse(null);
        if (groupId == null) return;

        AdventurerSavedData data = AdventurerSavedData.get(level);
        AdventurerGroup group = data.getGroup(groupId).orElse(null);
        if (group == null) return;

        Quest quest = group.getActiveQuest();
        if (quest == null) return;
        if (quest.getType() != Quest.QuestType.HUNT) return;
        if (!killedTypeId.equals(quest.getTargetMob())) return;

        int newCount = quest.getCurrentCount() + 1;
        quest.setCurrentCount(newCount);

        updateGroupActivity(group,
                "Hunting: " + newCount + "/" + quest.getTargetCount(),
                level);

        if (newCount >= quest.getTargetCount()) {
            completeQuestSpawned(group, data, level);
        }

        data.setDirty();
    }

    private static void handlePlayerKillNearGroup(ServerPlayer player,
                                                  String killedTypeId,
                                                  ServerLevel level) {
        AdventurerSavedData data = AdventurerSavedData.get(level);

        data.getAllGroups().stream()
                .filter(AdventurerGroup::isSpawned)
                .filter(g -> g.getActiveQuest() != null)
                .filter(g -> g.getActiveQuest().getType()
                        == Quest.QuestType.HUNT)
                .filter(g -> killedTypeId.equals(
                        g.getActiveQuest().getTargetMob()))
                .filter(g -> g.getCurrentPos()
                        .closerThan(player.blockPosition(), 32))
                .findFirst()
                .ifPresent(group -> {
                    Quest quest = group.getActiveQuest();
                    int newCount = quest.getCurrentCount() + 1;
                    quest.setCurrentCount(newCount);

                    updateGroupActivity(group,
                            "Hunting: " + newCount
                                    + "/" + quest.getTargetCount(),
                            level);

                    if (newCount >= quest.getTargetCount()) {
                        completeQuestSpawned(group, data, level);
                    }
                    data.setDirty();
                });
    }

    // -------------------------------------------------------------------------
    // Biome check — called from AdventurerSavedData tick
    // -------------------------------------------------------------------------

    public static void checkExploreQuest(AdventurerGroup group,
                                         ServerLevel level,
                                         AdventurerSavedData data) {
        if (!group.isSpawned()) return;
        Quest quest = group.getActiveQuest();
        if (quest == null) return;
        if (quest.getType() != Quest.QuestType.EXPLORE) return;

        var biomeHolder = level.getBiome(group.getCurrentPos());
        String currentBiome = biomeHolder.unwrapKey()
                .map(k -> k.registry().toString())
                .orElse("");

        if (currentBiome.equals(quest.getTargetBiome())) {
            quest.setCurrentCount(1);
            quest.setTargetCount(1);
            completeQuestSpawned(group, data, level);
        }
    }

    // -------------------------------------------------------------------------
    // Quest completion / failure
    // -------------------------------------------------------------------------

    public static void completeQuestSpawned(AdventurerGroup group,
                                            AdventurerSavedData data,
                                            ServerLevel level) {
        Quest quest = group.getActiveQuest();
        if (quest == null) return;

        quest.setStatus(Quest.QuestStatus.COMPLETED);
        group.setState(AdventurerGroup.GroupState.RETURNING);
        group.setStateChangeTick(level.getGameTime());

        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e != null)
                .forEach(e -> spawnParticles(level,
                        e.blockPosition(), true));

        updateGroupActivity(group, "Quest complete! Returning...", level);
        data.setDirty();
    }

    public static void failQuestSpawned(AdventurerGroup group,
                                        AdventurerSavedData data,
                                        ServerLevel level) {
        Quest quest = group.getActiveQuest();
        if (quest != null) {
            quest.setStatus(Quest.QuestStatus.FAILED);
        }

        boolean canDie = quest != null && switch (quest.getType()) {
            case HUNT, ESCORT -> true;
            default           -> false;
        };

        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e != null)
                .forEach(e -> spawnParticles(level,
                        e.blockPosition(), false));

        if (canDie) {
            group.setDead(true);
        } else {
            group.setActiveQuest(null);
            group.setState(AdventurerGroup.GroupState.RETURNING);
            group.setStateChangeTick(level.getGameTime());
            updateGroupActivity(group,
                    "Quest failed. Returning...", level);
        }

        data.setDirty();
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

    private static void spawnParticles(ServerLevel level,
                                       BlockPos pos, boolean success) {
        for (int i = 0; i < 12; i++) {
            double offsetX = (level.getRandom().nextDouble() - 0.5) * 1.5;
            double offsetZ = (level.getRandom().nextDouble() - 0.5) * 1.5;
            level.sendParticles(
                    success
                            ? ParticleTypes.HAPPY_VILLAGER
                            : ParticleTypes.ANGRY_VILLAGER,
                    pos.getX() + 0.5 + offsetX,
                    pos.getY() + 2.0,
                    pos.getZ() + 0.5 + offsetZ,
                    1, 0, 0, 0, 0.1
            );
        }
    }

    private static void triggerVillageWarning(AdventurerGroup group,
                                              ServerPlayer player,
                                              ServerLevel level) {
        VillageSavedData villageData = VillageSavedData.get(level);
        VillageWarningSystem.issueWarning(
                player.getUUID(),
                group.getHomeVillageId(),
                level,
                villageData);
    }
}