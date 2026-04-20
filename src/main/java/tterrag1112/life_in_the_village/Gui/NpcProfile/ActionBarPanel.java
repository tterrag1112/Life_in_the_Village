package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.NpcProfileActionPacket;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Renders the available action buttons for this NPC based on the action-bar
 * flags in the snapshot. Buttons fire {@link NpcProfileActionPacket} to the
 * server when pressed.
 *
 * <p>This panel is special: it owns a mutable button list that the host
 * screen must register with {@code addWidget} / {@code removeWidget}.
 */
public class ActionBarPanel implements NpcProfilePanel {

    private final List<StyledButton> buttons = new ArrayList<>();
    private final Consumer<StyledButton> addWidget;
    private final Consumer<StyledButton> removeWidget;
    private UUID currentNpcId;

    /**
     * @param addWidget    callback the host screen uses to register a button
     * @param removeWidget callback the host screen uses to unregister a button
     */
    public ActionBarPanel(Consumer<StyledButton> addWidget,
                          Consumer<StyledButton> removeWidget) {
        this.addWidget    = addWidget;
        this.removeWidget = removeWidget;
    }

    @Override
    public void onSnapshotUpdated(NpcProfileSnapshot snapshot) {
        this.currentNpcId = snapshot.npcId();
        rebuildButtons(snapshot);
    }

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        g.drawString(font, "Actions", area.x() + 4, area.y() + 6,
                BookScreenColors.DARK, false);
        if (buttons.isEmpty()) {
            g.drawString(font, "No actions available for this NPC.",
                    area.x() + 4, area.y() + 20, BookScreenColors.LIGHT, false);
        }
    }

    private void rebuildButtons(NpcProfileSnapshot s) {
        buttons.forEach(removeWidget);
        buttons.clear();

        int bx = 0;   // Positioned by the host screen via area; set by first call
        // Buttons are stacked vertically; the host screen will reposition them
        // via the stored list. We use placeholder coords; the screen moves them.
        int idx = 0;

        if (s.canTrade())
            idx = addBtn(s.npcId(), "Trade",             NpcProfileActionPacket.Action.TRADE, idx);
        if (s.canOpenGuild())
            idx = addBtn(s.npcId(), "Guild Board",       NpcProfileActionPacket.Action.OPEN_GUILD, idx);
        if (s.canAssignWork())
            idx = addBtn(s.npcId(), "Assign Work",       NpcProfileActionPacket.Action.ASSIGN_WORK, idx);
        if (s.canOpenCompanyWorker())
            idx = addBtn(s.npcId(), "Manage Worker",     NpcProfileActionPacket.Action.OPEN_COMPANY_WORKER, idx);
        if (s.canShowVillageBook())
            idx = addBtn(s.npcId(), "Village Book",      NpcProfileActionPacket.Action.SHOW_VILLAGE_BOOK, idx);
        if (s.canShowCraftingOrders())
            idx = addBtn(s.npcId(), "Crafting Orders",   NpcProfileActionPacket.Action.SHOW_CRAFTING_ORDERS, idx);
        if (s.canRentStall())
            idx = addBtn(s.npcId(), "Rent/Renew Stall",  NpcProfileActionPacket.Action.RENT_STALL, idx);
        // Gift is always available
        addBtn(s.npcId(), "Give Gift (hand)", NpcProfileActionPacket.Action.GIVE_GIFT, idx);
    }

    /** Creates and registers one button at a placeholder position (0,0); returns next index. */
    private int addBtn(UUID npcId, String label,
                       NpcProfileActionPacket.Action action, int idx) {
        StyledButton btn = StyledButton.builder(
                        net.minecraft.network.chat.Component.literal(label),
                        b -> PacketDistributor.sendToServer(
                                new NpcProfileActionPacket(npcId, action)))
                .pos(0, idx * 24)
                .size(140, 20)
                .build();
        buttons.add(btn);
        addWidget.accept(btn);
        return idx + 1;
    }

    /** Returns the buttons so the host screen can reposition them. */
    public List<StyledButton> getButtons() { return buttons; }
}
