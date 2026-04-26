package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.NpcProfileActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenBusinessFrontPacket;
import tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket;

/**
 * Business-front interaction screen. Spec line 178.
 *
 * <p>v1 layout — minimal but functional:</p>
 * <ul>
 *   <li>Title row: NPC name + building type subtitle.</li>
 *   <li>Primary action: large button labelled per
 *   {@link #primaryActionLabel} (Trade / Borrow / Order Meal /
 *   Commission / Request Treatment, etc.).</li>
 *   <li>Secondary verb buttons: small row beneath the primary —
 *   give_gift, ask_about_life, compliment, take_lesson. Each fires a
 *   {@link PlayerVerbInvokePacket}; the verb framework handles
 *   eligibility + cooldown.</li>
 *   <li>"View Profile" small button bottom-right; sends
 *   {@link NpcProfileActionPacket.Action#OPEN_PROFILE}.</li>
 * </ul>
 *
 * <p>Spec "Open decisions" #3: greeter's bark fires server-side via
 * action-bar text on approach (see {@link
 * tterrag1112.life_in_the_village.Npc.BusinessFront.GreetPlayerGoal#bark});
 * this screen opens only on right-click.</p>
 */
public class BusinessFrontScreen extends Screen {

    private static final int W = 240;
    private static final int H = 160;
    private static final int PADDING = 8;

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

        // Primary action — large button, top of body.
        Component primaryLabel = Component.literal(primaryActionLabel(data.buildingTypeName()));
        addRenderableWidget(StyledButton.builder(primaryLabel, b -> sendPrimaryAction())
                .pos(originX + PADDING, originY + 36)
                .size(W - PADDING * 2, 24)
                .build());

        // Secondary verb row — four small buttons beneath the primary.
        int verbY = originY + 70;
        int verbW = (W - PADDING * 2 - 12) / 4;
        addRenderableWidget(StyledButton.builder(Component.literal("Gift"),
                        b -> sendVerb("give_gift"))
                .pos(originX + PADDING, verbY).size(verbW, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Ask Life"),
                        b -> sendVerb("ask_life"))
                .pos(originX + PADDING + (verbW + 4), verbY).size(verbW, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Compliment"),
                        b -> sendVerb("compliment"))
                .pos(originX + PADDING + (verbW + 4) * 2, verbY).size(verbW, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Greet"),
                        b -> sendVerb("greet"))
                .pos(originX + PADDING + (verbW + 4) * 3, verbY).size(verbW, 18).build());

        // "View Profile" small button bottom-right.
        addRenderableWidget(StyledButton.builder(Component.literal("View Profile"),
                        b -> {
                            PacketDistributor.sendToServer(
                                    new NpcProfileActionPacket(data.npcId(),
                                            NpcProfileActionPacket.Action.OPEN_PROFILE));
                            this.onClose();
                        })
                .pos(originX + W - 90 - PADDING, originY + H - 24)
                .size(90, 18).build());

        // Close button bottom-left.
        addRenderableWidget(StyledButton.builder(Component.literal("Close"),
                        b -> this.onClose())
                .pos(originX + PADDING, originY + H - 24)
                .size(60, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.fill(originX, originY, originX + W, originY + H, BookScreenColors.PARCHMENT);
        g.renderOutline(originX, originY, W, H, BookScreenColors.BORDER);
        // Title row
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
        // Trade is the most common primary; for library / scribe / temple
        // we still send TRADE — the server-side handler downgrades to a
        // trade UI that the trade handler resolves per profession.
        // Library borrow + scribe commission have their own verbs that
        // a follow-up may promote to primary; v1 keeps the shape simple.
        PacketDistributor.sendToServer(new NpcProfileActionPacket(
                data.npcId(), NpcProfileActionPacket.Action.TRADE));
        this.onClose();
    }

    private void sendVerb(String verbId) {
        PacketDistributor.sendToServer(new PlayerVerbInvokePacket(
                data.npcId(), verbId, java.util.Map.of()));
        // Some verbs open follow-up screens; close this one so the
        // verb's screen isn't stacked under us.
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
