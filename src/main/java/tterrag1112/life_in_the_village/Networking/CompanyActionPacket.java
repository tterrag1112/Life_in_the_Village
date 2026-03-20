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
import tterrag1112.life_in_the_village.Gui.CompanyManagementScreen;
import tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
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
 * Server-bound packet for all player-initiated company actions.
 *
 * Param conventions per action:
 *   companyId  — always the company being acted on (UUID(0,0) for FOUND_COMPANY)
 *   targetId   — secondary UUID: npcId, buildingId, or villageId depending on action
 *   strParam   — string data: item registry key, new company name, etc.
 *   longParam  — numeric data: wage, price, treasury deposit amount, work end-hour
 *   intParam   — integer data: role ordinal, producer type ordinal, work start-hour, task count
 */
public record CompanyActionPacket(
        ActionType action,
        UUID       companyId,
        UUID       targetId,
        String     strParam,
        long       longParam,
        int        intParam
) implements CustomPacketPayload {

    // =========================================================================
    // ACTION TYPE ENUM
    // =========================================================================

    public enum ActionType {
        // ---- Company lifecycle ----
        FOUND_COMPANY,          // targetId=villageId, strParam=name
        RENAME_COMPANY,         // strParam=newName
        DISSOLVE_COMPANY,
        OPEN_MANAGEMENT,        // opens CompanyManagementScreen (no data change)

        // ---- Treasury ----
        DEPOSIT_TO_TREASURY,    // longParam=bronzeAmount

        // ---- Workers ----
        HIRE_NPC,               // targetId=npcId, intParam=WorkerRole.ordinal()
        FIRE_NPC,               // targetId=npcId
        SET_WORKER_WAGE,        // targetId=npcId, longParam=wage
        SET_WORKER_ROLE,        // targetId=npcId, intParam=WorkerRole.ordinal()
        SET_PRODUCER_TYPE,      // targetId=npcId, intParam=ProducerType.ordinal()
        ASSIGN_WORKER_TASK,     // targetId=npcId, strParam=itemId, intParam=count

        // ---- Schedule ----
        SET_WORK_HOURS,         // intParam=startHour, longParam=endHour

        // ---- Prices ----
        SET_ITEM_PRICE,         // strParam=itemId, longParam=pricePerUnit

        // ---- Buildings ----
        BUY_COMPANY_BUILDING,   // targetId=buildingId (deducts from player wallet)
    }

    // =========================================================================
    // PACKET PLUMBING
    // =========================================================================

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

    public static void handle(CompanyActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel      level  = (ServerLevel) player.level();
            CompanySavedData cdata  = CompanySavedData.get(level);
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
                        fail(player, "You need a Town-tier village to found a company. "
                                + "This village is " + tier.displayName + " tier ("
                                + village.getBuildingIds().size() + " buildings).");
                        return;
                    }

                    boolean alreadyHas = cdata.getByOwner(player.getUUID()).stream()
                            .anyMatch(c -> c.getHomeVillageId().equals(villageId));
                    if (alreadyHas) {
                        fail(player, "You already have a company based in this village.");
                        return;
                    }

                    String name = pkt.strParam().isBlank() ? "My Company" : pkt.strParam();
                    Company company = Company.create(name, player.getUUID(), villageId);
                    cdata.addCompany(company);

                    player.displayClientMessage(
                            Component.literal("Company \"" + company.getName()
                                            + "\" founded successfully!")
                                    .withStyle(ChatFormatting.GOLD), false);

                    // Re-open the village book so the standings page refreshes
                    VillageBookScreen.sendOpenPacket(player, villageId, level, vdata);
                }

                // =============================================================
                // RENAME COMPANY
                // =============================================================
                case RENAME_COMPANY -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;
                    String newName = pkt.strParam().isBlank() ? c.getName() : pkt.strParam();
                    c.setName(newName);
                    cdata.markDirty();
                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "OVERVIEW");
                }

                // =============================================================
                // DISSOLVE COMPANY
                // =============================================================
                case DISSOLVE_COMPANY -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    // Fire all workers cleanly
                    c.getWorkers().forEach(w -> releaseWorker(level, w.npcId()));

                    cdata.removeCompany(pkt.companyId());
                    cdata.markDirty();

                    player.displayClientMessage(
                            Component.literal("Company dissolved."), false);
                }

                // =============================================================
                // OPEN MANAGEMENT (no data change — just sends the screen)
                // =============================================================
                case OPEN_MANAGEMENT -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;
                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "");
                }

                // =============================================================
                // DEPOSIT TO TREASURY
                // =============================================================
                case DEPOSIT_TO_TREASURY -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long amount = pkt.longParam();
                    if (amount <= 0) return;

                    SimpleContainer wallet = playerWallet(player);
                    CurrencyValue cost = CurrencyValue.of(amount);

                    if (!CoinHelper.canAfford(wallet, cost)) {
                        fail(player, "Insufficient funds.");
                        return;
                    }

                    CoinHelper.spend(wallet, cost);
                    syncWallet(player, wallet);
                    c.depositBronze(amount);
                    cdata.markDirty();

                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "OVERVIEW");
                }

                // =============================================================
                // HIRE NPC  (via CompanyActionPacket — e.g. from management screen)
                // Direct coin-hire from TownspersonMob.mobInteract uses the
                // same server-side logic duplicated there for immediacy.
                // =============================================================
                case HIRE_NPC -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID npcId = pkt.targetId();
                    var npc = findNpc(level, npcId);
                    if (npc == null || npc.isCompanyWorker()) {
                        fail(player, "NPC not found or already employed.");
                        return;
                    }

                    Company.WorkerRole role = safeRole(pkt.intParam());
                    long wage = Math.max(c.getEffectiveMinWage(vdata), 8L);

                    Company.CompanyWorker worker = new Company.CompanyWorker(
                            npcId, role, Company.ProducerType.GENERIC,
                            Company.NO_BUILDING, wage,
                            level.getGameTime(), "", 8);

                    c.addWorker(worker);
                    npc.setCompanyId(c.getCompanyId());
                    npc.setProfession(Profession.COMPANY_WORKER);
                    cdata.markDirty();

                    player.displayClientMessage(
                            Component.literal("[" + npc.getNpcName()
                                    + "] I'll work for " + c.getName() + "!"), false);

                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "WORKERS");
                }

                // =============================================================
                // FIRE NPC
                // =============================================================
                case FIRE_NPC -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID npcId = pkt.targetId();
                    c.removeWorker(npcId);
                    releaseWorker(level, npcId);
                    cdata.markDirty();

                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "WORKERS");
                }

                // =============================================================
                // SET WORKER WAGE
                // =============================================================
                case SET_WORKER_WAGE -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long minWage = c.getEffectiveMinWage(vdata);
                    long wage    = Math.max(minWage, pkt.longParam());

                    c.getWorker(pkt.targetId())
                            .ifPresent(w -> c.updateWorker(w.withWage(wage)));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.companyId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET WORKER ROLE
                // =============================================================
                case SET_WORKER_ROLE -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    Company.WorkerRole role = safeRole(pkt.intParam());
                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(new Company.CompanyWorker(
                                    w.npcId(), role, w.producerType(),
                                    w.assignedBuildingId(), w.wagePerDay(),
                                    w.lastPaidTick(), w.assignedItemId(),
                                    w.dailyTargetCount())));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.companyId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET PRODUCER TYPE
                // =============================================================
                case SET_PRODUCER_TYPE -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    Company.ProducerType type = safeProducerType(pkt.intParam());

                    // Validate the company owns the required building
                    BuildingType required = type.requiredBuilding();
                    if (required != null) {
                        boolean hasBuilding = c.getBuildingIds().stream()
                                .map(vdata::getBuildingById)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .anyMatch(b -> b.getType() == required);
                        if (!hasBuilding) {
                            fail(player, "Your company needs a "
                                    + required.name().toLowerCase().replace('_', ' ')
                                    + " to use this producer type.");
                            return;
                        }
                    }

                    c.getWorker(pkt.targetId()).ifPresent(w ->
                            c.updateWorker(new Company.CompanyWorker(
                                    w.npcId(), w.role(), type,
                                    w.assignedBuildingId(), w.wagePerDay(),
                                    w.lastPaidTick(), w.assignedItemId(),
                                    w.dailyTargetCount())));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.companyId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // ASSIGN WORKER TASK
                // =============================================================
                case ASSIGN_WORKER_TASK -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    String itemId = pkt.strParam();
                    int    count  = Math.max(1, Math.min(64, pkt.intParam()));

                    c.getWorker(pkt.targetId())
                            .ifPresent(w -> c.updateWorker(w.withTask(itemId, count)));
                    cdata.markDirty();

                    refreshWorker(player, pkt.targetId(), pkt.companyId(),
                            level, cdata, vdata);
                }

                // =============================================================
                // SET WORK HOURS
                // =============================================================
                case SET_WORK_HOURS -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    int startHour = Math.max(0,  Math.min(23, pkt.intParam()));
                    int endHour   = Math.max(1,  Math.min(24, (int) pkt.longParam()));
                    if (endHour <= startHour) endHour = startHour + 1;

                    c.setWorkSchedule(new Company.WorkSchedule(startHour, endHour));
                    cdata.markDirty();

                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "SCHEDULE");
                }

                // =============================================================
                // SET ITEM PRICE
                // =============================================================
                case SET_ITEM_PRICE -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    long price = Math.max(1, pkt.longParam());
                    c.setPriceOverride(pkt.strParam(), price);
                    cdata.markDirty();

                    refreshManagement(player, pkt.companyId(), level, cdata, vdata, "PRICES");
                }

                // =============================================================
                // BUY COMPANY BUILDING
                // =============================================================
                case BUY_COMPANY_BUILDING -> {
                    Company c = ownedCompany(cdata, pkt.companyId(),
                            player.getUUID(), player);
                    if (c == null) return;

                    UUID buildingId = pkt.targetId();
                    Building building = vdata.getBuildingById(buildingId).orElse(null);
                    if (building == null) {
                        fail(player, "Building not found.");
                        return;
                    }

                    // Buildings that cannot be purchased for a company
                    Set<BuildingType> nonPurchasable = Set.of(
                            BuildingType.HOUSE, BuildingType.TOWN_HALL,
                            BuildingType.GUARD_TOWER, BuildingType.GUILD_HALL,
                            BuildingType.WELL, BuildingType.BELL_TOWER,
                            BuildingType.PRISON);
                    if (nonPurchasable.contains(building.getType())) {
                        fail(player, "This building cannot be purchased for a company.");
                        return;
                    }

                    // Check not already owned by any company
                    boolean takenByOther = cdata.getAllCompanies().stream()
                            .filter(co -> !co.getCompanyId()
                                    .equals(pkt.companyId()))
                            .anyMatch(co -> co.getBuildingIds()
                                    .contains(buildingId));
                    if (takenByOther) {
                        fail(player, "This building already belongs to another company.");
                        return;
                    }

                    // Already in this company
                    if (c.getBuildingIds().contains(buildingId)) {
                        fail(player, "This building is already part of your company.");
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

                    // Refresh the village book so the company buildings
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

    /** Returns the company if the player owns it, otherwise sends a failure
     *  message and returns null. */
    private static Company ownedCompany(CompanySavedData cdata,
                                        UUID companyId, UUID playerId,
                                        ServerPlayer player) {
        Company c = cdata.getById(companyId)
                .filter(co -> co.getOwnerPlayerId().equals(playerId))
                .orElse(null);
        if (c == null)
            fail(player, "You do not own this company.");
        return c;
    }

    /** Sends the CompanyManagementScreen to the player. */
    private static void refreshManagement(ServerPlayer player,
                                          UUID companyId,
                                          ServerLevel level,
                                          CompanySavedData cdata,
                                          VillageSavedData vdata,
                                          String section) {
        CompanyManagementScreen.sendOpenPacket(
                player, companyId, level, cdata, vdata, section);
    }

    /** Sends the CompanyWorkerScreen to the player. */
    private static void refreshWorker(ServerPlayer player,
                                      UUID npcId, UUID companyId,
                                      ServerLevel level,
                                      CompanySavedData cdata,
                                      VillageSavedData vdata) {
        CompanyWorkerScreen.sendOpenPacket(
                player, npcId, companyId, level, cdata, vdata);
    }

    /** Clears the company link from an NPC and returns them to NONE. */
    private static void releaseWorker(ServerLevel level, UUID npcId) {
        level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000,
                        30000000,  2048,  30000000),
                mob -> mob.getUUID().equals(npcId)
        ).stream().findFirst().ifPresent(npc -> {
            npc.clearCompanyId();
            npc.setProfession(Profession.NONE);
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
    private static Company.WorkerRole safeRole(int ordinal) {
        Company.WorkerRole[] values = Company.WorkerRole.values();
        return values[Math.max(0, Math.min(ordinal, values.length - 1))];
    }

    /** Converts an int to a ProducerType safely. */
    private static Company.ProducerType safeProducerType(int ordinal) {
        Company.ProducerType[] values = Company.ProducerType.values();
        return values[Math.max(0, Math.min(ordinal, values.length - 1))];
    }

    /** Sends a red failure message to the player. */
    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(
                Component.literal(message)
                        .withStyle(ChatFormatting.RED), false);
    }
}