package tterrag1112.life_in_the_village.Entities.custom;

import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Entities.*;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionGoalFactory;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;

import tterrag1112.life_in_the_village.Kingdom.KingdomTitleData;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleRegistry;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcWallet;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The core NPC entity for Life in the Village.
 *
 * <h3>Architecture (post-refactor)</h3>
 * This class is a thin coordination shell. Domain logic lives in:
 * <ul>
 *   <li>{@link FamilyComponent} — spouse, children, house, family role</li>
 *   <li>{@link EconomyComponent} — inventory, coin wealth, payments</li>
 *   <li>{@link AppearanceComponent} — name, skin/hair, personality traits</li>
 *   <li>{@link ProfessionGoalFactory} — all goal registration</li>
 *   <li>{@link NpcInteractionHandler} — player interaction dispatch</li>
 *   <li>{@link NpcDialogue} — contextual greeting generation</li>
 *   <li>{@link WorkSchedule} — daily time windows per profession</li>
 * </ul>
 *
 * Delegation methods preserve backward compatibility so existing goals,
 * managers, and commands compile without changes. These are marked with
 * brief comments indicating which component they delegate to.
 */
public class TownspersonMob extends PathfinderMob implements RangedAttackMob {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    private static final int MAX_HEALTH = 20;
    private static final long TICKS_PER_DAY = 24000L;

    // =========================================================================
    // SYNCHED DATA — synced to client for rendering
    // =========================================================================

