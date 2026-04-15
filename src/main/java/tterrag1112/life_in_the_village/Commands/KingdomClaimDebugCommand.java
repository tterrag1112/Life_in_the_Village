// src/main/java/tterrag1112/life_in_the_village/Commands/KingdomClaimDebugCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaimComputer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

/**
 * /liv kingdom claim compute [budget]       — compute a claim at player's pos, log stats
 * /liv kingdom claim preview &lt;kingdom&gt; — log an existing kingdom's claim extent
 */
public class KingdomClaimDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("liv")
                .then(Commands.literal("kingdom")
                        .then(Commands.literal("claim")
                                .then(Commands.literal("compute")
                                        .executes(ctx -> compute(ctx, KingdomClaimComputer.DEFAULT_BUDGET))
                                        .then(Commands.argument("budget", FloatArgumentType.floatArg(10f, 2000f))
                                                .executes(ctx -> compute(ctx,
                                                        FloatArgumentType.getFloat(ctx, "budget")))))
                                .then(Commands.literal("preview")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(KingdomClaimDebugCommand::preview))))));
    }

    private static int compute(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                               float budget) {
        var src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Must be a player"));
            return 0;
        }
        ServerLevel level = player.level();
        WorldAtlas atlas = WorldAtlas.get(level);
        BlockPos origin = player.blockPosition();

        // Fill a generous area around the origin so Dijkstra has terrain to work with.
        // Budget 180 ≈ ~80 cells radius worst case; fill 2000 blocks = ~31 cells radius
        // along cardinal + corners. Enough for default budgets.
        int fillRadius = Math.max(1500, (int)(Math.sqrt(budget) * 120));
        atlas.ensureRegionFilled(level, origin.getX(), origin.getZ(), fillRadius, 100_000_000L);
        atlas.ensureRegionsIndexed(
                WorldAtlas.blockToCell(origin.getX()),
                WorldAtlas.blockToCell(origin.getZ()),
                (fillRadius >> AtlasCell.CELL_SHIFT) + 2);

        KingdomClaim claim = KingdomClaimComputer.compute(atlas, origin, budget);

        String msg = "Claim computed at " + origin.toShortString() + "\n"
                + "  home:    " + claim.homeCategoryName() + "\n"
                + "  cells:   " + claim.size() + "\n"
                + "  spent:   " + String.format("%.1f", claim.budgetSpent())
                + " / " + String.format("%.1f", claim.budgetTotal());
        src.sendSuccess(() -> Component.literal(msg), false);
        System.out.println("[KingdomClaim] " + msg.replace("\n", " | "));
        return 1;
    }

    private static int preview(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);
        Kingdom k = data.getKingdomByName(name).orElse(null);
        if (k == null) { src.sendFailure(Component.literal("No kingdom: " + name)); return 0; }
        KingdomClaim claim = k.getTerritorialClaim().orElse(null);
        if (claim == null) { src.sendFailure(Component.literal("Kingdom has no claim")); return 0; }
        String msg = "Kingdom '" + name + "' claim: " + claim.size() + " cells, home="
                + claim.homeCategoryName();
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}