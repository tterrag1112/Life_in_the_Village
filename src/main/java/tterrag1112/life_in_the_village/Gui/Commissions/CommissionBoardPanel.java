package tterrag1112.life_in_the_village.Gui.Commissions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.CoinRow;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.ProgressBar;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Networking.CommissionEntry;

import java.util.*;

/**
 * Embeddable commission board panel — rendered by VillageBookScreen's
 * COMMISSIONS section. Self-contained enough that a future standalone
 * screen can embed it without refactoring (mirrors KingdomMapPanel's
 * lifecycle: construct → setEntries → render → mouseClicked/Scrolled).
 */
public class CommissionBoardPanel {

    public interface Callback { void onClaim(UUID orderId); }

    private enum StatusFilter {
        ALL("All"), OPEN("Open"), YOUR_CLAIMS("Yours"), CLAIMED_BY_OTHERS("Claimed");
        final String label; StatusFilter(String l) { label = l; }
    }
    private enum UrgencyFilter {
        ALL("All"), MEDIUM_OR_ABOVE("Med+"), HIGH_OR_ABOVE("High+"), CRITICAL_ONLY("Crit");
        final String label; UrgencyFilter(String l) { label = l; }
    }
    private enum ProfFilter {
        ALL("All"), FARMER("Farm"), BLACKSMITH("Smith"),
        CARPENTER("Carp"), MINER("Mine"), MERCHANT("Merc");
        final String label; ProfFilter(String l) { label = l; }
    }

    private static final int STRIP_H = 30;
    private static final int ROW_H   = 32;
    private static final long REWARD_STEP = 10L;

    private final Callback callback;
    private List<CommissionEntry> allEntries = List.of();
    private List<CommissionEntry> filtered   = List.of();

    private StatusFilter  statusFilter  = StatusFilter.ALL;
    private UrgencyFilter urgencyFilter = UrgencyFilter.ALL;
    private ProfFilter    profFilter    = ProfFilter.ALL;
    private long          minReward     = 0L;
    // Filter button x coords stored during drawFilterStrip, used in mouseClicked
    private final int[]   fbX = new int[5];
    private final int[]   fbW = new int[5];
    private int fb1Y, fb2Y;

    private ScrollList<CommissionEntry> list;
    private boolean needsRebuild = true;
    private int px, py, pw, ph;

    public CommissionBoardPanel(Callback callback) {
        this.callback = callback;
    }

    public void setEntries(List<CommissionEntry> entries) {
        if (!entries.equals(allEntries)) { allEntries = entries; needsRebuild = true; }
    }

    public void render(GuiGraphics g, Font font,
                       int x, int y, int w, int h, int mx, int my) {
        boolean boundsChanged = (x != px || y != py || w != pw || h != ph);
        px = x; py = y; pw = w; ph = h;
        if (needsRebuild || boundsChanged) { rebuildList(); needsRebuild = false; }
        drawFilterStrip(g, font, x, y, w, mx, my);
        if (list != null) list.render(g, mx, my);
    }

    private void drawFilterStrip(GuiGraphics g, Font font,
                                  int x, int y, int w, int mx, int my) {
        g.fill(x, y, x + w, y + STRIP_H, BookScreenColors.HIGHLIGHT);
        g.renderOutline(x, y, w, STRIP_H, BookScreenColors.BORDER);

        int row1y = y + 2;
        int row2y = y + 16;
        fb1Y = row1y; fb2Y = row2y;
        int BH = 11;

        // Row 1 — three cycling filter buttons
        int cx = x + 2;
        g.drawString(font, "Status:", cx, row1y + 1, BookScreenColors.MID, false);
        cx += font.width("Status:") + 2;
        fbX[0] = cx; fbW[0] = drawFilterBtn(g, font, cx, row1y, BH, statusFilter.label); cx += fbW[0] + 6;
        g.drawString(font, "Urgency:", cx, row1y + 1, BookScreenColors.MID, false);
        cx += font.width("Urgency:") + 2;
        fbX[1] = cx; fbW[1] = drawFilterBtn(g, font, cx, row1y, BH, urgencyFilter.label); cx += fbW[1] + 6;
        g.drawString(font, "Prof:", cx, row1y + 1, BookScreenColors.MID, false);
        cx += font.width("Prof:") + 2;
        fbX[2] = cx; fbW[2] = drawFilterBtn(g, font, cx, row1y, BH, profFilter.label);

        // Row 2 — min reward + match count
        cx = x + 2;
        g.drawString(font, "Min:", cx, row2y + 1, BookScreenColors.MID, false);
        cx += font.width("Min:") + 2;
        fbX[3] = cx; fbW[3] = drawFilterBtn(g, font, cx, row2y, BH, "−"); cx += fbW[3] + 2;
        String rewardStr = minReward == 0 ? "any" : minReward + "b";
        g.drawString(font, rewardStr, cx, row2y + 1, BookScreenColors.DARK, false);
        cx += font.width(rewardStr) + 2;
        fbX[4] = cx; fbW[4] = drawFilterBtn(g, font, cx, row2y, BH, "+");

        String matchStr = filtered.size() + " result" + (filtered.size() == 1 ? "" : "s");
        g.drawString(font, matchStr, x + w - font.width(matchStr) - 4, row2y + 1,
                BookScreenColors.MID, false);
    }

