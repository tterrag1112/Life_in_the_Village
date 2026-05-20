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
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
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
import tterrag1112.life_in_the_village.Npc.Health.HealthComponent;
import tterrag1112.life_in_the_village.Npc.Knowledge.NpcKnowledgeLedger;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalSet;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog;
import tterrag1112.life_in_the_village.Npc.Mood.NpcMoodState;
import tterrag1112.life_in_the_village.Npc.Nobility.NobilityComponent;
import tterrag1112.life_in_the_village.Npc.Relations.NpcRelationshipLedger;
import tterrag1112.life_in_the_village.Npc.Schedule.PersonalScheduleOverride;
import tterrag1112.life_in_the_village.Npc.Skills.SkillComponent;
import tterrag1112.life_in_the_village.Npc.Traits.TraitDriftLog;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;
import tterrag1112.life_in_the_village.Npc.Verbs.NpcVerbCooldowns;
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
    /** Phase 6.0 — visible "carry display" item, synced for client rendering.
     *  Set by {@code CarryHoldAnimationBehavior} from the Brain memory; entirely
     *  separate from the real inventory in {@link #economy}. */
    private static final EntityDataAccessor<ItemStack> CARRY_DISPLAY_ITEM =
            SynchedEntityData.defineId(TownspersonMob.class, EntityDataSerializers.ITEM_STACK);

    // Entity event ids for Brain-triggered animation states.
    // 64-66 allocated in Phase 6.0; 69-75 allocated in Phase 6.1.a.
    // 67-68 reserved for the carry-hold toggle.
    private static final byte ENTITY_EVENT_GESTURE_WAVE          = 64;
    private static final byte ENTITY_EVENT_GESTURE_STRETCH       = 65;
    private static final byte ENTITY_EVENT_GESTURE_LOOK_AROUND   = 66;
    private static final byte ENTITY_EVENT_CARRY_HOLD_START      = 67;
    private static final byte ENTITY_EVENT_CARRY_HOLD_STOP       = 68;
    private static final byte ENTITY_EVENT_GESTURE_YAWN          = 69;
    private static final byte ENTITY_EVENT_GESTURE_NOD           = 70;
    private static final byte ENTITY_EVENT_GESTURE_FRIENDLY_WAVE = 71;
    private static final byte ENTITY_EVENT_GESTURE_HEAD_SHAKE    = 72;
    private static final byte ENTITY_EVENT_GESTURE_SIGH          = 73;
    private static final byte ENTITY_EVENT_GESTURE_SLOUCH        = 74;
    private static final byte ENTITY_EVENT_GESTURE_LEAN          = 75;
    // Phase 6.1.b — sit/stand transitions.
    private static final byte ENTITY_EVENT_SIT_DOWN              = 76;
    private static final byte ENTITY_EVENT_STAND_UP              = 77;

    /** Generic idle-gesture animation. Several gesture variants share
     *  this state — the entity-event ID is what distinguishes them on
     *  the rendering side (clients can read {@link #lastGestureFired}
     *  to pick a sub-animation). */
    public final AnimationState idleGestureState = new AnimationState();
    /** Client-rendered carry-hold animation. Started/stopped when the
     *  synced {@link #CARRY_DISPLAY_ITEM} changes between empty and non-empty. */
    public final AnimationState carryHoldState = new AnimationState();
    // Phase 6.1.a — additional per-gesture animation states. Plumbed
    // so future renderer authoring can drive distinct poses per state.
    public final AnimationState yawnState          = new AnimationState();
    public final AnimationState nodState           = new AnimationState();
    public final AnimationState friendlyWaveState  = new AnimationState();
    public final AnimationState headShakeState     = new AnimationState();
    public final AnimationState sighState          = new AnimationState();
    public final AnimationState slouchState        = new AnimationState();
    public final AnimationState leanState          = new AnimationState();
    // Phase 6.1.b — sit/stand transitions (~10 ticks; pose itself uses
    // vanilla Pose.SITTING, no per-tick animation needed).
    public final AnimationState sitDownState       = new AnimationState();
    public final AnimationState standUpState       = new AnimationState();

    /** Last gesture fired, used by renderer to pick a sub-pose when
     *  several gestures share {@link #idleGestureState}. */
    public tterrag1112.life_in_the_village.Npc.Brain.Gesture lastGestureFired =
            tterrag1112.life_in_the_village.Npc.Brain.Gesture.LOOK_AROUND;

    /** Transient mood band written by {@code MoodReactionBehavior}, read
     *  by {@code IdleGesturePalette} when computing gesture weights.
     *  Not persisted — the next Brain tick recomputes it. */
    private tterrag1112.life_in_the_village.Npc.Brain.MoodBand moodBand =
            tterrag1112.life_in_the_village.Npc.Brain.MoodBand.NEUTRAL;

    // =========================================================================
    // COMPONENTS — domain logic delegates
    // =========================================================================

    private final FamilyComponent family = new FamilyComponent();
    private final EconomyComponent economy = new EconomyComponent();
    /** Track D3.2a — kingdom-tier nobility overlay (house, rank,
     *  prestige). Default-constructed = "common citizen". Inert at
     *  D3.2a close; D3.2b reads + writes for succession, fealty,
     *  marriage, dynasty-tree mechanics. */
    private final NobilityComponent nobility = new NobilityComponent();
    /** True once the NPC has received its one-time profession starter
     *  bundle. Persisted; flipped by {@link #setProfession} on the
     *  first non-NONE assignment so re-assignment doesn't re-pay. */
    private boolean professionStarterPaid;
    /** True once the NPC has had culture biases + religion applied
     *  via {@link #applyVillageCulture}. The application is deferred
     *  to the first {@code assignToBuilding(non-null villageName)}
     *  call because {@code finalizeSpawn} runs before village
     *  assignment in the populator path. Persisted so reloads
     *  don't double-apply. */
    private boolean cultureApplied;
    /** Game tick at which the NPC's CURRENT profession was assigned.
     *  Reset on every profession change; used by Phase 4 doc 26 to
     *  gate the merchant → trading-company promotion (365 days
     *  continuously merchant). 0 means never assigned a real
     *  profession. */
    private long professionStartedTick;
    private final AppearanceComponent appearance = new AppearanceComponent();
    private final NpcRelationshipComponent relationships = new NpcRelationshipComponent();
    private final TraitVector traits = new TraitVector();
    private final TraitDriftLog traitDrift = new TraitDriftLog();
    private final NpcMemoryLog memory = new NpcMemoryLog();
    private final NpcKnowledgeLedger knowledge = new NpcKnowledgeLedger();
    private final NpcMoodState mood = new NpcMoodState();
    private final SkillComponent skills = new SkillComponent();
    private final LifeGoalSet lifeGoals = new LifeGoalSet();
    private final NpcVerbCooldowns verbCooldowns = new NpcVerbCooldowns();
    private final PersonalScheduleOverride scheduleOverride = new PersonalScheduleOverride();
    private final NpcRelationshipLedger npcRelationships = new NpcRelationshipLedger();
    private final tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference hobbyPreference =
            new tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference();
    private final tterrag1112.life_in_the_village.Npc.Scribal.AuthorStatus authorStatus =
            new tterrag1112.life_in_the_village.Npc.Scribal.AuthorStatus();
    private final tterrag1112.life_in_the_village.Npc.Scribal.ScholarProgress scholarProgress =
            new tterrag1112.life_in_the_village.Npc.Scribal.ScholarProgress();
    private final tterrag1112.life_in_the_village.Npc.Aging.ChildhoodState childhoodState =
            new tterrag1112.life_in_the_village.Npc.Aging.ChildhoodState();
    private final tterrag1112.life_in_the_village.Npc.Aging.RetirementState retirementState =
            new tterrag1112.life_in_the_village.Npc.Aging.RetirementState();
    /** Phase 2 task 15: distinguishes age-natural deaths for memory/gossip routing. */
    private boolean dyingNatural = false;
    /**
     * Per-NPC religious belief state (Phase 3 doc 20). Phase 0
     * accessors leave the field empty; the spawn pass in
     * {@code finalizeSpawn} seeds the village's dominant religion
     * at strength 0.3 per spec line 64.
     */
    private final tterrag1112.life_in_the_village.Npc.Religion.PietyComponent piety =
            new tterrag1112.life_in_the_village.Npc.Religion.PietyComponent();
    /**
     * Per-NPC health state (Phase 3 doc 21). Conditions, constitution,
     * day counters. The daily {@code HealthTickSystem} sweeps onset +
     * resolution; onset hooks (combat, work-accident) write here.
     */
    private final tterrag1112.life_in_the_village.Npc.Health.HealthComponent health =
            new tterrag1112.life_in_the_village.Npc.Health.HealthComponent();
    /**
     * Healer-only stash for produced remedies. Stays empty for any
     * non-HEALER profession; the HealerWorkGoal fills it between
     * treatments.
     */
    private final tterrag1112.life_in_the_village.Npc.Health.HealerInventory healerInventory =
            new tterrag1112.life_in_the_village.Npc.Health.HealerInventory();
    /**
     * Phase 4 doc 29 — visitor metadata. Empty (visitorType=null)
     * for regular residents; populated when the
     * {@code VisitorFluxEngine} spawns this NPC as a visitor. The
     * settled-permanently flag flips on for refugees who get
     * accepted and stay (spec lines 199-208).
     */
    private final tterrag1112.life_in_the_village.Npc.Visitor.VisitorState visitorState =
            new tterrag1112.life_in_the_village.Npc.Visitor.VisitorState();

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

    /** P0a-13 — pending or in-progress repaint job for builder NPCs.
     *  Null when no job is assigned. Persisted via the {@code repaint.*}
     *  NBT keys on this entity's save data. */
    @Nullable private tterrag1112.life_in_the_village.Village.Decoration
            .Variants.RepaintJob repaintJob = null;

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
        builder.define(CARRY_DISPLAY_ITEM, ItemStack.EMPTY);
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    // =========================================================================
    // GOALS — delegated to ProfessionGoalFactory
    // =========================================================================

    @Override
    protected void registerGoals() {
        ProfessionGoalFactory.register(this);
        // B2.6 — homestead goals dispatch to per-plot-type handlers
        // for SPOUSE (any WORK_* phase) and CHILD (SOCIAL phase). The
        // goal is registered for all NPCs; canUse() filters by
        // FamilyRole + DayPhase so HEAD-role NPCs and other phases
        // skip cleanly. Priority slot lower than profession goals
        // so HEAD's profession always wins; SPOUSE / CHILD don't
        // have profession goals competing.
        this.goalSelector.addGoal(15,
                new tterrag1112.life_in_the_village.Entities.Goals
                        .Homestead.AbstractHomesteadGoal.Spouse(this));
        this.goalSelector.addGoal(15,
                new tterrag1112.life_in_the_village.Entities.Goals
                        .Homestead.AbstractHomesteadGoal.Child(this));
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

    /** Game tick when the NPC's current profession was assigned.
     *  Resets on every {@link #setProfession} change. Returns 0 if
     *  the NPC has never been assigned a real profession. Phase 4
     *  doc 26 reads this for the merchant tenure check. */
    public long getProfessionStartedTick() { return professionStartedTick; }

    public void setProfession(Profession profession) {
        Profession previous = getProfession();
        entityData.set(PROFESSION, profession.name());
        ProfessionGoalFactory.register(this);
        if (appearance.getName() != null && !appearance.getName().isEmpty()) {
            updateDisplayName();
        }
        // Reset the profession tenure clock on every change so
        // promotions like merchant → trading company that gate on
        // continuous-employment days can read a clean baseline.
        if (previous != profession
                && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            this.professionStartedTick = sl.getGameTime();
        }
        // First-ever real-profession assignment pays the starter pouch
        // (bronze via NpcStartingWealth + items via ProfessionStarterTable).
        // Guard prevents re-payment when an NPC swaps roles later.
        // NONE assignments don't burn the slot — drifters can still get
        // a starter when they later get hired into a real profession.
        if (!professionStarterPaid && profession != Profession.NONE) {
            applyStarterFor(profession);
            professionStarterPaid = true;
        }
        // Phase 4 doc 27: route the change through the implicit-guild
        // bootstrap so the NPC auto-(un)joins matching guild clusters.
        if (level() instanceof net.minecraft.server.level.ServerLevel sl
                && previous != profession) {
            tterrag1112.life_in_the_village.Guilds.Common.GuildBootstrap
                    .onProfessionChanged(sl, this, previous, profession, sl.getGameTime());
        }
        // Phase 5 doc 33: profession changes can affect the visible
        // accessory set (e.g. scribe's quill comes via the office mark
        // path, but the rebuild is cheap and the AppearanceComponent
        // takes care of the diff).
        if (level() instanceof net.minecraft.server.level.ServerLevel
                && previous != profession) {
            try {
                tterrag1112.life_in_the_village.Entities.custom.Appearance
                        .AppearanceRebuilder.rebuild(this);
            } catch (RuntimeException ignored) {}
        }
    }

    /** Pays bronze via {@link tterrag1112.life_in_the_village.Village.Economy.Currency.NpcStartingWealth}
     *  and inserts the {@link tterrag1112.life_in_the_village.Profession.ProfessionStarterTable}
     *  item bundle. Called once per NPC by {@link #setProfession}. */
    private void applyStarterFor(Profession profession) {
        var bronze = tterrag1112.life_in_the_village.Village.Economy.Currency
                .NpcStartingWealth.forProfession(profession, getRandom());
        economy.receive(bronze);
        for (net.minecraft.world.item.ItemStack stack
                : tterrag1112.life_in_the_village.Profession
                        .ProfessionStarterTable.itemsFor(profession)) {
            if (stack == null || stack.isEmpty()) continue;
            net.minecraft.world.item.ItemStack remainder =
                    economy.getInventory().addItem(stack.copy());
            // If the inventory was full (rare — first-time payment
            // typically lands in an empty stash), drop the leftover at
            // the NPC's feet so the player can pick it up.
            if (!remainder.isEmpty()
                    && level() instanceof net.minecraft.server.level.ServerLevel sl) {
                sl.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                        sl, getX(), getY(), getZ(), remainder));
            }
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

    public tterrag1112.life_in_the_village.Npc.Schedule.DayPhase getCurrentPhase() {
        return tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver
                .phaseAt(this, level().getDayTime());
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

    /** Direct access to the appearance component for Phase 5 doc 33 layer
     *  callers (rebuilder, debug commands). Most callers should use the
     *  delegated accessors below. */
    public AppearanceComponent getAppearance() { return appearance; }

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

    /**
     * Returns the 8-axis trait vector. Named {@code getTraitVector} rather
     * than {@code getTraits} because the legacy {@link #getTraits()} method
     * still returns the old {@code PersonalityTrait} list during the one-
     * release migration window.
     */
    public TraitVector getTraitVector() {
        return traits;
    }

    /** Cumulative trait-drift log; see {@code docs/npc_redesign/10-phase1-integration.md}. */
    public TraitDriftLog getTraitDrift() {
        return traitDrift;
    }

    /** Situational memory log; see {@code docs/npc_redesign/02-memory-system.md}. */
    public NpcMemoryLog getMemory() {
        return memory;
    }

    /** Knowledge ledger; see {@code docs/npc_redesign/03-knowledge-system.md}. */
    public NpcKnowledgeLedger getKnowledge() {
        return knowledge;
    }

    /** Short-term emotional state; see {@code docs/npc_redesign/04-mood-system.md}. */
    public NpcMoodState getMood() {
        return mood;
    }

    /** 8-skill cross-profession proficiency; see {@code docs/npc_redesign/05-skill-system.md}. */
    public SkillComponent getSkills() {
        return skills;
    }

    /** Active + history life goals; see {@code docs/npc_redesign/07-life-goals.md}. */
    public LifeGoalSet getLifeGoals() {
        return lifeGoals;
    }

    /** Verb cooldown log; see {@code docs/npc_redesign/09-player-verbs.md}. */
    public NpcVerbCooldowns getVerbCooldowns() {
        return verbCooldowns;
    }

    /** Personal schedule deviation; see {@code docs/npc_redesign/13-weekly-schedule.md}. */
    public PersonalScheduleOverride getScheduleOverride() {
        return scheduleOverride;
    }

    /**
     * NPC↔NPC relationship ledger; see
     * {@code docs/npc_redesign/11-npc-relationship-ledger.md}. Distinct
     * from the player→NPC ledger exposed via {@link #getRelationships()}.
     */
    public NpcRelationshipLedger getNpcRelationships() {
        return npcRelationships;
    }

    /**
     * Per-NPC hobby state (Phase 2 task 14). See
     * {@code docs/npc_redesign/14-hobby-activities.md}.
     */
    public tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference getHobbyPreference() {
        return hobbyPreference;
    }

    /** Author publication record (Phase 2 task 17). */
    public tterrag1112.life_in_the_village.Npc.Scribal.AuthorStatus getAuthorStatus() {
        return authorStatus;
    }

    /** Scholar in-progress research (Phase 2 task 17). */
    public tterrag1112.life_in_the_village.Npc.Scribal.ScholarProgress getScholarProgress() {
        return scholarProgress;
    }

    /** Per-NPC religious belief state (Phase 3 doc 20). Never null after construction. */
    public tterrag1112.life_in_the_village.Npc.Religion.PietyComponent getPiety() {
        return piety;
    }

    /** Per-NPC health state (Phase 3 doc 21). Never null. */
    public HealthComponent getHealthComponent() {
        return health;
    }

    /** Healer remedy stash (Phase 3 doc 21). Empty for non-healers. */
    public tterrag1112.life_in_the_village.Npc.Health.HealerInventory getHealerInventory() {
        return healerInventory;
    }

    /** Childhood state (Phase 2 task 15). Populated for CHILD/TEEN. */
    public tterrag1112.life_in_the_village.Npc.Aging.ChildhoodState getChildhoodState() {
        return childhoodState;
    }

    /** Retirement state (Phase 2 task 15). Populated for ELDERLY. */
    public tterrag1112.life_in_the_village.Npc.Aging.RetirementState getRetirementState() {
        return retirementState;
    }

    /** True when an age-natural death is in progress (vs combat). */
    public boolean isDyingNatural() { return dyingNatural; }
    public void markDyingNatural()  { this.dyingNatural = true; }

    /** Visitor metadata (Phase 4 doc 29). Always non-null; check
     *  {@link tterrag1112.life_in_the_village.Npc.Visitor.VisitorState#isVisitor}
     *  before treating an NPC as ephemeral. */
    public tterrag1112.life_in_the_village.Npc.Visitor.VisitorState getVisitorState() {
        return visitorState;
    }

    /** Convenience — true iff the NPC is currently flagged as an
     *  ephemeral visitor (not a settled refugee). */
    public boolean isVisitor() { return visitorState.isVisitor(); }

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

    /** Track D3.2a — kingdom-tier nobility overlay accessor. */
    public NobilityComponent getNobility() { return nobility; }

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
        // Phase 5 doc 31: apply culture biases the first time the NPC
        // gets a village assignment. The audit caught that finalizeSpawn
        // runs before assignToBuilding in the populator flow, so the
        // culture-derived trait bias + religion seed never landed
        // unless deferred to here.
        if (!cultureApplied && villageName != null
                && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            applyVillageCulture(sl);
        }
    }

    /**
     * Phase 5 doc 31 — applies the village's culture to this NPC:
     * additive trait nudges per {@code culture.traitBias()} and a
     * culture-specific religion seed (overwriting the default
     * {@code sunstead} seeded at finalizeSpawn). Idempotent via
     * {@link #cultureApplied}.
     */
    private void applyVillageCulture(net.minecraft.server.level.ServerLevel level) {
        var culture = tterrag1112.life_in_the_village.Cultures.CultureResolver
                .of(this);
        if (culture == null) return;
        // Trait biases.
        for (var axis : tterrag1112.life_in_the_village.Npc.Traits.TraitAxis.values()) {
            float bias = culture.traitBias().bias(axis);
            if (bias != 0f) traits.set(axis, traits.get(axis) + bias);
        }
        // Religion overwrite (replaces the default sunstead seed). The
        // default-religion entry is removed first so dual-faith
        // syncretism doesn't accidentally show up at PietyTier scoring.
        String defaultReligion = tterrag1112.life_in_the_village.Cultures
                .CultureResolver.religionFor(
                        tterrag1112.life_in_the_village.Cultures.CultureRegistry
                                .getOrDefault(null));
        String cultureReligion = tterrag1112.life_in_the_village.Cultures
                .CultureResolver.religionFor(culture);
        if (!cultureReligion.equals(defaultReligion)) {
            piety.setBelief(defaultReligion, 0f);
        }
        piety.setBelief(cultureReligion, 0.3f);
        // Phase 5 doc 33: seed Layer-1 appearance now that culture is
        // resolved. Skin tone variant uses a stable seed derived from
        // the entity UUID so the value persists across reloads.
        try {
            String stage = getLifeStage() == null ? "" : getLifeStage().name();
            long seed = getUUID().getMostSignificantBits()
                    ^ getUUID().getLeastSignificantBits();
            appearance.generateLayer1(culture.id(), stage, seed, level.getRandom());
        } catch (RuntimeException ex) {
            // Layer-1 seeding is best-effort; a missing texture / culture
            // still leaves the NPC functional with the default base.
        }
        cultureApplied = true;
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

    // ── P0a-13: repaint job accessors ────────────────────────────────────

    @Nullable
    public tterrag1112.life_in_the_village.Village.Decoration.Variants.RepaintJob
            getRepaintJob() { return repaintJob; }

    public void setRepaintJob(
            @Nullable tterrag1112.life_in_the_village.Village.Decoration
                    .Variants.RepaintJob job) {
        this.repaintJob = job;
    }

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

        // ── CRAFTING skill (replaces legacy NpcProfessionXp) ─────────────────
        tterrag1112.life_in_the_village.Npc.Skills.Skill craftSkill =
                tterrag1112.life_in_the_village.Npc.Skills.Skill.CRAFTING;
        int craftLvl = skills.getLevel(craftSkill);
        int craftXp  = (int) skills.getXp(craftSkill);
        player.displayClientMessage(Component.literal(
                "Crafting XP: " + craftXp
                        + "  |  Tier: "
                        + tterrag1112.life_in_the_village.Npc.Skills.SkillComponent.tierFor(craftLvl)
                        + " (lv" + craftLvl + ")"), false);

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
        // Phase 6.0 — tick the Brain BEFORE the Goal-driven super so the
        // observation layer (sensors, animation-only behaviors) sees a
        // consistent pre-Goal snapshot each step. Goals continue to drive
        // all movement and look targeting; no Brain behavior writes
        // WALK_TARGET / attack target / look target in this phase.
        tterrag1112.life_in_the_village.Npc.Brain.NpcSchedules
                .tick(this, level.getDayTime());
        this.getBrain().tick(level, this);
        super.customServerAiStep(level);
        tickAging(level);
        tickHouseCheck(level);
        tickConversationLock(level);
    }

    // =========================================================================
    // BRAIN — Phase 6.0 observation layer
    // =========================================================================

    // Lazy-built so DeferredHolders are resolved at first entity construction
    // (after registry freeze) — not at class load.
    private static ImmutableList<MemoryModuleType<?>> brainMemories() {
        return ImmutableList.of(
                MemoryModuleType.HOME,
                MemoryModuleType.JOB_SITE,
                MemoryModuleType.NEAREST_LIVING_ENTITIES,
                MemoryModuleType.NEAREST_PLAYERS,
                MemoryModuleType.NEAREST_VISIBLE_PLAYER,
                MemoryModuleType.NEAREST_BED,
                MemoryModuleType.HURT_BY,
                MemoryModuleType.HURT_BY_ENTITY,
                MemoryModuleType.LOOK_TARGET,
                MemoryModuleType.WALK_TARGET,
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .NEAREST_FREE_SEAT.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .SIT_COOLDOWN.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .LAST_INTERIOR_WANDER.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .LAST_PERSONAL_SPACE_NUDGE.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .CONVERSATION_PARTNER.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .CURRENT_MOOD_SNAPSHOT.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .IDLE_GESTURE_COOLDOWN.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .CARRYING_DISPLAY_ITEM.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .RECENT_INTERACTION_PARTNERS.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes
                        .CONVERSATION_CANDIDATES.get()
        );
    }

    private static ImmutableList<SensorType<? extends Sensor<? super TownspersonMob>>>
            brainSensors() {
        return ImmutableList.of(
                SensorType.NEAREST_LIVING_ENTITIES,
                SensorType.NEAREST_PLAYERS,
                SensorType.NEAREST_BED,
                SensorType.HURT_BY,
                tterrag1112.life_in_the_village.Npc.Brain.Sensors.NpcSensorTypes
                        .MOOD_SNAPSHOT.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Sensors.NpcSensorTypes
                        .HOME_AND_JOB_SITE.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Sensors.NpcSensorTypes
                        .CONVERSATION_CANDIDATES.get(),
                tterrag1112.life_in_the_village.Npc.Brain.Sensors.NpcSensorTypes
                        .NEARBY_FREE_SEATS.get()
        );
    }

    @Override
    protected Brain.Provider<TownspersonMob> brainProvider() {
        return Brain.provider(brainMemories(), brainSensors());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Brain<TownspersonMob> getBrain() {
        return (Brain<TownspersonMob>) super.getBrain();
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        Brain<TownspersonMob> brain = brainProvider().makeBrain(dynamic);
        // 1.21.11 removed vanilla Schedule / BuiltInRegistries.SCHEDULE in
        // favour of EnvironmentAttribute<Activity>. We drive activity
        // switching ourselves from customServerAiStep via NpcSchedules.tick.

        // CORE: always-on behaviors.
        //  - LookAtTargetSink consumes LOOK_TARGET memory and drives head
        //    rotation when set (Phase 6.1.a writes LOOK_TARGET only from
        //    GreetingAcknowledgmentBehavior, under canRotateHead).
        //  - MoveToTargetSink is the canonical consumer of WALK_TARGET
        //    memory (Phase 6.1.b). Every WALK_TARGET writer guards with
        //    BrainNavGuard.canSteerNavigation first.
        ImmutableList<BehaviorControl<? super TownspersonMob>> coreBehaviors =
                ImmutableList.of(
                        new net.minecraft.world.entity.ai.behavior
                                .LookAtTargetSink(45, 90),
                        new net.minecraft.world.entity.ai.behavior
                                .MoveToTargetSink(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .CarryHoldAnimationBehavior()
                );
        brain.addActivity(Activity.CORE, 0, coreBehaviors);

        // IDLE — priorities increment from start:
        // 0=IdleGesture, 1=MoodReaction, 2=GreetingAcknowledgment,
        // 3=SitAtFurniture, 4=InternalBuildingWander, 5=PersonalSpace.
        // The deliberate nav-using behaviors (sit, wander) outrank the
        // reactive PersonalSpace nudge.
        ImmutableList<BehaviorControl<? super TownspersonMob>> idleBehaviors =
                ImmutableList.of(
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .IdleGestureBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .MoodReactionBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .GreetingAcknowledgmentBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .SitAtFurnitureBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .InternalBuildingWanderBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .PersonalSpaceBehavior()
                );
        brain.addActivity(Activity.IDLE, 0, idleBehaviors);

        // SOCIAL — Phase 6.1.a + 6.1.b additions.
        // 0=Greeting, 1=SitAtFurniture, 2=PersonalSpace.
        ImmutableList<BehaviorControl<? super TownspersonMob>> socialBehaviors =
                ImmutableList.of(
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .GreetingAcknowledgmentBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .SitAtFurnitureBehavior(),
                        new tterrag1112.life_in_the_village.Npc.Brain.Behaviors
                                .PersonalSpaceBehavior()
                );
        brain.addActivity(tterrag1112.life_in_the_village.Npc.Brain.NpcActivities
                .SOCIAL.get(), 0, socialBehaviors);

        // Forward activities for future phases — register empty so the
        // schedule can switch into them without NPEs. Phase 6.1.b+ fills them.
        ImmutableList<BehaviorControl<? super TownspersonMob>> empty = ImmutableList.of();
        brain.addActivity(tterrag1112.life_in_the_village.Npc.Brain.NpcActivities
                .WORK.get(), 0, empty);
        brain.addActivity(tterrag1112.life_in_the_village.Npc.Brain.NpcActivities
                .REST.get(), 0, empty);

        brain.setCoreActivities(java.util.Set.of(Activity.CORE));
        brain.setDefaultActivity(Activity.IDLE);
        brain.useDefaultActivity();

        tterrag1112.life_in_the_village.Npc.Brain.ProfessionBrainFactory
                .configureBrain(this, brain);

        return brain;
    }

    // =========================================================================
    // BRAIN — animation hooks
    // =========================================================================

    /** Server-side: broadcast a gesture event so the client starts the
     *  matching {@link AnimationState}. Pure visual; no movement, no
     *  look-target change. */
    public void triggerGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture gesture) {
        if (gesture == null) return;
        this.lastGestureFired = gesture;
        if (level().isClientSide()) return;
        byte id = switch (gesture) {
            case WAVE          -> ENTITY_EVENT_GESTURE_WAVE;
            case STRETCH       -> ENTITY_EVENT_GESTURE_STRETCH;
            case LOOK_AROUND   -> ENTITY_EVENT_GESTURE_LOOK_AROUND;
            case YAWN          -> ENTITY_EVENT_GESTURE_YAWN;
            case NOD           -> ENTITY_EVENT_GESTURE_NOD;
            case FRIENDLY_WAVE -> ENTITY_EVENT_GESTURE_FRIENDLY_WAVE;
            case HEAD_SHAKE    -> ENTITY_EVENT_GESTURE_HEAD_SHAKE;
            case SIGH          -> ENTITY_EVENT_GESTURE_SIGH;
            case SLOUCH        -> ENTITY_EVENT_GESTURE_SLOUCH;
            case LEAN          -> ENTITY_EVENT_GESTURE_LEAN;
        };
        level().broadcastEntityEvent(this, id);
    }

    /** Sets the synced carry display item. Empty stack clears the
     *  carry-hold animation on the client. Does NOT touch the real inventory. */
    public void setCarryDisplayItem(ItemStack stack) {
        ItemStack current = entityData.get(CARRY_DISPLAY_ITEM);
        boolean wasEmpty = current.isEmpty();
        boolean nowEmpty = stack.isEmpty();
        entityData.set(CARRY_DISPLAY_ITEM, stack.copy());
        if (!level().isClientSide() && wasEmpty != nowEmpty) {
            level().broadcastEntityEvent(this,
                    nowEmpty ? ENTITY_EVENT_CARRY_HOLD_STOP : ENTITY_EVENT_CARRY_HOLD_START);
        }
    }

    public ItemStack getCarryDisplayItem() { return entityData.get(CARRY_DISPLAY_ITEM); }

    /** Phase 6.1.b — broadcast a sit-down transition event. */
    public void triggerSitDown() {
        if (level().isClientSide()) return;
        level().broadcastEntityEvent(this, ENTITY_EVENT_SIT_DOWN);
    }

    /** Phase 6.1.b — broadcast a stand-up transition event. */
    public void triggerStandUp() {
        if (level().isClientSide()) return;
        level().broadcastEntityEvent(this, ENTITY_EVENT_STAND_UP);
    }

    public tterrag1112.life_in_the_village.Npc.Brain.MoodBand getMoodBand() { return moodBand; }
    public void setMoodBand(tterrag1112.life_in_the_village.Npc.Brain.MoodBand band) {
        this.moodBand = band != null ? band : tterrag1112.life_in_the_village.Npc.Brain.MoodBand.NEUTRAL;
    }

    @Override
    public void handleEntityEvent(byte id) {
        switch (id) {
            case ENTITY_EVENT_GESTURE_WAVE          -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.WAVE,          idleGestureState);
            case ENTITY_EVENT_GESTURE_STRETCH       -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.STRETCH,       idleGestureState);
            case ENTITY_EVENT_GESTURE_LOOK_AROUND   -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.LOOK_AROUND,   idleGestureState);
            case ENTITY_EVENT_GESTURE_YAWN          -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.YAWN,          yawnState);
            case ENTITY_EVENT_GESTURE_NOD           -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.NOD,           nodState);
            case ENTITY_EVENT_GESTURE_FRIENDLY_WAVE -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.FRIENDLY_WAVE, friendlyWaveState);
            case ENTITY_EVENT_GESTURE_HEAD_SHAKE    -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.HEAD_SHAKE,    headShakeState);
            case ENTITY_EVENT_GESTURE_SIGH          -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.SIGH,          sighState);
            case ENTITY_EVENT_GESTURE_SLOUCH        -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.SLOUCH,        slouchState);
            case ENTITY_EVENT_GESTURE_LEAN          -> startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture.LEAN,          leanState);
            case ENTITY_EVENT_CARRY_HOLD_START      -> carryHoldState.start(this.tickCount);
            case ENTITY_EVENT_CARRY_HOLD_STOP       -> carryHoldState.stop();
            case ENTITY_EVENT_SIT_DOWN              -> sitDownState.start(this.tickCount);
            case ENTITY_EVENT_STAND_UP              -> standUpState.start(this.tickCount);
            default -> super.handleEntityEvent(id);
        }
    }

    private void startGesture(tterrag1112.life_in_the_village.Npc.Brain.Gesture g,
                              AnimationState state) {
        this.lastGestureFired = g;
        state.start(this.tickCount);
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
        // Phase 1: fire LifeStageAdvanced so the bus can drive goal
        // selection on ADULT and similar transitions.
        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.LifeStageAdvanced(
                        this, from.name(), to.name()));

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
        traits.randomize(random);
        // Mood baseline derives from traits; init after traits are set.
        mood.initializeFromTraits(traits);
        skills.initializeFromProfession(getProfession(), random, level.getLevel().getGameTime());

        // Phase 3 doc 20: seed default religion at strength 0.3.
        // Phase 5 doc 31 — at this point the NPC has no village
        // assigned yet (the populator's flow is finalizeSpawn →
        // setProfession → assignToBuilding), so the culture-aware
        // overwrite happens later in {@link #applyVillageCulture}
        // when the village name is set. The default seed here keeps
        // /summon-spawned NPCs from carrying an empty piety map.
        String defaultReligionId = tterrag1112.life_in_the_village.Cultures
                .CultureResolver.religionFor(
                        tterrag1112.life_in_the_village.Cultures.CultureRegistry
                                .getOrDefault(null));
        piety.setBelief(defaultReligionId, 0.3f);

        // Seed constitution per spec line 53 — 50..90 with mild bias
        // toward higher values; CHILD spawns underweighted, ELDERLY
        // already in decline.
        RandomSource rng = level.getRandom();
        int rawConstitution = 60 + rng.nextInt(31);
        if (isChild())   rawConstitution -= 15;
        if (isElderly()) rawConstitution -= 25;
        health.setConstitution(rawConstitution);

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
        List<AppearanceComponent.PersonalityTrait> legacyTraits = appearance.getTraits();
        if (!legacyTraits.isEmpty()) {
            output.putString("traits", legacyTraits.stream()
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

        // ── Wallet + starter-paid flag ──────────────────────────────────────
        // Wallet was previously memory-only; restarts wiped every NPC's
        // bronze. Now persisted as a single long; load defaults to 0 so
        // pre-fix saves come back as empty wallets (no migration risk).
        output.putLong("walletBronze", economy.getWallet().toBronze());
        output.putBoolean("professionStarterPaid", professionStarterPaid);
        output.putBoolean("cultureApplied", cultureApplied);
        output.putLong("professionStartedTick", professionStartedTick);

        // ── Relationships ────────────────────────────────────────────────────
        String rel = relationships.encode();
        if (!rel.isEmpty()) output.putString("npcRelationships", rel);

        // ── Traits (new 8-axis system; legacy list above is kept readable) ──
        traits.save(output);

        // ── Memory log ───────────────────────────────────────────────────────
        memory.save(output);

        // ── Knowledge ledger ────────────────────────────────────────────────
        knowledge.save(output);

        // ── Mood ─────────────────────────────────────────────────────────────
        mood.save(output);

        // ── Skills ───────────────────────────────────────────────────────────
        skills.save(output);

        // ── Trait drift log (Phase 1) ────────────────────────────────────────
        traitDrift.save(output);

        // ── Life goals (Phase 1 task 07) ─────────────────────────────────────
        lifeGoals.save(output);

        // ── Verb cooldowns (Phase 1 task 09) ─────────────────────────────────
        verbCooldowns.save(output);

        // ── Personal schedule override (Phase 2 task 13) ────────────────────
        scheduleOverride.save(output);

        // ── NPC↔NPC relationship ledger (Phase 2 task 11) ───────────────────
        npcRelationships.save(output);

        // ── Nobility overlay (Track D3.2a) ──────────────────────────────────
        nobility.save(output);

        // ── Hobby preferences (Phase 2 task 14) ─────────────────────────────
        hobbyPreference.save(output);

        // ── Author + scholar progress (Phase 2 task 17) ─────────────────────
        authorStatus.save(output);
        scholarProgress.save(output);

        // ── Religious belief state (Phase 3 task 20) ────────────────────────
        piety.save(output);

        // ── Health + remedy stash (Phase 3 task 21) ─────────────────────────
        health.save(output);
        healerInventory.save(output);

        // ── Child / elderly arc state (Phase 2 task 15) ─────────────────────
        childhoodState.save(output);
        retirementState.save(output);

        // ── Visitor metadata (Phase 4 task 29) ──────────────────────────────
        visitorState.save(output);

        // ── P0a-13: repaint job ─────────────────────────────────────────────
        if (repaintJob != null) {
            output.putString("repaint.buildingId",
                    repaintJob.buildingId().toString());
            output.putString("repaint.requesterId",
                    repaintJob.requesterId().toString());
            output.putString("repaint.slots",
                    tterrag1112.life_in_the_village.Village.Decoration
                            .Variants.RepaintJob.slotsCsv(repaintJob.slots()));
            if (repaintJob.primary() != null) {
                output.putString("repaint.primary", repaintJob.primary().name());
            }
            if (repaintJob.accent() != null) {
                output.putString("repaint.accent", repaintJob.accent().name());
            }
            if (repaintJob.roof() != null) {
                output.putString("repaint.roof", repaintJob.roof().name());
            }
            output.putString("repaint.state", repaintJob.state().name());
            output.putInt("repaint.visits", repaintJob.visitsCompleted());
            output.putLong("repaint.nextVisitTick", repaintJob.nextVisitTick());
        }
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

        // ── Wallet + starter-paid flag ──────────────────────────────────────
        long bronze = input.read("walletBronze", Codec.LONG).orElse(0L);
        if (bronze > 0L) economy.getWallet().receive(bronze);
        professionStarterPaid = input.read("professionStarterPaid", Codec.BOOL).orElse(false);
        cultureApplied        = input.read("cultureApplied",        Codec.BOOL).orElse(false);
        professionStartedTick = input.read("professionStartedTick", Codec.LONG).orElse(0L);

        // ── Relationships ────────────────────────────────────────────────────
        input.read("npcRelationships", Codec.STRING).ifPresent(relationships::decode);

        // ── Traits (new 8-axis system; migrate from legacy if not yet stored)
        boolean traitsLoaded = traits.load(input);
        if (!traitsLoaded && !appearance.getTraits().isEmpty()) {
            traits.migrateFromLegacy(appearance.getTraits());
        }

        // ── Memory log ───────────────────────────────────────────────────────
        memory.load(input);

        // ── Knowledge ledger ────────────────────────────────────────────────
        knowledge.load(input);

        // ── Mood (baseline derives from traits when not stored) ─────────────
        if (!mood.load(input)) mood.initializeFromTraits(traits);

        // ── Trait drift log (Phase 1) ────────────────────────────────────────
        traitDrift.load(input);

        // ── Life goals (Phase 1 task 07) ─────────────────────────────────────
        lifeGoals.load(input);

        // ── Verb cooldowns (Phase 1 task 09) ─────────────────────────────────
        verbCooldowns.load(input);

        // ── Personal schedule override (Phase 2 task 13) ────────────────────
        scheduleOverride.load(input);

        // ── NPC↔NPC relationship ledger (Phase 2 task 11) ───────────────────
        npcRelationships.load(input);

        // ── Nobility overlay (Track D3.2a) ──────────────────────────────────
        nobility.load(input);

        // ── Hobby preferences (Phase 2 task 14) ─────────────────────────────
        hobbyPreference.load(input);

        // ── Author + scholar progress (Phase 2 task 17) ─────────────────────
        authorStatus.load(input);
        scholarProgress.load(input);

        // ── Child / elderly arc state (Phase 2 task 15) ─────────────────────
        childhoodState.load(input);
        retirementState.load(input);

        // ── Religious belief state (Phase 3 task 20) ────────────────────────
        piety.load(input);

        // ── Health + remedy stash (Phase 3 task 21) ─────────────────────────
        health.load(input);
        healerInventory.load(input);

        // ── Visitor metadata (Phase 4 task 29) ──────────────────────────────
        visitorState.load(input);

        // ── Skills ──────────────────────────────────────────────────────────
        // load() leaves the component at default zeros if no record was
        // saved (e.g. brand-new NPC pre-applyVillageCulture);
        // initializeFromProfession runs at spawn-time for fresh NPCs.
        skills.load(input);

        // ── P0a-13: repaint job ─────────────────────────────────────────────
        var repaintBuilding = input.read("repaint.buildingId", Codec.STRING);
        if (repaintBuilding.isPresent()) {
            try {
                UUID bId = UUID.fromString(repaintBuilding.get());
                UUID rId = input.read("repaint.requesterId", Codec.STRING)
                        .map(UUID::fromString).orElse(new UUID(0L, 0L));
                String slotsCsv = input.read("repaint.slots", Codec.STRING).orElse("");
                var slots = tterrag1112.life_in_the_village.Village.Decoration
                        .Variants.RepaintJob.parseSlotsCsv(slotsCsv);
                net.minecraft.world.item.DyeColor primary =
                        input.read("repaint.primary", Codec.STRING)
                                .map(s -> {
                                    try { return net.minecraft.world.item.DyeColor.valueOf(s); }
                                    catch (IllegalArgumentException e) { return null; }
                                }).orElse(null);
                net.minecraft.world.item.DyeColor accent =
                        input.read("repaint.accent", Codec.STRING)
                                .map(s -> {
                                    try { return net.minecraft.world.item.DyeColor.valueOf(s); }
                                    catch (IllegalArgumentException e) { return null; }
                                }).orElse(null);
                net.minecraft.world.item.DyeColor roof =
                        input.read("repaint.roof", Codec.STRING)
                                .map(s -> {
                                    try { return net.minecraft.world.item.DyeColor.valueOf(s); }
                                    catch (IllegalArgumentException e) { return null; }
                                }).orElse(null);
                tterrag1112.life_in_the_village.Village.Decoration.Variants
                        .RepaintJob.State state =
                        input.read("repaint.state", Codec.STRING)
                                .map(s -> {
                                    try {
                                        return tterrag1112.life_in_the_village.Village
                                                .Decoration.Variants.RepaintJob.State.valueOf(s);
                                    } catch (IllegalArgumentException e) {
                                        return tterrag1112.life_in_the_village.Village
                                                .Decoration.Variants.RepaintJob.State.PLANNED;
                                    }
                                })
                                .orElse(tterrag1112.life_in_the_village.Village
                                        .Decoration.Variants.RepaintJob.State.PLANNED);
                int visits = input.read("repaint.visits", Codec.INT).orElse(0);
                long nextTick = input.read("repaint.nextVisitTick", Codec.LONG).orElse(0L);
                this.repaintJob = new tterrag1112.life_in_the_village.Village
                        .Decoration.Variants.RepaintJob(
                        bId, rId, slots, primary, accent, roof,
                        state, visits, nextTick);
            } catch (IllegalArgumentException ignored) {
                this.repaintJob = null;
            }
        }

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

        // Phase 2 task 16: BROKEN any active apprenticeship contracts
        // where this NPC was the master.
        tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager
                .onMasterDeath(level, npc.getUUID());

        // Phase 2 task 15: age-natural funeral stub + DIE_WITH_REGRET
        // gossip seed when the deceased died with unfinished business.
        tterrag1112.life_in_the_village.Npc.Aging.DeathArc
                .onNpcDeath(npc, level);

        // ── Phase 1: fire WitnessedDeath for every nearby NPC, FamilyDeath
        //    for every household member of the deceased.
        UUID deceasedId = npc.getUUID();
        net.minecraft.world.phys.AABB witnessZone =
                npc.getBoundingBox().inflate(WITNESS_RADIUS_BLOCKS);
        for (TownspersonMob witness : level.getEntitiesOfClass(
                TownspersonMob.class, witnessZone,
                w -> !w.getUUID().equals(deceasedId))) {
            tterrag1112.life_in_the_village.Npc.Events.RelationshipType relation =
                    classifyDeathRelation(witness, npc);
            tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                    new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent
                            .WitnessedDeath(witness, deceasedId, relation));
        }

        // FamilyDeath fan-out for spouse / children / parents in the
        // deceased's household. Reuses FamilyComponent's records — those
        // are the authoritative source for "who is family".
        FamilyComponent family = npc.getFamily();
        family.getSpouseId().ifPresent(spouseId -> findByUUID(level, spouseId).ifPresent(
                spouse -> tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                        new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.FamilyDeath(
                                spouse, deceasedId,
                                tterrag1112.life_in_the_village.Entities.FamilyRole.SPOUSE))));
        for (UUID childId : family.getChildrenIds()) {
            findByUUID(level, childId).ifPresent(
                    child -> tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                            new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.FamilyDeath(
                                    child, deceasedId,
                                    tterrag1112.life_in_the_village.Entities.FamilyRole.HEAD)));
        }
        family.getHeadOfHouseholdId().ifPresent(headId -> {
            if (!headId.equals(deceasedId)) {
                findByUUID(level, headId).ifPresent(
                        head -> tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.FamilyDeath(
                                        head, deceasedId,
                                        tterrag1112.life_in_the_village.Entities.FamilyRole.CHILD)));
            }
        });

        // Phase 3 task 06: vacate every office the deceased held and run
        // immediate elections so the village isn't down a leader / priest /
        // constable for the rest of the day. The death event has already
        // fired Phase 1 events above, so order doesn't matter.
        tterrag1112.life_in_the_village.Npc.Office.OfficeElection
                .vacateAllHeldBy(deceasedId, level, "death");
    }

    /** Spec: "scans nearby NPCs within 16 blocks" (10-phase1-integration.md line 48). */
    private static final double WITNESS_RADIUS_BLOCKS = 16.0;

    /**
     * Classifies the witness's relation to the deceased for routing
     * mood / drift. Phase 2's relationship ledger will provide a richer
     * lookup; Phase 1 falls back on family-component flags only.
     */
    private static tterrag1112.life_in_the_village.Npc.Events.RelationshipType
    classifyDeathRelation(TownspersonMob witness, TownspersonMob deceased) {
        UUID deceasedId = deceased.getUUID();
        FamilyComponent fam = witness.getFamily();
        if (fam.getSpouseId().filter(deceasedId::equals).isPresent())
            return tterrag1112.life_in_the_village.Npc.Events.RelationshipType.SPOUSE;
        if (fam.getChildrenIds().contains(deceasedId)
                || fam.getHeadOfHouseholdId().filter(deceasedId::equals).isPresent())
            return tterrag1112.life_in_the_village.Npc.Events.RelationshipType.KIN;
        return tterrag1112.life_in_the_village.Npc.Events.RelationshipType.NEUTRAL;
    }

    /**
     * Phase 1 hook for damage. Fires {@code Attacked} on
     * {@link net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent}
     * so the bus can route {@code VICTIM_OF_CRIME_BY} memory +
     * {@code CRIME_VICTIM} mood. Coexists with the existing
     * {@code ReputationEvents.onLivingHurt} (which only listens for
     * player attackers).
     */
    @SubscribeEvent
    public static void onNpcHurt(
            net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob npc)) return;
        var src = event.getSource();
        if (src == null) return;
        net.minecraft.world.entity.Entity attacker = src.getEntity();
        if (attacker == null || attacker.getUUID().equals(npc.getUUID())) return;
        boolean isPlayer = attacker instanceof net.minecraft.world.entity.player.Player;
        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Attacked(
                        npc, attacker.getUUID(), isPlayer, event.getAmount()));
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