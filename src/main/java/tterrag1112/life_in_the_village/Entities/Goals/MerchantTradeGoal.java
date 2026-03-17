package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class MerchantTradeGoal extends Goal {

    private final TownspersonMob entity;
    private int regenerateTimer = 0;
    private static final int REGENERATE_INTERVAL = 1200;

    public MerchantTradeGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;

        return true; }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;

        return super.canContinueToUse();
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;
        regenerateTimer++;
        if (regenerateTimer >= REGENERATE_INTERVAL) {
            regenerateTimer = 0;
            entity.regenerateOffers(serverLevel);
        }
    }
}
