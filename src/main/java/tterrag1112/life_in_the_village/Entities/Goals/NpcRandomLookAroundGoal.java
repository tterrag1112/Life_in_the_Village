package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;

import java.util.EnumSet;

/**
 * Phase 6.3.4.5.1 — vanilla {@link RandomLookAroundGoal} sets
 * {@code Goal.Flag.MOVE | Goal.Flag.LOOK}; the MOVE claim is
 * defensive in Mojang's code (it halts the entity briefly while
 * rotating the head) but it makes any Brain behavior that gates on
 * {@link tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard
 * #canSteerNavigation} unable to start while the look-around is
 * running — which is almost continuously for an idle NPC.
 *
 * <p>This subclass strips the MOVE flag. LOOK alone is the
 * semantically correct claim: the goal only rotates the head. With
 * MOVE removed, production / sell / buy behaviors can grab the nav
 * channel even when the ambient look-around is active.</p>
 */
public class NpcRandomLookAroundGoal extends RandomLookAroundGoal {

    public NpcRandomLookAroundGoal(Mob mob) {
        super(mob);
        // Replace the inherited {MOVE, LOOK} with LOOK only.
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }
}
