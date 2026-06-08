package tterrag1112.life_in_the_village.Gui.NpcProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Maps each {@link Section} to its panel implementation. */
public final class NpcProfilePanelRegistry {

    private NpcProfilePanelRegistry() {}

    /** Navigation sections, in sidebar display order. */
    public enum Section {
        IDENTITY("Identity"),
        FAMILY("Family"),
        WORK("Work"),
        REPUTATION("Reputation"),
        RELIGION("Religion"),
        DIALOGUE("Dialogue"),
        ACTIONS("Actions");

        public final String label;
        Section(String label) { this.label = label; }
    }

    /**
     * Builds a fresh panel map. {@code addWidget}/{@code removeWidget} are
     * forwarded to {@link SocialVerbsPanel} so it can manage its buttons.
     */
    public static Map<Section, NpcProfilePanel> build(
            Consumer<tterrag1112.life_in_the_village.Gui.Framework.StyledButton> addWidget,
            Consumer<tterrag1112.life_in_the_village.Gui.Framework.StyledButton> removeWidget) {
        Map<Section, NpcProfilePanel> map = new LinkedHashMap<>();
        map.put(Section.IDENTITY,   new IdentityPanel());
        map.put(Section.FAMILY,     new FamilyPanel());
        map.put(Section.WORK,       new WorkPanel());
        map.put(Section.REPUTATION, new ReputationPanel());
        map.put(Section.RELIGION,   new ReligionPanel());
        map.put(Section.DIALOGUE,   new DialoguePanel());
        map.put(Section.ACTIONS,    new SocialVerbsPanel(addWidget, removeWidget));
        return map;
    }
}
