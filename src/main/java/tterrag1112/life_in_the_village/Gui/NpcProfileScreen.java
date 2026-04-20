package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.Framework.Portrait;
import tterrag1112.life_in_the_village.Gui.Framework.PortraitCache;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Gui.NpcProfile.ActionBarPanel;
import tterrag1112.life_in_the_village.Gui.NpcProfile.IdentityPanel;
import tterrag1112.life_in_the_village.Gui.NpcProfile.NpcProfilePanel;
import tterrag1112.life_in_the_village.Gui.NpcProfile.NpcProfilePanelRegistry;
import tterrag1112.life_in_the_village.Networking.CloseNpcProfilePacket;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The unified NPC profile screen. Uses {@code Chrome.COMPACT} (320×240)
 * with a sidebar for section navigation and a right-hand page area for the
 * active panel. Opened by {@code OpenNpcProfilePacket}; refreshed by
 * {@code NpcProfileSyncPacket}.
 */
public class NpcProfileScreen extends Screen {

    // ─────────────────────────────────────────────────────────────────────────
    // Layout constants derived from COMPACT chrome
    // ─────────────────────────────────────────────────────────────────────────

    private static final Chrome.Dims DIMS = Chrome.COMPACT;
    private static final int ROW_H = 20;

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private NpcProfileSnapshot snapshot;
    private NpcProfilePanelRegistry.Section activeSection =
            NpcProfilePanelRegistry.Section.IDENTITY;

    private Map<NpcProfilePanelRegistry.Section, NpcProfilePanel> panels;
    private Sidebar<NpcProfilePanelRegistry.Section> sidebar;
    private final TooltipLayer tooltips = new TooltipLayer();

    private int panelX, panelY;
    private NpcProfilePanel.PageArea pageArea;

    private TownspersonMob previewEntity;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public NpcProfileScreen(NpcProfileSnapshot snapshot) {
        super(Component.literal(snapshot.name()));
        this.snapshot = snapshot;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width  - DIMS.w()) / 2;
        panelY = (height - DIMS.h()) / 2;

        // Sidebar entries
        List<Sidebar.Entry<NpcProfilePanelRegistry.Section>> entries = new ArrayList<>();
        for (NpcProfilePanelRegistry.Section s : NpcProfilePanelRegistry.Section.values()) {
            entries.add(new Sidebar.Entry<>(s, s.label, true));
        }

        sidebar = new Sidebar<>(panelX + 2, panelY + 14,
                DIMS.sidebarW() - 2, ROW_H,
                entries,
                () -> activeSection,
                sec -> { activeSection = sec; layoutActionButtons(); });

        // Page area (right of sidebar)
        int pageX = panelX + DIMS.sidebarW() + DIMS.pagePad();
        int pageW = DIMS.w() - DIMS.sidebarW() - DIMS.pagePad() * 2;
        int pageH = DIMS.h() - 14;
        pageArea = new NpcProfilePanel.PageArea(pageX, panelY + 14, pageW, pageH);

        // Build panels — ActionBarPanel needs add/remove widget callbacks
        panels = NpcProfilePanelRegistry.build(
                btn -> { addRenderableWidget(btn); btn.visible = false; },
                btn -> { removeWidget(btn); });

        // Notify all panels of initial snapshot
        panels.values().forEach(p -> p.onSnapshotUpdated(snapshot));

        // Build or fetch preview entity from cache, pass to IdentityPanel
        previewEntity = PortraitCache.getOrCreate(snapshot.npcId(), buildAppearance(snapshot));
        pushPreviewEntity();

        // Layout action buttons for initial section
        layoutActionButtons();
    }

    /** Repositions ActionBarPanel buttons inside the page area. */
    private void layoutActionButtons() {
        NpcProfilePanel panel = panels.get(NpcProfilePanelRegistry.Section.ACTIONS);
        if (!(panel instanceof ActionBarPanel abp)) return;

        boolean actionsActive = activeSection == NpcProfilePanelRegistry.Section.ACTIONS;
        int bx = pageArea.x() + 4;
        int by = pageArea.y() + 22;
        int idx = 0;
        for (StyledButton btn : abp.getButtons()) {
            btn.setX(bx);
            btn.setY(by + idx * 24);
            btn.visible = actionsActive;
            idx++;
        }
    }

    @Override
    public void onClose() {
        previewEntity = null;
        ClientPacketDistributor.sendToServer(new CloseNpcProfilePacket(snapshot.npcId()));
        super.onClose();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sync from server
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by {@code NpcProfileSyncPacket.handle} on the client thread. */
    public void applySync(NpcProfileSnapshot fresh) {
        boolean appearanceChanged = fresh.skinId() != snapshot.skinId()
                || fresh.hairStyle() != snapshot.hairStyle()
                || fresh.hairColor() != snapshot.hairColor()
                || fresh.age() != snapshot.age()
                || !fresh.professionName().equals(snapshot.professionName());
        this.snapshot = fresh;
        panels.values().forEach(p -> p.onSnapshotUpdated(fresh));

        if (appearanceChanged) {
            previewEntity = PortraitCache.rebuild(fresh.npcId(), buildAppearance(fresh));
            pushPreviewEntity();
        }
        layoutActionButtons();
    }

    private Portrait.AppearanceSnapshot buildAppearance(NpcProfileSnapshot s) {
        return new Portrait.AppearanceSnapshot(
                s.name(), s.isMale(), s.age(), s.skinId(), s.hairStyle(),
                s.hairColor(), s.professionName(), null);
    }

    private void pushPreviewEntity() {
        NpcProfilePanel panel = panels.get(NpcProfilePanelRegistry.Section.IDENTITY);
        if (panel instanceof IdentityPanel ip) {
            ip.setPreviewEntity(previewEntity);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        tooltips.reset();

        // Dim background
        g.fill(0, 0, width, height, 0xC0000000);

        // Chrome
        Chrome.draw(g, panelX, panelY, DIMS);
        Chrome.drawSidebarBg(g, panelX, panelY, DIMS);

        // Title bar
        g.drawCenteredString(font, snapshot.name(),
                panelX + DIMS.sidebarW() + (DIMS.w() - DIMS.sidebarW()) / 2,
                panelY + 3, BookScreenColors.DARK);

        // Sidebar
        sidebar.render(g, mouseX, mouseY);

        // Active panel
        NpcProfilePanel panel = panels.get(activeSection);
        if (panel != null) {
            panel.render(g, font, pageArea, snapshot, mouseX, mouseY);
        }

        // Buttons (registered as widgets, painted by super)
        super.render(g, mouseX, mouseY, pt);

        // Tooltips on top
        tooltips.flush(g);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (sidebar.mouseClicked(mx, my)) return true;
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event);
    }
}
