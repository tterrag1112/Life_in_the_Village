package tterrag1112.life_in_the_village.Entities.Goals.Profession.Innkeeper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.EnumSet;
import java.util.List;

public class InnkeeperGoal extends Goal {

    private enum Phase {
        IDLE, TENDING_BAR, SERVING_FOOD
    }

    private static final int IDLE_COOLDOWN = 600;
    private static final int TEND_DURATION = 12000; // half a day at the bar
    private static final int SERVE_INTERVAL = 200;  // check for hungry NPCs every 10s
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int SERVE_RANGE = 12;

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int tendTimer = 0;
    private int serveTimer = 0;
    private Building inn = null;
    private BlockPos barPos = null; // where innkeeper stands

    public InnkeeperGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        inn = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.INN)
                .orElse(null);

        if (inn == null) return false;

        barPos = findBarPos(level);
        return barPos != null;
    }

    @Override
    public void start() {
        phase = Phase.TENDING_BAR;
        tendTimer = 0;
        serveTimer = 0;
        if (barPos != null) {
            entity.getNavigation().moveTo(
                    barPos.getX() + 0.5, barPos.getY(),
                    barPos.getZ() + 0.5, 1.0);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE && tendTimer < TEND_DURATION;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        tendTimer++;
        serveTimer++;

        switch (phase) {
            case TENDING_BAR  -> tendBar(level);
            case SERVING_FOOD -> serveFood(level);
            default -> {}
        }

        // Periodically check for hungry visiting NPCs
        if (serveTimer >= SERVE_INTERVAL) {
            serveTimer = 0;
            List<TownspersonMob> hungry = findHungryVisitors(level);
            if (!hungry.isEmpty()) {
                phase = Phase.SERVING_FOOD;
            }
        }

        if (tendTimer >= TEND_DURATION) goIdle();
    }

    private void tendBar(ServerLevel level) {
        if (barPos == null) { goIdle(); return; }

        double distSq = entity.distanceToSqr(
                barPos.getX(), barPos.getY(), barPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    barPos.getX() + 0.5, barPos.getY(),
                    barPos.getZ() + 0.5, 1.0);
        } else {
            entity.getNavigation().stop();
            // Look around slowly — idle tending behavior
            if (tendTimer % 60 == 0) {
                entity.getLookControl().setLookAt(
                        barPos.getX() + entity.getRandom().nextInt(5) - 2,
                        barPos.getY(),
                        barPos.getZ() + entity.getRandom().nextInt(5) - 2
                );
            }
        }
    }

    private void serveFood(ServerLevel level) {
        List<TownspersonMob> hungry = findHungryVisitors(level);
        if (hungry.isEmpty()) {
            phase = Phase.TENDING_BAR;
            return;
        }

        TownspersonMob target = hungry.get(0);
        double distSq = entity.distanceToSqr(target);

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(target, 1.0);
            return;
        }

        entity.getNavigation().stop();
        entity.getLookControl().setLookAt(
                target.getX(), target.getY() + target.getEyeHeight(),
                target.getZ());

        // Find food in inn storage
        ItemStack food = findFoodInStorage(level);
        if (food.isEmpty()) {
            phase = Phase.TENDING_BAR;
            return;
        }

        // Give food to NPC — add to their inventory
        BuildingStorageAccess.takeItem(
                level, inn, food.getItem(), 1);
        target.getPersonalInventory().addItem(
                new ItemStack(food.getItem(), 1));

        // Charge the NPC a small fee
        CurrencyValue mealCost = CurrencyValue.of(4); // 4 bronze per meal
        if (target.canAfford(mealCost)) {
            target.pay(entity, mealCost);
        }

        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        phase = Phase.TENDING_BAR;
    }

    @Override
    public void stop() { goIdle(); }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        entity.getNavigation().stop();
        tendTimer = 0;
        serveTimer = 0;
    }

    // --- Helpers ---

    private BlockPos findBarPos(ServerLevel level) {
        if (inn == null) return null;
        // Stand near the center of the inn ground floor
        BlockPos origin = inn.getShape().getOrigin();
        return origin.offset(
                inn.getShape().getWidth() / 2,
                1,
                inn.getShape().getLength() / 2
        );
    }

    private List<TownspersonMob> findHungryVisitors(ServerLevel level) {
        if (inn == null) return List.of();
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                inn.getShape().toAABB().inflate(SERVE_RANGE),
                mob -> mob != entity
                        && mob.isSocialTime()
                        && !mob.isWorkingBlocked()
                        && needsFood(mob)
        );
    }

    private boolean needsFood(TownspersonMob mob) {
        // Check if NPC has any food in personal inventory
        var inv = mob.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem()
                    .components().has(
                            net.minecraft.core.component.DataComponents.FOOD)) {
                return false; // already has food
            }
        }
        return true;
    }

    private ItemStack findFoodInStorage(ServerLevel level) {
        for (var container : BuildingStorageAccess.findInventories(level, inn)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && stack.getItem()
                        .components().has(
                                net.minecraft.core.component.DataComponents.FOOD)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
