package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.NpcProfileActionPacket;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;
import tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket;
import tterrag1112.life_in_the_village.Networking.PrimeGiftPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Replaces the old {@code ActionBarPanel}. Renders the small set of
 * verbs that survive Track 4 (pure social verbs with no item/context
 * fit), plus a "Gift" primer button and a single context-appropriate
 * nav button.
 *
 * <h3>Survivors</h3>
 * Whitelist of verb ids kept on the profile (everything else moved to
 * physical/contextual triggers — see {@code NpcInteractionHandler} doc):
 * {@code compliment, insult, ask_about_life, ask_about, challenge,
 *  listen_in, tell_rumor, release_apprenticeship, petition_for_office,
 *  appoint_to_office, resign_from_office, petition_leader,
 *  accuse_of_crime}.
 */
public class SocialVerbsPanel implements NpcProfilePanel {

    private static final Set<String> WHITELIST = Set.of(
            "compliment", "insult",
            "ask_about_life", "ask_about",
            "challenge",
            "listen_in", "tell_rumor",
            "release_apprenticeship",
            "petition_for_office", "appoint_to_office", "resign_from_office",
            "petition_leader",
            "accuse_of_crime"
    );

    private final List<StyledButton> buttons = new ArrayList<>();
    private final Consumer<StyledButton> addWidget;
    private final Consumer<StyledButton> removeWidget;

    public SocialVerbsPanel(Consumer<StyledButton> addWidget,
                            Consumer<StyledButton> removeWidget) {
        this.addWidget    = addWidget;
        this.removeWidget = removeWidget;
    }

    @Override
    public void onSnapshotUpdated(NpcProfileSnapshot snapshot) {
        rebuildButtons(snapshot);
    }

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        g.drawString(font, "Social", area.x() + 4, area.y() + 6,
                BookScreenColors.DARK, false);
        if (buttons.isEmpty()) {
            g.drawString(font, "No social actions available.",
                    area.x() + 4, area.y() + 20, BookScreenColors.LIGHT, false);
        }
    }

    private void rebuildButtons(NpcProfileSnapshot s) {
        buttons.forEach(removeWidget);
        buttons.clear();

        int idx = 0;
        UUID npcId = s.npcId();

        // 1. Gift primer (always available; server-side cooldown).
        addButton(StyledButton.builder(Component.literal("Gift…"),
                        b -> ClientPacketDistributor.sendToServer(new PrimeGiftPacket(npcId)))
                .pos(0, idx * 24).size(140, 20).build());
        idx++;

        // 2. Nav button — single context-appropriate destination
        //    resolved server-side per Step-5 priority.
        if (s.hasNavTarget() && s.navTargetKind() != NpcProfileSnapshot.NavTargetKind.NONE) {
            String label = switch (s.navTargetKind()) {
                case OFFICE_SCREEN      -> "Open Office";
                case COMPANY_WORKER     -> "Manage Worker";
                case PARTY_STATUS       -> "Party Status";
                case PROFESSION_DEFAULT -> "Profession Details";
                case NONE               -> "";
            };
            idx = addNavButton(npcId, label, idx);
        }

        // 3. Surviving verb buttons (whitelisted).
        for (int i = 0; i < s.availableVerbIds().size(); i++) {
            String id = s.availableVerbIds().get(i);
            if (!WHITELIST.contains(id)) continue;
            String label = i < s.verbLabels().size() ? s.verbLabels().get(i) : id;
            idx = addVerbButton(npcId, label, id, idx);
        }
    }

    private int addVerbButton(UUID npcId, String label, String verbId, int idx) {
        addButton(StyledButton.builder(Component.literal(label),
                        b -> ClientPacketDistributor.sendToServer(
                                new PlayerVerbInvokePacket(npcId, verbId, java.util.Map.of())))
                .pos(0, idx * 24).size(140, 20).build());
        return idx + 1;
    }

    private int addNavButton(UUID npcId, String label, int idx) {
        addButton(StyledButton.builder(Component.literal(label),
                        b -> ClientPacketDistributor.sendToServer(
                                new NpcProfileActionPacket(npcId,
                                        NpcProfileActionPacket.Action.OPEN_NAV_TARGET)))
                .pos(0, idx * 24).size(140, 20).build());
        return idx + 1;
    }

    private void addButton(StyledButton b) {
        buttons.add(b);
        addWidget.accept(b);
    }

    /** Exposed so the host screen can reposition buttons within the page area. */
    public List<StyledButton> getButtons() { return buttons; }
}
