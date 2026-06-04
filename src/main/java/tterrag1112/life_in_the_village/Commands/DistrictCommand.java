package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.ResidentialVariant;
import tterrag1112.life_in_the_village.Village.Planning.V2.V2VillageSpawnerAdapter;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /litv district residential <count> [variant]} — dev tooling to place a
 * single residential district at the player by driving the REAL spawn pipeline
 * (planner → placer → downstream render) with a FORCED roster
 * ({@code TOWN_HALL:1, HOUSE:<count>}) via
 * {@link V2VillageSpawnerAdapter#spawn}'s selection-override seam — so the
 * residential variants + their tiling can be iterated by eye without a full
 * village. The town hall gives a stable civic anchor/precinct (visual
 * reference); the residential blocks place beyond it and tile as {@code count}
 * grows. {@code DISTRICT_ONLY_MODE} keeps rural + loose buildings off.
 *
 * <p>{@code [variant]} is parsed + echoed now so the arg path exists; it is a
 * no-op until residential internal-layout variants land (a later pass threads
 * it into the placement once a variant enum/consumer exists).
 *
 * <p>Op-only (permission 2). Re-running near a prior test village fails the
 * spawn proximity check — move away (or remove the test village) between runs.
 */
public final class DistrictCommand {

    private DistrictCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("district")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("residential")
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> runResidential(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                null))
                                        .then(Commands.argument("variant",
                                                        StringArgumentType.word())
                                                .executes(ctx -> runResidential(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        StringArgumentType.getString(ctx, "variant"))))))));
    }

    private static int runResidential(CommandContext<CommandSourceStack> ctx,
                                      int count, String variant)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos origin = player.blockPosition();

        // Forced roster — a civic anchor (TOWN_HALL) + the residential load.
        Map<BuildingType, Integer> override = new LinkedHashMap<>();
        override.put(BuildingType.TOWN_HALL, 1);
        override.put(BuildingType.HOUSE, count);

        // Parse the optional forced variant (unknown/absent → auto-select).
        ResidentialVariant forced = ResidentialVariant.parse(variant);
        String variantNote = variant == null ? "auto"
                : (forced != null ? forced.name() : variant + " (unknown → auto)");

        send(src, "[litv-district] residential count=" + count
                + " variant=" + variantNote
                + " at " + origin.getX() + "," + origin.getZ()
                + " — driving real spawn with forced roster " + override + " ...");

        Optional<Village> result = V2VillageSpawnerAdapter.spawn(
                level, origin, "default", "district_test_" + level.getGameTime(),
                Inclination.AGRICULTURAL, ViabilityTier.CITY, override, forced);

        if (result.isEmpty()) {
            send(src, "[litv-district] spawn returned empty (see log — e.g. proximity"
                    + " check vs a prior test village, or not-viable). Move away and retry.");
            return 0;
        }
        Village v = result.get();
        send(src, "[litv-district] placed '" + v.getName() + "' with "
                + v.getBuildingIds().size() + " building(s). See the log for"
                + " 'residential districts: N requested / N reserved' + per-block tiling.");
        return 1;
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
