package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * B2.7 — centralized help directory for the mod's command surface.
 * Registered under both {@code /liv help} and {@code /litv help}
 * so testers can discover commands from either top-level literal.
 *
 * <p>The user direction during B2.7 was "brief help text per
 * surviving command." Rather than spreading {@code .executes()}
 * help branches across 50+ command files, this command consolidates
 * the listing here. Each top-level literal still owns its own
 * subcommand structure; this just acts as a discovery surface.</p>
 *
 * <p>Categories (ordered by likely use-frequency):</p>
 * <ol>
 *   <li><b>Spawn / planning</b> — the spawn pipeline + V2 layer
 *       inspection commands.</li>
 *   <li><b>B2 debug</b> — decoration / parks / farms / homesteads
 *       / adjuncts.</li>
 *   <li><b>NPC / social</b> — dialogue, gossip, office, guild,
 *       request, family.</li>
 *   <li><b>Economy / trade</b> — business, market, trade routes.</li>
 *   <li><b>Civic</b> — laws, crime, religion, history.</li>
 *   <li><b>World</b> — atlas, kingdom, claim, village tags.</li>
 * </ol>
 */
public final class LivHelpCommand {

    private LivHelpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Both /liv help and /litv help route here.
        dispatcher.register(Commands.literal("liv")
                .then(Commands.literal("help")
                        .executes(LivHelpCommand::showAll)
                        .then(Commands.literal("spawn")    .executes(c -> show(c, SPAWN)))
                        .then(Commands.literal("debug")    .executes(c -> show(c, B2_DEBUG)))
                        .then(Commands.literal("npc")      .executes(c -> show(c, NPC)))
                        .then(Commands.literal("economy")  .executes(c -> show(c, ECONOMY)))
                        .then(Commands.literal("civic")    .executes(c -> show(c, CIVIC)))
                        .then(Commands.literal("world")    .executes(c -> show(c, WORLD)))));
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("help")
                        .executes(LivHelpCommand::showAll)));
    }

    private static int showAll(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("§b=== Life in the Village commands ===\n");
        sb.append(SPAWN).append("\n");
        sb.append(B2_DEBUG).append("\n");
        sb.append(NPC).append("\n");
        sb.append(ECONOMY).append("\n");
        sb.append(CIVIC).append("\n");
        sb.append(WORLD).append("\n");
        sb.append("§7Detail: §f/liv help <category> — categories: ")
                .append("spawn debug npc economy civic world");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // =========================================================================
    // Category catalogues
    // =========================================================================

    private static final String SPAWN =
            "§b[spawn / planning]\n"
                    + "§f/litv spawn§7 — full V2 pipeline at player position (B2.7-routed)\n"
                    + "§f/litv site§7 — Layer 1+2 site analysis only\n"
                    + "§f/litv place§7 — Layer 3 placement debug\n"
                    + "§f/litv layout§7 — Layer 4 PhasedPlanner output inspection\n"
                    + "§f/building village create§7 — V2-spawn variant under /building tree\n"
                    + "§f/building village spawn§7 — alias for create\n"
                    + "§f/building place§7 — direct NBT placement (skips planning)\n"
                    + "§f/castle spawn§7 — kingdom castle generator\n"
                    + "§f/castle design§7 — interactive castle blueprint";

    private static final String B2_DEBUG =
            "§b[B2 decoration debug]\n"
                    + "§f/liv decoration§7 — DecorationProfile / placement listings\n"
                    + "§f/liv parks <village>§7 — reserved GardenPlots\n"
                    + "§f/liv farms <village>§7 — FarmComplex per farmhouse + plot allocation\n"
                    + "§f/liv atlas§7 — World Atlas inspection (here/stats/sample/region)";

    private static final String NPC =
            "§b[NPC / social]\n"
                    + "§f/npc§7 — NPC inspection / spawning / debug\n"
                    + "§f/dialogue§7 — dialogue topic / gossip pool inspection\n"
                    + "§f/gossip§7 — village gossip propagation\n"
                    + "§f/verb§7 — verb-emit debugging\n"
                    + "§f/office§7 — office-holder management\n"
                    + "§f/request§7 — guild request board\n"
                    + "§f/visitor§7 — visitor flux / inn occupancy\n"
                    + "§f/apprentice§7 — apprenticeship pairs\n"
                    + "§f/scribe§7 — scribal commissions\n"
                    + "§f/letter§7 — letter routing\n"
                    + "§f/guild§7 / §f/guilds§7 — guild membership / treasury\n"
                    + "§f/party§7 — adventuring parties\n"
                    + "§f/appearance§7 — NPC appearance debugging";

    private static final String ECONOMY =
            "§b[economy / trade]\n"
                    + "§f/business§7 — building treasuries\n"
                    + "§f/economy§7 — village economy snapshots\n"
                    + "§f/order§7 — building expansion / commission orders\n"
                    + "§f/farm§7 / §f/farmbalance§7 — farm income / balance\n"
                    + "§f/farmplot§7 — per-plot ticking debug\n"
                    + "§f/building house buy/list/status§7 — housing market";

    private static final String CIVIC =
            "§b[civic]\n"
                    + "§f/law§7 — VillagePolicy active laws\n"
                    + "§f/crime§7 — crime ledger\n"
                    + "§f/religion§7 — religion / observances\n"
                    + "§f/health§7 — health system\n"
                    + "§f/history§7 — village history\n"
                    + "§f/event§7 — event lifecycle\n"
                    + "§f/plague§7 — plague spread\n"
                    + "§f/sim§7 — VillageSimEngine baseline\n"
                    + "§f/culture§7 — culture inspection\n"
                    + "§f/business§7 — trading businesses\n"
                    + "§f/building needs§7 — resource needs";

    private static final String WORLD =
            "§b[world]\n"
                    + "§f/kingdom§7 — kingdom bookkeeping\n"
                    + "§f/kingdom-claim§7 — kingdom territorial claims\n"
                    + "§f/village-tags§7 — village tag inspection";
}
