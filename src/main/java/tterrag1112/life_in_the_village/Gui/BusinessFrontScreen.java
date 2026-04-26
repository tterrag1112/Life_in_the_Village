package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.NpcProfileActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenBusinessFrontPacket;
import tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket;

import java.util.List;

/**
 * Business-front interaction screen. Spec line 178.
 *
 * <p>Layout:</p>
 * <ul>
 *   <li>Title row: NPC name + profession + building subtitle.</li>
 *   <li>Primary action: large button labelled per
 *   {@link #primaryActionLabel} (Trade / Borrow / Order Meal /
 *   Commission / Request Treatment, etc.).</li>
 *   <li>Verb grid: every entry from {@code data.verbIds()} renders
 *   as a button (server-side
 *   {@link tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry#availableFor}
 *   has already gated profession + cooldown). Layout is two columns
 *   of up-to-four rows, so an 8-verb list fits without scrolling.</li>
 *   <li>Corner circle: top-right "@"-style profile shortcut, sends
 *   {@link NpcProfileActionPacket.Action#OPEN_PROFILE}.</li>
 *   <li>Close: bottom-right small button.</li>
 * </ul>
 *
 * <p>Profession overrides — village leader, kingdom ruler, merchant,
 * and guild members never reach this screen; their dedicated GUIs
 * intercept earlier (see {@code NpcInteractionHandler.tryRouteBusinessFront}).</p>
 */
public class BusinessFrontScreen extends Screen {

    private static final int W = 260;
    private static final int H = 200;
    private static final int PADDING = 8;
    private static final int CORNER_DIAMETER = 16;
    private static final int VERB_BTN_W = (W - PADDING * 2 - 6) / 2; // two columns
    private static final int VERB_BTN_H = 18;

    private final OpenBusinessFrontPacket data;
    private int originX, originY;

    public BusinessFrontScreen(OpenBusinessFrontPacket data) {
        super(Component.literal(data.npcName()));
        this.data = data;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        originX = (width - W) / 2;
        originY = (height - H) / 2;

        // Primary action — large button just under the title bar.
        Component primaryLabel = Component.literal(primaryActionLabel(data.buildingTypeName()));
        addRenderableWidget(StyledButton.builder(primaryLabel, b -> sendPrimaryAction())
                .pos(originX + PADDING, originY + 36)
                .size(W - PADDING * 2, 24)
                .build());

        // Verb grid: two-column layout, up to 4 rows.
        layoutVerbButtons();

        // Corner profile circle — top-right inside the panel.
        var profileBtn = StyledButton.builder(Component.literal("@"),
                        b -> {
                            PacketDistributor.sendToServer(
                                    new NpcProfileActionPacket(data.npcId(),
                                            NpcProfileActionPacket.Action.OPEN_PROFILE));
                            this.onClose();
                        })
                .pos(originX + W - CORNER_DIAMETER - 4, originY + 4)
                .size(CORNER_DIAMETER, CORNER_DIAMETER)
                .build();
        profileBtn.setTooltip(Tooltip.create(Component.literal("View full profile")));
        addRenderableWidget(profileBtn);

        // Close button bottom-right.
        addRenderableWidget(StyledButton.builder(Component.literal("Close"),
                        b -> this.onClose())
                .pos(originX + W - 60 - PADDING, originY + H - 22)
                .size(60, 18).build());
    }

    private void layoutVerbButtons() {
        List<String> ids = data.verbIds();
        List<String> labels = data.verbLabels();
        if (ids.isEmpty()) return;

        int gridY0 = originY + 70;
        int rowGap = 4;
        int colGap = 6;
        int maxRows = 4;
        int placed = 0;
        for (int i = 0; i < ids.size() && placed < maxRows * 2; i++) {
            String id = ids.get(i);
            String label = i < labels.size() ? labels.get(i) : id;
            int col = placed % 2;
            int row = placed / 2;
            int x = originX + PADDING + col * (VERB_BTN_W + colGap);
            int y = gridY0 + row * (VERB_BTN_H + rowGap);
            addRenderableWidget(StyledButton.builder(Component.literal(label),
                            b -> sendVerb(id))
                    .pos(x, y).size(VERB_BTN_W, VERB_BTN_H).build());
            placed++;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.fill(originX, originY, originX + W, originY + H, BookScreenColors.PARCHMENT);
        g.renderOutline(originX, originY, W, H, BookScreenColors.BORDER);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        g.drawString(font, data.npcName(),
                originX + PADDING, originY + PADDING, BookScreenColors.DARK, false);
        String subtitle = data.professionName() + " — " + prettyBuildingName(data.buildingTypeName())
                + (data.villageName().isEmpty() ? "" : " in " + data.villageName());
        g.drawString(font, subtitle,
                originX + PADDING, originY + PADDING + 12, BookScreenColors.LIGHT, false);
    }

    // ── Wire helpers ──────────────────────────────────────────────────────

    private void sendPrimaryAction() {
        // TRADE is the universal primary; the server-side handler
        // resolves the actual UI per profession (trade screen for
        // merchant/smith, borrow screen for library, commission for
        // scribe, request-treatment for healer, etc).
        PacketDistributor.sendToServer(new NpcProfileActionPacket(
                data.npcId(), NpcProfileActionPacket.Action.TRADE));
        this.onClose();
    }

    private void sendVerb(String verbId) {
        PacketDistributor.sendToServer(new PlayerVerbInvokePacket(
                data.npcId(), verbId, java.util.Map.of()));
        // Some verbs open follow-up screens; close this one so they
        // aren't stacked behind us.
        this.onClose();
    }

    /** Per-building-type label for the primary action. Spec line 184. */
    static String primaryActionLabel(String buildingType) {
        return switch (buildingType) {
            case "MARKET", "STOCKPILE" -> "Trade";
            case "BLACKSMITH", "CARPENTRY", "BAKERY", "STONEMASON",
                 "WEAVER", "CANDLEMAKER", "MILLER",
                 "ARMORER", "TOOLSMITH", "ATELIER" -> "Trade / Commission";
            case "INN" -> "Order Meal";
            case "LIBRARY" -> "Borrow Book";
            case "SCRIBE_WORKSHOP" -> "Commission Letter";
            case "SCHOLARS_RETREAT" -> "Take Lesson";
            case "APOTHECARY", "HEALER_HUT" -> "Request Treatment";
            case "TEMPLE", "CHAPEL", "SHRINE" -> "Request Blessing";
            case "TOWN_HALL" -> "Petition";
            case "GUILD_HALL" -> "Open Guild";
            default -> "Trade";
        };
    }

    private static String prettyBuildingName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "Building";
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : typeName.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '_') { sb.append(' '); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
