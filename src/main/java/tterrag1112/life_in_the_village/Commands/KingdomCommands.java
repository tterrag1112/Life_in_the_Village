package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Kingdom.*;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.Optional;
import java.util.UUID;

public class KingdomCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("kingdom")
                        // /kingdom create <name> <culture>
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("culture",
                                                        StringArgumentType.word())
                                                .executes(ctx -> createKingdom(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "culture")
                                                ))
                                        )
                                )
                        )
                        .then(Commands.literal("kingdom")
                                .then(Commands.literal("givebook")
                                        .executes(ctx -> giveKingdomBook(
                                                ctx.getSource()))))



                        // /kingdom addvillage <kingdom> <village>
                        .then(Commands.literal("addvillage")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .then(Commands.argument("village",
                                                        StringArgumentType.word())
                                                .executes(ctx -> addVillage(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "kingdom"),
                                                        StringArgumentType.getString(ctx, "village")
                                                ))
                                        )
                                )
                        )

                        // /kingdom setruler <kingdom> (sets player as ruler)
                        .then(Commands.literal("setruler")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .executes(ctx -> setPlayerRuler(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "kingdom")
                                        ))
                                )
                        )

                        // /kingdom relation <kingdom> <other> <relation>
                        .then(Commands.literal("relation")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .then(Commands.argument("other",
                                                        StringArgumentType.word())
                                                .then(Commands.argument("relation",
                                                                StringArgumentType.word())
                                                        .executes(ctx -> setRelation(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "other"),
                                                                StringArgumentType.getString(ctx, "relation")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // /kingdom law <kingdom> <enact|repeal> <law>
                        .then(Commands.literal("law")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .then(Commands.argument("action",
                                                        StringArgumentType.word())
                                                .then(Commands.argument("law",
                                                                StringArgumentType.word())
                                                        .executes(ctx -> manageLaw(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "kingdom"),
                                                                StringArgumentType.getString(ctx, "action"),
                                                                StringArgumentType.getString(ctx, "law")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // /kingdom settax <kingdom> <rate>
                        .then(Commands.literal("settax")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .then(Commands.argument("rate",
                                                        DoubleArgumentType.doubleArg(0, 1))
                                                .executes(ctx -> setTax(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "kingdom"),
                                                        DoubleArgumentType.getDouble(ctx, "rate")
                                                ))
                                        )
                                )
                        )

                        // /kingdom info <kingdom>
                        .then(Commands.literal("info")
                                .then(Commands.argument("kingdom",
                                                StringArgumentType.word())
                                        .executes(ctx -> kingdomInfo(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "kingdom")
                                        ))
                                )
                        )

                        // /kingdom list
                        .then(Commands.literal("list")
                                .executes(ctx -> listKingdoms(ctx.getSource()))
                        )
        );
    }

    private static int createKingdom(CommandSourceStack src,
                                     String name, String culture) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        if (data.getKingdomByName(name).isPresent()) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + name + "' already exists."));
            return 0;
        }

        Kingdom kingdom = new Kingdom(name, culture);
        data.addKingdom(kingdom);
        src.sendSuccess(() -> Component.literal(
                        "Created kingdom '" + name + "' with culture '" + culture + "'"),
                true);
        return 1;
    }

    private static int addVillage(CommandSourceStack src,
                                  String kingdomName, String villageName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        if (kingdom == null) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + kingdomName + "' not found."));
            return 0;
        }

        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "Village '" + villageName + "' not found."));
            return 0;
        }

        // Check if village already belongs to a kingdom
        data.getKingdomForVillage(village.getId()).ifPresent(existing -> {
            existing.removeVillage(village.getId());
        });

        kingdom.addVillage(village.getId());

        // Update village culture to match kingdom
        // (culture system integration point for future)

        data.setDirty();
        src.sendSuccess(() -> Component.literal(
                "Added village '" + villageName + "' to kingdom '"
                        + kingdomName + "'"), true);
        return 1;
    }

    private static int setPlayerRuler(CommandSourceStack src,
                                      String kingdomName) {
        if (src.getPlayer() == null) {
            src.sendFailure(Component.literal(
                    "Must be a player to become ruler."));
            return 0;
        }

        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        if (kingdom == null) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + kingdomName + "' not found."));
            return 0;
        }

        kingdom.setRulerPlayerId(src.getPlayer().getUUID());
        data.setDirty();
        src.sendSuccess(() -> Component.literal(
                src.getPlayer().getName().getString()
                        + " is now ruler of '" + kingdomName + "'"), true);
        return 1;
    }

    private static int setRelation(CommandSourceStack src,
                                   String kingdomName, String otherName,
                                   String relationName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        Kingdom other   = data.getKingdomByName(otherName).orElse(null);

        if (kingdom == null || other == null) {
            src.sendFailure(Component.literal("Kingdom not found."));
            return 0;
        }

        DiplomaticRelation relation;
        try {
            relation = DiplomaticRelation.valueOf(
                    relationName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(
                    "Invalid relation. Valid: "
                            + java.util.Arrays.toString(
                            DiplomaticRelation.values())));
            return 0;
        }

        // Set both sides
        kingdom.setRelation(other.getId(), relation);
        other.setRelation(kingdom.getId(), relation);
        data.setDirty();

        src.sendSuccess(() -> Component.literal(
                "Set relation between '" + kingdomName + "' and '"
                        + otherName + "' to " + relation), true);
        return 1;
    }

    private static int manageLaw(CommandSourceStack src,
                                 String kingdomName, String action,
                                 String lawName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        if (kingdom == null) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + kingdomName + "' not found."));
            return 0;
        }

        KingdomLaw law;
        try {
            law = KingdomLaw.valueOf(lawName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(
                    "Invalid law. Valid: "
                            + java.util.Arrays.toString(KingdomLaw.values())));
            return 0;
        }

        if (action.equalsIgnoreCase("enact")) {
            kingdom.enactLaw(law);
            src.sendSuccess(() -> Component.literal(
                            "Enacted law " + law + " in '" + kingdomName + "'"),
                    true);
        } else if (action.equalsIgnoreCase("repeal")) {
            kingdom.repealLaw(law);
            src.sendSuccess(() -> Component.literal(
                            "Repealed law " + law + " in '" + kingdomName + "'"),
                    true);
        } else {
            src.sendFailure(Component.literal(
                    "Action must be 'enact' or 'repeal'"));
            return 0;
        }

        data.setDirty();
        return 1;
    }

    private static int setTax(CommandSourceStack src,
                              String kingdomName, double rate) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        if (kingdom == null) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + kingdomName + "' not found."));
            return 0;
        }

        kingdom.setIncomeTaxRate(rate);
        data.setDirty();
        src.sendSuccess(() -> Component.literal(
                "Set income tax rate of '" + kingdomName
                        + "' to " + (int)(rate * 100) + "%"), true);
        return 1;
    }

    private static int kingdomInfo(CommandSourceStack src,
                                   String kingdomName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Kingdom kingdom = data.getKingdomByName(kingdomName).orElse(null);
        if (kingdom == null) {
            src.sendFailure(Component.literal(
                    "Kingdom '" + kingdomName + "' not found."));
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                "=== " + kingdom.getName() + " ==="), false);
        src.sendSuccess(() -> Component.literal(
                "Culture: " + kingdom.getCulture()), false);
        src.sendSuccess(() -> Component.literal(
                "Villages: " + kingdom.getVillageIds().size()), false);
        src.sendSuccess(() -> Component.literal(
                "Treasury: " + kingdom.getTreasury()), false);
        src.sendSuccess(() -> Component.literal(
                        "Tax rate: " + (int)(kingdom.getIncomeTaxRate() * 100)
                                + "% + " + kingdom.getFlatUpkeepBronze() + "b upkeep"),
                false);
        src.sendSuccess(() -> Component.literal(
                "Ruler: " + (kingdom.hasRuler()
                        ? (kingdom.isPlayerRuled() ? "Player" : "NPC")
                        : "None")), false);

        if (!kingdom.getActiveLaws().isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "Laws: " + kingdom.getActiveLaws().stream()
                            .map(KingdomLaw::name)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none")), false);
        }

        if (!kingdom.getAllRelations().isEmpty()) {
            src.sendSuccess(() -> Component.literal("Relations:"), false);
            kingdom.getAllRelations().forEach((id, rel) ->
                    data.getKingdomById(id).ifPresent(other ->
                            src.sendSuccess(() -> Component.literal(
                                    "  " + other.getName() + ": " + rel), false)
                    )
            );
        }

        return 1;
    }

    private static int listKingdoms(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        var kingdoms = data.getAllKingdoms();
        if (kingdoms.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No kingdoms exist yet."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal(
                "=== Kingdoms ==="), false);
        kingdoms.forEach(k ->
                src.sendSuccess(() -> Component.literal(
                        "  " + k.getName()
                                + " (" + k.getCulture() + ")"
                                + " — " + k.getVillageIds().size() + " villages"
                                + " — treasury: " + k.getTreasury()), false)
        );
        return 1;
    }

    private static int giveKingdomBook(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player))
            return 0;

        ItemStack book = new ItemStack(
                ModItems.KINGDOM_BOOK.get());
        player.addItem(book);

        src.sendSuccess(() -> Component.literal(
                "Given kingdom book."), false);
        return 1;
    }
}