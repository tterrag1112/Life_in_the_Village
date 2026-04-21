package tterrag1112.life_in_the_village.Profession.Tasks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.PlayerWorkplace;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized completion detection for typed tasks.
 *
 * <h3>Responsibilities</h3>
 * Listens to gameplay events (block break, crafting, mob death) and advances
 * any active {@link PlayerWorkplace.WorkAssignment} whose {@link WorkTaskType}
 * matches the event.
 *
 * <p>This handler is <em>in addition to</em> the existing quota detection in
 * {@code WorkplaceAssignmentManager.onItemDelivered} (which covers GATHER and
 * DELIVER when items are deposited into a building chest). Events handled
 * here cover completion triggers that don't involve item deposits:</p>
 *
 * <ul>
 *   <li>{@link WorkTaskType#HARVEST} — crop block broken at full growth</li>
 *   <li>{@link WorkTaskType#CRAFT} — target item appears in a crafting result</li>
 *   <li>{@link WorkTaskType#HUNT} — target mob killed by the player</li>
 * </ul>
 *
 * <h3>Why not merge with {@code WorkplaceAssignmentManager}?</h3>
 * Keeping completion detection in its own class means new task types can be
 * added by writing a new {@code @SubscribeEvent} handler here without touching
 * the assignment manager. The manager only deals with issuing tasks and paying
 * rewards; this class deals with detecting progress.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class TaskCompletionEvents {

    private TaskCompletionEvents() {}

    // =========================================================================
    // HARVEST — crop break at full growth
    // =========================================================================

    @SubscribeEvent
    public static void onCropHarvest(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        BlockState state = event.getState();
        if (!state.is(net.minecraft.tags.BlockTags.CROPS)) return;
        if (!isFullyGrown(state)) return;

        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);
        boolean advanced = advanceMatching(data,
                t -> t.taskType() == WorkTaskType.HARVEST, 1);
        if (advanced) player.setData(ModData.PROFESSION_DATA, data);
    }

    // =========================================================================
    // CRAFT — target item produced by a crafting recipe
    // =========================================================================

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack result = event.getCrafting();
        if (result.isEmpty()) return;

        String itemId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        int count = result.getCount();

        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);
        boolean advanced = advanceMatching(data, t ->
                        (t.taskType() == WorkTaskType.CRAFT
                                || t.taskType() == WorkTaskType.SMELT
                                || t.taskType() == WorkTaskType.PROCESS)
                                && t.hasTargetItem()
                                && t.targetItem().equals(itemId),
                count);
        if (advanced) player.setData(ModData.PROFESSION_DATA, data);
    }

    // =========================================================================
    // HUNT — target mob killed
    // =========================================================================

    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;

        LivingEntity killed = event.getEntity();
        if (killed instanceof ServerPlayer) return;

        String mobId = BuiltInRegistries.ENTITY_TYPE
                .getKey(killed.getType()).toString();

        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);
        boolean advanced = advanceMatching(data, t ->
                        t.taskType() == WorkTaskType.HUNT
                                && t.hasTargetItem()
                                && t.targetItem().equals(mobId),
                1);
        if (advanced) player.setData(ModData.PROFESSION_DATA, data);
    }

    // =========================================================================
    // Shared progression helper
    // =========================================================================

    /**
     * Advances every task across every workplace that matches {@code predicate}
     * by {@code delta}. Returns true if at least one task was modified.
     */
    private static boolean advanceMatching(
            PlayerProfessionData data,
            java.util.function.Predicate<PlayerWorkplace.WorkAssignment> predicate,
            int delta) {
        if (delta <= 0) return false;

        boolean modified = false;
        for (var workplaceEntry : data.getAllWorkplaces().entrySet()) {
            PlayerProfession profession = workplaceEntry.getKey();
            PlayerWorkplace.WorkplaceEntry entry = workplaceEntry.getValue();

            List<PlayerWorkplace.WorkAssignment> current = entry.activeTasks();
            if (current.isEmpty()) continue;

            List<PlayerWorkplace.WorkAssignment> updated = new ArrayList<>(current.size());
            boolean thisWorkplaceChanged = false;
            for (PlayerWorkplace.WorkAssignment t : current) {
                if (!predicate.test(t) || t.isComplete()) {
                    updated.add(t);
                    continue;
                }
                int newCount = Math.min(t.currentCount() + delta, t.targetCount());
                updated.add(t.withProgress(newCount));
                thisWorkplaceChanged = true;
            }
            if (thisWorkplaceChanged) {
                data.setWorkplace(profession, entry.withTasks(updated));
                modified = true;
            }
        }
        return modified;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isFullyGrown(BlockState state) {
        var ageProperty = state.getProperties().stream()
                .filter(p -> p.getName().equals("age"))
                .findFirst();
        if (ageProperty.isEmpty()) return false;
        try {
            var prop = (net.minecraft.world.level.block.state.properties.IntegerProperty)
                    ageProperty.get();
            int age = state.getValue(prop);
            int max = prop.getPossibleValues().stream()
                    .mapToInt(Integer::intValue).max().orElse(0);
            return age >= max;
        } catch (Exception e) {
            return false;
        }
    }
}
