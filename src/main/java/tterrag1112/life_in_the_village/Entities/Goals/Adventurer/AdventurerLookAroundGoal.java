package tterrag1112.life_in_the_village.Entities.Goals.Adventurer;

import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.EnumSet;

public class AdventurerLookAroundGoal extends Goal {

    private final TownspersonMob entity;
    private double lookX;
    private double lookZ;
    private int lookTime;

    public AdventurerLookAroundGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return entity.getGroupId().isPresent()
                && entity.getRandom().nextFloat() < 0.02f;
    }

    @Override
    public boolean canContinueToUse() {
        return lookTime > 0;
    }

    @Override
    public void start() {
        double angle = entity.getRandom().nextDouble() * Math.PI * 2;
        lookX = Math.cos(angle);
        lookZ = Math.sin(angle);
        lookTime = 20 + entity.getRandom().nextInt(20);
    }

    @Override
    public void tick() {
        lookTime--;
        entity.getLookControl().setLookAt(
                entity.getX() + lookX,
                entity.getEyeY(),
                entity.getZ() + lookZ);
    }
}