    private int drawFilterBtn(GuiGraphics g, Font font, int x, int y, int h, String label) {
        int bw = font.width(label) + 8;
        g.fill(x, y, x + bw, y + h, BookScreenColors.SIDEBAR);
        g.renderOutline(x, y, bw, h, BookScreenColors.BORDER);
        g.drawString(font, label, x + 4, y + 2, BookScreenColors.DARK, false);
        return bw;
    }

    private void renderRow(GuiGraphics g, int rowX, int rowY, int rowW, int rowH,
                           CommissionEntry e, boolean hovered) {
        Font font = Minecraft.getInstance().font;
        int bg = hovered ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT;
        if (e.claimedBySelf()) bg = BookScreenColors.GREEN_BG;
        else if (e.claimedByOther()) bg = 0xFFEEEECC;
        g.fill(rowX, rowY, rowX + rowW, rowY + ROW_H, bg);
        g.renderOutline(rowX, rowY, rowW, ROW_H, BookScreenColors.BORDER);

        // Item icon (16×16)
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(e.itemId()))
                .map(h -> h.value()).orElse(Items.BARRIER);
        g.renderItem(new ItemStack(item), rowX + 2, rowY + (ROW_H - 16) / 2);

        // Quantity + name
        String name = e.quantity() + "× " + prettify(e.itemId());
        int maxNameW = rowW / 2 - 22;
        while (font.width(name) > maxNameW && name.length() > 4)
            name = name.substring(0, name.length() - 1);
        g.drawString(font, name, rowX + 20, rowY + 3, BookScreenColors.DARK, false);

        // Urgency pill
        String urg = e.urgency();
        Pill.draw(g, font, rowX + 20, rowY + 13,
                urg, urgencyBg(urg), urgencyFg(urg));

        // Reward
        int coinX = rowX + rowW / 2;
        CoinRow.draw(g, e.bronzeReward(), coinX, rowY + (ROW_H - 16) / 2);

