package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.V2VillageSpawnerAdapter;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /litv spawn [tier] [villageTypeOrCategory] [name]} — runs the full V2
 * stack at the player's position and physically spawns the village with all
 * post-passes (decoration, parks, farms, homesteads, NPC population, trade
 * routes, simulation baseline, guild bootstrap, history seeding, initial laws).
 *
 * <p><b>B2.7 fix:</b> previously this command duplicated Layers
 * 1–4 manually and called the Layer 5 spawner directly,
 * skipping every Layer 4 post-pass that B2.1–B2.6 wired into
 * {@link V2VillageSpawnerAdapter}. The duplication is gone; the
 * command is now a thin wrapper that delegates to the canonical
 * adapter the same way {@code VillageSpawner.spawn} does.</p>
 *
 * <p><b>Gate-0:</b> optional {@code [tier] [villageTypeOrCategory] [name]}
 * arguments. They route through the SAME B2.8 override overload of
 * {@link V2VillageSpawnerAdapter#spawn} that {@code /building village spawn
 * <inclination> <tier> [name]} uses (no new pipeline path): tier maps to the
 * {@link ViabilityTier} override, a category ({@link Inclination} name) maps to
 * the inclination override, and a village-type id is passed as the adapter's
 * {@code villageType}. No-arg behavior is unchanged (self-derived tier +
 * auto-name).</p>
 */
public final class SpawnCommand {

    private static final String DEFAULT_VILLAGE_TYPE = "default";

    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("spawn")
                        .executes(ctx -> run(ctx, null, null, null))
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (ViabilityTier t : ViabilityTier.values()) {
                                        if (t != ViabilityTier.UNVIABLE) b.suggest(t.name());
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> run(ctx,
                                        StringArgumentType.getString(ctx, "tier"), null, null))
                                .then(Commands.argument("typeOrCategory", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (Inclination i : Inclination.values()) b.suggest(i.name());
                                            for (String t : VillageTypeRegistry.INSTANCE
                                                    .getAvailableTypes()) b.suggest(t);
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> run(ctx,
                                                StringArgumentType.getString(ctx, "tier"),
                                                StringArgumentType.getString(ctx, "typeOrCategory"),
                                                null))
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> run(ctx,
                                                        StringArgumentType.getString(ctx, "tier"),
                                                        StringArgumentType.getString(ctx, "typeOrCategory"),
                                                        StringArgumentType.getString(ctx, "name"))))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx,
                           String tierArg, String typeOrCategoryArg, String nameArg)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos centre = player.blockPosition();

        // ── Optional tier override (same ViabilityTier vocabulary as B2.8). ──
        ViabilityTier tierOverride = null;
        if (tierArg != null) {
            try {
                tierOverride = ViabilityTier.valueOf(tierArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                src.sendFailure(Component.literal("Unknown tier '" + tierArg + "'. Valid: "
                        + java.util.Arrays.toString(ViabilityTier.values())));
                return 0;
            }
        }

        // ── Optional type-or-category: an Inclination name becomes the
        //    inclination override; anything else must be a registered
        //    village-type id and is passed as the adapter's villageType. ──
        Inclination inclinationOverride = null;
        String villageType = DEFAULT_VILLAGE_TYPE;
        if (typeOrCategoryArg != null) {
            Inclination parsed = null;
            try {
                parsed = Inclination.valueOf(typeOrCategoryArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
            if (parsed != null) {
                inclinationOverride = parsed;
            } else if (VillageTypeRegistry.INSTANCE.getAvailableTypes()
                    .contains(typeOrCategoryArg)) {
                villageType = typeOrCategoryArg;
            } else {
                src.sendFailure(Component.literal("Unknown village type or category '"
                        + typeOrCategoryArg + "'. Categories: "
                        + java.util.Arrays.toString(Inclination.values())
                        + "; types: " + VillageTypeRegistry.INSTANCE.getAvailableTypes()));
                return 0;
            }
        }

        long t0 = System.currentTimeMillis();
        String villageName = nameArg != null ? nameArg
                : "v2_" + Long.toHexString(level.getSeed())
                        + "_" + centre.getX() + "_" + centre.getZ();
        String forcedDesc = (tierOverride == null && inclinationOverride == null)
                ? "" : " (forced"
                        + (tierOverride != null ? " tier=" + tierOverride : "")
                        + (inclinationOverride != null
                                ? " category=" + inclinationOverride : "") + ")";
        send(src, "[litv-spawn] running full V2 pipeline at "
                + centre.getX() + "," + centre.getZ() + forcedDesc + " ...");

        // Same canonical adapter; the override overload is the B2.8 seam the
        // /building village spawn command uses (nulls = self-derived, as before).
        Optional<Village> result = V2VillageSpawnerAdapter.spawn(
                level, centre, villageType, villageName,
                inclinationOverride, tierOverride);
        long elapsed = System.currentTimeMillis() - t0;

        if (result.isEmpty()) {
            send(src, "[litv-spawn] no village spawned (check log for "
                    + "site-too-close-to-existing-village or unviable terrain)");
            return 0;
        }
        Village village = result.get();
        send(src, "[litv-spawn] spawned '" + village.getName() + "' tier="
                + village.getSizeTier() + " buildings=" + village.getBuildingIds().size()
                + " in " + elapsed + "ms");
        send(src, "  full pipeline ran: roads + buildings + decoration + parks + "
                + "farms + homesteads + NPCs + trade routes + simulation baseline");
        return 1;
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
