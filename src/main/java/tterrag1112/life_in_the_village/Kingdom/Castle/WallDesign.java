package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.List;

/**
 * A named procedural wall face design.
 *
 * Defines the surface decoration rules applied to one face of a RoomStack.
 * All block references use palette slot names — no hardcoded blocks.
 *
 * Rules are applied in order after the base fill is placed.
 * Each rule can overwrite, add, or remove blocks.
 *
 * In TMA terms, this is what a "theme's wall design" does — it takes a
 * rectangular canvas (the wall face) and applies architectural decoration.
 */
public record WallDesign(
        String id,
        List<DecoRule> rules
) {

    // =========================================================================
    // Decoration rules
    // =========================================================================

    /**
     * A single decoration rule applied to a wall face.
     * Rules are data-driven so new ones can be added by datapacks.
     */
    public sealed interface DecoRule
            permits DecoRule.StringCourse, DecoRule.Pilaster,
            DecoRule.Window, DecoRule.Quoin,
            DecoRule.CorbelTable, DecoRule.NicheFill,
            DecoRule.RandomNoise {

        /** Apply this rule to the wall face canvas. */
        void apply(FaceCanvas canvas, ArchitectPalette palette, RandomSource rng);

        // ---- String course -----------------------------------------------

        /**
         * Horizontal band of trim blocks at a fixed relative Y within each floor.
         * relativeY: 0.0 = floor bottom, 1.0 = floor top
         */
        record StringCourse(double relativeY, int thickness,
                            String slot) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                int y = (int)(canvas.floorHeight() * relativeY);
                for (int t = 0; t < thickness; t++) {
                    for (int x = 0; x < canvas.faceLength(); x++) {
                        canvas.set(x, y + t, palette.pick(slot, rng));
                    }
                }
            }
        }

        // ---- Pilaster -------------------------------------------------------

        /**
         * Vertical accent strips at regular horizontal intervals.
         * Uses support slot for the shaft, support_cap at the top.
         */
        record Pilaster(int interval, int width) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                for (int x = 0; x < canvas.faceLength(); x++) {
                    if (x % interval < width) {
                        // Shaft
                        for (int y = 1; y < canvas.floorHeight() - 1; y++) {
                            canvas.set(x, y, palette.pick("support", rng));
                        }
                        // Cap
                        canvas.set(x, canvas.floorHeight() - 1,
                                palette.pick("support_cap", rng));
                        // Base
                        canvas.set(x, 0, palette.pick("support_cap", rng));
                    }
                }
            }
        }

        // ---- Window ---------------------------------------------------------

        /**
         * Cut a window opening at regular horizontal intervals.
         * windowStyle drives the shape; window_fill fills the opening.
         */
        record Window(int interval, int horizontalOffset,
                      double verticalCenter,
                      StyleDetailConfig.WindowStyle windowStyle,
                      boolean fillWithPane) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                int midY = (int)(canvas.floorHeight() * verticalCenter);

                for (int x = horizontalOffset; x < canvas.faceLength();
                     x += interval) {
                    applyWindow(canvas, palette, rng, x, midY);
                }
            }

            private void applyWindow(FaceCanvas canvas, ArchitectPalette palette,
                                     RandomSource rng, int cx, int midY) {
                BlockState air  = Blocks.AIR.defaultBlockState();
                BlockState fill = fillWithPane
                        ? palette.pick("window_fill", rng)
                        : air;

                switch (windowStyle) {
                    case ARROW_SLIT -> {
                        canvas.set(cx, midY,     air);
                        canvas.set(cx, midY + 1, air);
                    }
                    case CROSS_SLIT -> {
                        canvas.set(cx, midY - 1, air);
                        canvas.set(cx, midY,     air);
                        canvas.set(cx, midY + 1, air);
                        if (cx > 0)
                            canvas.set(cx - 1, midY, air);
                        if (cx < canvas.faceLength() - 1)
                            canvas.set(cx + 1, midY, air);
                    }
                    case WIDE_ARCH -> {
                        // 2-wide 3-tall opening with a filled interior
                        for (int dy = -1; dy <= 1; dy++) {
                            canvas.set(cx, midY + dy, fill);
                            if (cx + 1 < canvas.faceLength())
                                canvas.set(cx + 1, midY + dy, fill);
                        }
                        // Arch top — stair facing INTO the wall (inward face),
                        // upside-down so it forms a pointed arch head.
                        // Use the outward face direction to determine which way is "in".
                        // Outward is stored on the canvas; stair faces the opposite (inward).
                        Direction inward = canvas.outward().getOpposite();
                        // Inward must be a horizontal direction — it always is since outward is horizontal
                        BlockState archTop = palette.stair("stair", inward, Half.TOP, rng);
                        canvas.set(cx,     midY + 2, archTop);
                        if (cx + 1 < canvas.faceLength())
                            canvas.set(cx + 1, midY + 2, archTop);
                    }
                    case CIRCULAR -> {
                        // Diamond/cross pattern
                        canvas.set(cx, midY,     fill);
                        canvas.set(cx, midY + 1, fill);
                        if (cx > 0) {
                            canvas.set(cx - 1, midY, fill);
                        }
                        if (cx < canvas.faceLength() - 1) {
                            canvas.set(cx + 1, midY, fill);
                        }
                    }
                    case NONE -> {}
                }
            }
        }

        // ---- Quoin ----------------------------------------------------------

        /**
         * Accent blocks at the corners of the face, alternating every other Y.
         */
        record Quoin(int depth, String slot) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                for (int y = 0; y < canvas.floorHeight(); y++) {
                    if (y % 2 == 0) continue; // alternate courses
                    for (int d = 0; d < depth; d++) {
                        canvas.set(d, y, palette.pick(slot, rng));
                        canvas.set(canvas.faceLength() - 1 - d, y,
                                palette.pick(slot, rng));
                    }
                }
            }
        }

        // ---- Corbel table ---------------------------------------------------

        /**
         * Projecting slab/stair blocks under the wall top — machicolation effect.
         * Applied at the topmost Y of the floor.
         */
        record CorbelTable(int spacing) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                int topY = canvas.floorHeight() - 1;
                for (int x = 0; x < canvas.faceLength(); x++) {
                    if (x % spacing == 0) {
                        // Corbel — slab projecting outward (recorded but not
                        // actually moved outward here — the placer handles that)
                        canvas.set(x, topY,
                                palette.slab("slab", SlabType.TOP));
                    }
                }
            }
        }

        // ---- Niche fill -----------------------------------------------------

        /**
         * Recessed alcoves — the inverse of pilasters.
         * Replaces fill with fill_alt at regular intervals for visual depth.
         */
        record NicheFill(int interval, int nichWidth,
                         int nichHeight) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                int midY = canvas.floorHeight() / 2;
                for (int x = interval / 2; x < canvas.faceLength(); x += interval) {
                    for (int nx = 0; nx < nichWidth; nx++) {
                        int cx = x + nx - nichWidth / 2;
                        if (cx < 0 || cx >= canvas.faceLength()) continue;
                        for (int ny = 0; ny < nichHeight; ny++) {
                            int cy = midY - nichHeight / 2 + ny;
                            if (cy < 0 || cy >= canvas.floorHeight()) continue;
                            canvas.set(cx, cy,
                                    palette.pick("fill_alt", rng));
                        }
                    }
                }
            }
        }

        // ---- Random noise ---------------------------------------------------

        /**
         * Randomly substitutes fill with fill_alt at a given probability.
         * Adds natural variation without a fixed pattern.
         */
        record RandomNoise(float probability) implements DecoRule {
            @Override
            public void apply(FaceCanvas canvas, ArchitectPalette palette,
                              RandomSource rng) {
                for (int x = 0; x < canvas.faceLength(); x++) {
                    for (int y = 0; y < canvas.floorHeight(); y++) {
                        if (rng.nextFloat() < probability) {
                            canvas.set(x, y, palette.pick("fill_alt", rng));
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Face canvas — the 2D surface a design writes to
    // =========================================================================

    /**
     * Represents a single wall face as a 2D grid of block states.
     * Rules write into this canvas; the placer reads it out to world coordinates.
     *
     * X axis = along the wall face length
     * Y axis = height within the current floor (0 = floor bottom)
     */
    public static class FaceCanvas {

        private BlockState[][] grid; // [x][y]
        private final int faceLength;
        private final int floorHeight;
        private final Direction outward;
        private final BlockPos origin; // world position of [0][0]

        public FaceCanvas(int faceLength, int floorHeight,
                          Direction outward, BlockPos worldOrigin,
                          BlockState defaultFill) {
            this.faceLength  = faceLength;
            this.floorHeight = floorHeight;
            this.outward     = outward;
            this.origin      = worldOrigin;
            this.grid        = new BlockState[faceLength][floorHeight];

            // Initialise with default fill
            for (int x = 0; x < faceLength; x++) {
                for (int y = 0; y < floorHeight; y++) {
                    grid[x][y] = defaultFill;
                }
            }
        }

        public void set(int x, int y, BlockState state) {
            if (x < 0 || x >= faceLength || y < 0 || y >= floorHeight) return;
            grid[x][y] = state;
        }

        public BlockState get(int x, int y) {
            if (x < 0 || x >= faceLength || y < 0 || y >= floorHeight) {
                return Blocks.AIR.defaultBlockState();
            }
            return grid[x][y];
        }

        public int faceLength()  { return faceLength;  }
        public int floorHeight() { return floorHeight; }
        public Direction outward() { return outward; }

        /** Write canvas contents to the world. */
        public void flush(LevelAccessor level) {
            Direction along = outward.getClockWise();
            for (int x = 0; x < faceLength; x++) {
                for (int y = 0; y < floorHeight; y++) {
                    BlockPos pos = origin
                            .relative(along, x)
                            .above(y);
                    level.setBlock(pos, grid[x][y], Block.UPDATE_CLIENTS);
                }
            }
        }
        public void stretch(int targetHeight,
                            java.util.Map<Integer, LayerTrait> layerTraits) {
            if (targetHeight == this.floorHeight) return;

            // Build a new grid at the target height
            BlockState[][] newGrid = new BlockState[faceLength][targetHeight];

            // Fill with the default fill state first
            BlockState defaultFill = grid[0][0];
            for (int x = 0; x < faceLength; x++)
                for (int y = 0; y < targetHeight; y++)
                    newGrid[x][y] = defaultFill;

            // Collect source rows tagged by trait
            // Process from bottom to top, inserting/removing rows as needed
            java.util.List<int[]> sourceRows = new java.util.ArrayList<>();
            // Each entry: [sourceY, insertCount]
            // insertCount = how many times to repeat this row in the output

            int delta = targetHeight - this.floorHeight;

            if (delta > 0) {
                // Need to expand — clone rows tagged CLONE_THRICE or CLONE_ONCE
                java.util.List<Integer> cloneThrice = new java.util.ArrayList<>();
                java.util.List<Integer> cloneOnce   = new java.util.ArrayList<>();

                for (int y = 0; y < this.floorHeight; y++) {
                    LayerTrait trait = layerTraits.getOrDefault(y, LayerTrait.STANDARD);
                    if (trait == LayerTrait.CLONE_THRICE) cloneThrice.add(y);
                    else if (trait == LayerTrait.CLONE_ONCE) cloneOnce.add(y);
                }

                // Distribute extra rows preferring CLONE_THRICE
                int[] extraPerRow = new int[this.floorHeight];
                int remaining = delta;

                // Each CLONE_THRICE can absorb up to 3 extra
                for (int y : cloneThrice) {
                    int give = Math.min(3, remaining);
                    extraPerRow[y] = give;
                    remaining -= give;
                    if (remaining <= 0) break;
                }
                // Overflow to CLONE_ONCE
                if (remaining > 0) {
                    for (int y : cloneOnce) {
                        int give = Math.min(1, remaining);
                        extraPerRow[y] = give;
                        remaining -= give;
                        if (remaining <= 0) break;
                    }
                }

                // Build new grid by copying source rows with their extras
                int destY = 0;
                for (int srcY = 0; srcY < this.floorHeight && destY < targetHeight; srcY++) {
                    // Copy original row
                    for (int x = 0; x < faceLength; x++)
                        newGrid[x][destY] = grid[x][srcY];
                    destY++;
                    // Insert clones
                    for (int clone = 0; clone < extraPerRow[srcY] && destY < targetHeight; clone++) {
                        for (int x = 0; x < faceLength; x++)
                            newGrid[x][destY] = grid[x][srcY];
                        destY++;
                    }
                }

            } else {
                // Need to compress — remove OPTIONAL rows first
                java.util.List<Integer> optional = new java.util.ArrayList<>();
                for (int y = 0; y < this.floorHeight; y++) {
                    LayerTrait trait = layerTraits.getOrDefault(y, LayerTrait.STANDARD);
                    if (trait == LayerTrait.OPTIONAL) optional.add(y);
                }

                java.util.Set<Integer> skip = new java.util.HashSet<>();
                int toRemove = -delta;
                for (int y : optional) {
                    if (toRemove <= 0) break;
                    skip.add(y);
                    toRemove--;
                }

                int destY = 0;
                for (int srcY = 0; srcY < this.floorHeight && destY < targetHeight; srcY++) {
                    if (skip.contains(srcY)) continue;
                    for (int x = 0; x < faceLength; x++)
                        newGrid[x][destY] = grid[x][srcY];
                    destY++;
                }
            }

            // Replace grid with stretched version
            // Can't assign to final field — need a mutable wrapper
            // This requires making grid non-final in FaceCanvas:
            //   private BlockState[][] grid;  (remove final)
            for (int x = 0; x < faceLength; x++)
                for (int y = 0; y < Math.min(targetHeight, this.floorHeight); y++)
                    grid[x][y] = newGrid[x][y];
        }

    }

    // =========================================================================
    // Built-in designs
    // =========================================================================

    /**
     * Plain: no decoration, just random fill noise.
     * Baseline for all styles.
     */
    public static final WallDesign PLAIN = new WallDesign("plain",
            List.of(new DecoRule.RandomNoise(0.15f)));

    /**
     * Norman: string course at mid-height, random fill noise.
     */
    public static final WallDesign NORMAN = new WallDesign("norman",
            List.of(
                    new DecoRule.RandomNoise(0.12f),
                    new DecoRule.StringCourse(0.5, 1, "trim"),
                    new DecoRule.Window(5, 2,
                            0.65, StyleDetailConfig.WindowStyle.ARROW_SLIT, false)
            ));

    /**
     * Norman heavy: battered base string course + quoins + arrow slits.
     */
    public static final WallDesign NORMAN_HEAVY = new WallDesign("norman_heavy",
            List.of(
                    new DecoRule.RandomNoise(0.10f),
                    new DecoRule.StringCourse(0.25, 1, "trim"),
                    new DecoRule.StringCourse(0.75, 1, "trim"),
                    new DecoRule.Quoin(1, "trim"),
                    new DecoRule.Window(5, 2,
                            0.65, StyleDetailConfig.WindowStyle.ARROW_SLIT, false)
            ));

    /**
     * Banded: horizontal bands at 1/4, 1/2, 3/4 height.
     * Byzantine, Mughal.
     */
    public static final WallDesign BANDED = new WallDesign("banded",
            List.of(
                    new DecoRule.RandomNoise(0.08f),
                    new DecoRule.StringCourse(0.25, 1, "trim"),
                    new DecoRule.StringCourse(0.5,  2, "fill_alt"),
                    new DecoRule.StringCourse(0.75, 1, "trim"),
                    new DecoRule.Window(4, 1,
                            0.6, StyleDetailConfig.WindowStyle.CIRCULAR, true)
            ));

    /**
     * Pilastered: vertical pilaster strips every 6 blocks.
     * French Gothic, Crystal Spire.
     */
    public static final WallDesign PILASTERED = new WallDesign("pilastered",
            List.of(
                    new DecoRule.RandomNoise(0.08f),
                    new DecoRule.Pilaster(6, 1),
                    new DecoRule.StringCourse(0.0,  1, "trim"),
                    new DecoRule.StringCourse(1.0,  1, "trim"),
                    new DecoRule.Window(5, 2,
                            0.65, StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));

    /**
     * Moorish: pilasters + horseshoe window + niche decoration.
     */
    public static final WallDesign MOORISH = new WallDesign("moorish",
            List.of(
                    new DecoRule.RandomNoise(0.06f),
                    new DecoRule.Pilaster(5, 1),
                    new DecoRule.StringCourse(0.3, 1, "trim"),
                    new DecoRule.StringCourse(0.7, 1, "trim"),
                    new DecoRule.NicheFill(5, 1, 3),
                    new DecoRule.Window(5, 2,
                            0.6, StyleDetailConfig.WindowStyle.CIRCULAR, true)
            ));

    /**
     * Japanese: clean horizontal banding, wide windows, minimal noise.
     */
    public static final WallDesign JAPANESE = new WallDesign("japanese",
            List.of(
                    new DecoRule.RandomNoise(0.04f),
                    new DecoRule.StringCourse(0.0, 2, "trim"),
                    new DecoRule.StringCourse(0.5, 1, "fill_alt"),
                    new DecoRule.StringCourse(0.95, 2, "trim"),
                    new DecoRule.Window(4, 1,
                            0.55, StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));

    /**
     * Dwarven: heavy quoins, corbel table, no windows, deep noise.
     */
    public static final WallDesign DWARVEN = new WallDesign("dwarven",
            List.of(
                    new DecoRule.RandomNoise(0.18f),
                    new DecoRule.Quoin(2, "trim"),
                    new DecoRule.StringCourse(0.5, 1, "trim"),
                    new DecoRule.CorbelTable(3)
            ));

    /**
     * Dark fantasy: string courses + corbel table + cross slits.
     */
    public static final WallDesign DARK = new WallDesign("dark",
            List.of(
                    new DecoRule.RandomNoise(0.12f),
                    new DecoRule.StringCourse(0.33, 1, "trim"),
                    new DecoRule.StringCourse(0.66, 1, "trim"),
                    new DecoRule.CorbelTable(2),
                    new DecoRule.Window(5, 2,
                            0.65, StyleDetailConfig.WindowStyle.CROSS_SLIT, false)
            ));

    /**
     * Elvish: gentle noise, organic feel, wide arched windows.
     */
    public static final WallDesign ELVISH = new WallDesign("elvish",
            List.of(
                    new DecoRule.RandomNoise(0.10f),
                    new DecoRule.StringCourse(0.5, 1, "fill_alt"),
                    new DecoRule.NicheFill(6, 2, 2),
                    new DecoRule.Window(4, 1,
                            0.6, StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));

    /**
     * Ruined: heavy noise, no pattern (everything is broken).
     */
    public static final WallDesign RUINED = new WallDesign("ruined",
            List.of(new DecoRule.RandomNoise(0.35f)));

    /**
     * Formal palace: strong banding every 3 blocks, pilasters every 6,
     * large arched windows with glass. Clean and symmetrical.
     * Inspired by Cattingham Palace theme.
     */
    public static final WallDesign FORMAL_PALACE = new WallDesign("formal_palace",
            List.of(
                    new DecoRule.RandomNoise(0.04f),
                    new DecoRule.StringCourse(0.0,  1, "trim"),
                    new DecoRule.StringCourse(0.33, 1, "trim"),
                    new DecoRule.StringCourse(0.66, 1, "trim"),
                    new DecoRule.StringCourse(1.0,  1, "trim"),
                    new DecoRule.Pilaster(6, 1),
                    new DecoRule.Quoin(1, "trim"),
                    new DecoRule.Window(6, 2, 0.55,
                            StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));

    /**
     * Grand palace: very clean, minimal noise, quoins, wide arched windows,
     * string courses only at floor boundaries. Bright and formal.
     */
    public static final WallDesign GRAND_PALACE = new WallDesign("grand_palace",
            List.of(
                    new DecoRule.RandomNoise(0.02f),
                    new DecoRule.StringCourse(0.0,  2, "trim"),
                    new DecoRule.StringCourse(1.0,  2, "trim"),
                    new DecoRule.Quoin(2, "trim"),
                    new DecoRule.Pilaster(8, 1),
                    new DecoRule.Window(4, 1, 0.5,
                            StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));

    /**
     * Rustic manor: warm asymmetric banding, timber-frame-feel pilasters,
     * moderate noise for organic variation.
     */
    public static final WallDesign RUSTIC_MANOR = new WallDesign("rustic_manor",
            List.of(
                    new DecoRule.RandomNoise(0.12f),
                    new DecoRule.StringCourse(0.25, 1, "trim"),
                    new DecoRule.StringCourse(0.75, 1, "trim"),
                    new DecoRule.Pilaster(5, 1),
                    new DecoRule.Window(5, 2, 0.6,
                            StyleDetailConfig.WindowStyle.WIDE_ARCH, true)
            ));
}