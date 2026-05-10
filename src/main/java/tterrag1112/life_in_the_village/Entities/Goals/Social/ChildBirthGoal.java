// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Social/ChildBirthGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.*;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Allows a married HEAD NPC (with a spouse and a home) to produce a child
 * NPC when village conditions are favourable.
 *
 * <h3>Conditions for birth</h3>
 * <ul>
 *   <li>This NPC is the household HEAD and has a spouse.</li>
 *   <li>The household has a house assigned.</li>
 *   <li>The household currently has fewer than {@link #MAX_CHILDREN} children.</li>
 *   <li>The village food need is SATISFIED or SURPLUS — no births during famine.</li>
 *   <li>Village population is below the house capacity ceiling
 *       (2 NPCs per house building).</li>
 *   <li>A random cooldown of {@link #BIRTH_INTERVAL} ticks has elapsed since
 *       the last check (roughly once per 3 in-game days).</li>
 * </ul>
 *
 * <h3>What is produced</h3>
 * A new {@link TownspersonMob} is spawned at age 0 (CHILD life stage)
 * with the household's surname, assigned to the parent's house, and
 * added to the parent's {@code childrenIds} list. The birth is recorded
 * in the kingdom history if the village belongs to a kingdom.
 */
public class ChildBirthGoal extends Goal {

    // ── Configuration ─────────────────────────────────────────────────────────
    /** Ticks between birth eligibility checks (~3 in-game days). */
    private static final int  BIRTH_INTERVAL = 24000 * 3;
    /** Probability of birth per eligible check (1 in N). */
    private static final int  BIRTH_CHANCE   = 4;
    /** Maximum children per household. */
    private static final int  MAX_CHILDREN   = 3;
    /** Minimum age (days) before a couple can have children. */
    private static final int  MIN_PARENT_AGE = 20;

    private final TownspersonMob entity;
    private int checkTimer = 0;

    public ChildBirthGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        // Only HEAD adults with a spouse and a home can trigger birth
        if (entity.getFamilyRole() != FamilyRole.HEAD) return false;
        if (!entity.hasSpouse()) return false;
        if (!entity.hasHome()) return false;
        if (!entity.isAdult()) return false;
        if (entity.getAge() < MIN_PARENT_AGE) return false;

        checkTimer++;
        if (checkTimer < BIRTH_INTERVAL) return false;
        checkTimer = 0;

        // Random chance — not every eligible tick produces a child
        if (entity.getRandom().nextInt(BIRTH_CHANCE) != 0) return false;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        // Check conditions
        return childrenBelowMax(level)
                && foodIsSufficient(level)
                && populationBelowCeiling(level);
    }

    @Override
    public boolean canContinueToUse() { return false; } // one-shot

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        spawnChild(level);
    }

    // =========================================================================
    // Condition checks
    // =========================================================================

    private boolean childrenBelowMax(ServerLevel level) {
        return entity.getChildrenIds().size() < MAX_CHILDREN;
    }

    private boolean foodIsSufficient(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(village -> {
                    var foodNeed = village.getNeeds().get(NeedCategory.FOOD);
                    if (foodNeed == null) return true; // no data — allow
                    NeedLevel lvl = foodNeed.getLevel();
                    return lvl == NeedLevel.SATISFIED || lvl == NeedLevel.SURPLUS;
                })
                .orElse(false);
    }

    private boolean populationBelowCeiling(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> villageOpt = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName);
        if (villageOpt.isEmpty()) return false;

        Village village = villageOpt.get();

        // Count houses in the village
        long houseCount = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.HOUSE
                        || b.getType() == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.FARMHOUSE)
                .count();

        // Population ceiling = 4 NPCs per house (generous — allows family growth)
        long ceiling = houseCount * 4;

        long currentPop = level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        return currentPop < ceiling;
    }

    // =========================================================================
    // Spawning
    // =========================================================================

    private void spawnChild(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        UUID houseId = entity.getHouseId().orElse(null);
        if (houseId == null) return;

        var houseOpt = data.getBuildingById(houseId);
        if (houseOpt.isEmpty()) return;

        // Spawn the child at the parent's position (inside or near the house)
        TownspersonMob child = ModEntities.TOWNSPERSON.get()
                .create(level, EntitySpawnReason.BREEDING);
        if (child == null) return;

        child.setPos(
                entity.getX() + (entity.getRandom().nextDouble() - 0.5),
                entity.getY(),
                entity.getZ() + (entity.getRandom().nextDouble() - 0.5));

        child.finalizeSpawn(level,
                level.getCurrentDifficultyAt(child.blockPosition()),
                EntitySpawnReason.BREEDING, null);

        // ── Identity ──────────────────────────────────────────────────────────
        boolean childIsMale = entity.getRandom().nextBoolean();
        String firstName = NpcNameRegistry.INSTANCE
                .generateFirstName(childIsMale, entity.getRandom());
        // Inherit father's surname
        String parentSurname = entity.getSurname();
        child.setNpcName(firstName + " " + (parentSurname.isEmpty()
                ? NpcNameRegistry.INSTANCE.generateSurname(entity.getRandom())
                : parentSurname));

        // ── Age — born as infant ───────────────────────────────────────────────
        child.setAge(0);

        // ── Family assignment ─────────────────────────────────────────────────
        child.setFamilyRole(FamilyRole.CHILD);
        child.setHouseId(houseId);
        child.setHeadOfHouseholdId(entity.getUUID());
        entity.getAssignedVillageName().ifPresent(child::setAssignedVillageName);

        // Register child with parent
        entity.addChildId(child.getUUID());

        // Register child with spouse too
        entity.getSpouseId().ifPresent(spouseId ->
                level.getEntitiesOfClass(TownspersonMob.class,
                                entity.getBoundingBox().inflate(64),
                                mob -> mob.getUUID().equals(spouseId))
                        .forEach(spouse -> spouse.addChildId(child.getUUID())));

        // Track D3.5B — heir-traits hybrid roll for noble children.
        // Non-noble children retain the legacy "zero + culture-bias"
        // path applied at context-load.
        tterrag1112.life_in_the_village.Npc.Nobility.HeirTraitRoll
                .tryApplyAtBirth(child, entity, level);

        level.addFreshEntity(child);

        // ── Phase 1: BirthInFamily fires for mother + spouse if known ──────
        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.BirthInFamily(
                        entity, child.getUUID()));
        entity.getSpouseId().ifPresent(spouseId ->
                level.getEntitiesOfClass(TownspersonMob.class,
                                entity.getBoundingBox().inflate(64),
                                mob -> mob.getUUID().equals(spouseId))
                        .forEach(spouse ->
                                tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                                        new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.BirthInFamily(
                                                spouse, child.getUUID()))));

        // ── HouseholdManager ──────────────────────────────────────────────────
        HouseholdManager.onNpcBorn(child.getUUID(), houseId, data, level.getGameTime());

        // ── Kingdom history ───────────────────────────────────────────────────
        entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(village ->
                        data.getKingdomForVillage(village.getId())
                                .ifPresent(kingdom -> {
                                    kingdom.getHistory().recordEvent(
                                            HistoryTextGenerator.notableBirth(
                                                    child.getNpcName(),
                                                    village.getName(),
                                                    level.getGameTime()),
                                            kingdom.getName(),
                                            kingdom.getRulerName(level));
                                    data.markDirty();
                                }));

        System.out.println("ChildBirthGoal: " + child.getNpcName()
                + " born to " + entity.getNpcName()
                + " in " + entity.getAssignedVillageName().orElse("unknown"));
    }
}