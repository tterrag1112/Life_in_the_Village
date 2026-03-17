package tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class GuardAttackGoal extends TargetGoal {

    private final TownspersonMob guard;
    private LivingEntity target;
    private static final double DETECTION_RANGE = 16.0;

    public GuardAttackGoal(TownspersonMob guard) {
        super(guard, true);
        this.guard = guard;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(guard.level() instanceof ServerLevel serverLevel)) return false;

        AABB searchArea = guard.getBoundingBox().inflate(DETECTION_RANGE);

        // Check for hostile mobs
        List<Monster> monsters = serverLevel.getEntitiesOfClass(Monster.class, searchArea);
        if (!monsters.isEmpty()) {
            target = monsters.get(0);
            return true;
        }

        // Check for hostile players
        Optional<Village> village = guard.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(serverLevel).getVillageByName(name));

        if (village.isPresent()) {
            List<Player> players = serverLevel.getEntitiesOfClass(Player.class, searchArea);
            for (Player player : players) {
                if (village.get().isHostile(player.getUUID())) {
                    target = player;
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        return guard.distanceTo(target) <= DETECTION_RANGE * 1.5;
    }

    @Override
    public void start() {
        guard.setTarget(target);
        super.start();
    }

    @Override
    public void stop() {
        target = null;
        guard.setTarget(null);
        super.stop();
    }
}
