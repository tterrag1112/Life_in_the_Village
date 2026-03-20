package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.CompanyManagementScreen;
import tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.UUID;

import static tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier.TOWN;

public record CompanyActionPacket(
        ActionType action,
        UUID companyId,
        UUID targetId,     // npcId, buildingId, etc.
        String strParam,   // item id, company name, etc.
        long longParam,    // wage, price, etc.
        int intParam       // hour, count, etc.
) implements CustomPacketPayload {

    public enum ActionType {
        // Company management
        FOUND_COMPANY,          // strParam=name, targetId=homeVillageId
        RENAME_COMPANY,         // strParam=newName
        DISSOLVE_COMPANY,
        DEPOSIT_TO_TREASURY,    // longParam=amount

        // Worker management
        HIRE_NPC,               // targetId=npcId, intParam=role ordinal
        FIRE_NPC,               // targetId=npcId
        SET_WORKER_WAGE,        // targetId=npcId, longParam=wage
        ASSIGN_WORKER_TASK,     // targetId=npcId, strParam=itemId, intParam=count

        // Schedule
        SET_WORK_HOURS,         // intParam=startHour, longParam=endHour

        // Prices
        SET_ITEM_PRICE,         // strParam=itemId, longParam=price

        // Buildings
        ADD_BUILDING,           // targetId=buildingId
    }

    public static final Type<CompanyActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "company_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            CompanyActionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.companyId());
                buf.writeUUID(pkt.targetId());
                buf.writeUtf(pkt.strParam());
                buf.writeVarLong(pkt.longParam());
                buf.writeVarInt(pkt.intParam());
            },
            buf -> new CompanyActionPacket(
                    ActionType.valueOf(buf.readUtf()),
                    buf.readUUID(), buf.readUUID(),
                    buf.readUtf(), buf.readVarLong(), buf.readVarInt())
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(CompanyActionPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = (ServerLevel) player.level();
            CompanySavedData data    = CompanySavedData.get(level);
            VillageSavedData vdata   = VillageSavedData.get(level);

            switch (pkt.action()) {

                case FOUND_COMPANY -> {
                    // Validate: village must be TOWN tier or above
                    UUID villageId = pkt.targetId();
                    var village = vdata.getVillageById(villageId).orElse(null);
                    if (village == null) return;
                    int buildings = village.getBuildingIds().size();
                    var tier = VillageSizeTier. fromBuildingCount(buildings);
                    if (tier.ordinal() == TOWN.ordinal()) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "You need a Town-tier village to found a company."),
                                false);
                        return;
                    }
                    // One company per player per village for now
                    boolean alreadyHas = data.getByOwner(player.getUUID())
                            .stream().anyMatch(c ->
                                    c.getHomeVillageId().equals(villageId));
                    if (alreadyHas) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "You already have a company based in this village."),
                                false);
                        return;
                    }
                    Company company = Company.create(
                            pkt.strParam(), player.getUUID(), villageId);
                    data.addCompany(company);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                            "Company \"" + company.getName()
                                                    + "\" founded successfully!")
                                    .withStyle(net.minecraft.ChatFormatting.GOLD),
                            false);
                    // Re-open management screen with fresh data
                    CompanyManagementScreen.sendOpenPacket(
                            player, company.getCompanyId(), level, data, vdata);
                }

                case RENAME_COMPANY -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    c.setName(pkt.strParam());
                    data.markDirty();
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case HIRE_NPC -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    UUID npcId = pkt.targetId();
                    // Check NPC is unemployed or eligible
                    var npc = level.getEntitiesOfClass(
                            tterrag1112.life_in_the_village.Entities.custom
                                    .TownspersonMob.class,
                            player.getBoundingBox().inflate(32),
                            mob -> mob.getUUID().equals(npcId)
                                    && !mob.isCompanyWorker()
                    ).stream().findFirst().orElse(null);
                    if (npc == null) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "NPC not found or already employed."),
                                false);
                        return;
                    }
                    Company.WorkerRole role =
                            Company.WorkerRole.values()[
                                    Math.max(0, Math.min(
                                            pkt.intParam(),
                                            Company.WorkerRole.values().length - 1))];
                    long minWage = c.getEffectiveMinWage(vdata);
                    Company.CompanyWorker worker = new Company.CompanyWorker(
                            npcId, role,
                            c.getBuildingIds().isEmpty()
                                    ? UUID.randomUUID() // placeholder
                                    : c.getBuildingIds().get(0),
                            Math.max(minWage, 8L), // default wage
                            level.getGameTime(), "", 8);
                    c.addWorker(worker);
                    npc.setCompanyId(c.getCompanyId());
                    data.markDirty();
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "[" + npc.getNpcName()
                                            + "] I am now working for "
                                            + c.getName() + ". Thank you!"),
                            false);
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case FIRE_NPC -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    UUID npcId = pkt.targetId();
                    c.removeWorker(npcId);
                    // Clear company link from NPC
                    level.getEntitiesOfClass(
                                    tterrag1112.life_in_the_village.Entities.custom
                                            .TownspersonMob.class,
                                    new net.minecraft.world.phys.AABB(
                                            -30000000, -2048, -30000000,
                                            30000000,  2048,  30000000),
                                    mob -> mob.getUUID().equals(npcId)
                            ).stream().findFirst()
                            .ifPresent(npc -> npc.clearCompanyId());
                    data.markDirty();
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case SET_WORKER_WAGE -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    long minWage = c.getEffectiveMinWage(vdata);
                    long wage = Math.max(minWage, pkt.longParam());
                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(w.withWage(wage)));
                    data.markDirty();
                    refreshWorkerScreen(player, pkt.targetId(), pkt.companyId(),
                            level, data, vdata);
                }

                case ASSIGN_WORKER_TASK -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(w.withTask(
                                    pkt.strParam(), pkt.intParam())));
                    data.markDirty();
                    refreshWorkerScreen(player, pkt.targetId(), pkt.companyId(),
                            level, data, vdata);
                }

                case SET_WORK_HOURS -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    c.setWorkSchedule(new Company.WorkSchedule(
                            pkt.intParam(), (int) pkt.longParam()));
                    data.markDirty();
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case SET_ITEM_PRICE -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    c.setPriceOverride(pkt.strParam(), pkt.longParam());
                    data.markDirty();
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case DEPOSIT_TO_TREASURY -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    long amount = pkt.longParam();
                    // Deduct from player
                    var playerContainer = new net.minecraft.world.SimpleContainer(
                            player.getInventory().getContainerSize());
                    for (int i = 0; i < player.getInventory()
                            .getContainerSize(); i++) {
                        playerContainer.setItem(i,
                                player.getInventory().getItem(i).copy());
                    }
                    tterrag1112.life_in_the_village.Village.Economy
                            .Currency.CurrencyValue cost =
                            tterrag1112.life_in_the_village.Village.Economy
                                    .Currency.CurrencyValue.of(amount);
                    if (!tterrag1112.life_in_the_village.Village.Economy
                            .Currency.CoinHelper.canAfford(
                                    playerContainer, cost)) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "Insufficient funds."), false);
                        return;
                    }
                    tterrag1112.life_in_the_village.Village.Economy.Currency
                            .CoinHelper.spend(playerContainer, cost);
                    // Write back
                    for (int i = 0; i < player.getInventory()
                            .getContainerSize(); i++) {
                        player.getInventory().setItem(i,
                                playerContainer.getItem(i));
                    }
                    c.depositBronze(amount);
                    data.markDirty();
                    refreshManagementScreen(player, pkt.companyId(),
                            level, data, vdata);
                }

                case ADD_BUILDING -> {
                    Company c = getOwnedCompany(data, pkt.companyId(),
                            player.getUUID());
                    if (c == null) return;
                    c.addBuilding(pkt.targetId());
                    data.markDirty();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Company getOwnedCompany(CompanySavedData data,
                                           UUID companyId,
                                           UUID playerId) {
        return data.getById(companyId)
                .filter(c -> c.getOwnerPlayerId().equals(playerId))
                .orElse(null);
    }

    private static void refreshManagementScreen(ServerPlayer player,
                                                UUID companyId, ServerLevel level,
                                                CompanySavedData data, VillageSavedData vdata) {
        CompanyManagementScreen.sendOpenPacket(
                player, companyId, level, data, vdata);
    }

    private static void refreshWorkerScreen(ServerPlayer player,
                                            UUID npcId, UUID companyId, ServerLevel level,
                                            CompanySavedData data, VillageSavedData vdata) {
        CompanyWorkerScreen.sendOpenPacket(
                player, npcId, companyId, level, data, vdata);
    }
}