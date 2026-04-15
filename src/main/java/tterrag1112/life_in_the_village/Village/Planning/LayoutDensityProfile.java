// src/main/java/tterrag1112/life_in_the_village/Village/Planning/LayoutDensityProfile.java
package tterrag1112.life_in_the_village.Village.Planning;

/**
 * Governs how densely a village is laid out.
 * Lower levels → sparse, spread-out hamlets.
 * Higher levels → compact, feature-rich towns.
 */
public class LayoutDensityProfile {

    // -------------------------------------------------------------------------
    // Factory — derive from a village "level" (1-based, 1 = new hamlet)
    // -------------------------------------------------------------------------

    public static LayoutDensityProfile forLevel(int villageLevel) {
        // Clamp to a sensible range
        int lvl = Math.max(1, Math.min(villageLevel, 10));

        // Spacing: how far apart buildings are placed on the ring
        // (lower level = bigger gaps)
        int spacing = lerp(28, 10, lvl, 1, 10);

        // How much random jitter is applied to each building's
        // ideal ring position (higher level = tighter = less jitter)
        int jitter = lerp(14, 4, lvl, 1, 10);

        // Ring radii for the placement rings around the town hall
        int ring1Radius = lerp(20, 12, lvl, 1, 10);
        int ring2Radius = lerp(40, 22, lvl, 1, 10);
        int ring3Radius = lerp(60, 34, lvl, 1, 10);

        // Farm plot offset from centre (farms go in the flat
        // direction — further out for lower-level villages)
        int farmOffset = lerp(36, 20, lvl, 1, 10);

        // Number of decoration clusters
        int decorationClusters = lerp(2, 8, lvl, 1, 10);

        // Path width in blocks (1 = narrow dirt track, 2 = full path)
        int pathWidth = lvl >= 5 ? 2 : 1;

        // Whether the planner should include a second ring
        boolean useRing2 = lvl >= 3;
        // Whether to include a third ring (large settlements)
        boolean useRing3 = lvl >= 7;

        // Minimum gap between any two footprints
        int minGap = lerp(6, 2, lvl, 1, 10);

        String label = lvl <= 2 ? "SPARSE"
                : lvl <= 5 ? "MODERATE"
                : lvl <= 8 ? "DENSE"
                : "PACKED";

        return new LayoutDensityProfile(
                lvl, spacing, jitter,
                ring1Radius, ring2Radius, ring3Radius,
                farmOffset, decorationClusters,
                pathWidth, useRing2, useRing3, minGap, label);
    }

    // -------------------------------------------------------------------------
    // Linear interpolation helper
    // -------------------------------------------------------------------------

    private static int lerp(int atMin, int atMax,
                            int value, int min, int max) {
        float t = (float)(value - min) / (max - min);
        return Math.round(atMin + t * (atMax - atMin));
    }

    // -------------------------------------------------------------------------
    // Fields (all final, constructed by factory)
    // -------------------------------------------------------------------------

    private final int     villageLevel;
    private final int     buildingSpacing;
    private final int     buildingJitter;
    private final int     ring1Radius;
    private final int     ring2Radius;
    private final int     ring3Radius;
    private final int     farmOffset;
    private final int     decorationClusters;
    private final int     pathWidth;
    private final boolean useRing2;
    private final boolean useRing3;
    private final int     minGap;
    private final String  label;

    private LayoutDensityProfile(int villageLevel,
                                 int buildingSpacing,
                                 int buildingJitter,
                                 int ring1Radius,
                                 int ring2Radius,
                                 int ring3Radius,
                                 int farmOffset,
                                 int decorationClusters,
                                 int pathWidth,
                                 boolean useRing2,
                                 boolean useRing3,
                                 int minGap,
                                 String label) {
        this.villageLevel       = villageLevel;
        this.buildingSpacing    = buildingSpacing;
        this.buildingJitter     = buildingJitter;
        this.ring1Radius        = ring1Radius;
        this.ring2Radius        = ring2Radius;
        this.ring3Radius        = ring3Radius;
        this.farmOffset         = farmOffset;
        this.decorationClusters = decorationClusters;
        this.pathWidth          = pathWidth;
        this.useRing2           = useRing2;
        this.useRing3           = useRing3;
        this.minGap             = minGap;
        this.label              = label;
    }


    // Also add this constant used below:

    // Accessors
    public int     getVillageLevel()       { return villageLevel;       }
    public int     getBuildingSpacing()    { return buildingSpacing;    }
    public int     getBuildingJitter()     { return buildingJitter;     }
    public int     getRing1Radius()        { return ring1Radius;        }
    public int     getRing2Radius()        { return ring2Radius;        }
    public int     getRing3Radius()        { return ring3Radius;        }
    public int     getFarmOffset()         { return farmOffset;         }
    public int     getDecorationClusters() { return decorationClusters; }
    public int     getPathWidth()          { return pathWidth;          }
    public boolean isUseRing2()            { return useRing2;           }
    public boolean isUseRing3()            { return useRing3;           }
    public int     getMinGap()             { return minGap;             }
    public String  label()                 { return label;              }
}

