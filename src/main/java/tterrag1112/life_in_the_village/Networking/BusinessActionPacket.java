package tterrag1112.life_in_the_village.Networking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.BusinessManagementScreen;
import tterrag1112.life_in_the_village.Gui.BusinessWorkerScreen;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-bound packet for all player-initiated business actions.
 *
 * Param conventions per action:
 *   businessId  — always the business being acted on (UUID(0,0) for FOUND_COMPANY)
 *   targetId   — secondary UUID: npcId, buildingId, or villageId depending on action
 *   strParam   — string data: item registry key, new business name, etc.
 *   longParam  — numeric data: wage, price, treasury deposit amount, work end-hour
 *   intParam   — integer data: role ordinal, producer type ordinal, work start-hour, task count
 */
public record BusinessActionPacket(
        ActionType action,
        UUID       businessId,
        UUID       targetId,
        String     strParam,
        long       longParam,
        int        intParam
) implements CustomPacketPayload {

    // =========================================================================
    // ACTION TYPE ENUM
    // =========================================================================

    public enum ActionType {
        // ---- Business lifecycle ----
        FOUND_COMPANY,          // targetId=villageId, strParam=name
        RENAME_COMPANY,         // strParam=newName
        DISSOLVE_COMPANY,
        OPEN_MANAGEMENT,        // opens BusinessManagementScreen (no data change)

        // ---- Treasury ----
        DEPOSIT_TO_TREASURY,    // longParam=bronzeAmount

        // ---- Workers ----
        HIRE_NPC,               // targetId=npcId, intParam=WorkerRole.ordinal()
        FIRE_NPC,               // targetId=npcId
        SET_WORKER_WAGE,        // targetId=npcId, longParam=wage
        SET_WORKER_ROLE,        // targetId=npcId, intParam=WorkerRole.ordinal()
        SET_PRODUCER_TYPE,      // targetId=npcId, intParam=ProducerType.ordinal()
        ASSIGN_WORKER_TASK,     // targetId=npcId, strParam=itemId, intParam=count
        OPEN_WORKER_SCREEN,

        // ---- Schedule ----
        SET_WORK_HOURS,         // intParam=startHour, longParam=endHour

        // ---- Prices ----
        SET_ITEM_PRICE,         // strParam=itemId, longParam=pricePerUnit
        REMOVE_ITEM_PRICE,  // strParam=itemId — removes override, reverts to market price


        // ---- Buildings ----
        BUY_COMPANY_BUILDING,   // targetId=buildingId (deducts from player wallet)
    }

    // =========================================================================
    // PACKET PLUMBING
    // =========================================================================

    public static final Type<BusinessActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "company_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            BusinessActionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.businessId());
                buf.writeUUID(pkt.targetId());
                buf.writeUtf(pkt.strParam());
                buf.writeVarLong(pkt.longParam());
                buf.writeVarInt(pkt.intParam());
            },
            buf -> new BusinessActionPacket(
                    ActionType.valueOf(buf.readUtf()),
                    buf.readUUID(),
                    buf.readUUID(),
                    buf.readUtf(),
                    buf.readVarLong(),
                    buf.readVarInt())
    );

    @Override
    public Type<?> type() { return TYPE; }

    // =========================================================================
    // HANDLER
    // =========================================================================

    public static void handle(BusinessActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel      level  = (ServerLevel) player.level();
            BusinessSavedData cdata  = BusinessSavedData.get(level);
            VillageSavedData vdata  = VillageSavedData.get(level);

            switch (pkt.action()) {

                // =============================================================
                // FOUND COMPANY
                // =============================================================
                case FOUND_COMPANY -> {
                    UUID villageId = pkt.targetId();
                    Village village = vdata.getVillageById(villageId).orElse(null);

                    if (village == null) {
                        fail(player, "Village not found.");
                        return;
                    }

                    VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                            village.getBuildingIds().size());

                    if (tier.ordinal() < VillageSizeTier.TOWN.ordinal()) {
                        fail(player, "You need a Town-tier village to found a business. "
                                + "This village is " + tier.displayName + " tier ("
                                + village.getBuildingIds().size() + " buildings).");
                        return;
                    }

                    boolean alreadyHas = cdata.getByOwner(player.getUUID()).stream()
                            .anyMatch(c -> c.getHomeVillageId().equals(villageId));
                    if (alreadyHas) {
                        fail(player, "You already have a business based in this village.");
                        return;
                    }

                    String name = pkt.strParam().isBlank() ? "My Business" : pkt.strParam();
                    Business business = Business.create(name, player.getUUID(), villageId);
                    cdata.addBusiness(business);

                    player.displayClientMessage(
                            Component.literal("Business \"" + business.getName()
                                            + "\" founded successfully!")
                                    .withStyle(ChatFormatting.GOLD), false);

                    // Re-open the village book so the standings page refreshes
                    VillageBookScreen.sendOpenPacket(player, villageId, level, vdata);
                }

                // =============================================================
                // RENAME COMPANY
                // =============================================================
                case RENAME_COMPANY -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;
                    String newName = pkt.strParam().isBlank() ? c.getName() : pkt.strParam();
                    c.setName(newName);
                    cdata.markDirty();
                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "OVERVIEW");
                }
                case OPEN_WORKER_SCREEN -> {
                    Business c = ownedCompany(cdata, pkt.businessId(), player.getUUID(), player);
                    if (c == null) return;
                    refreshWorker(player, pkt.targetId(), pkt.businessId(), level, cdata, vdata);
                }

                // =============================================================
                // DISSOLVE COMPANY
                // =============================================================
                case DISSOLVE_COMPANY -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    // Fire all workers cleanly
                    c.getWorkers().forEach(w -> releaseWorker(level, w.npcId()));

                    cdata.removeBusiness(pkt.businessId());
                    cdata.markDirty();

                    player.displayClientMessage(
                            Component.literal("Business dissolved."), false);
                }

                // =============================================================
                // OPEN MANAGEMENT (no data change — just sends the screen)
                // =============================================================
                case OPEN_MANAGEMENT -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;
                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "");
                }

                // =============================================================
                // DEPOSIT TO TREASURY
                // =============================================================
                case DEPOSIT_TO_TREASURY -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long amount = pkt.longParam();
                    if (amount <= 0) return;

                    CurrencyValue cost = CurrencyValue.of(amount);

                    if (!CoinHelper.playerCanAfford(player, cost)) {
                        fail(player, "Insufficient funds.");
                        return;
                    }

                    CoinHelper.playerPay(player, cost);
                    c.depositBronze(amount);
                    cdata.markDirty();

                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "OVERVIEW");
                }

                // =============================================================
                // HIRE NPC  (via BusinessActionPacket — e.g. from management screen)
                // Direct coin-hire from TownspersonMob.mobInteract uses the
                // same server-side logic duplicated there for immediacy.
                // =============================================================
                case HIRE_NPC -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID npcId = pkt.targetId();
                    var npc = findNpc(level, npcId);
                    if (npc == null || npc.isBusinessWorker()) {
                        fail(player, "NPC not found or already employed.");
                        return;
                    }

                    Business.WorkerRole role = safeRole(pkt.intParam());
                    long wage = Math.max(c.getEffectiveMinWage(vdata), 8L);

                    Business.BusinessWorker worker = new Business.BusinessWorker(
                            npcId, role, Business.ProducerType.GENERIC,
                            Business.NO_BUILDING, wage,
                            level.getGameTime(), "", 8,
                            tterrag1112.life_in_the_village.Guilds.Companies
                                    .EmploymentTier.APPRENTICE);

                    c.addWorker(worker);
                    npc.setBusinessId(c.getBusinessId());
                    // Phase 6.3.3.a — gated, PLAYER (player hires via UI packet).
                    tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                            npc, Profession.COMPANY_WORKER,
                            tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.BUSINESS_PROMOTION,
                            tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.PLAYER);
                    cdata.markDirty();

                    player.displayClientMessage(
                            Component.literal("[" + npc.getNpcName()
                                    + "] I'll work for " + c.getName() + "!"), false);

                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "WORKERS");
                }

                // =============================================================
                // FIRE NPC
                // =============================================================
                case FIRE_NPC -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID npcId = pkt.targetId();
                    c.removeWorker(npcId);
                    releaseWorker(level, npcId);
                    cdata.markDirty();

                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "WORKERS");
                }

                // =============================================================
                // SET WORKER WAGE
                // =============================================================
                case SET_WORKER_WAGE -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long minWage = c.getEffectiveMinWage(vdata);
                    long wage    = Math.max(minWage, pkt.longParam());

                    c.getWorker(pkt.targetId())
                            .ifPresent(w -> c.updateWorker(w.withWage(wage)));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.businessId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET WORKER ROLE
                // =============================================================
                case SET_WORKER_ROLE -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    Business.WorkerRole role = safeRole(pkt.intParam());
                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(new Business.BusinessWorker(
                                    w.npcId(), role, w.producerType(),
                                    w.assignedBuildingId(), w.wagePerDay(),
                                    w.lastPaidTick(), w.assignedItemId(),
                                    w.dailyTargetCount(), w.tier())));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.businessId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET PRODUCER TYPE
                // =============================================================
                case SET_PRODUCER_TYPE -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    Business.ProducerType type = safeProducerType(pkt.intParam());

                    // Validate the business owns the required building
                    BuildingType required = type.requiredBuilding();
                    if (required != null) {
                        boolean hasBuilding = c.getBuildingIds().stream()
                                .map(vdata::getBuildingById)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .anyMatch(b -> b.getType() == required);
                        if (!hasBuilding) {
                            fail(player, "Your business needs a "
                                    + required.name().toLowerCase().replace('_', ' ')
                                    + " to use this producer type.");
                            return;
                        }
                    }

                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(new Business.BusinessWorker(
                                    w.npcId(), w.role(), type,
                                    w.assignedBuildingId(), w.wagePerDay(),
                                    w.lastPaidTick(), w.assignedItemId(),
                                    w.dailyTargetCount(), w.tier())));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.businessId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // ASSIGN WORKER TASK
                // =============================================================
                case ASSIGN_WORKER_TASK -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    String itemId = pkt.strParam();
                    int    count  = Math.max(1, Math.min(64, pkt.intParam()));

                    c.getWorker(pkt.targetId())
                            .ifPresent(w -> c.updateWorker(w.withTask(itemId, count)));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.businessId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET WORK HOURS
                // =============================================================
                case SET_WORK_HOURS -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    int startHour = Math.max(0,  Math.min(23, pkt.intParam()));
                    int endHour   = Math.max(1,  Math.min(24, (int) pkt.longParam()));
                    if (endHour <= startHour) endHour = startHour + 1;

                    c.setWorkSchedule(new Business.WorkSchedule(startHour, endHour));
                    cdata.markDirty();

                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "SCHEDULE");
                }

                // =============================================================
                // SET ITEM PRICE
                // =============================================================
                case SET_ITEM_PRICE -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long price = Math.max(1, pkt.longParam());
                    c.setPriceOverride(pkt.strParam(), price);
                    cdata.markDirty();

                    refreshManagement(player, pkt.businessId(), level, cdata, vdata, "PRICES");
                }
                case REMOVE_ITEM_PRICE -> {
                    Business c = ownedCompany(cdata, pkt.businessId(), player.getUUID(), player);
                    if (c == null) return;
                    c.removePriceOverride(pkt.strParam());
                    cdata.markDirty();
                    refreshWorker(player, pkt.targetId(), pkt.businessId(), level, cdata, vdata);
                }

                // =============================================================
                // BUY COMPANY BUILDING
                // =============================================================
                case BUY_COMPANY_BUILDING -> {
                    Business c = ownedCompany(cdata, pkt.businessId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID buildingId = pkt.targetId();
                    Building building = vdata.getBuildingById(buildingId).orElse(null);
                    if (building == null) {
                        fail(player, "Building not found.");
                        return;
                    }

                    // Buildings that cannot be purchased for a business
                    Set<BuildingType> nonPurchasable = Set.of(
                            BuildingType.HOUSE, BuildingType.TOWN_HALL,
                            BuildingType.GUARD_TOWER, BuildingType.GUILD_HALL,
                            BuildingType.WELL, BuildingType.BELL_TOWER,
                            BuildingType.PRISON);
                    if (nonPurchasable.contains(building.getType())) {
                        fail(player, "This building cannot be purchased for a business.");
                        return;
                    }

                    // Check not already owned by any business
                    boolean takenByOther = cdata.getAllBusinesses().stream()
                            .filter(co -> !co.getBusinessId()
                                    .equals(pkt.businessId()))
                            .anyMatch(co -> co.getBuildingIds()
                                    .contains(buildingId));
                    if (takenByOther) {
                        fail(player, "This building already belongs to another business.");
                        return;
                    }

                    // Already in this business
                    if (c.getBuildingIds().contains(buildingId)) {
                        fail(player, "This building is already part of your business.");
                        return;
                    }

                    // Price — same formula as HousePurchaseManager for consistency
                    Village village = vdata.getVillageById(c.getHomeVillageId())
                            .orElse(null);
                    if (village == null) {
                        fail(player, "Home village not found.");
                        return;
                    }

                    long price = HousePurchaseManager.calculatePrice(
                            building, village, vdata);
                    CurrencyValue cost = CurrencyValue.of(price);

                    SimpleContainer wallet = playerWallet(player);
                    if (!CoinHelper.canAfford(wallet, cost)) {
                        fail(player, "Insufficient funds. "
                                + CurrencyValue.of(price) + " required.");
                        return;
                    }

                    CoinHelper.spend(wallet, cost);
                    syncWallet(player, wallet);

                    // Pay half to the village treasury
                    vdata.getKingdomForVillage(village.getId())
                            .ifPresent(k -> k.depositToTreasury(price / 2));

                    c.addBuilding(buildingId);
                    cdata.markDirty();

                    player.displayClientMessage(
                            Component.literal(building.getName()
                                            + " added to " + c.getName() + "!")
                                    .withStyle(ChatFormatting.GOLD), false);

                    // Refresh the village book so the business buildings
                    // page reflects the purchase immediately
                    VillageBookScreen.sendOpenPacket(
                            player, village.getId(), level, vdata);
                }
            }
        });
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /** Returns the business if the player owns it, otherwise sends a failure
     *  message and returns null. */
    private static Business ownedCompany(BusinessSavedData cdata,
                                        UUID businessId, UUID playerId,
                                        ServerPlayer player) {
        Business c = cdata.getById(businessId)
                .filter(co -> co.getOwnerPlayerId().equals(playerId))
                .orElse(null);
        if (c == null)
            fail(player, "You do not own this business.");
        return c;
    }

    /** Sends the BusinessManagementScreen to the player. */
    private static void refreshManagement(ServerPlayer player,
                                          UUID businessId,
                                          ServerLevel level,
                                          BusinessSavedData cdata,
                                          VillageSavedData vdata,
                                          String section) {
        BusinessManagementScreen.sendOpenPacket(
                player, businessId, level, cdata, vdata, section);
    }

    /** Sends the BusinessWorkerScreen to the player. */
    private static void refreshWorker(ServerPlayer player,
                                      UUID npcId, UUID businessId,
                                      ServerLevel level,
                                      BusinessSavedData cdata,
                                      VillageSavedData vdata) {
        BusinessWorkerScreen.sendOpenPacket(
                player, npcId, businessId, level, cdata, vdata);
    }

    /** Clears the business link from an NPC and returns them to NONE. */
    private static void releaseWorker(ServerLevel level, UUID npcId) {
        TownspersonMob.findByUUID(level, npcId).ifPresent(npc -> {
            npc.clearBusinessId();
            // Phase 6.3.3.a — gated, PLAYER (player-driven fire via UI packet).
            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                    npc, Profession.NONE,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.BUSINESS_PROMOTION,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.PLAYER);
        });
    }

    /** Finds a TownspersonMob by UUID anywhere in the level. */
    private static tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
    findNpc(ServerLevel level, UUID id) {
        return level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000,
                        30000000,  2048,  30000000),
                mob -> mob.getUUID().equals(id)
        ).stream().findFirst().orElse(null);
    }

    /** Copies the player's inventory into a SimpleContainer for coin operations. */
    private static SimpleContainer playerWallet(ServerPlayer player) {
        SimpleContainer c = new SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            c.setItem(i, player.getInventory().getItem(i).copy());
        return c;
    }

    /** Writes a modified wallet back to the player's inventory. */
    private static void syncWallet(ServerPlayer player, SimpleContainer wallet) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, wallet.getItem(i));
        player.inventoryMenu.broadcastChanges();
    }

    /** Converts an int to a WorkerRole safely. */
    private static Business.WorkerRole safeRole(int ordinal) {
        Business.WorkerRole[] values = Business.WorkerRole.values();
        return values[Math.max(0, Math.min(ordinal, values.length - 1))];
    }

    /** Converts an int to a ProducerType safely. */
    private static Business.ProducerType safeProducerType(int ordinal) {
        Business.ProducerType[] values = Business.ProducerType.values();
        return values[Math.max(0, Math.min(ordinal, values.length - 1))];
    }

    /** Sends a red failure message to the player. */
    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(
                Component.literal(message)
                        .withStyle(ChatFormatting.RED), false);
    }

}