// src/main/java/tterrag1112/life_in_the_village/Gui/RepaintScreen.java
package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket;
import tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket.BuildingEntry;
import tterrag1112.life_in_the_village.Networking.RepaintActionPacket;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.ColorSlot;

import java.util.List;

/**
 * Doc 15 §"Player-requested repaint" — UI surface.
 *
 * <p>One panel per repaint session: the left half is a scrollable
 * list of repaint-allowed buildings; the right half is three rows
 * of 16 dye-colour swatches (PRIMARY / ACCENT / ROOF). Slots not
 * present in the selected building's {@code variant.colorSlots}
 * are dimmed and unclickable. The bottom strip shows the cost
 * summary and the Confirm / Cancel buttons.</p>
 *
 * <p>Cost calculation runs client-side for display (using the same
 * formula as {@link tterrag1112.life_in_the_village.Village
 * .Decoration.Variants.RepaintCost}); the server re-validates on
 * the action packet so a tampered client can't underpay.</p>
 */
public class RepaintScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 220;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_W, PANEL_H, 0, 6);

    private static final int LIST_W = 140;
    private static final int LIST_H = 150;
    private static final int ROW_H = 18;
    private static final int SWATCH_W = 12;
    private static final int SWATCH_H = 12;
    private static final int SWATCH_GAP = 2;

    private final OpenRepaintScreenPacket data;
    private int panelX, panelY;
    private ScrollList<BuildingEntry> buildingList;

    @Nullable private BuildingEntry selected;
    @Nullable private DyeColor primaryPick;
    @Nullable private DyeColor accentPick;
    @Nullable private DyeColor roofPick;

    private StyledButton confirmButton;
    private StyledButton cancelButton;

    public RepaintScreen(OpenRepaintScreenPacket data) {
        super(Component.literal("Repaint Building"));
        this.data = data;
    }

    @Override public boolean isPauseScreen() { return false; }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void init() {
        panelX = (width  - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        buildingList = new ScrollList<>(
                panelX + 8, panelY + 22,
                LIST_W, LIST_H, ROW_H,
                data.buildings(), this::drawBuildingRow, this::onBuildingClick);

        cancelButton = StyledButton.builder(
                Component.literal("Cancel"),
                b -> onClose())
                .pos(panelX + 8, panelY + PANEL_H - 24)
                .size(60, 18)
                .build();
        addRenderableWidget(cancelButton);

        confirmButton = StyledButton.builder(
                Component.literal("Confirm"),
                b -> onConfirm())
                .pos(panelX + PANEL_W - 68, panelY + PANEL_H - 24)
                .size(60, 18)
                .build();
        addRenderableWidget(confirmButton);
        confirmButton.active = false;

        if (!data.buildings().isEmpty()) {
            selectBuilding(data.buildings().get(0));
        }
    }

    private void drawBuildingRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                                 BuildingEntry e, boolean hovered) {
        boolean isSelected = selected != null
                && selected.buildingId().equals(e.buildingId());
        if (isSelected || hovered) {
            g.fill(rx, ry, rx + rw, ry + rh, BookScreenColors.HIGHLIGHT);
        }
        String label = e.name();
        if (label.length() > 18) label = label.substring(0, 18) + "…";
        g.drawString(font, label,
                rx + 4, ry + (rh - 8) / 2,
                e.hasJob() ? BookScreenColors.MID : BookScreenColors.DARK,
                false);
        // small swatches for current colours
        int sx = rx + rw - 28;
        if (e.primaryColor() >= 0) drawSwatch(g, sx, ry + 3, byteToDye(e.primaryColor()));
        sx += 8;
        if (e.accentColor() >= 0) drawSwatch(g, sx, ry + 3, byteToDye(e.accentColor()));
        sx += 8;
        if (e.roofColor() >= 0) drawSwatch(g, sx, ry + 3, byteToDye(e.roofColor()));
    }

    private boolean onBuildingClick(BuildingEntry e, int button,
                                    double relX, double relY) {
        if (button != 0) return false;
        if (e.hasJob()) return true;
        selectBuilding(e);
        return true;
    }

    private void selectBuilding(BuildingEntry e) {
        selected = e;
        primaryPick = byteToDye(e.primaryColor());
        accentPick  = byteToDye(e.accentColor());
        roofPick    = byteToDye(e.roofColor());
        updateConfirmState();
    }

    private void updateConfirmState() {
        if (selected == null || selected.hasJob()) {
            confirmButton.active = false;
            return;
        }
        boolean any = primaryPick != null
                || accentPick != null
                || roofPick != null;
        confirmButton.active = any;
    }

    private void onConfirm() {
        if (selected == null || selected.hasJob()) return;
        PacketDistributor.sendToServer(new RepaintActionPacket(
                data.npcId(),
                selected.buildingId(),
                dyeToByte(primaryPick),
                dyeToByte(accentPick),
                dyeToByte(roofPick)));
        onClose();
    }

    // ── Render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        g.fill(0, 0, width, height, 0xC0000000);
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.PARCHMENT);
        g.drawCenteredString(font,
                "Repaint — " + data.npcName(),
                width / 2, panelY + 6, BookScreenColors.DARK);

        // Building list
        g.drawString(font, "Buildings",
                panelX + 8, panelY + 14, BookScreenColors.MID, false);
        buildingList.render(g, mouseX, mouseY);

        // Right-hand panel with colour pickers and cost.
        int rx = panelX + LIST_W + 20;
        int ry = panelY + 22;
        int rw = PANEL_W - LIST_W - 28;

        if (selected == null) {
            g.drawString(font, "Select a building to begin.",
                    rx, ry, BookScreenColors.MID, false);
        } else {
            renderColourPickers(g, rx, ry, rw, mouseX, mouseY);
            int costY = panelY + PANEL_H - 56;
            renderCostSummary(g, rx, costY, rw);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    private void renderColourPickers(GuiGraphics g, int x, int y, int w,
                                     int mouseX, int mouseY) {
        int slotsMask = selected.colorSlotsMask();

        g.drawString(font, "Variant: " + selected.variantId(),
                x, y, BookScreenColors.DARK, false);
        y += 12;

        y = renderSlotRow(g, x, y, "Primary", ColorSlot.PRIMARY,
                primaryPick, slotsMask, mouseX, mouseY);
        y = renderSlotRow(g, x, y, "Accent",  ColorSlot.ACCENT,
                accentPick,  slotsMask, mouseX, mouseY);
        y = renderSlotRow(g, x, y, "Roof",    ColorSlot.ROOF,
                roofPick,    slotsMask, mouseX, mouseY);
    }

    private int renderSlotRow(GuiGraphics g, int x, int y, String label,
                              ColorSlot slot, @Nullable DyeColor current,
                              int slotsMask, int mouseX, int mouseY) {
        boolean enabled = (slotsMask & (1 << slot.ordinal())) != 0;
        int textCol = enabled ? BookScreenColors.DARK : BookScreenColors.MID;
        g.drawString(font, label + ":", x, y, textCol, false);
        int gridX = x + 50;
        int gridY = y - 2;
        DyeColor[] colours = DyeColor.values();
        for (int i = 0; i < colours.length; i++) {
            int sx = gridX + (i % 8) * (SWATCH_W + SWATCH_GAP);
            int sy = gridY + (i / 8) * (SWATCH_H + SWATCH_GAP);
            DyeColor c = colours[i];
            int colour = enabled ? rgbOf(c) : dimRgb(rgbOf(c));
            g.fill(sx, sy, sx + SWATCH_W, sy + SWATCH_H, 0xFF000000 | colour);
            if (current == c) {
                g.renderOutline(sx - 1, sy - 1, SWATCH_W + 2, SWATCH_H + 2,
                        BookScreenColors.GOLD | 0xFF000000);
            }
            if (enabled
                    && mouseX >= sx && mouseX <= sx + SWATCH_W
                    && mouseY >= sy && mouseY <= sy + SWATCH_H) {
                g.renderOutline(sx, sy, SWATCH_W, SWATCH_H,
                        0xFFFFFFFF);
            }
        }
        return gridY + 2 * (SWATCH_H + SWATCH_GAP) + 8;
    }

    private void renderCostSummary(GuiGraphics g, int x, int y, int w) {
        int total = totalDyesNeeded();
        long bronze = bronzeFee();
        if (total == 0 && bronze == 0) {
            g.drawString(font, "No changes.", x, y, BookScreenColors.MID, false);
            return;
        }
        g.drawString(font, "Cost:", x, y, BookScreenColors.DARK, false);
        g.drawString(font, total + " dye items, " + bronze + " bronze",
                x + 32, y, BookScreenColors.DARK, false);

        // Per-colour breakdown line
        StringBuilder breakdown = new StringBuilder();
        if (primaryPick != null) breakdown.append(perSlotDyes())
                .append(" ").append(primaryPick.name().toLowerCase());
        if (accentPick != null) {
            if (breakdown.length() > 0) breakdown.append(", ");
            breakdown.append(perSlotDyes())
                    .append(" ").append(accentPick.name().toLowerCase());
        }
        if (roofPick != null) {
            if (breakdown.length() > 0) breakdown.append(", ");
            breakdown.append(perSlotDyes())
                    .append(" ").append(roofPick.name().toLowerCase());
        }
        if (breakdown.length() > 0) {
            g.drawString(font, breakdown.toString(),
                    x, y + 12, BookScreenColors.MID, false);
        }
    }

    // ── Mouse routing ────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (buildingList.mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        if (selected != null && handleSwatchClick(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    private boolean handleSwatchClick(double mx, double my, int button) {
        if (button != 0) return false;
        int rx = panelX + LIST_W + 20;
        int ry = panelY + 22 + 12;
        int gridXBase = rx + 50;
        DyeColor[] colours = DyeColor.values();
        int slotsMask = selected.colorSlotsMask();
        ColorSlot[] order = { ColorSlot.PRIMARY, ColorSlot.ACCENT, ColorSlot.ROOF };
        for (int row = 0; row < 3; row++) {
            ColorSlot slot = order[row];
            boolean enabled = (slotsMask & (1 << slot.ordinal())) != 0;
            int gridY = ry + row * (2 * (SWATCH_H + SWATCH_GAP) + 8) - 2;
            if (!enabled) continue;
            for (int i = 0; i < colours.length; i++) {
                int sx = gridXBase + (i % 8) * (SWATCH_W + SWATCH_GAP);
                int sy = gridY + (i / 8) * (SWATCH_H + SWATCH_GAP);
                if (mx >= sx && mx <= sx + SWATCH_W
                        && my >= sy && my <= sy + SWATCH_H) {
                    setPick(slot, colours[i]);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (buildingList != null
                && buildingList.mouseScrolled(mouseX, mouseY, vertical)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void setPick(ColorSlot slot, DyeColor color) {
        switch (slot) {
            case PRIMARY -> primaryPick = (primaryPick == color) ? null : color;
            case ACCENT  -> accentPick  = (accentPick  == color) ? null : color;
            case ROOF    -> roofPick    = (roofPick    == color) ? null : color;
        }
        updateConfirmState();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private int totalDyesNeeded() {
        if (selected == null) return 0;
        int per = perSlotDyes();
        int slots = 0;
        if (primaryPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.PRIMARY.ordinal())) != 0) slots++;
        if (accentPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.ACCENT.ordinal())) != 0) slots++;
        if (roofPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.ROOF.ordinal())) != 0) slots++;
        return per * slots;
    }

    private long bronzeFee() {
        if (selected == null) return 0;
        int slots = 0;
        if (primaryPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.PRIMARY.ordinal())) != 0) slots++;
        if (accentPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.ACCENT.ordinal())) != 0) slots++;
        if (roofPick != null
                && (selected.colorSlotsMask() & (1 << ColorSlot.ROOF.ordinal())) != 0) slots++;
        return 4L * slots;
    }

    private int perSlotDyes() {
        if (selected == null) return 0;
        int area = Math.max(1, selected.footprintWidth() * selected.footprintLength());
        return 4 * (int) Math.max(1, Math.ceil(area / 64.0));
    }

    private static void drawSwatch(GuiGraphics g, int x, int y, @Nullable DyeColor c) {
        if (c == null) return;
        g.fill(x, y, x + 6, y + 6, 0xFF000000 | rgbOf(c));
    }

    private static int rgbOf(DyeColor c) {
        // Stable hardcoded swatch palette for the UI — independent of
        // any specific Mojang/NeoForge mapping method (those vary
        // across versions). Approximates the in-world dyed-block tint.
        return switch (c) {
            case WHITE      -> 0xF9FFFE;
            case ORANGE     -> 0xF9801D;
            case MAGENTA    -> 0xC74EBD;
            case LIGHT_BLUE -> 0x3AB3DA;
            case YELLOW     -> 0xFED83D;
            case LIME       -> 0x80C71F;
            case PINK       -> 0xF38BAA;
            case GRAY       -> 0x474F52;
            case LIGHT_GRAY -> 0x9D9D97;
            case CYAN       -> 0x169C9C;
            case PURPLE     -> 0x8932B8;
            case BLUE       -> 0x3C44AA;
            case BROWN      -> 0x835432;
            case GREEN      -> 0x5E7C16;
            case RED        -> 0xB02E26;
            case BLACK      -> 0x1D1D21;
        };
    }

    private static int dimRgb(int rgb) {
        int r = ((rgb >> 16) & 0xFF) / 2;
        int g = ((rgb >> 8) & 0xFF) / 2;
        int b = rgb & 0xFF;
        b /= 2;
        return (r << 16) | (g << 8) | b;
    }

    @Nullable
    private static DyeColor byteToDye(byte b) {
        if (b < 0 || b >= DyeColor.values().length) return null;
        return DyeColor.values()[b];
    }

    private static byte dyeToByte(@Nullable DyeColor c) {
        return c == null ? -1 : (byte) c.ordinal();
    }
}
