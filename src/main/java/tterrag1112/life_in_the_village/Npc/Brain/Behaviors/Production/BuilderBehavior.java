package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.*;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.ExpansionRequest;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.*;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;

import java.util.*;
import java.util.stream.Collectors;

public class BuilderBehavior extends Behavior<TownspersonMob> {

    private enum Phase {
        IDLE, ANALYZING, WALKING_TO_WORKSHOP,
        GATHERING, WALKING_TO_BUILDING, CONSTRUCTING, FINDING_SITE, PLACING_BUILDING

    }

    private int blockPlaceTimer = 0;
    private static final int TICKS_PER_BLOCK_PLACE = 5;

    private boolean constructionStarted = false;

    private List<StructureTemplate.StructureBlockInfo> blocksToPlace = new ArrayList<>();
    private List<StructureTemplate.StructureBlockInfo> attachmentBlocksToPlace = new ArrayList<>();
    private boolean placingAttachments = false;

    private boolean isAttachmentBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TorchBlock
                || block instanceof WallTorchBlock
                || block instanceof LadderBlock
                || block instanceof VineBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof SignBlock
                || block instanceof WallSignBlock
                || block instanceof BannerBlock
                || block instanceof WallBannerBlock
                || block instanceof RailBlock
                || block instanceof BaseRailBlock
                || block instanceof FenceBlock
                || block instanceof FenceGateBlock;
    }


    private TownspersonMob entity;

    public BuilderBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private int idleTimer = 0;
    private int constructionTimer = 0;
    private int constructionDuration = 0;

    private Building targetBuilding = null;
    private Building workshopBuilding = null;
    private UpgradeRequirements requirements = null;

    private static final int IDLE_INTERVAL = 400; // check every 20 seconds
    private static final int TICKS_PER_BLOCK = 2; // construction time scaling

    

    private int idleCooldown = 0;
    private static final int IDLE_COOLDOWN = 400;

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;

        if (idleCooldown > 0) { idleCooldown--; return false; }

        if (entity.isWorkingBlocked()) return false; // stop building when food is critical
        return phase == Phase.IDLE && entity.getRandom().nextInt(20) == 0;
    }
    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.ANALYZING;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (!entity.isWorkTime()) return false;

        idleCooldown = IDLE_COOLDOWN;
        return phase != Phase.IDLE;

    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        //System.out.println("BuilderGoal tick - phase: " + phase);
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        switch (phase) {
            case FINDING_SITE    -> findSite(serverLevel);
            case PLACING_BUILDING -> placeBuilding(serverLevel);
            case ANALYZING -> analyze(serverLevel);
            case WALKING_TO_WORKSHOP -> walkToWorkshop(serverLevel);
            case GATHERING -> gather(serverLevel);
            case WALKING_TO_BUILDING -> walkToBuilding();
            case CONSTRUCTING -> construct(serverLevel);
        }
    }
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // --- Phase handlers ---

    private void analyze(ServerLevel level) {
        // Check for pending expansion first
        Optional<ExpansionRequest> expansion =
                getVillageName(level)
                        .flatMap(name -> VillageSavedData.get(level)
                                .getVillageByName(name))
                        .flatMap(v -> VillageSavedData.get(level)
                                .getPendingExpansionForVillage(
                                        v.getId()));

        if (expansion.isPresent()) {
            currentExpansion = expansion.get();
            currentExpansion.setStatus(
                    ExpansionRequest.RequestStatus.IN_PROGRESS);
            VillageSavedData.get(level).setDirty();

            // Check if this is an upgrade or new build
            // by seeing if the building type already exists
            boolean isUpgrade = getVillageName(level)
                    .flatMap(name -> VillageSavedData.get(level)
                            .getVillageByName(name))
                    .map(v -> v.getBuildingIds().stream()
                            .map(id -> VillageSavedData.get(level)
                                    .getBuildingById(id))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .anyMatch(b -> b.getType()
                                    == currentExpansion
                                    .getBuildingType()
                                    && BuildingRegistry
                                    .canUpgrade(b)))
                    .orElse(false);

            if (isUpgrade) {
                // Find the building to upgrade and go directly
                // to WALKING_TO_BUILDING
                getVillageName(level)
                        .flatMap(name -> VillageSavedData.get(level)
                                .getVillageByName(name))
                        .ifPresent(v -> {
                            v.getBuildingIds().stream()
                                    .map(id -> VillageSavedData
                                            .get(level)
                                            .getBuildingById(id))
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .filter(b -> b.getType()
                                            == currentExpansion
                                            .getBuildingType()
                                            && BuildingRegistry
                                            .canUpgrade(b))
                                    .findFirst()
                                    .ifPresent(b -> {
                                        targetBuilding = b;
                                        Optional<UpgradeRequirements> req = UpgradeRequirements.compute(level, b);
                                        if (req.isEmpty()) {
                                            targetBuilding = null;
                                            phase = Phase.IDLE;
                                            idleCooldown = IDLE_COOLDOWN;
                                            return;
                                        }
                                        requirements = req.get();
                                        workshopBuilding = findStockpile(v, VillageSavedData.get(level));
                                        phase = workshopBuilding != null
                                                ? Phase.WALKING_TO_WORKSHOP
                                                : Phase.IDLE;
                                    });

                        });
            } else {
                phase = Phase.FINDING_SITE;
            }
            return;
        }

        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
    }

    private void walkToWorkshop(ServerLevel level) {
        BlockPos workshopCenter = workshopBuilding.getShape().getOrigin();
        double dist = entity.distanceToSqr(
                workshopCenter.getX(), entity.getY(), workshopCenter.getZ()
        );

        if (dist > 4) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    workshopCenter.getX(), workshopCenter.getY(),
                    workshopCenter.getZ(), 1.0
            ));
        } else {
            // Arrived at workshop
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.GATHERING;
        }
    }

    private Map<Item, Integer> missingMaterials = new HashMap<>();

    private void gather(ServerLevel level) {

        if (requirements == null) {
            phase = Phase.IDLE;
            idleCooldown = IDLE_COOLDOWN;
            return;
        }
        SimpleContainer personal = entity.getPersonalInventory();

        // Check village has enough coins for upgrade cost


        // Rest of gather logic unchanged...
        Map<Item, Integer> needed = new HashMap<>(
                requirements.getRequiredItems());
        // Build what we still need accounting for personal inventory
        for (int i = 0; i < personal.getContainerSize(); i++) {
            ItemStack stack = personal.getItem(i);
            if (!stack.isEmpty() && needed.containsKey(stack.getItem())) {
                needed.merge(stack.getItem(), -stack.getCount(), Integer::sum);
            }
        }
        needed.entrySet().removeIf(e -> e.getValue() <= 0);

        if (needed.isEmpty()) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            phase = Phase.WALKING_TO_BUILDING;
            return;
        }

        missingMaterials.clear();

        // Take everything available from stockpile upfront
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            Item item = entry.getKey();
            int required = entry.getValue();

            // Count how much is available
            int available = countItemInBuilding(level, workshopBuilding, item);
            int toTake = Math.min(required, available);

            if (toTake > 0) {
                BuildingStorageAccess.takeItem(level, workshopBuilding, item, toTake);
                personal.addItem(new ItemStack(item, toTake));
            }

            if (toTake < required) {
                missingMaterials.put(item, required - toTake);
            }
        }

        if (missingMaterials.isEmpty()) {
            // Have everything — proceed to building
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            phase = Phase.WALKING_TO_BUILDING;
        } else {
            if (!missingMaterials.isEmpty()) {
                VillageSavedData vdata = VillageSavedData.get(level);
                long tick = level.getGameTime();

                String villageName = entity.getAssignedVillageName().orElse("");
                vdata.getVillageByName(villageName).ifPresent(village -> {
                    missingMaterials.forEach((item, qty) -> {
                        String itemId = BuiltInRegistries.ITEM
                                .getKey(item).toString();
                        CraftingOrderManager.postOrderIfNeeded(
                                entity.getUUID(),
                                village.getId(),
                                itemId,
                                Math.min(qty, 64),   // sensible per-order cap
                                tick,
                                vdata);
                    });
                });
            }

            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            phase = Phase.WALKING_TO_BUILDING;
        }
    }

    private int countItemInBuilding(ServerLevel level, Building building, Item item) {
        int total = 0;
        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
        }
        return total;
    }

    private void walkToBuilding() {
        BlockPos target = targetBuilding.getShape().getOrigin();
        double dist = entity.distanceToSqr(
                target.getX(), entity.getY(), target.getZ()
        );


        if (dist > 4) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0
            ));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

            var size = targetBuilding.getShape();
            int volume = size.getWidth() * size.getHeight() * size.getLength();
            constructionDuration = volume * TICKS_PER_BLOCK;
            constructionTimer = 0;

            phase = Phase.CONSTRUCTING;
        }
    }

    private boolean hasPlacedAnyBlock = false;

    private void construct(ServerLevel level) {
        if (!constructionStarted && blocksToPlace.isEmpty() && attachmentBlocksToPlace.isEmpty() && constructionTimer == 0) {
            Identifier nextId = Identifier.fromNamespaceAndPath(
                    targetBuilding.getStructureId().getNamespace(),
                    targetBuilding.getStructureId().getPath()
                            .replaceAll("level_\\d+", "level_" + (targetBuilding.getLevel() + 1))
            );

            Optional<StructureTemplate> nextTemplate = BuildingPlacer.loadTemplate(level, nextId);
            if (nextTemplate.isEmpty()) {
                phase = Phase.IDLE;
                idleCooldown = IDLE_COOLDOWN;
                return;
            }

            List<StructureTemplate.StructureBlockInfo> allBlocks = new ArrayList<>();
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(targetBuilding.getRotation());

            for (var palette : nextTemplate.get().palettes) {
                allBlocks.addAll(palette.blocks());
            }

            List<StructureTemplate.StructureBlockInfo> allTransformed = allBlocks.stream()
                    .filter(b -> !b.state().isAir())
                    .map(b -> new StructureTemplate.StructureBlockInfo(
                            targetBuilding.getShape().getOrigin().offset(
                                    StructureTemplate.transform(b.pos(), settings.getMirror(),
                                            settings.getRotation(), BlockPos.ZERO)
                            ),
                            b.state(),
                            b.nbt()
                    ))
                    .sorted(Comparator
                            .comparingInt((StructureTemplate.StructureBlockInfo b) -> b.pos().getY())
                            .thenComparingInt(b -> b.pos().getX())
                            .thenComparingInt(b -> b.pos().getZ())
                    )
                    .collect(Collectors.toList());

            for (var blockInfo : allTransformed) {
                if (isAttachmentBlock(blockInfo.state())) {
                    attachmentBlocksToPlace.add(blockInfo);
                } else {
                    blocksToPlace.add(blockInfo);
                }
            }



            blocksToPlace.sort(Comparator
                    .comparingInt((StructureTemplate.StructureBlockInfo b) -> b.pos().getY())
                    .thenComparingInt(b -> b.pos().getX())
                    .thenComparingInt(b -> b.pos().getZ())
            );

            constructionStarted = true; // ← mark that we've loaded blocks
            return; // wait until next tick to start placing
        }

        constructionTimer++;
        blockPlaceTimer++;

        if (blockPlaceTimer >= TICKS_PER_BLOCK_PLACE) {
            blockPlaceTimer = 0;

            List<StructureTemplate.StructureBlockInfo> activeList =
                    !blocksToPlace.isEmpty() ? blocksToPlace :
                            !attachmentBlocksToPlace.isEmpty() ? attachmentBlocksToPlace : null;

            if (activeList != null) {
                StructureTemplate.StructureBlockInfo blockInfo = activeList.remove(0);
                BlockPos worldPos = blockInfo.pos();

                if (level.getBlockState(worldPos).equals(blockInfo.state())) {
                    return; // already correct, skip
                }

                Item requiredItem = blockInfo.state().getBlock().asItem();

                if (requiredItem != Items.AIR && !hasItemInPersonalInventory(requiredItem)) {
                    activeList.add(0, blockInfo);


                    int available = countItemInBuilding(level, workshopBuilding, requiredItem);
                    if (available > 0) {
                        int take = Math.min(available, 64);
                        BuildingStorageAccess.takeItem(level, workshopBuilding, requiredItem, take);
                        entity.getPersonalInventory().addItem(new ItemStack(requiredItem, take));

                    }
                    return;
                }

                if (requiredItem != Items.AIR) {
                    consumeFromPersonalInventory(requiredItem, 1);
                }

                entity.getLookControl().setLookAt(worldPos.getX(), worldPos.getY(), worldPos.getZ());
                level.removeBlock(worldPos, false);
                level.setBlock(worldPos, blockInfo.state(), 2 | 16);
                hasPlacedAnyBlock = true;
                entity.swing(InteractionHand.MAIN_HAND);

                var soundType = blockInfo.state().getSoundType();
                level.playSound(null, worldPos,
                        soundType.getPlaceSound(),
                        SoundSource.BLOCKS,
                        soundType.getVolume() * 0.5f,
                        soundType.getPitch()
                );
            }
        }

        // Only finalize after blocks were actually loaded and all placed
        if (constructionStarted && hasPlacedAnyBlock
                && blocksToPlace.isEmpty() && attachmentBlocksToPlace.isEmpty()) {

            Identifier nextId = Identifier.fromNamespaceAndPath(
                    targetBuilding.getStructureId().getNamespace(),
                    targetBuilding.getStructureId().getPath()
                            .replaceAll("level_\\d+", "level_" + (targetBuilding.getLevel() + 1))
            );
            targetBuilding.setStructureId(nextId);
            targetBuilding.setLevel(targetBuilding.getLevel() + 1);

            requirements.consumeFrom(entity.getPersonalInventory()); // ← only called once
            returnItemsToStockpile(level);
            entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            VillageSavedData.get(level).setDirty();

            blocksToPlace.clear();
            attachmentBlocksToPlace.clear();
            constructionTimer = 0;
            constructionStarted = false;
            hasPlacedAnyBlock = false;
            targetBuilding = null;
            workshopBuilding = null;
            requirements = null;
            phase = Phase.IDLE;
            idleCooldown = IDLE_COOLDOWN;
        }
    }

    private ExpansionRequest currentExpansion = null;

    private void findSite(ServerLevel level) {
        if (currentExpansion == null) {
            phase = Phase.IDLE;
            return; }

        BuildingType type = currentExpansion.getBuildingType();
        BuildingRegistry.BuildingDef def =
                BuildingRegistry.get(type).orElse(null);

        if (def == null) {
            failExpansion(level);
            return;
        }

        Identifier structId = Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID, def.structurePath()
        );

        Optional<StructureTemplate> template = BuildingPlacer.loadTemplate(level, structId);
        if (template.isEmpty()) {
            failExpansion(level);
            return;
        }

        Vec3i size = template.get().getSize();

        // Find village bounds
        Optional<AABB> bounds = getVillageName(level)
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(v -> v.getBounds(VillageSavedData.get(level)));

        if (bounds.isEmpty()) { failExpansion(level); return; }

        Optional<BlockPos> site = getVillageName(level)
                .flatMap(name -> VillageSavedData.get(level)
                        .getVillageByName(name))
                .flatMap(village -> BuildSiteFinder.findSite(
                        level,
                        village,                    // ← pass Village object
                        VillageSavedData.get(level),
                        type,                       // ← pass BuildingType
                        size.getX(),
                        size.getZ()));

        if (site.isEmpty()) {
            failExpansion(level);
            return;
        }

        currentExpansion.setTargetPos(site.get());
        VillageSavedData.get(level).setDirty();
        phase = Phase.PLACING_BUILDING;
    }

    private void placeBuilding(ServerLevel level) {
        if (currentExpansion == null) { phase = Phase.IDLE; return; }

        BlockPos target = currentExpansion.getTargetPos();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > 9) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        BuildingType type = currentExpansion.getBuildingType();

        // Get structure path from BuildingRegistry, not toString()
        BuildingRegistry.BuildingDef def = BuildingRegistry.get(type).orElse(null);
        if (def == null) { failExpansion(level); return; }

        Identifier structId = Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID, def.structurePath());

        // Re-query live surface at placement time
        int surfY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        BlockPos buildPos = new BlockPos(target.getX(), surfY, target.getZ());

        String buildingName = type.name().toLowerCase() + "_"
                + (int)(Math.random() * 1000);

        getVillageName(level).ifPresent(villageName -> {
            try {
                VillageSavedData data = VillageSavedData.get(level);

                // Face the building toward the village centre if known,
                // otherwise use NONE
                Rotation rotation = data.getVillageByName(villageName)
                        .flatMap(v -> v.getBounds(data))
                        .map(bounds -> {
                            BlockPos vCentre = BlockPos.containing(bounds.getCenter());
                            return chooseFacingRotation(buildPos, vCentre);
                        })
                        .orElse(Rotation.NONE);

                Optional<Building> placedOpt = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId, buildingName, type, rotation);

                if (placedOpt.isEmpty()) {
                    failExpansion(level);
                    return;
                }

                Building placed = placedOpt.get();

                data.getVillageByName(villageName).ifPresent(v -> {
                    v.getBuildingIds().add(placed.getId());
                    v.checkAndFireTierChangeHook(level);

                    // Connect a path from the new building to the village hub
                    connectExpansionPath(level, placed, v, data);

                    final Building finalPlaced = placed;
                    data.getVillageByName(villageName).ifPresent(vi ->
                            VillageDecorator.decorateExpansionBuilding(
                                    level, finalPlaced, vi, data));

                    VillageExpansionManager.onBuildingPlaced(
                            placed, v, data, level,
                            level.getGameTime());
                    data.setDirty();
                });

                currentExpansion.setStatus(
                        ExpansionRequest.RequestStatus.COMPLETE);
                data.removeExpansionRequest(currentExpansion.getId());

            } catch (Exception e) {
                System.out.println("BuilderGoal: failed to place "
                        + type + " — " + e.getMessage());
                failExpansion(level);
            }
        });

        currentExpansion = null;
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
    }

    private static Rotation chooseFacingRotation(BlockPos buildPos, BlockPos target) {
        int dx = target.getX() - buildPos.getX();
        int dz = target.getZ() - buildPos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0
                    ? Rotation.CLOCKWISE_90
                    : Rotation.COUNTERCLOCKWISE_90;
        } else {
            return dz > 0
                    ? Rotation.CLOCKWISE_180
                    : Rotation.NONE;
        }
    }

    private void failExpansion(ServerLevel level) {
        if (currentExpansion != null) {
            currentExpansion.setStatus(ExpansionRequest.RequestStatus.FAILED);
            VillageSavedData.get(level).removeExpansionRequest(currentExpansion.getId());
            currentExpansion = null;
        }
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN * 3; // longer cooldown after failure
    }

    private Optional<String> getVillageName(ServerLevel level) {
        return entity.getAssignedVillageName();
    }

    // --- Helpers ---

    private boolean hasItemInPersonalInventory(Item item) {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);

            if (stack.is(item)) return true;
        }
        return false;
    }

    private void consumeFromPersonalInventory(Item item, int count) {
        SimpleContainer inv = entity.getPersonalInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private void returnItemsToStockpile(ServerLevel level) {
        if (workshopBuilding == null) {
            // Find stockpile if we don't have a reference
            entity.getAssignedVillageName().ifPresent(villageName -> {
                VillageSavedData data = VillageSavedData.get(level);
                data.getVillageByName(villageName).ifPresent(village -> {
                    Building stockpile = findStockpile(village, data);
                    if (stockpile != null) dumpInventoryToBuilding(level, stockpile);
                });
            });
        } else {
            dumpInventoryToBuilding(level, workshopBuilding);
        }
    }

    private void dumpInventoryToBuilding(ServerLevel level, Building building) {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                boolean stored = BuildingStorageAccess.storeItem(level, building, stack.copy());
                if (stored) {

                    inv.setItem(i, ItemStack.EMPTY);
                } else {
                    System.out.println("Builder: stockpile full, could not return "
                            + stack.getItem());
                }
            }
        }
    }

    private Building findStockpile(
            tterrag1112.life_in_the_village.Village.Village village,
            VillageSavedData data
    ) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (entity.level() instanceof ServerLevel serverLevel) {
            returnItemsToStockpile(serverLevel);
        }
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        constructionStarted = false;
        blocksToPlace.clear();
        attachmentBlocksToPlace.clear();
        constructionTimer = 0;
        targetBuilding = null;
        workshopBuilding = null;
        requirements = null;
        currentExpansion = null;

    }

    private void connectExpansionPath(ServerLevel level,
                                        Building newBuilding,
                                        Village village,
                                        VillageSavedData data) {

        VillageDecorator.decorateExpansionBuilding(level, newBuilding, village, data);

          System.out.println("BuilderGoal: connected expansion road to "
                                 + newBuilding.getType() + " — "
                                 + newBuilding.getName());
      }


    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
