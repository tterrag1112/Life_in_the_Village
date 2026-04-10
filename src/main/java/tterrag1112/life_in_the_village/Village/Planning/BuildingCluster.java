// src/main/java/tterrag1112/life_in_the_village/Village/Planning/BuildingCluster.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of buildings to be placed near each other as a unit.
 *
 * <h3>Cluster shapes</h3>
 * <ul>
 *   <li>{@code TIGHT_RING} — buildings around a small ring centered on
 *       the cluster anchor. Used for civic buildings — reads as a plaza.</li>
 *   <li>{@code LINEAR_ROW} — buildings along a short line perpendicular
 *       to the village axis. Reads as a row of houses on a street.</li>
 *   <li>{@code LOOSE} — buildings randomly distributed within a radius.
 *       Reads as a workshop district.</li>
 * </ul>
 */
public class BuildingCluster {

    public enum Shape { TIGHT_RING, LINEAR_ROW, LOOSE }

    private final int clusterId;
    private final BuildingZone zone;
    private final Shape shape;
    private final List<VillageTypeData.StarterBuilding> members = new ArrayList<>();
    private BlockPos anchor;


    public BuildingCluster(int clusterId, BuildingZone zone, Shape shape) {
        this.clusterId = clusterId;
        this.zone = zone;
        this.shape = shape;
    }

    public int getClusterId() { return clusterId; }
    public BuildingZone getZone() { return zone; }
    public Shape getShape() { return shape; }
    public List<VillageTypeData.StarterBuilding> getMembers() { return members; }
    public void addMember(VillageTypeData.StarterBuilding sb) { members.add(sb); }    public BlockPos getAnchor() { return anchor; }
    public void setAnchor(BlockPos pos) { this.anchor = pos; }

    /**
     * Default cluster shape for a zone.
     */
    public static Shape defaultShapeFor(BuildingZone zone) {
        return switch (zone) {
            case CIVIC -> Shape.TIGHT_RING;
            case RESIDENTIAL -> Shape.LINEAR_ROW;
            case PRODUCTION -> Shape.LOOSE;
            case AGRICULTURAL -> Shape.LOOSE;
            case DEFENSIVE -> Shape.LOOSE;
        };
    }
}