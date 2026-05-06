package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.ForestRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.HighGround;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Region;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.StoneRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.List;
import java.util.Optional;

/**
 * {@code /litv featuremap [<radius>]} — runs V2 Layer 1's terrain
 * feature map at the player's position and prints a summary.
 *
 * <p>Default radius: 150 blocks (matches the spec).
 *
 * <p>Output:
 * <ul>
 *   <li>Cell counts by category</li>
 *   <li>Aggregate query results (largestFlatRegion, riverPath,
 *       coastline, highGroundRegions, forestRegions, stoneExposedRegions)</li>
 *   <li>Viability tier preview against the design-doc thresholds</li>
 *   <li>Total scan time</li>
 * </ul>
 */
public final class FeatureMapCommand {

    private static final int DEFAULT_RADIUS = 150;

    private FeatureMapCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("featuremap")
                        .executes(ctx -> run(ctx, DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 512))
                                .executes(ctx -> run(ctx,
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos centre = player.blockPosition();

        send(src, "[litv-featuremap] scanning radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");
        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);

        // -- Cell counts ----------------------------------------------------
        int[] counts = fmap.categoryCounts();
        StringBuilder catSummary = new StringBuilder("cells: total=")
                .append(fmap.gridSize() * fmap.gridSize());
        for (BlockCategory c : BlockCategory.values()) {
            catSummary.append(' ').append(c.name())
                    .append('=').append(counts[c.ordinal()]);
        }
        send(src, catSummary.toString());

        // -- Largest flat region -------------------------------------------
        Optional<Region> flat = fmap.largestFlatRegion();
        if (flat.isPresent()) {
            Region r = flat.get();
            send(src, "largestFlatRegion: centre="
                    + r.centre().getX() + "," + r.centre().getZ()
                    + " area=" + r.area()
                    + " bbox=[" + r.minX() + "," + r.minZ()
                    + " .. " + r.maxX() + "," + r.maxZ() + "]");
        } else {
            send(src, "largestFlatRegion: absent");
        }

        // -- River path -----------------------------------------------------
        Optional<List<BlockPos>> river = fmap.riverPath();
        if (river.isPresent() && !river.get().isEmpty()) {
            List<BlockPos> rp = river.get();
            BlockPos a = rp.get(0);
            BlockPos b = rp.get(rp.size() - 1);
            send(src, "riverPath: " + rp.size() + " points, from "
                    + a.getX() + "," + a.getZ() + " to "
                    + b.getX() + "," + b.getZ());
        } else {
            send(src, "riverPath: absent");
        }

        // -- Coastline ------------------------------------------------------
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isPresent() && !coast.get().isEmpty()) {
            send(src, "coastline: " + coast.get().size() + " points");
        } else {
            send(src, "coastline: absent");
        }

        // -- High ground ----------------------------------------------------
        List<HighGround> hills = fmap.highGroundRegions();
        if (hills.isEmpty()) {
            send(src, "highGround: 0 regions");
        } else {
            HighGround biggest = hills.get(0);
            for (HighGround h : hills) {
                if (h.area() > biggest.area()) biggest = h;
            }
            BlockPos peak = biggest.peak();
            send(src, "highGround: " + hills.size()
                    + " regions, biggest area=" + biggest.area()
                    + " prominence=" + biggest.prominence()
                    + " at " + peak.getX() + "," + peak.getY() + "," + peak.getZ());
        }

        // -- Forest regions -------------------------------------------------
        List<ForestRegion> forests = fmap.forestRegions();
        int totalForestArea = 0;
        int totalForestCore = 0;
        for (ForestRegion f : forests) {
            totalForestArea += f.area();
            totalForestCore += f.coreCells();
        }
        send(src, "forestRegions: " + forests.size()
                + " regions, total area=" + totalForestArea
                + " core cells=" + totalForestCore);

        // -- Stone-exposed regions -----------------------------------------
        List<StoneRegion> stoneRegs = fmap.stoneExposedRegions();
        if (stoneRegs.isEmpty()) {
            send(src, "stoneExposedRegions: 0 regions");
        } else {
            StringBuilder areas = new StringBuilder();
            StringBuilder slopes = new StringBuilder();
            int show = Math.min(5, stoneRegs.size());
            for (int i = 0; i < show; i++) {
                if (i > 0) { areas.append(", "); slopes.append(", "); }
                areas.append(stoneRegs.get(i).area());
                slopes.append(stoneRegs.get(i).avgSlope());
            }
            String tail = stoneRegs.size() > show
                    ? " (+" + (stoneRegs.size() - show) + " more)" : "";
            send(src, "stoneExposedRegions: " + stoneRegs.size()
                    + " regions, areas=[" + areas + "]"
                    + " avgSlopes=[" + slopes + "]" + tail);
        }

        // -- Viability preview ---------------------------------------------
        send(src, "viability: " + viabilityTierFor(flat.map(Region::area).orElse(0),
                fmap.cellSize()));

        // -- Timing ---------------------------------------------------------
        send(src, "scan time: " + fmap.scanTimeMs() + " ms");

        return 1;
    }

    /**
     * Convert a flat-region cell count to a design-doc viability tier
     * preview. Thresholds are the recommended numbers from
     * ADAPTIVE-VILLAGE-DESIGN.md §"Viability Tier" — area in BLOCKS,
     * computed as cells × cellSize².
     *
     * <ul>
     *   <li>≥ 80×80 = 6400 → CITY</li>
     *   <li>≥ 40×40 = 1600 → TOWN</li>
     *   <li>≥ 20×20 =  400 → HAMLET</li>
     *   <li>≥ 10×10 =  100 → OUTPOST</li>
     *   <li>otherwise UNVIABLE</li>
     * </ul>
     */
    private static String viabilityTierFor(int flatCellArea, int cellSize) {
        int blockArea = flatCellArea * cellSize * cellSize;
        String tier;
        if (blockArea >= 80 * 80) tier = "CITY";
        else if (blockArea >= 40 * 40) tier = "TOWN";
        else if (blockArea >= 20 * 20) tier = "HAMLET";
        else if (blockArea >= 10 * 10) tier = "OUTPOST";
        else tier = "UNVIABLE";
        return "Largest flat: " + flatCellArea + " cells (" + blockArea
                + " blocks²) → " + tier + " tier candidate";
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
