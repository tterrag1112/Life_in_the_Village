package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.EnumSet;
import java.util.List;

public class GreetingGoal extends Goal {

    private static final int GREET_RANGE = 5;
    private static final int GREET_DURATION = 60;
    private static final int COOLDOWN = 1200; // don't greet same area too often

    private final TownspersonMob entity;
    private TownspersonMob greetTarget = null;
    private int greetTimer = 0;
    private int cooldown = 0;

    public GreetingGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (entity.shouldBeHome()) return false;
        if (entity.isWorkTime()) return false;
        if (entity.isChild()) return false;
        if (cooldown > 0) { cooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(GREET_RANGE),
                mob -> mob != entity
                        && !mob.isChild()
                        && mob.getAssignedVillageName()
                        .map(name -> name.equals(
                                entity.getAssignedVillageName().orElse("")))
                        .orElse(false)        );

        if (nearby.isEmpty()) return false;

        greetTarget = nearby.get(
                entity.getRandom().nextInt(nearby.size()));
        return entity.getRandom().nextInt(20) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return greetTarget != null
                && greetTarget.isAlive()
                && greetTimer < GREET_DURATION;
    }

    @Override
    public void start() {
        entity.setCurrentActivity("Greeting " + greetTarget.getFirstName());
        greetTimer = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (greetTarget == null) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        greetTimer++;

        // Look at each other
        entity.getLookControl().setLookAt(greetTarget,
                30f, 30f);

        // Wave arm at start of greeting
        if (greetTimer == 5) {
            entity.swing(InteractionHand.MAIN_HAND);
            greetTarget.swing(InteractionHand.MAIN_HAND);
        }

        // Crouch bow briefly
        if (greetTimer >= 10 && greetTimer <= 25) {
            entity.setShiftKeyDown(true);
        } else {
            entity.setShiftKeyDown(false);
        }

        // Play greeting sound
        if (greetTimer == 15) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_YES,
                    SoundSource.NEUTRAL, 0.5f,
                    0.9f + entity.getRandom().nextFloat() * 0.2f);
        }

        // Response sound from target
        if (greetTimer == 30) {
            level.playSound(null, greetTarget.blockPosition(),
                    SoundEvents.VILLAGER_AMBIENT,
                    SoundSource.NEUTRAL, 0.5f,
                    0.9f + entity.getRandom().nextFloat() * 0.2f);
            greetTarget.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void stop() {
        entity.setShiftKeyDown(false);
        greetTarget = null;
        greetTimer = 0;
        cooldown = COOLDOWN;
        entity.clearCurrentActivity();
    }
}