    private static final EntityDataAccessor<String> PROFESSION =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DISPLAY_NAME_KEY =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> LIFE_STAGE =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> FAMILY_ROLE =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> SKIN_TONE =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_STYLE =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> GROUP_ID =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> IS_GROUP_LEADER =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> CARAVAN_ID =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.STRING);

    // =========================================================================
    // COMPONENTS — domain logic delegates
    // =========================================================================

    private final FamilyComponent family = new FamilyComponent();
    private final EconomyComponent economy = new EconomyComponent();
    private final AppearanceComponent appearance = new AppearanceComponent();
    private final NpcRelationshipComponent relationships = new NpcRelationshipComponent();

    // =========================================================================
    // IDENTITY — age, gender, life stage
    // =========================================================================

    private int age = 18;
    private long birthTick = -1L;
    private long lastAgeTick = -1L;
    private boolean isMale = true;
    private String adventurerTitle = "";

    // =========================================================================
    // COMBAT — adventurer/guard role
    // =========================================================================

    @Nullable private CombatRole combatRole = null;

    // =========================================================================
    // WORK ASSIGNMENT — links NPC to village, building, plot
    // =========================================================================

    @Nullable private UUID assignedBuildingId = null;
    @Nullable private String assignedVillageName = null;
    @Nullable private BlockPos assignedPost = null;
    @Nullable private UUID assignedPlotId = null;
    @Nullable private UUID companyId = null;
    private boolean workingBlocked = false;
    private tterrag1112.life_in_the_village.Entities.ActivityState activityState =
            tterrag1112.life_in_the_village.Entities.ActivityState.IDLE;
    private UUID currentExpeditionId = null;

    // =========================================================================
    // EVENTS — festival/event overrides
    // =========================================================================

    @Nullable private VillageEvent.EventType eventOverride = null;
    private float eventTradeDiscount = 1.0f;

    // =========================================================================
    // CONVERSATION LOCK — freezes the NPC while a player has the profile open
    // =========================================================================

    /** Player currently holding the profile conversation, or {@code null}. */
    @Nullable private UUID conversationPartner = null;
    /** Server tick when the current lock expires (safety auto-unlock). */
    private long conversationUnlockTick = 0L;
    /** Was AI disabled before the conversation started? (restore on unlock.) */
    private boolean conversationPrevNoAi = false;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public TownspersonMob(EntityType<TownspersonMob> entityType, Level level) {
        super(entityType, level);
    }

    // =========================================================================
    // ATTRIBUTES
    // =========================================================================

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32)
                .add(Attributes.MOVEMENT_SPEED, 0.35)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.SCALE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PROFESSION, Profession.NONE.name());
        builder.define(DISPLAY_NAME_KEY, "Townsperson");
        builder.define(LIFE_STAGE, LifeStage.ADULT.name());
        builder.define(FAMILY_ROLE, FamilyRole.UNASSIGNED.name());
        builder.define(SKIN_TONE, 0);
        builder.define(HAIR_STYLE, 0);
        builder.define(HAIR_COLOR, 0);
        builder.define(GROUP_ID, "");
        builder.define(IS_GROUP_LEADER, false);
        builder.define(CARAVAN_ID, "");
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    // =========================================================================
    // GOALS — delegated to ProfessionGoalFactory
    // =========================================================================

    @Override
    protected void registerGoals() {
        ProfessionGoalFactory.register(this);
    }

    // =========================================================================
    // PROFESSION
    // =========================================================================

    public Profession getProfession() {
        try {
            return Profession.valueOf(entityData.get(PROFESSION));
        } catch (IllegalArgumentException e) {
            return Profession.NONE;
        }
    }

    public void setProfession(Profession profession) {
        entityData.set(PROFESSION, profession.name());
        ProfessionGoalFactory.register(this);
        if (appearance.getName() != null && !appearance.getName().isEmpty()) {
            updateDisplayName();
        }
    }


    // =========================================================================
    // SCHEDULE — delegated to WorkSchedule
    // =========================================================================

    public boolean isWorkTime() {
        if (eventOverride == VillageEvent.EventType.FESTIVAL_OF_LIGHTS) return false;
        if (eventOverride == VillageEvent.EventType.TRAINING_DAY) {
            return getProfession() == Profession.GUARD;
        }
        if (eventOverride == VillageEvent.EventType.HARVEST_FESTIVAL
                || eventOverride == VillageEvent.EventType.VILLAGE_FAIR) {
            return WorkSchedule.isWorkTime(this)
                    && (level().getDayTime() % 24000) < 3000;
        }
        return WorkSchedule.isWorkTime(this);
    }

    public boolean isSleepTime()  { return WorkSchedule.isSleepTime(this); }
    public boolean isSocialTime() { return WorkSchedule.isSocialTime(this); }
    public boolean isMealTime()   { return WorkSchedule.isMealTime(this); }
    public boolean shouldBeHome() { return WorkSchedule.shouldBeHome(this); }

    public WorkSchedule.DayPhase getCurrentPhase() {
        return WorkSchedule.getCurrentPhase(this);
    }

    // =========================================================================
    // IDENTITY — name, display, age, life stage
    // =========================================================================

    public String getNpcName()           { return appearance.getName(); }
    public void setNpcName(String name)  { appearance.setName(name); updateDisplayName(); }
    public String getSurname()           { return appearance.getSurname(); }
    public String getFirstName() {
        String name = appearance.getName();
        if (name == null || name.isEmpty()) return "";
        return name.split(" ")[0];
    }

    public String getAdventurerTitle() { return adventurerTitle; }
    public void setAdventurerTitle(String title) {
        this.adventurerTitle = title == null ? "" : title;
        updateDisplayName();
    }


    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity entity) {
        super.pickUpItem(level, entity);
    }

    private void updateDisplayName() {
        String base = appearance.getName() != null ? appearance.getName() : "";

        StringBuilder display = new StringBuilder();

        if(getProfession() == Profession.VILLAGE_LEADER){
            display.append(KingdomTitleRegistry.INSTANCE.getCulture("default").getLordTitle(this.isMale)).append(" ");
        }
        if(getProfession() == Profession.KINGDOM_RULER){
            display.append(KingdomTitleRegistry.INSTANCE.getCulture("default").getRulerTitle(this.isMale)).append(" ");
        }

        if (!base.isEmpty()) {
            display.append(base);
        } else {
            display.append(getProfession().getDisplayName());
        }
        if (!adventurerTitle.isEmpty()) {
            display.append(" ").append(adventurerTitle);
        }
        String actDisplay = activityState.toDisplayString();
        if (!actDisplay.isEmpty()) {
            display.append(" — ").append(actDisplay);
        }

        String finalName = display.toString().trim();
        entityData.set(DISPLAY_NAME_KEY, finalName);
        setCustomName(Component.literal(finalName));
        setCustomNameVisible(true);
    }


    @Override
    public Component getDisplayName() {
        Component custom = getCustomName();
        if (custom != null) return custom;
        return super.getDisplayName();
    }

    @Override
    public boolean shouldShowName() { return true; }

    public int getAge()                { return age; }
    public void setAge(int age)        { this.age = age; updateScale(); }
    public boolean isMale()            { return isMale; }
    public void setMale(boolean male)  { this.isMale = male; }

    public LifeStage getLifeStage() { return LifeStage.fromAge(age); }
    public boolean isAdult()        { return getLifeStage() == LifeStage.ADULT; }
    public boolean isChild()        { return getLifeStage() == LifeStage.CHILD; }
    public boolean isElderly()      { return getLifeStage() == LifeStage.ELDERLY; }

    public long getLastAgeTick()          { return lastAgeTick; }
    public void setLastAgeTick(long tick) { this.lastAgeTick = tick; }

    private void updateScale() {
        var attr = getAttribute(Attributes.SCALE);
        if (attr == null) return;
        attr.setBaseValue(switch (getLifeStage()) {
            case CHILD   -> 0.5;
            case TEEN    -> 0.75;
            case ADULT   -> 1.0;
            case ELDERLY -> 0.95;
        });
    }

    public void adoptSurname(String surname, ServerLevel level) {
        String firstName = getFirstName();
        setNpcName(firstName + " " + surname);

        if (family.getSpouseId().isPresent()) {
            UUID sid = family.getSpouseId().get();
            level.getEntitiesOfClass(TownspersonMob.class,
                    getBoundingBox().inflate(64),
                    mob -> mob.getUUID().equals(sid)
            ).forEach(spouse -> {
                String sf = spouse.getFirstName();
                spouse.setNpcName(sf + " " + surname);
            });
        }

        family.getChildrenIds().forEach(childId ->
                level.getEntitiesOfClass(TownspersonMob.class,
                        getBoundingBox().inflate(64),
                        mob -> mob.getUUID().equals(childId)
                ).forEach(child -> {
                    String cf = child.getFirstName();
                    child.setNpcName(cf + " " + surname);
                })
        );
    }

    // =========================================================================
    // APPEARANCE — delegated to AppearanceComponent
    // =========================================================================

    public int getSkinTone()  { return entityData.get(SKIN_TONE); }
    public int getHairStyle() { return entityData.get(HAIR_STYLE); }
    public int getHairColor() { return entityData.get(HAIR_COLOR); }

    public void setAppearance(int skinTone, int hairStyle, int hairColor) {
        appearance.setSkinTone(skinTone);
        appearance.setHairStyle(hairStyle);
        appearance.setHairColor(hairColor);
        entityData.set(SKIN_TONE, skinTone);
        entityData.set(HAIR_STYLE, hairStyle);
        entityData.set(HAIR_COLOR, hairColor);
    }

    public void randomizeAppearance(RandomSource random) {
        setAppearance(random.nextInt(6), random.nextInt(8), random.nextInt(7));
    }

    // =========================================================================
    // PERSONALITY — delegated to AppearanceComponent
    // =========================================================================

    public List<AppearanceComponent.PersonalityTrait> getTraits() {
        return appearance.getTraits();
    }

    public boolean hasTrait(AppearanceComponent.PersonalityTrait trait) {
        return appearance.hasTrait(trait);
    }

    public void addTrait(AppearanceComponent.PersonalityTrait trait) {
        appearance.addTrait(trait);
    }

    public void clearTraits() {
        appearance.clearTraits();
    }

    public int getActionTickRate(int baseRate) {
        return appearance.getActionTickRate(baseRate);
    }

    public double getPriceModifier() {
        return appearance.getPriceModifier();
    }

    public double getDetectionRange() {
        return appearance.getDetectionRange();
    }

    public static AppearanceComponent.PersonalityTrait randomTrait(RandomSource random) {
        AppearanceComponent.PersonalityTrait[] values = AppearanceComponent.PersonalityTrait.values();
        return values[random.nextInt(values.length)];
    }

    // =========================================================================
    // FAMILY — delegated to FamilyComponent
    // =========================================================================

    public FamilyComponent getFamily() { return family; }

    public FamilyRole getFamilyRole()          { return family.getRole(); }
    public void setFamilyRole(FamilyRole role) {
        family.setRole(role);
        entityData.set(FAMILY_ROLE, role.name());
    }

    public boolean hasHome()                   { return family.hasHome(); }
    public boolean hasSpouse()                 { return family.hasSpouse(); }

    public Optional<UUID> getHouseId()         { return family.getHouseId(); }
    public void setHouseId(@Nullable UUID id)  { family.setHouseId(id); }

    public Optional<UUID> getSpouseId()        { return family.getSpouseId(); }
    public void setSpouseId(@Nullable UUID id) { family.setSpouseId(id); }

    public Optional<UUID> getHeadOfHouseholdId()    { return family.getHeadOfHouseholdId(); }
    public void setHeadOfHouseholdId(@Nullable UUID id) { family.setHeadOfHouseholdId(id); }

    public List<UUID> getChildrenIds()             { return family.getChildrenIds(); }
    public void addChildId(UUID childId)           { family.addChild(childId); }

    // =========================================================================
    // RELATIONSHIPS — delegated to NpcRelationshipComponent
    // =========================================================================

    public NpcRelationshipComponent getRelationships()  { return relationships; }
    public int getRelationshipDelta(UUID playerId)      { return relationships.getDelta(playerId); }
    public int adjustRelationship(UUID playerId, int d) { return relationships.adjust(playerId, d); }

    // =========================================================================
