package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;

/**
 * Declarative sector specification. The planner translates each
 * declaration to a full {@code Sector} record (via
 * {@code pctx.offerSector}) during realisation. Defaults applied at
 * translation time: empty slots, canGrow=false, growth=null,
 * exclusionShape=null. {@code parentEdge} maps to
 * {@code parentEdgeId} (-1 if null).
 *
 * <p>Field name mapping vs. the existing {@code Sector} record:
 * {@code zone → zoneHint}, {@code cap → capacity},
 * {@code maxFp → expectedMaxFootprint}.
 *
 * <p>Phase A's flip on the spec's draft adds {@code parentEdge}: the
 * measurement log shows sectors tied to specific edges (e.g.
 * {@code crossroads_arm_0} belongs to edge#0). Floating sectors
 * (rings) leave {@code parentEdge} null.
 */
public record SectorDeclaration(
        String id,
        SectorRole role,
        BuildingZone zone,
        int cap,
        int maxFp,
        @Nullable EdgeRef parentEdge
) {}