        // Claim button or status label
        if (isClaimable(e)) {
            int bx = rowX + rowW - 34, by = rowY + (ROW_H - 14) / 2;
            g.fill(bx, by, bx + 28, by + 14, BookScreenColors.HIGHLIGHT);
            g.renderOutline(bx, by, 28, 14, BookScreenColors.BORDER);
            int tw = font.width("Claim");
            g.drawString(font, "Claim", bx + (28 - tw) / 2, by + 3,
                    BookScreenColors.DARK, false);
        } else if (e.claimedBySelf()) {
            // Progress bar sub-line
            int barX = rowX + 20, barY = rowY + ROW_H - 10, barW = rowW / 2 - 24;
            float progress = e.quantity() == 0 ? 1f : (float) e.deliveredCount() / e.quantity();
            ProgressBar.draw(g, barX, barY, barW, 6, progress,
                    BookScreenColors.SIDEBAR, BookScreenColors.GREEN_TXT, BookScreenColors.BORDER);
        } else if (e.claimedByOther() && !e.claimingPlayerName().isEmpty()) {
            String msg = "(" + e.claimingPlayerName() + ")";
            g.drawString(font, msg, rowX + 20, rowY + ROW_H - 10,
                    BookScreenColors.LIGHT, false);
        } else if (!"OPEN".equals(e.status())) {
            g.drawString(font, e.status().toLowerCase(), rowX + rowW - 40,
                    rowY + (ROW_H - 8) / 2, BookScreenColors.MID, false);
        }
    }

    private boolean handleRowClick(CommissionEntry e, int btn, double relX, double relY) {
        if (!isClaimable(e)) return false;
        if (relX < pw - 34) return false;
        callback.onClaim(e.orderId());
        return true;
    }

    private boolean isClaimable(CommissionEntry e) {
        if (!"OPEN".equals(e.status())) return false;
        if (e.claimedBySelf()) return false;
        return filtered.stream().noneMatch(CommissionEntry::claimedBySelf);
    }

    private int urgencyBg(String u) {
        return switch (u.toUpperCase()) {
            case "MEDIUM"   -> 0xFFFFE0A0;
            case "HIGH"     -> BookScreenColors.AMBER;
            case "CRITICAL" -> BookScreenColors.RED_BG;
            default         -> BookScreenColors.SIDEBAR;
        };
    }
    private int urgencyFg(String u) {
        return switch (u.toUpperCase()) {
            case "CRITICAL" -> BookScreenColors.RED_TXT;
            case "HIGH"     -> BookScreenColors.DARK;
            default         -> BookScreenColors.MID;
        };
    }
    private String prettify(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }

    public boolean mouseClicked(double mx, double my, int button) {
        int BH = 11;
        // Filter strip row 1 (buttons 0-2)
        if (my >= fb1Y && my < fb1Y + BH) {
            if (mx >= fbX[0] && mx < fbX[0] + fbW[0]) { cycleStatus(); return true; }
            if (mx >= fbX[1] && mx < fbX[1] + fbW[1]) { cycleUrgency(); return true; }
            if (mx >= fbX[2] && mx < fbX[2] + fbW[2]) { cycleProf(); return true; }
        }
        // Filter strip row 2 (buttons 3-4)
        if (my >= fb2Y && my < fb2Y + BH) {
            if (mx >= fbX[3] && mx < fbX[3] + fbW[3]) {
                minReward = Math.max(0L, minReward - REWARD_STEP); needsRebuild = true; return true;
            }
            if (mx >= fbX[4] && mx < fbX[4] + fbW[4]) {
                minReward += REWARD_STEP; needsRebuild = true; return true;
            }
        }
        if (list != null && my > py + STRIP_H)
            return list.mouseClicked(mx, my, button);
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double dy) {
        if (list != null && my > py + STRIP_H)
            return list.mouseScrolled(mx, my, dy);
        return false;
    }

    private void cycleStatus()  {
        statusFilter  = StatusFilter.values()[(statusFilter.ordinal()  + 1) % StatusFilter.values().length];
        needsRebuild = true;
    }
    private void cycleUrgency() {
        urgencyFilter = UrgencyFilter.values()[(urgencyFilter.ordinal() + 1) % UrgencyFilter.values().length];
        needsRebuild = true;
    }
    private void cycleProf() {
        profFilter = ProfFilter.values()[(profFilter.ordinal() + 1) % ProfFilter.values().length];
        needsRebuild = true;
    }

    private void rebuildList() {
        filtered = allEntries.stream()
                .filter(this::matchesFilters)
                .sorted(Comparator
                        .comparing((CommissionEntry e) -> urgencyOrd(e.urgency())).reversed()
                        .thenComparing(Comparator.comparingLong(CommissionEntry::bronzeReward).reversed())
                        .thenComparing(e -> prettify(e.itemId())))
                .toList();

        list = new ScrollList<>(px, py + STRIP_H, pw, ph - STRIP_H, ROW_H, filtered,
                this::renderRow, this::handleRowClick);
    }

    private boolean matchesFilters(CommissionEntry e) {
        // Status filter
        switch (statusFilter) {
            case OPEN             -> { if (!"OPEN".equals(e.status())) return false; }
            case YOUR_CLAIMS      -> { if (!e.claimedBySelf()) return false; }
            case CLAIMED_BY_OTHERS -> { if (!e.claimedByOther()) return false; }
            default -> {}
        }
        // Urgency filter
        int ord = urgencyOrd(e.urgency());
        switch (urgencyFilter) {
            case MEDIUM_OR_ABOVE  -> { if (ord < 1) return false; }
            case HIGH_OR_ABOVE    -> { if (ord < 2) return false; }
            case CRITICAL_ONLY    -> { if (ord < 3) return false; }
            default -> {}
        }
        // Prof filter
        if (profFilter != ProfFilter.ALL
                && !profFilter.name().equalsIgnoreCase(e.relevantProfession()))
            return false;
        // Min reward
        if (e.bronzeReward() < minReward) return false;
        return true;
    }

    private int urgencyOrd(String u) {
        return switch (u.toUpperCase()) {
            case "LOW"      -> 0;
            case "MEDIUM"   -> 1;
            case "HIGH"     -> 2;
            case "CRITICAL" -> 3;
            default         -> 0;
        };
    }
}
