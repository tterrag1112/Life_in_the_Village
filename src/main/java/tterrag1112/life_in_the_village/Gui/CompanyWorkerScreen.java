package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

public class CompanyWorkerScreen extends Screen {

    private static final int W = 240, H = 200;
    private static final int COL_PARCHMENT = 0xFFF5F0E0;
    private static final int COL_BORDER    = 0xFFB8A878;
    private static final int COL_DARK      = 0xFF3B2E1A;
    private static final int COL_MID       = 0xFF7A6040;
    private static final int COL_HIGHLIGHT = 0xFFD4C48A;
    private static final int COL_GOLD      = 0xFFB8860B;
    private static final int COL_GREEN_TXT = 0xFF2D6B1A;

    // Packet data
    private final tterrag1112.life_in_the_village.Company.OpenCompanyWorkerPacket data;
    private int panelX, panelY;

    // Available items to assign — populated from building storage
    private int selectedItemIndex = -1;
    private int targetCount = 8;

    public CompanyWorkerScreen(tterrag1112.life_in_the_village.Company.OpenCompanyWorkerPacket data) {
        super(Component.literal(data.npcName()));
        this.data = data;
    }

    /** Called server-side: opens the worker screen for the owner. */
    public static void open(net.minecraft.server.level.ServerPlayer player,
                            tterrag1112.life_in_the_village.Entities.custom
                                    .TownspersonMob npc,
                            Company company) {
        // Delegate to sendOpenPacket via level
        var level = (net.minecraft.server.level.ServerLevel) npc.level();
        sendOpenPacket(player, npc.getUUID(), company.getCompanyId(),
                level, CompanySavedData.get(level), VillageSavedData.get(level));
    }

    public static void sendOpenPacket(
            net.minecraft.server.level.ServerPlayer player,
            UUID npcId, UUID companyId,
            net.minecraft.server.level.ServerLevel level,
            CompanySavedData cdata, VillageSavedData vdata) {

        Company company = cdata.getById(companyId).orElse(null);
        if (company == null) return;

        Company.CompanyWorker worker = company.getWorker(npcId).orElse(null);

        String npcName = level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom
                                .TownspersonMob.class,
                        new net.minecraft.world.phys.AABB(
                                -30000000, -2048, -30000000,
                                30000000,  2048,  30000000),
                        mob -> mob.getUUID().equals(npcId)
                ).stream().findFirst()
                .map(npc -> npc.getNpcName())
                .orElse("Worker");

        // Available items from the assigned building's storage
        List<tterrag1112.life_in_the_village.Company.OpenCompanyWorkerPacket.AvailableItem> items = new ArrayList<>();
        if (worker != null) {
            vdata.getBuildingById(worker.assignedBuildingId())
                    .ifPresent(building -> {
                        for (var container :
                                tterrag1112.life_in_the_village.Village
                                        .BuildingStorageAccess
                                        .findInventories(level, building)) {
                            for (int i = 0; i < container.getContainerSize(); i++) {
                                var stack = container.getItem(i);
                                if (stack.isEmpty()) continue;
                                String itemId = net.minecraft.core.registries
                                        .BuiltInRegistries.ITEM
                                        .getKey(stack.getItem()).toString();
                                String displayName = stack.getHoverName()
                                        .getString();
                                items.add(new tterrag1112.life_in_the_village.Company.OpenCompanyWorkerPacket.AvailableItem(
                                        itemId, displayName, stack.getCount()));
                            }
                        }
                    });
        }

        long minWage = company.getEffectiveMinWage(vdata);
        long wage    = worker != null ? worker.wagePerDay() : minWage;
        String currentItemId = worker != null
                ? worker.assignedItemId() : "";
        int currentTarget = worker != null
                ? worker.dailyTargetCount() : 8;
        String role = worker != null
                ? worker.role().name() : Company.WorkerRole.PRODUCER.name();

        PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Company.OpenCompanyWorkerPacket(npcId, companyId, npcName,
                        wage, minWage, currentItemId, currentTarget,
                        role, items));
    }

    @Override
    protected void init() {
        panelX = (width  - W) / 2;
        panelY = (height - H) / 2;
        buildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void buildWidgets() {
        clearWidgets();
        int cx = panelX + W / 2;

        // Role selector buttons
        int rx = panelX + 8;
        for (Company.WorkerRole role : Company.WorkerRole.values()) {
            final Company.WorkerRole r = role;
            boolean active = data.role().equals(role.name());
            addRenderableWidget(Button.builder(
                            Component.literal(formatRole(role.name())),
                            b -> sendRoleChange(r))
                    .pos(rx, panelY + 36)
                    .size(60, 14).build());
            // Note: button styling for active state is done in render
            rx += 64;
        }

        // Item list — clickable rows drawn in render(), buttons for +/- count
        addRenderableWidget(Button.builder(Component.literal("-"),
                        b -> { targetCount = Math.max(1, targetCount - 1);
                            buildWidgets(); })
                .pos(cx - 28, panelY + 140).size(16, 14).build());

        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> { targetCount = Math.min(64, targetCount + 1);
                            buildWidgets(); })
                .pos(cx + 10, panelY + 140).size(16, 14).build());

        // Confirm assignment
        addRenderableWidget(Button.builder(
                        Component.literal("Assign Task"),
                        b -> {
                            if (selectedItemIndex >= 0
                                    && selectedItemIndex < data.availableItems().size()) {
                                String itemId = data.availableItems()
                                        .get(selectedItemIndex).itemId();
                                ClientPacketDistributor.sendToServer(
                                        new CompanyActionPacket(
                                                CompanyActionPacket.ActionType
                                                        .ASSIGN_WORKER_TASK,
                                                data.companyId(),
                                                data.npcId(),
                                                itemId,
                                                0L,
                                                targetCount));
                            }
                        })
                .pos(panelX + 8, panelY + H - 26)
                .size(W - 16, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Panel background
        g.fill(panelX, panelY, panelX + W, panelY + H, COL_PARCHMENT);
        g.renderOutline(panelX, panelY, W, H, COL_BORDER);
        g.renderOutline(panelX + 2, panelY + 2, W - 4, H - 4, COL_HIGHLIGHT);

        // Header
        g.fill(panelX, panelY, panelX + W, panelY + 18, COL_HIGHLIGHT);
        g.drawCenteredString(font, data.npcName(),
                panelX + W / 2, panelY + 5, COL_DARK);

        // Role label
        g.drawString(font, "Role:", panelX + 8, panelY + 24, COL_MID, false);

        // Current task
        int y = panelY + 58;
        g.drawString(font, "Assign item to produce/sell:",
                panelX + 8, y, COL_MID, false);
        y += 12;

        // Item list
        int listH = 56;
        g.fill(panelX + 8, y, panelX + W - 8, y + listH, 0xFFEEE8D0);
        g.renderOutline(panelX + 8, y, W - 16, listH, COL_BORDER);

        int iy = y + 2;
        for (int i = 0; i < data.availableItems().size()
                && iy < y + listH - 8; i++) {
            var item = data.availableItems().get(i);
            boolean selected = i == selectedItemIndex;
            if (selected)
                g.fill(panelX + 8, iy, panelX + W - 8, iy + 11, COL_HIGHLIGHT);
            g.drawString(font, item.displayName() + " (" + item.stockCount() + ")",
                    panelX + 12, iy + 1,
                    selected ? COL_DARK : COL_MID, false);
            iy += 12;
        }

        // Count display
        y += listH + 8;
        g.drawString(font, "Daily target: " + targetCount,
                panelX + W / 2 - 30, y + 2, COL_DARK, false);

        // Wage display
        g.drawString(font, "Wage: " + formatBronze(data.wage()) + "/day",
                panelX + 8, panelY + H - 42, COL_GOLD, false);
        if (data.wage() < data.minWage()) {
            g.drawString(font, "(below min. wage!)",
                    panelX + 100, panelY + H - 42, 0xFFCC2222, false);
        }

        // Current assignment
        if (!data.currentItemId().isEmpty()) {
            g.drawString(font, "Current: " + formatItemId(data.currentItemId())
                            + " x" + data.currentTargetCount(),
                    panelX + 8, panelY + H - 52, COL_GREEN_TXT, false);
        }

        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(
            net.minecraft.client.input.MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        // Item list click detection
        int listTop  = panelY + 70;
        int listLeft = panelX + 8;
        if (event.x() >= listLeft && event.x() <= panelX + W - 8
                && event.y() >= listTop && event.y() < listTop + 56) {
            int clickedIndex = (int)((event.y() - listTop) / 12);
            if (clickedIndex >= 0
                    && clickedIndex < data.availableItems().size()) {
                selectedItemIndex = clickedIndex;
            }
        }
        return super.mouseClicked(event, consumed);
    }

    private void sendRoleChange(Company.WorkerRole role) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.HIRE_NPC,
                data.companyId(), data.npcId(), "", 0L, role.ordinal()));
    }

    private String formatBronze(long bronze) {
        long gold = bronze / 10000L, silver = (bronze % 10000L) / 100L,
                b = bronze % 100L;
        StringBuilder sb = new StringBuilder();
        if (gold > 0) sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
    }

    private String formatRole(String role) {
        return role == null || role.isEmpty() ? "—"
                : role.charAt(0) + role.substring(1).toLowerCase();
    }

    private String formatItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "—";
        String path = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        return path.replace('_', ' ');
    }
}