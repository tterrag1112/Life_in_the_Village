package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Items.JobContractTerms;
import tterrag1112.life_in_the_village.Networking.OpenScribeCounterPacket;
import tterrag1112.life_in_the_village.Networking.ScribeCounterActionPacket;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.UUID;

/**
 * Three-tab composer for the SCRIBE business-front primary.
 *
 * <p>Minimum-viable UI: an EditBox per text field, cycling buttons for
 * dropdown-like fields (profession, rank, duration), and a Submit
 * button per tab. Server-side validation and minting live in
 * {@code Npc.Scribal.ScribeCounter}.</p>
 */
public class ScribeCounterScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 220;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_W, PANEL_H, 0, 6);

    public enum Tab { JOB_CONTRACT, LETTER, BOOK_COPY }

    private final UUID scribeId;
    private final String scribeName;
    private final String villageName;
    private Tab activeTab = Tab.JOB_CONTRACT;

    private int panelX, panelY;

    // JOB_CONTRACT inputs.
    private int professionIdx = 0;
    private static final Profession[] PROFESSIONS = Profession.values();
    private int rankIdx = 0;
    private static final JobContractTerms.Rank[] RANKS = JobContractTerms.Rank.values();
    private EditBox salaryBox;
    private EditBox targetItemBox;
    private EditBox durationBox;

    // LETTER inputs.
    private EditBox recipientBox;
    private EditBox letterBodyBox;

    // BOOK_COPY inputs.
    private EditBox sourceBookBox;

    public ScribeCounterScreen(OpenScribeCounterPacket data) {
        super(Component.literal("Scribe — " + data.scribeName()));
        this.scribeId    = data.scribeId();
        this.scribeName  = data.scribeName();
        this.villageName = data.villageName();
    }

    /** Server-side handler entry point. */
    public static void openOnClient(OpenScribeCounterPacket data) {
        Minecraft.getInstance().setScreen(new ScribeCounterScreen(data));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        // Tab buttons.
        addRenderableWidget(StyledButton.builder(Component.literal("Contract"),
                        b -> { activeTab = Tab.JOB_CONTRACT; rebuild(); })
                .pos(panelX + 8,  panelY + 20).size(80, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Letter"),
                        b -> { activeTab = Tab.LETTER; rebuild(); })
                .pos(panelX + 96, panelY + 20).size(80, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Book Copy"),
                        b -> { activeTab = Tab.BOOK_COPY; rebuild(); })
                .pos(panelX + 184, panelY + 20).size(80, 18).build());

        // Per-tab content.
        rebuildContent();

        // Close button.
        addRenderableWidget(StyledButton.builder(Component.literal("Close"),
                        b -> onClose())
                .pos(panelX + PANEL_W - 68, panelY + PANEL_H - 22)
                .size(60, 18).build());
    }

    private void rebuild() {
        // Re-init clears widgets and rebuilds.
        this.clearWidgets();
        init();
    }

    private void rebuildContent() {
        int x = panelX + 12;
        int y = panelY + 50;
        switch (activeTab) {
            case JOB_CONTRACT -> {
                addRenderableWidget(StyledButton.builder(
                                Component.literal("Profession: " + PROFESSIONS[professionIdx].name()),
                                b -> {
                                    professionIdx = (professionIdx + 1) % PROFESSIONS.length;
                                    rebuild();
                                })
                        .pos(x, y).size(256, 18).build());
                y += 22;
                addRenderableWidget(StyledButton.builder(
                                Component.literal("Rank: " + RANKS[rankIdx].name()),
                                b -> { rankIdx = (rankIdx + 1) % RANKS.length; rebuild(); })
                        .pos(x, y).size(256, 18).build());
                y += 22;
                salaryBox = newBox(x, y, 256, "Salary (bronze)", "0");
                y += 22;
                targetItemBox = newBox(x, y, 256,
                        "Commission target item id (or empty)", "");
                y += 22;
                durationBox = newBox(x, y, 256,
                        "Duration in days (0 = indefinite)", "0");
                y += 28;
                addRenderableWidget(StyledButton.builder(
                                Component.literal("Draft Contract"),
                                b -> submitJobContract())
                        .pos(x, y).size(256, 20).build());
            }
            case LETTER -> {
                recipientBox = newBox(x, y, 256, "Recipient name", "");
                y += 22;
                letterBodyBox = newBox(x, y, 256, "Letter body", "");
                y += 80; // body box can grow visually
                addRenderableWidget(StyledButton.builder(
                                Component.literal("Commission Letter"),
                                b -> submitLetter())
                        .pos(x, y).size(256, 20).build());
            }
            case BOOK_COPY -> {
                sourceBookBox = newBox(x, y, 256, "Source book title or id", "");
                y += 22;
                addRenderableWidget(StyledButton.builder(
                                Component.literal("Commission Book Copy"),
                                b -> submitBookCopy())
                        .pos(x, y).size(256, 20).build());
                // Tip rendered in render() once activeTab == BOOK_COPY.
            }
        }
    }

    private EditBox newBox(int x, int y, int w, String hint, String initial) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(256);
        box.setValue(initial);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        gfx.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(gfx, panelX, panelY, DIMS, Chrome.PARCHMENT);
        gfx.drawString(font, "Scribe — " + scribeName,
                panelX + 8, panelY + 6, BookScreenColors.DARK, false);
        if (!villageName.isEmpty()) {
            gfx.drawString(font, "in " + villageName,
                    panelX + 8, panelY + 6 + 10, BookScreenColors.LIGHT, false);
        }
        if (activeTab == Tab.BOOK_COPY) {
            gfx.drawString(font,
                    "Tip: right-click holding a book for the fast path.",
                    panelX + 12, panelY + 100, BookScreenColors.LIGHT, false);
        }
        super.render(gfx, mouseX, mouseY, partial);
    }

    // ── Submit handlers ────────────────────────────────────────────────────

    private void submitJobContract() {
        long salary = parseLong(salaryBox);
        int duration = (int) parseLong(durationBox);
        ClientPacketDistributor.sendToServer(new ScribeCounterActionPacket(
                scribeId,
                ScribeCounterActionPacket.Tab.JOB_CONTRACT,
                PROFESSIONS[professionIdx].name(),
                RANKS[rankIdx].name(),
                salary,
                targetItemBox == null ? "" : targetItemBox.getValue().trim(),
                duration,
                "", "",
                ""));
        onClose();
    }

    private void submitLetter() {
        ClientPacketDistributor.sendToServer(new ScribeCounterActionPacket(
                scribeId,
                ScribeCounterActionPacket.Tab.LETTER,
                "", "", 0L, "", 0,
                recipientBox == null ? "" : recipientBox.getValue().trim(),
                letterBodyBox == null ? "" : letterBodyBox.getValue(),
                ""));
        onClose();
    }

    private void submitBookCopy() {
        ClientPacketDistributor.sendToServer(new ScribeCounterActionPacket(
                scribeId,
                ScribeCounterActionPacket.Tab.BOOK_COPY,
                "", "", 0L, "", 0,
                "", "",
                sourceBookBox == null ? "" : sourceBookBox.getValue().trim()));
        onClose();
    }

    private static long parseLong(EditBox box) {
        if (box == null) return 0L;
        try { return Long.parseLong(box.getValue().trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

}
