package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5 doc 31 debug surface.
 *
 * <ul>
 *   <li>{@code /culture info <id>} — full culture spec dump.</li>
 *   <li>{@code /culture list} — every registered culture.</li>
 *   <li>{@code /culture resolve npc <uuid> <hook>} — invoke a
 *       resolver hook for one NPC and print the result.</li>
 *   <li>{@code /culture resolve village <name> <hook>}.</li>
 * </ul>
 *
 * <p>Note: the spec asks for {@code /culture set <village> <id>} to
 * migrate a village's culture for testing. v1 ships read-only debug
 * commands; the village's culture is derived from its kingdom's
 * culture (the canonical source). To "change" a village's culture
 * you change the kingdom's culture via {@code /kingdom} or the
 * kingdom's data directly. Documented in 31 Revision Notes.</p>
 */
public final class CultureDebugCommand {

    private CultureDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("culture")
                .then(Commands.literal("list")
                        .executes(CultureDebugCommand::handleList))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Culture cul : CultureRegistry.all()) b.suggest(cul.id());
                                    return b.buildFuture();
                                })
                                .executes(CultureDebugCommand::handleInfo)))
                .then(Commands.literal("resolve")
                        .then(Commands.literal("npc")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("hook", StringArgumentType.word())
                                                .suggests(CultureDebugCommand::suggestHooks)
                                                .executes(CultureDebugCommand::handleResolveNpc))))
                        .then(Commands.literal("village")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("hook", StringArgumentType.word())
                                                .suggests(CultureDebugCommand::suggestHooks)
                                                .executes(CultureDebugCommand::handleResolveVillage)))))
        );
    }

    // ── /culture list ─────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CultureRegistry.ensureInit();
        StringBuilder sb = new StringBuilder("§e=== Cultures (")
                .append(CultureRegistry.all().size()).append(") ===§r");
        for (Culture c : CultureRegistry.all()) {
            sb.append(String.format(Locale.ROOT, "%n  §a%-12s§r %s §7(religion=%s)",
                    c.id(), c.displayName(), c.religion().religionId()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /culture info <id> ────────────────────────────────────────────────

    private static int handleInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        Optional<Culture> c = CultureRegistry.get(id);
        if (c.isEmpty()) {
            src.sendFailure(Component.literal("No culture " + id));
            return 0;
        }
        Culture cul = c.get();
        StringBuilder sb = new StringBuilder("§e=== ").append(cul.displayName())
                .append(" (").append(cul.id()).append(") ===§r");
        sb.append("\n  §7religion:§f ").append(cul.religion().religionId());
        sb.append("\n  §7traitBias:§f ").append(cul.traitBias().biases());
        sb.append("\n  §7initialLaws:§f ").append(cul.lawDefaults().initialLaws());
        sb.append("\n  §7preferredLaws:§f ").append(cul.lawDefaults().preferredLaws());
        sb.append("\n  §7forbiddenLaws:§f ").append(cul.lawDefaults().forbiddenLaws());
        sb.append("\n  §7officeRules:§f ");
        cul.officeRules().preferredSelection().forEach((office, method) ->
                sb.append("\n    §a").append(office).append("§r → ").append(method.name()));
        sb.append("\n  §7economicNorms:§f gift=").append(cul.economicNorms().giftPropensity())
                .append(" haggle=").append(cul.economicNorms().hagglingTendency())
                .append(" credit=").append(cul.economicNorms().creditAccepted())
                .append(" luxury=").append(cul.economicNorms().luxuryDemand());
        sb.append("\n  §7hobbyWeights:§f ").append(cul.hobbyWeights().weights());
        sb.append("\n  §7visitorAffinity:§f ").append(cul.visitorAffinity().weightMultipliers());
        var ann = cul.apprenticeshipNorms();
        sb.append("\n  §7apprenticeship:§f ").append(ann.standardDurationDays())
                .append("d max=").append(ann.maxApprenticesPerMaster())
                .append(" masterpiece=").append(ann.requiresMasterpiece())
                .append(" daughter=").append(ann.daughterInheritsShop());
        sb.append("\n  §7aesthetics:§f silhouette=").append(cul.aesthetics().primarySilhouette())
                .append(" palette=").append(cul.aesthetics().clothingPalette());
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /culture resolve ──────────────────────────────────────────────────

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestHooks(CommandContext<CommandSourceStack> ctx,
                         com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        for (String h : new String[] {
                "religion", "selection", "laws", "economy", "hobby",
                "visitor", "apprenticeship", "aesthetics" }) {
            b.suggest(h);
        }
        return b.buildFuture();
    }

    private static int handleResolveNpc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID uuid = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = TownspersonMob.findByUUID(level, uuid).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC " + uuid));
            return 0;
        }
        Culture culture = CultureResolver.of(npc);
        return printResolve(src, "NPC " + npc.getNpcName(), culture,
                StringArgumentType.getString(ctx, "hook"));
    }

    private static int handleResolveVillage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "name");
        Village v = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("No village " + name));
            return 0;
        }
        Culture culture = CultureResolver.of(level, v);
        return printResolve(src, "village " + v.getName(), culture,
                StringArgumentType.getString(ctx, "hook"));
    }

    private static int printResolve(CommandSourceStack src, String subject,
                                    Culture culture, String hook) {
        String body = switch (hook.toLowerCase(Locale.ROOT)) {
            case "religion" -> "religionId=" + culture.religion().religionId();
            case "selection" -> "preferredSelection=" + culture.officeRules().preferredSelection();
            case "laws" -> "initial=" + culture.lawDefaults().initialLaws()
                    + " preferred=" + culture.lawDefaults().preferredLaws()
                    + " forbidden=" + culture.lawDefaults().forbiddenLaws();
            case "economy" -> "gift=" + culture.economicNorms().giftPropensity()
                    + " haggle=" + culture.economicNorms().hagglingTendency()
                    + " luxury=" + culture.economicNorms().luxuryDemand()
                    + " consumption=" + culture.economicNorms().consumptionBias();
            case "hobby" -> "weights=" + culture.hobbyWeights().weights();
            case "visitor" -> {
                StringBuilder s = new StringBuilder("multipliers={");
                for (Map.Entry<VisitorType, Float> e : culture.visitorAffinity().weightMultipliers().entrySet()) {
                    s.append(e.getKey()).append("=").append(e.getValue()).append(" ");
                }
                yield s.append("}").toString();
            }
            case "apprenticeship" -> {
                var n = culture.apprenticeshipNorms();
                yield "duration=" + n.standardDurationDays()
                        + "d max=" + n.maxApprenticesPerMaster()
                        + " masterpiece=" + n.requiresMasterpiece()
                        + " daughter=" + n.daughterInheritsShop();
            }
            case "aesthetics" -> "silhouette=" + culture.aesthetics().primarySilhouette()
                    + " palette=" + culture.aesthetics().clothingPalette()
                    + " banner=" + culture.aesthetics().bannerPattern();
            default -> "(unknown hook — try religion/selection/laws/economy/hobby/visitor/apprenticeship/aesthetics)";
        };
        StringBuilder sb = new StringBuilder("§e=== Culture resolve for ")
                .append(subject).append(" ===§r");
        sb.append("\n  §7culture:§f ").append(culture.displayName())
                .append(" (").append(culture.id()).append(")");
        sb.append("\n  §7hook:§f ").append(hook);
        sb.append("\n  §a").append(body);
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }
}