// INVENTORY & CURRENCY — delegated to EconomyComponent
// =========================================================================

    public EconomyComponent getEconomy()             { return economy; }
    public SimpleContainer getPersonalInventory()    { return economy.getInventory(); }
    public NpcWallet getWallet()                     { return economy.getWallet(); }

    // Wealth queries
    public CurrencyValue getWealth()                 { return economy.getWealth(); }
    public boolean canAfford(CurrencyValue price)    { return economy.canAfford(price); }

    /**
     * Combined affordability: personal wallet + assigned building treasury.
     * Use for business purchases where the building funds the transaction.
     */
    public boolean canAffordWithBuilding(CurrencyValue price, ServerLevel level) {
        long buildingTreasury = getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingEconomy(id))
                .map(tterrag1112.life_in_the_village.Village.Economy
                        .BuildingEconomy::getTreasury)
                .orElse(0L);
        return economy.canAffordWithBuilding(price, buildingTreasury);
    }

    /**
     * Total wealth for display/AI decisions: personal + building treasury.
     */
    public CurrencyValue getTotalWealth(ServerLevel level) {
        long buildingTreasury = getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingEconomy(id))
                .map(tterrag1112.life_in_the_village.Village.Economy
                        .BuildingEconomy::getTreasury)
                .orElse(0L);
        return CurrencyValue.of(economy.getWealth().toBronze() + buildingTreasury);
    }

    // Personal wallet mutations — no visual, use NpcEconomy for visible transfers
    public boolean spend(CurrencyValue amount)       { return economy.spend(amount); }
    public void receive(CurrencyValue amount)        { economy.receive(amount); }

    /**
     * NPC-to-NPC payment with visual feedback.
     * Prefer this over direct wallet manipulation for all observed transactions.
     */
    public boolean pay(TownspersonMob receiver,
                       CurrencyValue amount) {
        if (!(level() instanceof ServerLevel sl)) {
            return economy.payTo(receiver.economy, amount);
        }
        return tterrag1112.life_in_the_village.Village.Economy.Currency
                .NpcEconomy.npcPay(this, receiver, amount, sl);
    }

    // =========================================================================
    // COMBAT ROLE — adventurer/party
    // =========================================================================

    public @Nullable CombatRole getCombatRole() { return combatRole; }

    public void setCombatRole(@Nullable CombatRole role) {
        this.combatRole = role;
        if (getProfession() == Profession.ADVENTURER) {
            ProfessionGoalFactory.register(this);
        }
    }

    public void setCombatRoleSilent(@Nullable CombatRole role) {
        this.combatRole = role;
    }

    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        if (combatRole != CombatRole.ARCHER) return;

        Arrow arrow = new Arrow(level(), this, new ItemStack(Items.ARROW), null);
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + dist * 0.2, dz, 1.6f, 10.0f);
        level().addFreshEntity(arrow);
    }

    // =========================================================================
    // WORK ASSIGNMENT
    // =========================================================================

    public Optional<UUID> getAssignedBuildingId()       { return Optional.ofNullable(assignedBuildingId); }
    public void setAssignedBuildingId(UUID id)           { this.assignedBuildingId = id; }

    public Optional<String> getAssignedVillageName()    { return Optional.ofNullable(assignedVillageName); }
    public void setAssignedVillageName(String name)      { this.assignedVillageName = name; }

    public void assignToBuilding(UUID buildingId, String villageName) {
        this.assignedBuildingId = buildingId;
        this.assignedVillageName = villageName;
    }

    public Optional<Building> getAssignedBuilding(ServerLevel level) {
        if (assignedBuildingId == null) return Optional.empty();
        return VillageSavedData.get(level).getBuildingById(assignedBuildingId);
    }
    public void clearAssignedBuilding(){
        this.setAssignedBuildingId(null);
    }

    public Optional<BlockPos> getAssignedPost()         { return Optional.ofNullable(assignedPost); }
    public void setAssignedPost(@Nullable BlockPos pos)  { this.assignedPost = pos; }

    public Optional<UUID> getAssignedPlotId()           { return Optional.ofNullable(assignedPlotId); }
    public void setAssignedPlotId(UUID id)               { this.assignedPlotId = id; }

    public boolean isWorkingBlocked()                   { return workingBlocked; }
    public void setIsWorkingBlocked(boolean blocked)     { this.workingBlocked = blocked; }

    /** Sets the NPC's current activity from a plain string (backward-compatible entry point). */
    public void setCurrentActivity(String activity) {
        setActivityState(tterrag1112.life_in_the_village.Entities.ActivityState.of(activity));
    }

    /** Full structured setter — use this when you also want to record a blocking reason. */
    public void setActivityState(tterrag1112.life_in_the_village.Entities.ActivityState state) {
        if (state == null) state = tterrag1112.life_in_the_village.Entities.ActivityState.IDLE;
        if (state.equals(activityState)) return;
        activityState = state;
        updateDisplayName();
    }

    public void clearCurrentActivity() {
        setActivityState(tterrag1112.life_in_the_village.Entities.ActivityState.IDLE);
    }

    /** Returns the display-safe activity string (no internal blocking reason). */
    public String getCurrentActivity() { return activityState.toDisplayString(); }

    /** Returns the full structured activity state for debug and goal use. */
    public tterrag1112.life_in_the_village.Entities.ActivityState getActivityState() {
        return activityState;
    }

    // =========================================================================
    // COMPANY
    // =========================================================================

    public Optional<UUID> getCompanyId()   { return Optional.ofNullable(companyId); }
    public void setCompanyId(UUID id)      { this.companyId = id; }
    public void clearCompanyId()           { this.companyId = null; }
    public boolean isCompanyWorker()       { return companyId != null; }

    // =========================================================================
    // ADVENTURER GROUP / CARAVAN
    // =========================================================================

    public Optional<UUID> getGroupId() {
        String raw = entityData.get(GROUP_ID);
        if (raw == null || raw.isEmpty()) return Optional.empty();
        try { return Optional.of(UUID.fromString(raw)); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public void setGroupId(@Nullable UUID id) {
        entityData.set(GROUP_ID, id != null ? id.toString() : "");
    }

    public void clearGroupId()             { entityData.set(GROUP_ID, ""); }
    public boolean isAdventurerGroupMember() { return !entityData.get(GROUP_ID).isEmpty(); }
    public boolean isGroupLeader()          { return entityData.get(IS_GROUP_LEADER); }
    public void setIsGroupLeader(boolean v) { entityData.set(IS_GROUP_LEADER, v); }

    public Optional<UUID> getCaravanId() {
        String raw = entityData.get(CARAVAN_ID);
        if (raw.isEmpty()) return Optional.empty();
        try { return Optional.of(UUID.fromString(raw)); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public void setCaravanId(UUID id)   { entityData.set(CARAVAN_ID, id.toString()); }
    public void clearCaravanId()        { entityData.set(CARAVAN_ID, ""); }
    public boolean isCaravanMember()    { return !entityData.get(CARAVAN_ID).isEmpty(); }

    // =========================================================================
    // EVENTS
    // =========================================================================

    public void setEventOverride(VillageEvent.EventType type) { this.eventOverride = type; }
    public void clearEventOverride()                           { this.eventOverride = null; }
    public Optional<VillageEvent.EventType> getEventOverride() { return Optional.ofNullable(eventOverride); }
    public boolean isEventTime()                               { return eventOverride != null; }
    public void setEventTradeDiscount(float discount)          { this.eventTradeDiscount = discount; }
    public float getEventTradeDiscount()                       { return eventTradeDiscount; }

    // =========================================================================
    // INTERACTION — delegated to NpcInteractionHandler
    // =========================================================================

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return NpcInteractionHandler.handle(this, player, hand);
    }

    // =========================================================================
    // CONVERSATION LOCK — used by NpcProfileHub so the NPC doesn't wander off
    // while a player has its profile screen open. Auto-unlocks after a
    // safety timeout if the client fails to send a close packet.
    // =========================================================================

    /** True while a player is holding this NPC in a conversation. */
    public boolean isInConversation() { return conversationPartner != null; }

    /** Player currently holding the conversation, or empty. */
    public Optional<UUID> getConversationPartner() {
        return Optional.ofNullable(conversationPartner);
    }

    /**
     * Locks the NPC for a player conversation. Idempotent when called again
     * with the same player; rejects other players while one lock is active.
     *
     * @param playerId     the player opening the conversation
     * @param currentTick  current server tick
     * @param timeoutTicks max ticks the lock may survive without a close
     * @return {@code true} if the lock is held by {@code playerId} after call
     */
    public boolean lockForConversation(UUID playerId,
                                       long currentTick,
                                       long timeoutTicks) {
        if (conversationPartner != null && !conversationPartner.equals(playerId)) {
            return false;
        }
        if (conversationPartner == null) {
            conversationPrevNoAi = isNoAi();
            setNoAi(true);
        }
        conversationPartner   = playerId;
        conversationUnlockTick = currentTick + Math.max(1L, timeoutTicks);
        return true;
    }

    /**
     * Clears the conversation lock if {@code playerId} holds it (or always,
     * when {@code playerId} is {@code null} — used by the safety timeout).
     * Restores the previous {@code NoAi} state.
     */
    public void unlockConversation(@Nullable UUID playerId) {
        if (conversationPartner == null) return;
        if (playerId != null && !playerId.equals(conversationPartner)) return;
        conversationPartner    = null;
        conversationUnlockTick = 0L;
        setNoAi(conversationPrevNoAi);
        conversationPrevNoAi   = false;
    }

    // =========================================================================
    // GOAL HELPER — used by ProfessionGoalFactory and NpcInteractionHandler
    // =========================================================================

    @SuppressWarnings("unchecked")
    public <T extends net.minecraft.world.entity.ai.goal.Goal> T getGoal(Class<T> goalClass) {
        return (T) goalSelector.getAvailableGoals().stream()
                .filter(WrappedGoal::isRunning)
                .map(WrappedGoal::getGoal)
                .filter(goal -> goal.getClass() == goalClass)
                .findFirst()
                .orElse(null);
    }

    // =========================================================================
    // WORK HELPERS — used by goals (package-accessible)
    // =========================================================================

    public boolean needsPickaxe() {
        var inv = economy.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ItemTags.PICKAXES)
                    && stack.getDamageValue() < stack.getMaxDamage() - 10) {
                return false;
            }
        }
        return true;
    }

    public boolean needsOre() {
        return economy.countItem(Items.RAW_IRON) < 4;
    }

    public Map<Item, Integer> getSellableItems(Profession prof) {
        Map<Item, Integer> items = new LinkedHashMap<>();
        switch (prof) {
            case FARMER, FARMHAND -> {
                items.put(Items.WHEAT, 8);
                items.put(Items.CARROT, 8);
                items.put(Items.POTATO, 8);
                items.put(Items.BEETROOT, 8);
            }
            case BLACKSMITH -> {
                items.put(Items.IRON_SWORD, 1);
                items.put(Items.IRON_PICKAXE, 1);
                items.put(Items.IRON_AXE, 1);
                items.put(Items.IRON_HELMET, 1);
                items.put(Items.IRON_CHESTPLATE, 1);
            }
            case CARPENTER -> {
                items.put(Items.OAK_PLANKS, 16);
                items.put(Items.OAK_STAIRS, 8);
                items.put(Items.OAK_FENCE, 8);
            }
            case MINER -> {
                items.put(Items.RAW_IRON, 8);
                items.put(Items.RAW_COPPER, 8);
                items.put(Items.RAW_GOLD, 4);
                items.put(Items.COBBLESTONE, 32);
            }
            default -> {}
        }
        return items;
    }

    /** Innkeeper interaction — kept here because it accesses entity state directly. */
    public void handleInnkeeperInteraction(ServerPlayer player, ServerLevel level) {
        player.displayClientMessage(
                Component.literal("[" + getNpcName() + "] "
                                + "Welcome! Rest here and recover your strength.")
                        .withStyle(ChatFormatting.GREEN), false);
        player.getFoodData().eat(6, 0.6f);
    }

    // =========================================================================
    // DEBUG — shown when player right-clicks with a stick
    // =========================================================================

    public void showDebugInfo(Player player) {
        tterrag1112.life_in_the_village.Entities.ActivityState as = activityState;

        player.displayClientMessage(Component.literal(
                        "=== " + getDisplayName().getString() + " ===")
                .withStyle(ChatFormatting.GOLD), false);

        // ── Identity ──────────────────────────────────────────────────────────
        player.displayClientMessage(Component.literal(
                "UUID: " + getUUID()), false);
        player.displayClientMessage(Component.literal(
                "Profession: " + getProfession().getDisplayName()
                        + " (" + getProfession().name() + ")"), false);
        player.displayClientMessage(Component.literal(
                "Life Stage: " + getLifeStage()
                        + " (age " + age + " days)"), false);
        player.displayClientMessage(Component.literal(
                "Family Role: " + getFamilyRole()), false);

        // ── Personality & economy ─────────────────────────────────────────────
        player.displayClientMessage(Component.literal(
                "Traits: " + (getTraits().isEmpty() ? "none"
                        : getTraits().stream().map(Enum::name)
                        .collect(Collectors.joining(", ")))), false);
        player.displayClientMessage(Component.literal(
                "Wealth: " + getWealth()
                        + "  |  Wallet: " + getWallet().toValue()), false);

        // ── Assignments ───────────────────────────────────────────────────────
        player.displayClientMessage(Component.literal(
                "Village: " + getAssignedVillageName().orElse("none")), false);
        player.displayClientMessage(Component.literal(
                "Building: " + getAssignedBuildingId().map(UUID::toString).orElse("none")), false);
        player.displayClientMessage(Component.literal(
                "House: " + getHouseId().map(UUID::toString).orElse("none")), false);

        // ── Schedule & work state ─────────────────────────────────────────────
        player.displayClientMessage(Component.literal(
                "Schedule phase: " + getCurrentPhase()
                        + "  |  Work blocked: " + workingBlocked), false);

        // ── Activity — always shown; blocking reason shown when present ────────
        if (as.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "Activity: idle").withStyle(ChatFormatting.GRAY), false);
        } else {
            net.minecraft.ChatFormatting actColour =
                    as.isBlocked() ? ChatFormatting.RED : ChatFormatting.WHITE;
            player.displayClientMessage(Component.literal(
                            "Activity: " + as.toDisplayString())
                    .withStyle(actColour), false);
            if (as.isBlocked()) {
                player.displayClientMessage(Component.literal(
                                "  Blocking: " + as.blockingReason())
                        .withStyle(ChatFormatting.RED), false);
            }
        }

        // ── NPC experience ────────────────────────────────────────────────────
        int npcXp  = tterrag1112.life_in_the_village.Profession.NpcProfessionXp.get(this);
        int npcLvl = tterrag1112.life_in_the_village.Profession.NpcProfessionXp.getLevel(this);
        player.displayClientMessage(Component.literal(
                "NPC XP: " + npcXp
                        + "  |  Tier: " + tterrag1112.life_in_the_village.Profession.NpcProfessionXp.TIER_NAMES[npcLvl]
                        + " (lv" + npcLvl + ")"), false);

        // ── Role ──────────────────────────────────────────────────────────────
        tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role =
                tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager.getRole(this);
        player.displayClientMessage(Component.literal(
                "Role: " + (role != null ? role.name() : "none")
                        + (role != null && role.isApprentice() ? " [apprentice]" : "")), false);

        // ── Combat role (guards / adventurers only) ───────────────────────────
        if (combatRole != null) {
            player.displayClientMessage(Component.literal(
                    "Combat Role: " + combatRole.name()), false);
        }
    }

    /**
     * Lists the NPC's personal inventory contents as chat messages.
     * Called on shift + right-click with a stick.
     */
    public void showInventoryInfo(Player player) {
        net.minecraft.world.SimpleContainer inv = getPersonalInventory();
        player.displayClientMessage(Component.literal(
                        "=== Inventory: " + getNpcName() + " ===")
                .withStyle(ChatFormatting.AQUA), false);

        boolean empty = true;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            empty = false;
            player.displayClientMessage(Component.literal(
                    "  [" + i + "] " + stack.getCount() + "x "
                            + stack.getHoverName().getString()), false);
        }
        if (empty) {
            player.displayClientMessage(Component.literal(
                    "  (empty)").withStyle(ChatFormatting.GRAY), false);
        }

        // Also show wallet balance for quick reference
        player.displayClientMessage(Component.literal(
                "  Wallet: " + getWallet().toValue())
                .withStyle(ChatFormatting.YELLOW), false);
    }

    // =========================================================================
    // SERVER AI — aging and house validation
    // =========================================================================

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        tickAging(level);
        tickHouseCheck(level);
        tickConversationLock(level);
    }

    private void tickConversationLock(ServerLevel level) {
        if (conversationPartner == null) return;
        if (level.getGameTime() >= conversationUnlockTick) {
            unlockConversation(null);
        }
    }

    private void tickAging(ServerLevel level) {
        long currentTick = level.getGameTime();
        if (birthTick < 0) {
            birthTick = currentTick - ((long) age * TICKS_PER_DAY);
        }
        int expectedAge = (int) ((currentTick - birthTick) / TICKS_PER_DAY);
        if (expectedAge > age) {
            LifeStage oldStage = getLifeStage();
            age = expectedAge;
            LifeStage newStage = getLifeStage();
            entityData.set(LIFE_STAGE, newStage.name());
            updateScale();
            if (oldStage != newStage) {
                onLifeStageChanged(oldStage, newStage, level);
            }
        }
    }

    private void tickHouseCheck(ServerLevel level) {
        if (level.getGameTime() % 200 != 0) return;
        if (getFamilyRole() != FamilyRole.HEAD) return;
        if (family.hasHome()) return;
        setFamilyRole(FamilyRole.UNASSIGNED);
    }

    private void onLifeStageChanged(LifeStage from, LifeStage to, ServerLevel level) {
        switch (to) {
            case TEEN -> {
                if (getProfession() == Profession.NONE) {
                    ProfessionGoalFactory.register(this);
                }
            }
            case ADULT, ELDERLY -> ProfessionGoalFactory.register(this);
            default -> {}
        }
        if (to == LifeStage.ELDERLY) {
            setIsWorkingBlocked(true);
        }
    }

    // =========================================================================
    // SPAWN SETUP
    // =========================================================================

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level,
                                        DifficultyInstance difficulty,
                                        EntitySpawnReason spawnType,
                                        @Nullable SpawnGroupData spawnGroupData) {
        isMale = random.nextBoolean();
        age = 18 + random.nextInt(43);

        String firstName = NpcNameRegistry.INSTANCE.generateFirstName(isMale, random);
        String surname = NpcNameRegistry.INSTANCE.generateSurname(random);
        setNpcName(firstName + " " + surname);

        AppearanceComponent.PersonalityTrait first = randomTrait(random);
        addTrait(first);
        if (random.nextBoolean()) {
            AppearanceComponent.PersonalityTrait[] all = AppearanceComponent.PersonalityTrait.values();
            for (int attempts = 0; attempts < 10; attempts++) {
                AppearanceComponent.PersonalityTrait candidate = all[random.nextInt(all.length)];
                if (candidate != first) {
                    addTrait(candidate);
                    break;
                }
            }
        }

        randomizeAppearance(random);
        updateScale();
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    // =========================================================================
    // NBT SAVE
    // =========================================================================

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);

        // ── Profession & assignment ──────────────────────────────────────────
        output.putString("profession", getProfession().name());
        if (assignedBuildingId != null) output.putString("assignedBuildingId", assignedBuildingId.toString());
        if (assignedVillageName != null) output.putString("assignedVillageName", assignedVillageName);
        if (assignedPlotId != null) output.putString("assignedPlotId", assignedPlotId.toString());
        if (assignedPost != null) {
            output.putInt("postX", assignedPost.getX());
            output.putInt("postY", assignedPost.getY());
            output.putInt("postZ", assignedPost.getZ());
        }
        getGroupId().ifPresent(id -> output.store("groupId", UUIDUtil.CODEC, id));
        output.store("isGroupLeader", Codec.BOOL, entityData.get(IS_GROUP_LEADER));
        getCaravanId().ifPresent(id -> output.store("caravanId", UUIDUtil.CODEC, id));
        if (companyId != null) output.putString("companyId", companyId.toString());
        if (combatRole != null) output.putString("combatRole", combatRole.name());
        if (currentExpeditionId != null) {
            output.putString("currentExpeditionId", currentExpeditionId.toString());
        }

        // ── Identity ─────────────────────────────────────────────────────────
        output.putString("npcName", appearance.getName());
        output.putInt("age", age);
        output.putLong("lastAgeTick", lastAgeTick);
        output.putLong("birthTick", birthTick);
        output.putBoolean("isMale", isMale);
        if (!adventurerTitle.isEmpty()) output.putString("adventurerTitle", adventurerTitle);

        // ── Appearance ───────────────────────────────────────────────────────
        output.putInt("skinTone", appearance.getSkinTone());
        output.putInt("hairStyle", appearance.getHairStyle());
        output.putInt("hairColor", appearance.getHairColor());

        // ── Personality ──────────────────────────────────────────────────────
        List<AppearanceComponent.PersonalityTrait> traits = appearance.getTraits();
        if (!traits.isEmpty()) {
            output.putString("traits", traits.stream()
                    .map(AppearanceComponent.PersonalityTrait::name).collect(Collectors.joining(",")));
        }

        // ── Family ───────────────────────────────────────────────────────────
        output.putString("familyRole", family.getRole().name());
        family.getHouseId().ifPresent(id -> output.putString("houseId", id.toString()));
        family.getSpouseId().ifPresent(id -> output.putString("spouseId", id.toString()));
        family.getHeadOfHouseholdId().ifPresent(id -> output.putString("headOfHouseholdId", id.toString()));
        if (!family.getChildrenIds().isEmpty()) {
            output.putString("childrenIds", family.getChildrenIds().stream()
                    .map(UUID::toString).collect(Collectors.joining(",")));
        }

        // ── Inventory ────────────────────────────────────────────────────────
        net.minecraft.core.NonNullList<ItemStack> items =
                net.minecraft.core.NonNullList.withSize(
                        economy.getInventory().getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < economy.getInventory().getContainerSize(); i++) {
            items.set(i, economy.getInventory().getItem(i));
        }
        net.minecraft.world.ContainerHelper.saveAllItems(output, items, false);

        // ── Relationships ────────────────────────────────────────────────────
        String rel = relationships.encode();
        if (!rel.isEmpty()) output.putString("npcRelationships", rel);
    }

    // =========================================================================
    // NBT LOAD
    // =========================================================================

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);

        // ── Profession & assignment ──────────────────────────────────────────
        input.read("profession", Codec.STRING).ifPresent(s -> {
            try { setProfession(Profession.valueOf(s)); }
            catch (IllegalArgumentException ignored) {}
        });
        input.read("assignedBuildingId", Codec.STRING)
                .ifPresent(s -> assignedBuildingId = UUID.fromString(s));
        input.read("assignedVillageName", Codec.STRING)
                .ifPresent(s -> assignedVillageName = s);
        input.read("assignedPlotId", Codec.STRING).ifPresent(s -> {
            if (!s.isEmpty()) assignedPlotId = UUID.fromString(s);
        });
        if (input.read("postX", Codec.INT).isPresent()) {
            assignedPost = new BlockPos(
                    input.read("postX", Codec.INT).orElse(0),
                    input.read("postY", Codec.INT).orElse(0),
                    input.read("postZ", Codec.INT).orElse(0));
        }
        input.read("groupId", UUIDUtil.CODEC).ifPresent(this::setGroupId);
        input.read("isGroupLeader", Codec.BOOL)
                .ifPresent(v -> entityData.set(IS_GROUP_LEADER, v));
        input.read("caravanId", UUIDUtil.CODEC).ifPresent(this::setCaravanId);
        input.read("companyId", Codec.STRING)
                .ifPresent(s -> companyId = UUID.fromString(s));
        input.read("combatRole", Codec.STRING).ifPresent(s -> {
            try { setCombatRoleSilent(CombatRole.valueOf(s)); }
            catch (IllegalArgumentException ignored) {}
        });

        input.read("currentExpeditionID", UUIDUtil.CODEC).ifPresent(this::setCurrentExpeditionId);

        // ── Identity ─────────────────────────────────────────────────────────
        input.read("npcName", Codec.STRING).ifPresent(s -> {
            appearance.setName(s);
            if (!s.isEmpty()) {
                setCustomName(Component.literal(s));
                setCustomNameVisible(true);
            }
        });
        input.read("age", Codec.INT).ifPresent(a -> { age = a; updateScale(); });
        input.read("birthTick", Codec.LONG).ifPresent(t -> birthTick = t);
        input.read("lastAgeTick", Codec.LONG).ifPresent(t -> lastAgeTick = t);
        input.read("isMale", Codec.BOOL).ifPresent(v -> isMale = v);
        input.read("adventurerTitle", Codec.STRING).ifPresent(t -> adventurerTitle = t);

        // ── Appearance ───────────────────────────────────────────────────────
        input.read("skinTone", Codec.INT).ifPresent(v -> {
            appearance.setSkinTone(v); entityData.set(SKIN_TONE, v);
        });
        input.read("hairStyle", Codec.INT).ifPresent(v -> {
            appearance.setHairStyle(v); entityData.set(HAIR_STYLE, v);
        });
        input.read("hairColor", Codec.INT).ifPresent(v -> {
            appearance.setHairColor(v); entityData.set(HAIR_COLOR, v);
        });

        // ── Personality ──────────────────────────────────────────────────────
        input.read("traits", Codec.STRING).ifPresent(s -> {
            if (!s.isEmpty()) {
                for (String name : s.split(",")) {
                    try { appearance.addTrait(AppearanceComponent.PersonalityTrait.valueOf(name)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        });

        // ── Family ───────────────────────────────────────────────────────────
        input.read("familyRole", Codec.STRING).ifPresent(s -> {
            try { family.setRole(FamilyRole.valueOf(s)); }
            catch (IllegalArgumentException ignored) {}
        });
        input.read("houseId", Codec.STRING)
                .ifPresent(s -> family.setHouseId(UUID.fromString(s)));
        input.read("spouseId", Codec.STRING)
                .ifPresent(s -> family.setSpouseId(UUID.fromString(s)));
        input.read("headOfHouseholdId", Codec.STRING)
                .ifPresent(s -> family.setHeadOfHouseholdId(UUID.fromString(s)));
        input.read("childrenIds", Codec.STRING).ifPresent(s -> {
            for (String id : s.split(",")) {
                if (!id.isEmpty()) {
                    try { family.addChild(UUID.fromString(id)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        });

        // ── Inventory ────────────────────────────────────────────────────────
        net.minecraft.core.NonNullList<ItemStack> items =
                net.minecraft.core.NonNullList.withSize(
                        economy.getInventory().getContainerSize(), ItemStack.EMPTY);
        net.minecraft.world.ContainerHelper.loadAllItems(input, items);
        for (int i = 0; i < items.size(); i++) {
            economy.getInventory().setItem(i, items.get(i));
        }

        // ── Relationships ────────────────────────────────────────────────────
        input.read("npcRelationships", Codec.STRING).ifPresent(relationships::decode);

        // ── Sync entity data from loaded state ───────────────────────────────
        entityData.set(LIFE_STAGE, getLifeStage().name());
        entityData.set(FAMILY_ROLE, family.getRole().name());
        activityState = tterrag1112.life_in_the_village.Entities.ActivityState.IDLE;
        updateDisplayName();
        updateScale();
    }

    // =========================================================================
    // DEATH EVENT — cleanup households and adventurer groups
    // =========================================================================

    @SubscribeEvent
    public static void onNpcDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob npc)) return;
        if (!(npc.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);
        HouseholdManager.onNpcDied(npc.getUUID(), data);
    }

    // =========================================================================
    // UTILITY — static lookup
    // =========================================================================

    public static Optional<TownspersonMob> findByUUID(ServerLevel level, UUID id) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                        new net.minecraft.world.phys.AABB(
                                -30_000_000, -64, -30_000_000,
                                30_000_000, 320, 30_000_000),
                        mob -> mob.getUUID().equals(id))
                .stream().findFirst();
    }
    public UUID getCurrentExpeditionId() { return currentExpeditionId; }

    public void setCurrentExpeditionId(UUID id) {
        this.currentExpeditionId = id;
    }

    public boolean isAway() { return currentExpeditionId != null; }

}