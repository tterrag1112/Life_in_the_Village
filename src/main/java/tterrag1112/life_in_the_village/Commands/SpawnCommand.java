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
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ReconciliationEngine;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.MinimalSpawner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.OverlapAuditor;

import java.util.List;

/**
 * {@code /litv spawn [<radius>]} — runs the full V2 stack
 * (L1+L2+L3+L4+L5) at the player's position and physically
 * spawns the village in the world via {@link MinimalSpawner}.
 *
 * <p>This is V1 of V2 spawn integration: deliberately scoped to
 * buildings + roads + per-building pads + scoped vegetation
 * clearing only. NPC population, broad terrain modification,
 * decoration, plaza paving, and trade-route hookup are NOT
 * triggered.
 */
public final class SpawnCommand {

    private static final int DEFAULT_RADIUS = 100;

    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("spawn")
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
        long seed = level.getSeed();

        long t0 = System.currentTimeMillis();
        send(src, "[litv-spawn] running L1+L2+L3+L4+L5 radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        // Layers 1 + 2.
        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.INSTANCE.getDefault();
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " anchor=(" + siteCtx.anchor().getX() + ","
                + siteCtx.anchor().getY() + "," + siteCtx.anchor().getZ() + ")");

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            send(src, "tier=UNVIABLE — aborting spawn");
            return 0;
        }

        // Layers 3 + 4.
        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        BuildingSelector.SelectionResult sel =
                BuildingSelector.select(siteCtx, fmap, profile);
        List<UnavailableBuilding> unavailable = sel.unavailable();
        ReconciliationEngine.ReconciliationResult recon = ReconciliationEngine.reconcile(
                sel.selected(), siteCtx.tier(), culture.id(),
                StructureAvailabilityRegistry.INSTANCE);
        if (!recon.drops().isEmpty() || !recon.tradeFulfilled().isEmpty()) {
            send(src, "reconciliation: " + recon.drops().size() + " drops, "
                    + recon.tradeFulfilled().size() + " trade-fulfilled");
        }
        List<BuildingType> sorted = DependencyResolver.topoSort(recon.finalSelection(), seed);
        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level);
        PlacementResult placement = phased.placement();
        send(src, "planner: placed=" + placement.placed().size()
                + " dropped=" + placement.dropped().size()
                + " unavailable=" + placement.unavailable().size()
                + " viable=" + placement.villageViable());

        if (!placement.villageViable()) {
            send(src, "village not viable — aborting spawn");
            return 0;
        }

        // Layer 5 — physical spawn.
        String villageName = "v2_" + Long.toHexString(seed) + "_"
                + centre.getX() + "_" + centre.getZ();
        MinimalSpawner.SpawnResult spawnResult = MinimalSpawner.spawn(
                level, placement, phased.network(), culture, villageName, siteCtx.tier());
        long t1 = System.currentTimeMillis();

        // Audit summary.
        OverlapAuditor.OverlapReport audit = spawnResult.overlapReport();
        if (audit.fatal()) {
            send(src, "phase 5 audit: " + audit.conflicts().size()
                    + " conflicts (fatal — spawn ABORTED)");
            for (OverlapAuditor.Conflict c : audit.conflicts()) {
                send(src, "  " + c.description() + " — "
                        + c.aDesc() + " vs " + c.bDesc());
            }
            return 0;
        }
        if (spawnResult.aborted()) {
            send(src, "spawn ABORTED: " + spawnResult.abortReason());
            for (String r : spawnResult.viabilityFailures()) {
                send(src, "  - " + r);
            }
            send(src, "  no vegetation cleared, no roads painted, no buildings placed");
            return 0;
        }
        send(src, "phase 5 audit: " + audit.conflicts().size() + " conflicts (non-fatal)");
        for (OverlapAuditor.Conflict c : audit.conflicts()) {
            send(src, "  " + c.description() + " — "
                    + c.aDesc() + " vs " + c.bDesc());
        }
        send(src, "terrain: " + spawnResult.padsLevel() + " LEVEL, "
                + spawnResult.padsPlatform() + " PLATFORM, "
                + spawnResult.buildingsDroppedTerrain() + " DROP");

        send(src, "spawning:");
        send(src, "  cleared " + spawnResult.vegetationCleared() + " vegetation blocks");
        send(src, "  built " + (spawnResult.padsLevel() + spawnResult.padsPlatform())
                + " pads (" + spawnResult.padsPlatform() + " platforms)");
        send(src, "  placed " + spawnResult.buildingsPlaced() + " buildings"
                + (spawnResult.buildingsFailedPlacement() > 0
                        ? " (" + spawnResult.buildingsFailedPlacement() + " NBT load failures)"
                        : ""));
        send(src, "  painted " + spawnResult.roadBlocksPainted() + " road blocks");

        if (!spawnResult.additionalDrops().isEmpty()) {
            send(src, "terrain drops (" + spawnResult.additionalDrops().size() + "):");
            for (DroppedBuilding db : spawnResult.additionalDrops()) {
                send(src, "  " + db.type().name() + ": " + db.detail());
            }
        }

        send(src, "done in " + spawnResult.elapsedMs()
                + " ms (total " + (t1 - t0) + " ms)");
        return 1;
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
