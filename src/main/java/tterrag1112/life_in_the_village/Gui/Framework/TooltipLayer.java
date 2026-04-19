package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deferred tooltip queue. Widgets call {@link #queue(int, int, List)} during
 * their normal render pass; the host screen then calls {@link #flush(GuiGraphics)}
 * after {@code super.render(...)} to paint all queued tooltips on top of every
 * other layer. {@link #reset()} should run at the start of each frame.
 *
 * <p>Backed by {@code GuiGraphics.renderTooltip} so the visuals match vanilla.
 */
public class TooltipLayer {

    private record Entry(int x, int y, List<Component> lines) {}

    private final List<Entry> pending = new ArrayList<>();

    /** Queues a multi-line tooltip to be rendered at (x,y) when {@link #flush} runs. */
    public void queue(int x, int y, List<Component> lines) {
        if (lines == null || lines.isEmpty()) return;
        pending.add(new Entry(x, y, lines));
    }

    /** Renders all queued tooltips, in queue order. Safe to call when nothing is pending. */
    public void flush(GuiGraphics g) {
        if (pending.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        for (Entry e : pending) {
            List<ClientTooltipComponent> comps = e.lines().stream()
                    .map(Component::getVisualOrderText)
                    .map(ClientTooltipComponent::create)
                    .toList();
            g.renderTooltip(font, comps, e.x(), e.y(),
                    DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    /** Clears the queue. Call once per frame, before any widget render passes. */
    public void reset() {
        pending.clear();
    }
}
