package tterrag1112.life_in_the_village.Events;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadSpeedModifier;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadUnderfootDetector;

import java.util.List;

/**
 * Applies a transient movement-speed bonus to players walking on maintained roads.
 *
 * <p>Every 10 ticks ({@link #interval()}), each online player is checked:
 * <ol>
 *   <li>{@link RoadUnderfootDetector} finds the realized edge underfoot (or none).</li>
 *   <li>{@link RoadSpeedModifier} computes the bonus from the edge's tier and maintenance.</li>
 *   <li>The bonus is applied (or updated) as a transient {@link AttributeModifier} on
 *       {@link Attributes#MOVEMENT_SPEED} using operation {@code ADD_MULTIPLIED_BASE}.</li>
 *   <li>If the player stepped off the road (bonus == 0), the modifier is removed.</li>
 * </ol>
 *
 * <p>The modifier uses a stable {@link ResourceLocation} ID so it can be safely updated
 * and removed between ticks without leaving orphaned modifiers.
 */
public final class PlayerRoadSpeedSystem implements TickSubsystem {

    /** Stable modifier ID — must not clash with any other modifier in the game. */
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("life_in_the_village", "road_speed_bonus");

    @Override
    public String name() { return "road_speed"; }

    @Override
    public int interval() { return 10; }

    @Override
    public int priority() { return 120; }

    @Override
    public void tick(TickContext ctx) {
        WorldRoadGraph graph = WorldRoadSavedData.get(ctx.level()).getGraph();
        List<ServerPlayer> players = ctx.level().getServer().getPlayerList().getPlayers();

        for (ServerPlayer player : players) {
            AttributeInstance attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr == null) continue;

            RoadEdge edge = RoadUnderfootDetector.detectEdge(player, graph);
            double bonus = edge != null
                    ? RoadSpeedModifier.speedBonus(edge.getTier(), edge.getMaintenance())
                    : 0.0;

            if (bonus > 0.0) {
                attr.addOrUpdateTransientModifier(
                        new AttributeModifier(MODIFIER_ID, bonus,
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            } else {
                attr.removeModifier(MODIFIER_ID);
            }
        }
    }
}
