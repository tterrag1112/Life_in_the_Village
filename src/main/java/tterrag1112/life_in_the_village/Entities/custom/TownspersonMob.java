package tterrag1112.life_in_the_village.Entities.custom;

import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer.*;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderMaintenanceGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Carpenter.CarpenterGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.CompanyWorker.CompanyWorkerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmhandGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard.CaravanGuardGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard.GuardAttackGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard.GuardEquipmentGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard.GuardPatrolGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Guild.GuildWorkerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Innkeeper.InnkeeperGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Leader.KingdomRulerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Leader.VillageLeaderGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.CaravanMerchantGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Miner.MinerGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.StockpileKeeper.StockpileKeeperGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Social.*;
import tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen;
import tterrag1112.life_in_the_village.Gui.GuildScreen;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleData;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleRegistry;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.CraftingOrderInteraction;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Entities.*;
import tterrag1112.life_in_the_village.Events.ReputationEvents;
import tterrag1112.life_in_the_village.Networking.BuilderInventoryPacket;
import tterrag1112.life_in_the_village.Networking.StockpileContentsPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class TownspersonMob extends PathfinderMob implements RangedAttackMob {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    private static final int MAX_HEALTH = 20;
    private long birthTick = -1L;
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
    private static final EntityDataAccessor<Boolean> IS_GROUP_LEADER =
            SynchedEntityData.defineId(TownspersonMob.class,
                    EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> CARAVAN_ID =
            SynchedEntityData.defineId(TownspersonMob.class,
                    EntityDataSerializers.STRING);

    // =========================================================================
    // IDENTITY FIELDS — name, age, appearance
    // =========================================================================

    private String npcName = "";
    private int age = 18;
    private long lastAgeTick = -1L;
    private int skinTone = 0;
    private int hairStyle = 0;
    private int hairColor = 0;
    private boolean isMale = true;

    @Nullable
    private CombatRole combatRole = null;

    public @Nullable CombatRole getCombatRole() { return combatRole; }
    public void setCombatRole(@Nullable CombatRole role) {
        this.combatRole = role;
        // Re-register goals so party goals activate/deactivate
        // immediately when a role is assigned or cleared
        if (goalSelector != null
                && getProfession() == Profession.ADVENTURER) {
            goalSelector.removeAllGoals(g -> true);
            targetSelector.removeAllGoals(g -> true);
            registerGoals();
        }
    }
    public void setCombatRoleSilent(@Nullable CombatRole role) {
        this.combatRole = role;
    }


    // =========================================================================
    // PERSONALITY
    // =========================================================================

    private final Set<PersonalityTrait> traits = new HashSet<>();

    // =========================================================================
    // FAMILY
    // =========================================================================

    private FamilyRole familyRole = FamilyRole.UNASSIGNED;
    @Nullable private UUID houseId = null;
    @Nullable private UUID spouseId = null;
    @Nullable private UUID headOfHouseholdId = null;
    private List<UUID> childrenIds = new ArrayList<>();

    // =========================================================================
    // WORK / ASSIGNMENT
    // =========================================================================

    @Nullable private UUID assignedBuildingId = null;
    @Nullable private String assignedVillageName = null;
    @Nullable private BlockPos assignedPost = null;
    @Nullable private UUID assignedPlotId = null;
    private boolean workingBlocked = false;

    private String currentActivity = "";

    @Nullable private UUID companyId = null;


    public void setCurrentActivity(String activity) {
        if (activity.equals(currentActivity)) return; // no change needed
        currentActivity = activity;
        updateDisplayName();
    }

    public void clearCurrentActivity() {
        setCurrentActivity("");
    }

    // =========================================================================
    // INVENTORY
    // =========================================================================

    private final SimpleContainer personalInventory = new SimpleContainer(36);



    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public TownspersonMob(EntityType<TownspersonMob> entityType, Level level) {
        super(entityType, level);
    }

    // =========================================================================
    // ATTRIBUTES & DATA
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
        builder.define(GROUP_ID, "");  // empty string = no group
        builder.define(IS_GROUP_LEADER, false);
        builder.define(CARAVAN_ID, "");



    }


    @Override
    public boolean removeWhenFarAway(double dist) {
        return false;
    }

    // =========================================================================
    // GOALS
    // =========================================================================

    @Override
    protected void registerGoals() {
        // Universal goals — all professions
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new ReturnHomeGoal(this));
        goalSelector.addGoal(5, new EatMealGoal(this));
        goalSelector.addGoal(3, new SeekHouseGoal(this));
        goalSelector.addGoal(6, new ChildBirthGoal(this));
        goalSelector.addGoal(5, new SocializeGoal(this));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));
        goalSelector.addGoal(11, new WanderInBuildingGoal(this));

        switch (getLifeStage()) {
            case CHILD -> {
                goalSelector.addGoal(5, new ChildPlayGoal(this));
            }
            case TEEN, ADULT -> {
                goalSelector.addGoal(5, new SocializeGoal(this));
                goalSelector.addGoal(6, new GreetingGoal(this));
                if (getLifeStage() == LifeStage.ADULT) {
                    goalSelector.addGoal(6, new CourtingGoal(this));
                }
            }
            case ELDERLY -> {
                goalSelector.addGoal(5, new ElderlyRelaxGoal(this));
                goalSelector.addGoal(6, new GreetingGoal(this));
            }
        }

        // Profession-specific goals
        registerProfessionGoals();
    }

    private void registerProfessionGoals() {
        switch (getProfession()) {
            case MERCHANT -> {
                goalSelector.addGoal(10, new MerchantGoal(this));
                goalSelector.addGoal(1, new CaravanMerchantGoal(this));
            }
            case BUILDER  -> {
                goalSelector.addGoal(10, new BuilderGoal(this));
                goalSelector.addGoal(11, new BuilderMaintenanceGoal(this));  // NEW
            }
            case INNKEEPER -> goalSelector.addGoal(10, new InnkeeperGoal(this));
            case STOCKPILE_KEEPER -> {
                goalSelector.addGoal(10, new StockpileKeeperGoal(this));
            }
            case MINER -> {
                goalSelector.addGoal(10, new MinerGoal(this));
                goalSelector.addGoal(11, new SellToMarketGoal(this,
                        getSellableItems(Profession.MINER)));
                goalSelector.addGoal(11, new PostListingGoal(this,
                        getSellableItems(Profession.MINER)));
                goalSelector.addGoal(11, new BuyFromNpcGoal(this,
                        () -> needsPickaxe(),
                        () -> net.minecraft.world.item.Items.IRON_PICKAXE,
                        () -> 1,
                        400
                ));
            }
            case BLACKSMITH -> {
                goalSelector.addGoal(10, new BlacksmithGoal(this));
                goalSelector.addGoal(11, new PostListingGoal(this,
                        getSellableItems(Profession.BLACKSMITH)));

                // Blacksmith buys raw ores when low
                goalSelector.addGoal(11, new BuyFromNpcGoal(this,
                        () -> needsOre(),
                        () -> net.minecraft.world.item.Items.RAW_IRON,
                        () -> 16,
                        1200
                ));
                goalSelector.addGoal(11, new SellToMarketGoal(this,
                        getSellableItems(Profession.BLACKSMITH)));
            }
            case CARPENTER -> {
                goalSelector.addGoal(10, new CarpenterGoal(this));
                goalSelector.addGoal(11, new SellToMarketGoal(this,
                        getSellableItems(Profession.CARPENTER)));
                goalSelector.addGoal(11, new PostListingGoal(this,
                        getSellableItems(Profession.CARPENTER)));
            }
            case VILLAGE_LEADER -> {
                goalSelector.addGoal(3, new VillageLeaderGoal(this));
            }
            case KINGDOM_RULER -> {
                goalSelector.addGoal(3, new KingdomRulerGoal(this));
            }
            case GUARD -> {
                goalSelector.addGoal(1, new CaravanGuardGoal(this));
                goalSelector.addGoal(2, new GuardEquipmentGoal(this));
                goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true));
                // Guards override ReturnHomeGoal with their own patrol at same priority
                goalSelector.addGoal(3, new GuardPatrolGoal(this));
                targetSelector.addGoal(1, new GuardAttackGoal(this));
                targetSelector.addGoal(2, new HurtByTargetGoal(this));
            }
            case FARMER -> {
                goalSelector.addGoal(10, new FarmerGoal(this));
                goalSelector.addGoal(10, new PostJobGoal(this));
                goalSelector.addGoal(11, new SellToMarketGoal(this,
                        getSellableItems(Profession.FARMER)));
                goalSelector.addGoal(11, new PostListingGoal(this,
                        getSellableItems(getProfession())));


            }
            case FARMHAND -> {goalSelector.addGoal(10, new FarmhandGoal(this));
                goalSelector.addGoal(11, new SellToMarketGoal(this,
                        getSellableItems(Profession.FARMHAND)));
            }

            case GUILDWORKER -> goalSelector.addGoal(10, new GuildWorkerGoal(this));
            case GUILDMASTER -> goalSelector.addGoal(10, new WanderInBuildingGoal(this));
            case ADVENTURER -> {
                if (combatRole != null) {
                    // Combat — must come before movement goals
                    goalSelector.addGoal(1, new PartyFollowGoal(this));
                    goalSelector.addGoal(2, new PartyDefendGoal(this));
                    goalSelector.addGoal(3, new PartyRoleGoal(this));

                    // Melee attack — fires when target is set by
                    // PartyDefendGoal or HurtByTargetGoal
                    goalSelector.addGoal(2, new MeleeAttackGoal(
                            this, 1.2, true));

                    // Ranged attack for ARCHER — uses bow
                    if (combatRole == CombatRole.ARCHER) {
                        goalSelector.addGoal(2,
                                new net.minecraft.world.entity.ai.goal
                                        .RangedBowAttackGoal<>(
                                        this, 1.0, 20, 15.0f));
                    }

                    goalSelector.addGoal(4, new PartyCampGoal(this));
                    goalSelector.addGoal(5, new PartyConversationGoal(this));
                    goalSelector.addGoal(6, new PartyAmbientGoal(this));

                    // Target selectors — these drive who the NPC fights
                    targetSelector.addGoal(1, new HurtByTargetGoal(this));
                    targetSelector.addGoal(2,
                            new NearestAttackableTargetGoal<>(
                                    this,
                                    net.minecraft.world.entity.monster.Monster.class,
                                    10,
                                    true,
                                    false,
                                    (target, level) -> {
                                        PlayerPartySavedData partyData =
                                                PlayerPartySavedData.get(level);
                                        return partyData
                                                .getPartyContaining(getUUID())
                                                .map(party -> {
                                                    ServerPlayer leader =
                                                            level.getServer()
                                                                    .getPlayerList()
                                                                    .getPlayer(
                                                                            party.getLeaderPlayerId());
                                                    if (leader != null
                                                            && target instanceof net.minecraft.world.entity.Mob m
                                                            && m.getTarget() != null
                                                            && m.getTarget()
                                                            .equals(leader)) {
                                                        return true;
                                                    }
                                                    return party.getMembers()
                                                            .stream()
                                                            .filter(PlayerParty.PartyMember::isAlive)
                                                            .anyMatch(member -> {
                                                                var memberEntity =
                                                                        level.getEntity(
                                                                                member.npcId());
                                                                return target instanceof net.minecraft.world.entity.Mob m2
                                                                        && m2.getTarget() != null
                                                                        && memberEntity != null
                                                                        && m2.getTarget()
                                                                        .equals(memberEntity);
                                                            });
                                                })
                                                .orElse(false);
                                    }));
                } else {
                    goalSelector.addGoal(2, new AdventurerInteractGoal(this));
                    goalSelector.addGoal(2, new AdventurerPatrolGoal(this));
                    goalSelector.addGoal(4, new AdventurerLookAroundGoal(this));
                    goalSelector.addGoal(2, new AdventurerHuntGoal(this));
                    goalSelector.addGoal(3, new AdventurerExploreGoal(this));
                    goalSelector.addGoal(10, new SocializeGoal(this));
                    goalSelector.addGoal(4, new AdventurerCampGoal(this));
                    goalSelector.addGoal(1, new AdventurerCombatAssistGoal(this));
                    targetSelector.addGoal(1, new HurtByTargetGoal(this));
                }
            }
            case COMPANY_WORKER -> goalSelector.addGoal(10, new CompanyWorkerGoal(this));

            case NONE     -> goalSelector.addGoal(10, new SeekJobGoal(this));
        }
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
        if (goalSelector != null) {
            goalSelector.removeAllGoals(g -> true);
            targetSelector.removeAllGoals(g -> true);
            registerGoals();
        }
        // Refresh title if name is already set
        if (npcName != null && !npcName.isEmpty()) {
            setNpcName(npcName);
        }
    }

    public boolean isWorkTime() {
        // Festival of Lights — NPCs stay up at night
        if (eventOverride == VillageEvent.EventType.FESTIVAL_OF_LIGHTS) {
            return false; // they celebrate instead
        }
        // Training Day — only guards work
        if (eventOverride == VillageEvent.EventType.TRAINING_DAY) {
            return getProfession() == Profession.GUARD;
        }
        // Harvest Festival, Fair — reduced work hours
        if (eventOverride == VillageEvent.EventType.HARVEST_FESTIVAL
                || eventOverride == VillageEvent.EventType.VILLAGE_FAIR) {
            // Only work first quarter of normal hours
            return WorkSchedule.isWorkTime(this)
                    && (level().getDayTime() % 24000) < 3000;
        }
        return WorkSchedule.isWorkTime(this);
    }

    public boolean isSleepTime() {
        return WorkSchedule.isSleepTime(this);
    }

    public boolean isSocialTime() {
        return WorkSchedule.isSocialTime(this);
    }

    public boolean shouldBeHome() {
        return WorkSchedule.shouldBeHome(this);
    }

    // =========================================================================
    // IDENTITY — name, age, life stage
    // =========================================================================

    public String getAdventurerTitle() { return adventurerTitle; }
    public void setAdventurerTitle(String title) {
        this.adventurerTitle = title == null ? "" : title;
        updateDisplayName();
    }
    public String getNpcName()          { return npcName; }
    public void setNpcName(String name) {
        this.npcName = name;
        updateDisplayName();
    }
    private String adventurerTitle = "";

    private void updateDisplayName() {
        String base = npcName != null ? npcName : "Townsperson";

        // Apply title prefix
        if (level() instanceof ServerLevel sl) {
            VillageSavedData data = VillageSavedData.get(sl);
            String culture = getAssignedVillageName()
                    .flatMap(data::getVillageByName)
                    .flatMap(v -> data.getKingdomForVillage(v.getId()))
                    .map(Kingdom::getCulture)
                    .orElse("default");

            KingdomTitleData titles =
                    KingdomTitleRegistry.INSTANCE.getCulture(culture);

            String title = switch (getProfession()) {
                case VILLAGE_LEADER -> titles.getLordTitle(isMale());
                case KINGDOM_RULER  -> titles.getRulerTitle(isMale());
                default             -> null;
            };

            if (title != null) base = title + " " + base;
        }
        if (!adventurerTitle.isEmpty()) base = base + " " + adventurerTitle;


        // Build display name with activity
        net.minecraft.network.chat.MutableComponent display;
        if (currentActivity != null && !currentActivity.isEmpty()) {
            display = Component.literal(base)
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("\n"))
                    .append(Component.literal(currentActivity)
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        } else {
            display = Component.literal(base)
                    .withStyle(ChatFormatting.WHITE);
        }

        setCustomName(display);
        setCustomNameVisible(true);
    }
    public String getFirstName() {
        String[] parts = npcName.split(" ", 2);
        return parts[0];
    }


    public int getAge()       { return age; }
    public int getAgeDays()   { return age; } // alias for compatibility

    public void setAge(int age) {
        this.age = age;
        entityData.set(LIFE_STAGE, getLifeStage().name());
        updateScale();
    }



    public LifeStage getLifeStage() {
        return LifeStage.fromAge(age);
    }

    public long getLastAgeTick()            { return lastAgeTick; }
    public void setLastAgeTick(long tick)   { this.lastAgeTick = tick; }

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

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        tickAging(level);
        tickHouseCheck(level);

    }

    private void tickAging(ServerLevel level) {
        long currentTick = level.getGameTime();

        // Initialize birthTick from current age on first tick
        if (birthTick < 0) {
            birthTick = currentTick - ((long) age * TICKS_PER_DAY);
        }

        // Compute expected age from birthTick
        int expectedAge = (int)((currentTick - birthTick) / TICKS_PER_DAY);

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
        // Only check occasionally — every 200 ticks
        if (level.getGameTime() % 200 != 0) return;
        if (getFamilyRole() != FamilyRole.HEAD) return;
        if (houseId != null) return;

        // HEAD with no house — demote to UNASSIGNED
        setFamilyRole(FamilyRole.UNASSIGNED);
        System.out.println(getNpcName() + " demoted from HEAD to UNASSIGNED — no house");
    }

    private void onLifeStageChanged(LifeStage from, LifeStage to, ServerLevel level) {
        System.out.println(getDisplayName().getString()
                + " aged from " + from + " to " + to + " at age " + age);

        switch (to) {
            case TEEN -> {
                // Children becoming teens can start seeking jobs
                if (getProfession() == Profession.NONE) {
                    goalSelector.removeAllGoals(g -> true);
                    targetSelector.removeAllGoals(g -> true);
                    registerGoals();
                }
            }
            case ADULT -> {
                // Teens becoming adults get full profession seeking + courting
                goalSelector.removeAllGoals(g -> true);
                targetSelector.removeAllGoals(g -> true);
                registerGoals();
            }
            case ELDERLY -> {
                // Adults becoming elderly stop working
                setIsWorkingBlocked(true);
                goalSelector.removeAllGoals(g -> true);
                targetSelector.removeAllGoals(g -> true);
                registerGoals();
            }
            default -> {}
        }
    }

    public void adoptSurname(String surname, ServerLevel level) {
        String[] parts = npcName.split(" ", 2);
        setNpcName(parts[0] + " " + surname);

        if (spouseId != null) {
            level.getEntitiesOfClass(TownspersonMob.class,
                    getBoundingBox().inflate(64),
                    mob -> mob.getUUID().equals(spouseId)
            ).forEach(spouse -> {
                String[] spouseParts = spouse.npcName.split(" ", 2);
                spouse.setNpcName(spouseParts[0] + " " + surname);
            });
        }

        childrenIds.forEach(childId ->
                level.getEntitiesOfClass(TownspersonMob.class,
                        getBoundingBox().inflate(64),
                        mob -> mob.getUUID().equals(childId)
                ).forEach(child -> {
                    String[] childParts = child.npcName.split(" ", 2);
                    child.setNpcName(childParts[0] + " " + surname);
                })
        );
    }
    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        if (npcName != null && !npcName.isEmpty()) {
            return Component.literal(npcName);
        }
        return super.getDisplayName();
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    public boolean isMale()          { return isMale; }
    public void setMale(boolean male) { this.isMale = male; }

    // =========================================================================
    // APPEARANCE
    // =========================================================================

    public int getSkinTone()  { return entityData.get(SKIN_TONE); }
    public int getHairStyle() { return entityData.get(HAIR_STYLE); }
    public int getHairColor() { return entityData.get(HAIR_COLOR); }

    public void setAppearance(int skinTone, int hairStyle, int hairColor) {
        this.skinTone = skinTone;
        this.hairStyle = hairStyle;
        this.hairColor = hairColor;
        entityData.set(SKIN_TONE, skinTone);
        entityData.set(HAIR_STYLE, hairStyle);
        entityData.set(HAIR_COLOR, hairColor);
    }

    public void randomizeAppearance(RandomSource random) {
        setAppearance(random.nextInt(6), random.nextInt(8), random.nextInt(7));
    }

    // =========================================================================
    // PERSONALITY TRAITS
    // =========================================================================

    public Set<PersonalityTrait> getTraits() {
        return Collections.unmodifiableSet(traits);
    }

    public boolean hasTrait(PersonalityTrait trait) {
        return traits.contains(trait);
    }

    public void addTrait(PersonalityTrait trait) {
        traits.removeIf(t -> t.conflictsWith(trait));
        traits.add(trait);
    }

    public void clearTraits() {
        traits.clear();
    }

    /** Aggregate work speed modifier from all traits. */
    public int getActionTickRate(int baseRate) {
        double modifier = traits.stream()
                .mapToDouble(PersonalityTrait::workSpeedModifier)
                .reduce(1.0, (a, b) -> a * b);
        return Math.max(1, (int)(baseRate / modifier));
    }

    /** Aggregate price modifier from all traits. */
    public double getPriceModifier() {
        return traits.stream()
                .mapToDouble(PersonalityTrait::priceModifier)
                .reduce(1.0, (a, b) -> a * b);
    }

    /** Detection range modified by brave/timid traits. */
    public double getDetectionRange() {
        if (hasTrait(PersonalityTrait.BRAVE))  return 24.0;
        if (hasTrait(PersonalityTrait.TIMID))  return 8.0;
        return 16.0;
    }

    public static PersonalityTrait randomTrait(RandomSource random) {
        PersonalityTrait[] values = PersonalityTrait.values();
        return values[random.nextInt(values.length)];
    }

    // =========================================================================
    // FAMILY
    // =========================================================================

    public FamilyRole getFamilyRole() { return familyRole; }

    public void setFamilyRole(FamilyRole role) {
        this.familyRole = role;
        entityData.set(FAMILY_ROLE, role.name());
    }

    public boolean hasHome()   { return houseId != null; }
    public boolean hasSpouse() { return spouseId != null; }
    public boolean isAdult()   { return getLifeStage() == LifeStage.ADULT; }
    public boolean isChild()   { return getLifeStage() == LifeStage.CHILD; }
    public boolean isElderly() { return getLifeStage() == LifeStage.ELDERLY; }

    public Optional<UUID> getHouseId()              { return Optional.ofNullable(houseId); }
    public void setHouseId(@Nullable UUID id)        { this.houseId = id; }

    public void setSpouseId(@Nullable UUID id)       { this.spouseId = id; }

    public Optional<UUID> getHeadOfHouseholdId()    { return Optional.ofNullable(headOfHouseholdId); }

    public List<UUID> getChildrenIds()              { return Collections.unmodifiableList(childrenIds); }

    public void addChildId(UUID childId) {
        if (!childrenIds.contains(childId)) childrenIds.add(childId);
    }

    // getSurname extracts the last word of the NPC's full name
    public String getSurname() {
        if (npcName == null || npcName.isEmpty()) return "";
        String[] parts = npcName.split(" ");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    public Optional<UUID> getSpouseId() { return Optional.ofNullable(spouseId); }
    public void setHeadOfHouseholdId(UUID id) { this.headOfHouseholdId = id; }

    // =========================================================================
    // WORK / ASSIGNMENT
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

    public Optional<BlockPos> getAssignedPost()         { return Optional.ofNullable(assignedPost); }
    public void setAssignedPost(@Nullable BlockPos pos)  { this.assignedPost = pos; }

    public Optional<UUID> getAssignedPlotId()           { return Optional.ofNullable(assignedPlotId); }
    public void setAssignedPlotId(UUID id)               { this.assignedPlotId = id; }

    public boolean isWorkingBlocked()                   { return workingBlocked; }
    public void setIsWorkingBlocked(boolean blocked)     { this.workingBlocked = blocked; }

    public boolean needsPickaxe() {
        // Check personal inventory for a usable pickaxe
        var inv = getPersonalInventory();
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
        if (!(level() instanceof ServerLevel level)) return false;
        Building building = getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        if (building == null) return false;

        // Need ore if building has less than 8 raw iron
        return BuildingStorageAccess.countItem(
                level, building,
                net.minecraft.world.item.Items.RAW_IRON) < 8;
    }

    private void handleInnkeeperInteraction(ServerPlayer player,
                                            ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        long currentTick = level.getGameTime();

        // Check if already rented
        if (data.hasRentedRoom(player.getUUID(), currentTick)) {
            data.getRentedBed(player.getUUID()).ifPresent(bed ->
                    player.displayClientMessage(
                            Component.literal("Your room is ready. "
                                    + "Bed at " + bed.toShortString()), false)
            );
            return;
        }

        // Cost — 1 silver per night
        CurrencyValue nightCost = CurrencyValue.ofSilver(1);
        var playerContainer = buildTempContainer(player);

        if (!CoinHelper.canAfford(playerContainer, nightCost)) {
            player.displayClientMessage(
                    Component.literal("A room costs "
                            + nightCost + " per night. You can't afford it."),
                    false);
            return;
        }

        // Find an available bed in the inn
        Building inn = getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (inn == null) return;

        BlockPos bed = findAvailableBed(level, inn, data);
        if (bed == null) {
            player.displayClientMessage(
                    Component.literal("Sorry, no rooms available tonight."),
                    false);
            return;
        }

        // Charge player
        CoinHelper.spend(playerContainer, nightCost);
        syncTempContainer(player, playerContainer);
        receive(nightCost);

        // Assign room — expires after one in-game day
        long expiry = currentTick + 24000L;
        data.rentRoom(player.getUUID(), expiry);
        data.assignBed(player.getUUID(), bed);

        player.displayClientMessage(
                Component.literal("Welcome! Your room is ready. "
                        + "Bed at " + bed.toShortString()
                        + ". Paid " + CurrencyValue.ofSilver(1) + "."),
                false);
    }

    private BlockPos findAvailableBed(ServerLevel level, Building inn,
                                      VillageSavedData data) {
        Set<BlockPos> occupiedBeds = new HashSet<>(data.getAllRentedBeds().values());
        BlockPos min = inn.getShape().getMin();
        BlockPos max = inn.getShape().getMax();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            var state = level.getBlockState(pos);
            if (state.is(BlockTags.BEDS)
                    && state.getBlock() instanceof BedBlock
                    && state.getValue(BedBlock.PART) == BedPart.HEAD
                    && !occupiedBeds.contains(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private net.minecraft.world.SimpleContainer buildTempContainer(
            ServerPlayer player) {
        var container = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            container.setItem(i, player.getInventory().getItem(i).copy());
        }
        return container;
    }

    private void syncTempContainer(ServerPlayer player,
                                   net.minecraft.world.SimpleContainer container) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, container.getItem(i));
        }
        player.inventoryMenu.broadcastChanges();
    }

    // =========================================================================
    // INVENTORY & CURRENCY
    // =========================================================================

    public SimpleContainer getPersonalInventory()       { return personalInventory; }
    public CurrencyValue getWealth()                    { return CoinHelper.getWealth(personalInventory); }
    public boolean canAfford(CurrencyValue price)       { return CoinHelper.canAfford(personalInventory, price); }
    public boolean canAffordWithBuilding(CurrencyValue price, ServerLevel level) {
        return getTotalWealth(level).isAffordable(price);
    }
    public boolean spend(CurrencyValue amount)          { return CoinHelper.spend(personalInventory, amount); }
    public void receive(CurrencyValue amount)           { CoinHelper.giveCoins(personalInventory, amount); }

    public boolean pay(TownspersonMob receiver, CurrencyValue amount) {
        return CoinHelper.pay(personalInventory, receiver.personalInventory, amount);
    }

    @Override
    public void performRangedAttack(
            net.minecraft.world.entity.LivingEntity target,
            float distanceFactor) {
        // Only fire if ARCHER role
        if (combatRole != CombatRole.ARCHER) return;

        Arrow arrow =
                new Arrow(
                        level(), this,
                        new ItemStack(Items.ARROW),
                        null);

        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        arrow.shoot(dx, dy + dist * 0.2, dz,
                1.6f, 12.0f - (float) dist * 0.2f);

        level().addFreshEntity(arrow);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }


    public static Set<Item> getSellableItems(Profession profession) {
        return switch (profession) {
            case FARMER, FARMHAND -> Set.of(
                    Items.WHEAT, Items.CARROT, Items.POTATO,
                    Items.BEETROOT, Items.BREAD
            );
            case BLACKSMITH -> Set.of(
                    Items.IRON_INGOT, Items.GOLD_INGOT,
                    Items.IRON_SWORD, Items.IRON_PICKAXE,
                    Items.IRON_AXE, Items.IRON_SHOVEL
            );
            case MINER -> Set.of(
                    Items.COBBLESTONE, Items.STONE, Items.COAL,
                    Items.IRON_ORE, Items.RAW_IRON, Items.GRAVEL
            );
            case CARPENTER -> Set.of(
                    Items.OAK_PLANKS,   Items.SPRUCE_PLANKS,  Items.BIRCH_PLANKS,
                    Items.OAK_SLAB,     Items.SPRUCE_SLAB,    Items.BIRCH_SLAB,
                    Items.OAK_STAIRS,   Items.SPRUCE_STAIRS,
                    Items.OAK_DOOR,     Items.SPRUCE_DOOR,
                    Items.OAK_FENCE,    Items.SPRUCE_FENCE,
                    Items.CHEST,        Items.BARREL,          Items.BOOKSHELF
            );
            default -> Set.of();
        };
    }



    @SuppressWarnings("unchecked")
    public <T extends Goal> T getGoal(Class<T> goalClass) {
        return (T) goalSelector.getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .filter(goalClass::isInstance)
                .findFirst()
                .orElse(null);
    }




    // =========================================================================
    // INTERACTION
    // =========================================================================

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;

        // Stick = debug info
        if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().is(Items.STICK)) {
            showDebugInfo(player);
            return InteractionResult.SUCCESS;
        }

        switch (getProfession()) {
            case MERCHANT -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {

                    if (sp.isShiftKeyDown()) {
                        // Shift interact = request work assignment
                        WorkplaceAssignmentManager.handleWorkRequest(sp, this, sl);
                    } else {
                        // Normal interact = open trade screen (unchanged)
                        MerchantGoal goal = getGoal(MerchantGoal.class);
                        if (goal != null && goal.isOpenForTrade()) {
                            TradeHandler.openTradeScreen(sp, this);
                        } else {
                            player.displayClientMessage(
                                    Component.literal(getNpcName()
                                            + " is not open for trade right now."),
                                    false);
                        }
                    }
                }
            }

            case GUARD -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {
                    // Guards offer work assignment on normal interact —
                    // they have no trade screen
                    WorkplaceAssignmentManager.handleWorkRequest(sp, this, sl);
                }
            }
            case ADVENTURER ->{
                if (getCombatRole() != null
                        && hand == InteractionHand.MAIN_HAND) {
                    if (player instanceof ServerPlayer sp) {
                        PlayerPartySavedData partyData = PlayerPartySavedData.get((ServerLevel) level());

                        // Only the party leader can interact
                        partyData.getPartyContaining(getUUID())
                                .filter(party -> party.getLeaderPlayerId()
                                        .equals(sp.getUUID()))
                                .ifPresentOrElse(
                                        party -> {
                                            // Show quick status in chat
                                            party.getMember(getUUID())
                                                    .ifPresent(m -> {
                                                        sp.displayClientMessage(
                                                                Component.literal(
                                                                        "[" + m.name()
                                                                                + "] " + m.role().symbol
                                                                                + " " + m.role().getDisplayName()
                                                                                + " | Lv." + m.level()
                                                                                + " | " + m.kills()
                                                                                + " kills"),
                                                                false);
                                                        sp.displayClientMessage(
                                                                Component.literal(
                                                                        "  " + m.role().description),
                                                                false);
                                                    });
                                        },
                                        () -> sp.displayClientMessage(
                                                Component.literal(
                                                        getNpcName()
                                                                + ": I'm ready for adventure!"),
                                                false));
                    }
                    return InteractionResult.SUCCESS;
                }

            }
            case INNKEEPER -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {
                    handleInnkeeperInteraction(sp, sl);
                }
            }
            case GUILDWORKER -> {
                if (level() instanceof ServerLevel serverLevel && hand == InteractionHand.MAIN_HAND) {

                    VillageSavedData vdata = VillageSavedData.get(serverLevel);

                    // Find the guild for this NPC's village
                    Optional<UUID> guildIdOpt = getAssignedVillageName()
                            .flatMap(name -> vdata.getVillageByName(name))
                            .flatMap(village ->
                                    vdata.getGuildForVillage(village.getId()))
                            .map(GuildData::guildId);

                    if (guildIdOpt.isEmpty()) {
                        if (player instanceof ServerPlayer sp) {
                            sp.displayClientMessage(
                                    Component.literal(
                                            getNpcName()
                                                    + ": This village has no guild hall yet."),
                                    false);
                        }
                        return InteractionResult.SUCCESS;
                    }

                    if (player instanceof ServerPlayer sp) {
                        PlayerGuildData guildData =
                                PlayerGuildData.get(serverLevel);
                        PlayerPartySavedData partyData =
                                PlayerPartySavedData.get(serverLevel);

                        GuildScreen.sendOpenPacket(
                                sp, guildIdOpt.get(), serverLevel,
                                guildData, vdata, partyData);
                    }

                    return InteractionResult.SUCCESS;
                }

            }
            case VILLAGE_LEADER -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {

                    // Sneak + interact = show full order board
                    if (sp.isShiftKeyDown()) {
                        CraftingOrderInteraction.showOrderBoard(sp, this, sl);
                        return InteractionResult.SUCCESS;
                    }

                    // Normal interact = open village book (unchanged), then hint about orders
                    handleVillageLeaderInteraction(sp, sl);
                    CraftingOrderInteraction.showOrderHint(sp, this, sl);
                }
            }
            case FARMER, BLACKSMITH, CARPENTER, MINER -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {
                    WorkplaceAssignmentManager.handleWorkRequest(sp, this, sl);
                }
            }
            case STOCKPILE_KEEPER -> {
                // Show the stockpile screen as before
                openStockpileScreen(player);

                // Also show any crafting orders this keeper has posted
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {
                    CraftingOrderInteraction.showStockpileOrders(sp, this, sl);
                }
            }            case BUILDER -> openInventoryScreen(player, "Builder Inventory");
            case NONE -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {

                    ItemStack held = player.getMainHandItem();
                    boolean holdingCoin =
                            tterrag1112.life_in_the_village.Village.Economy
                                    .Currency.CurrencyValue.valuePerCoin(held.getItem()) > 0;

                    if (!holdingCoin) {
                        player.displayClientMessage(
                                Component.literal("[" + getNpcName()
                                        + "] I'm looking for work. "
                                        + "Offer me a coin to hire me."),
                                false);
                        return InteractionResult.SUCCESS;
                    }

                    tterrag1112.life_in_the_village.Guilds.Companies
                            .CompanySavedData compData =
                            tterrag1112.life_in_the_village.Guilds.Companies
                                    .CompanySavedData.get(sl);
                    var companies = compData.getByOwner(sp.getUUID());

                    if (companies.isEmpty()) {
                        player.displayClientMessage(
                                Component.literal("[" + getNpcName()
                                        + "] You don't own a company. "
                                        + "Found one first from a town."),
                                false);
                        return InteractionResult.SUCCESS;
                    }

                    if (compData.getCompanyForWorker(getUUID()).isPresent()) {
                        player.displayClientMessage(
                                Component.literal("[" + getNpcName()
                                        + "] I'm already employed."),
                                false);
                        return InteractionResult.SUCCESS;
                    }

                    var company = companies.get(0);
                    held.shrink(1);

                    tterrag1112.life_in_the_village.Guilds.Companies
                            .Company.CompanyWorker worker =
                            new tterrag1112.life_in_the_village.Guilds.Companies
                                    .Company.CompanyWorker(
                                    getUUID(),
                                    Company.WorkerRole.PRODUCER,
                                    Company.ProducerType.GENERIC,
                                    company.getBuildingIds().isEmpty()
                                            ? UUID.fromString("00000000-0000-0000-0000-000000000000") // null sentinel
                                            : company.getBuildingIds().get(0),
                                    Math.max(company.getEffectiveMinWage(
                                            tterrag1112.life_in_the_village.Networking
                                                    .VillageSavedData.get(sl)), 8L),
                                    sl.getGameTime(), "", 8);

                    company.addWorker(worker);
                    setCompanyId(company.getCompanyId());
                    setProfession(Profession.COMPANY_WORKER);
                    compData.markDirty();

                    player.displayClientMessage(
                            Component.literal("[" + getNpcName()
                                    + "] I'll work for " + company.getName()
                                    + "! Thank you."),
                            false);

                    CompanyWorkerScreen.open(sp, this, company);
                }
            }

            case COMPANY_WORKER -> {
                if (level() instanceof ServerLevel sl
                        && player instanceof ServerPlayer sp) {
                    CompanySavedData compData =
                            CompanySavedData.get(sl);
                    compData.getCompanyForWorker(getUUID()).ifPresent(company -> {
                        if (company.getOwnerPlayerId().equals(sp.getUUID())) {
                            CompanyWorkerScreen.open(sp, this, company);
                        } else {
                            sp.displayClientMessage(
                                    Component.literal(getNpcName()
                                            + " is employed by "
                                            + company.getName() + "."),
                                    false);
                        }
                    });
                }
            }

            default ->
                player.displayClientMessage(
                        Component.literal(getDisplayName().getString() + " is busy."), false);

        }
        return InteractionResult.SUCCESS;
    }

    public CurrencyValue getTotalWealth(ServerLevel level) {
        Building building = getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        return CoinHelper.getTotalWealth(personalInventory, level, building);
    }

    public boolean canAffordTotal(CurrencyValue price, ServerLevel level) {
        return getTotalWealth(level).isAffordable(price);
    }

    public boolean payWithBuilding(TownspersonMob receiver,
                                   CurrencyValue amount,
                                   ServerLevel level) {
        Building building = getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        return CoinHelper.payWithBuilding(
                personalInventory,
                amount, level, building);
    }

    private void showDebugInfo(Player player) {
        player.displayClientMessage(Component.literal(
                "=== " + getDisplayName().getString() + " ===").withStyle(ChatFormatting.GOLD), false);
        player.displayClientMessage(Component.literal("UUID: " + getUUID()), false);
        player.displayClientMessage(Component.literal("Profession: " + getProfession()), false);
        player.displayClientMessage(Component.literal("Life Stage: " + getLifeStage()
                + " (age " + age + " days)"), false);
        player.displayClientMessage(Component.literal("Family Role: " + getFamilyRole()), false);
        player.displayClientMessage(Component.literal("Traits: " + (traits.isEmpty() ? "none"
                : traits.stream().map(PersonalityTrait::name).collect(Collectors.joining(", ")))), false);
        player.displayClientMessage(Component.literal("Village: "
                + getAssignedVillageName().orElse("none")), false);
        player.displayClientMessage(Component.literal("Building: "
                + getAssignedBuildingId()
                .flatMap(id -> level() instanceof ServerLevel sl
                        ? VillageSavedData.get(sl).getBuildingById(id).map(Building::getName)
                        : Optional.empty())
                .orElse("none")), false);
        player.displayClientMessage(Component.literal("House: "
                + (houseId != null ? houseId.toString() : "none")), false);
        player.displayClientMessage(Component.literal("Spouse: "
                + (spouseId != null ? spouseId.toString() : "none")), false);
        player.displayClientMessage(Component.literal("Children: " + childrenIds.size()), false);
        player.displayClientMessage(Component.literal("Wealth: " + getWealth()), false);
        player.displayClientMessage(Component.literal("Working Blocked: " + workingBlocked), false);
        player.displayClientMessage(Component.literal("Plot: "
                + (assignedPlotId != null ? assignedPlotId.toString() : "none")), false);
    }

    private void openInventoryScreen(Player player, String title) {
        if (!(player instanceof ServerPlayer sp)) return;
        Map<net.minecraft.world.item.Item, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            ItemStack stack = personalInventory.getItem(i);
            if (!stack.isEmpty()) totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<ItemStack> icons = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        totals.entrySet().stream()
                .sorted(Map.Entry.<net.minecraft.world.item.Item, Integer>comparingByValue().reversed())
                .forEach(e -> { icons.add(new ItemStack(e.getKey(), 1)); counts.add(e.getValue()); });
        PacketDistributor.sendToPlayer(sp, new BuilderInventoryPacket(icons, counts));
    }

    private void openStockpileScreen(Player player) {
        if (!(level() instanceof ServerLevel sl)) return;
        if (!(player instanceof ServerPlayer sp)) return;
        getAssignedBuilding(sl).ifPresent(building -> {
            Map<net.minecraft.world.item.Item, Integer> totals = new LinkedHashMap<>();
            for (var container : BuildingStorageAccess.findInventories(sl, building)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
            List<ItemStack> icons = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            totals.entrySet().stream()
                    .sorted(Map.Entry.<net.minecraft.world.item.Item, Integer>comparingByValue().reversed())
                    .forEach(e -> { icons.add(new ItemStack(e.getKey(), 1)); counts.add(e.getValue()); });
            PacketDistributor.sendToPlayer(sp,
                    new StockpileContentsPacket(building.getName(), icons, counts));
        });
    }

    // =========================================================================
    // SPAWN SETUP
    // =========================================================================

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level,
                                        DifficultyInstance difficulty, EntitySpawnReason spawnType,
                                        @Nullable SpawnGroupData spawnGroupData) {

        // Gender
        isMale = random.nextBoolean();

        // Age
        age = 18 + random.nextInt(43);

        // Name — first name only at spawn, surname assigned when family forms
        String firstName = NpcNameRegistry.INSTANCE
                .generateFirstName(isMale, random);
        String surname = NpcNameRegistry.INSTANCE
                .generateSurname(random);
        setNpcName(firstName + " " + surname);

        // Traits
        PersonalityTrait first = randomTrait(random);
        addTrait(first);
        if (random.nextBoolean()) {
            PersonalityTrait[] all = PersonalityTrait.values();
            for (int attempts = 0; attempts < 10; attempts++) {
                PersonalityTrait candidate = all[random.nextInt(all.length)];
                if (!candidate.conflictsWith(first)) {
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
    // NBT SAVE / LOAD
    // =========================================================================

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);

        // Profession & assignment
        output.putString("profession", getProfession().name());
        if (assignedBuildingId != null) output.putString("assignedBuildingId", assignedBuildingId.toString());
        if (assignedVillageName != null) output.putString("assignedVillageName", assignedVillageName);
        if (assignedPlotId != null) output.putString("assignedPlotId", assignedPlotId.toString());
        if (assignedPost != null) {
            output.putInt("postX", assignedPost.getX());
            output.putInt("postY", assignedPost.getY());
            output.putInt("postZ", assignedPost.getZ());
        }
        getGroupId().ifPresent(id ->
                output.store("groupId", UUIDUtil.CODEC, id));
        output.store("isGroupLeader", Codec.BOOL, entityData.get(IS_GROUP_LEADER));

        getCaravanId().ifPresent(id ->
                output.store("caravanId", UUIDUtil.CODEC, id));
        if (companyId != null)
            output.putString("companyId", companyId.toString());
        if (combatRole != null) output.putString("combatRole", combatRole.name());


        // Identity
        output.putString("npcName", npcName);
        output.putInt("age", age);
        output.putLong("lastAgeTick", lastAgeTick);
        output.putLong("birthTick", birthTick);
        output.putBoolean("isMale", isMale);
        if (!adventurerTitle.isEmpty())
            output.putString("adventurerTitle", adventurerTitle);



        // Appearance
        output.putInt("skinTone", skinTone);
        output.putInt("hairStyle", hairStyle);
        output.putInt("hairColor", hairColor);

        // Personality
        if (!traits.isEmpty()) {
            output.putString("traits", traits.stream()
                    .map(PersonalityTrait::name).collect(Collectors.joining(",")));
        }

        // Family
        output.putString("familyRole", familyRole.name());
        if (houseId != null) output.putString("houseId", houseId.toString());
        if (spouseId != null) output.putString("spouseId", spouseId.toString());
        if (headOfHouseholdId != null) output.putString("headOfHouseholdId", headOfHouseholdId.toString());
        if (!childrenIds.isEmpty()) {
            output.putString("childrenIds", childrenIds.stream()
                    .map(UUID::toString).collect(Collectors.joining(",")));
        }

        // Trading

        // Inventory
        NonNullList<ItemStack> items =
                NonNullList.withSize(personalInventory.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            items.set(i, personalInventory.getItem(i));
        }
        ContainerHelper.saveAllItems(output, items, false);
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);

        // Profession & assignment
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
                    input.read("postZ", Codec.INT).orElse(0)
            );
        }
        input.read("group_id", UUIDUtil.CODEC).ifPresent(this::setGroupId);
        input.read("isGroupLeader", Codec.BOOL)
                .ifPresent(v -> entityData.set(IS_GROUP_LEADER, v));

        input.read("caravanId", UUIDUtil.CODEC)
                .ifPresent(this::setCaravanId);
        input.read("companyId", Codec.STRING)
                .ifPresent(s -> companyId = UUID.fromString(s));
        input.read("combatRole", Codec.STRING).ifPresent(s -> {
            try { setCombatRole(CombatRole.valueOf(s)); }
            catch (IllegalArgumentException ignored) {}
        });

        // Identity
        input.read("npcName", Codec.STRING).ifPresent(s -> npcName = s);
        input.read("age", Codec.INT).ifPresent(a -> { age = a; updateScale(); });
        input.read("birthTick", Codec.LONG).ifPresent(t -> birthTick = t);
        input.read("lastAgeTick", Codec.LONG).ifPresent(t -> lastAgeTick = t);
        input.read("isMale", Codec.BOOL).ifPresent(v -> isMale = v);
        input.read("adventurerTitle", Codec.STRING)
                .ifPresent(t -> adventurerTitle = t);
        input.read("npcName", Codec.STRING).ifPresent(n -> {
            npcName = n;
            if (!n.isEmpty()) {
                setCustomName(Component.literal(n));
                setCustomNameVisible(true);
            }
        });


        currentActivity = "";
        updateDisplayName();


        // Appearance
        input.read("skinTone", Codec.INT).ifPresent(v -> {
            skinTone = v; entityData.set(SKIN_TONE, v);
        });
        input.read("hairStyle", Codec.INT).ifPresent(v -> {
            hairStyle = v; entityData.set(HAIR_STYLE, v);
        });
        input.read("hairColor", Codec.INT).ifPresent(v -> {
            hairColor = v; entityData.set(HAIR_COLOR, v);
        });

        // Personality
        traits.clear();
        input.read("traits", Codec.STRING).ifPresent(s -> {
            if (!s.isEmpty()) {
                for (String name : s.split(",")) {
                    try { traits.add(PersonalityTrait.valueOf(name)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        });

        // Family
        input.read("familyRole", Codec.STRING).ifPresent(s -> {
            try { familyRole = FamilyRole.valueOf(s); }
            catch (IllegalArgumentException ignored) {}
        });
        input.read("houseId", Codec.STRING).ifPresent(s -> houseId = UUID.fromString(s));
        input.read("spouseId", Codec.STRING).ifPresent(s -> spouseId = UUID.fromString(s));
        input.read("headOfHouseholdId", Codec.STRING)
                .ifPresent(s -> headOfHouseholdId = UUID.fromString(s));
        input.read("childrenIds", Codec.STRING).ifPresent(s -> {
            childrenIds = new ArrayList<>();
            for (String id : s.split(",")) {
                if (!id.isEmpty()) childrenIds.add(UUID.fromString(id));
            }
        });

        // Trading

        // Inventory
        NonNullList<ItemStack> items =
                NonNullList.withSize(personalInventory.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        for (int i = 0; i < items.size(); i++) {
            personalInventory.setItem(i, items.get(i));
        }

        // Sync entity data
        entityData.set(LIFE_STAGE, getLifeStage().name());
        entityData.set(FAMILY_ROLE, familyRole.name());
        updateScale();
    }

    //EVENTS

    private VillageEvent.EventType eventOverride = null;
    private float eventTradeDiscount = 1.0f;

    public void setEventOverride(VillageEvent.EventType type) {
        this.eventOverride = type;
    }

    public void clearEventOverride() {
        this.eventOverride = null;
    }

    public Optional<VillageEvent.EventType> getEventOverride() {
        return Optional.ofNullable(eventOverride);
    }

    public void setEventTradeDiscount(float discount) {
        this.eventTradeDiscount = discount;
    }

    public float getEventTradeDiscount() { return eventTradeDiscount; }

    // During events NPCs should socialize instead of work
    public boolean isEventTime() {
        return eventOverride != null;
    }



    private static final EntityDataAccessor<String> GROUP_ID =
            SynchedEntityData.defineId(TownspersonMob.class,
                    EntityDataSerializers.STRING);

    public Optional<UUID> getGroupId() {
        String raw = this.entityData.get(GROUP_ID);
        if (raw == null || raw.isEmpty()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void setGroupId(@Nullable UUID id) {
        this.entityData.set(GROUP_ID, id != null ? id.toString() : "");
    }

    public void clearGroupId() {
        entityData.set(GROUP_ID, "");
    }

    public boolean isAdventurerGroupMember() {
        return !entityData.get(GROUP_ID).isEmpty();
    }

    public boolean isGroupLeader() {
        return entityData.get(IS_GROUP_LEADER);
    }

    public void setIsGroupLeader(boolean leader) {
        entityData.set(IS_GROUP_LEADER, leader);
    }

    public Optional<UUID> getCaravanId() {
        String raw = entityData.get(CARAVAN_ID);
        if (raw.isEmpty()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void setCaravanId(UUID id) {
        entityData.set(CARAVAN_ID, id.toString());
    }

    public void clearCaravanId() {
        entityData.set(CARAVAN_ID, "");
    }

    public boolean isCaravanMember() {
        return !entityData.get(CARAVAN_ID).isEmpty();
    }

    private void handleVillageLeaderInteraction(
            ServerPlayer player, ServerLevel sl) {
        VillageSavedData data = VillageSavedData.get(sl);

        getAssignedVillageName().flatMap(data::getVillageByName).ifPresent(village -> {
            VillageBookScreen.sendOpenPacket(player, village.getId(), sl, data);
        });
    }

    public Optional<UUID> getCompanyId() {
        return Optional.ofNullable(companyId);
    }

    public void setCompanyId(UUID id) { this.companyId = id; }
    public void clearCompanyId()      { this.companyId = null; }

    public boolean isCompanyWorker() { return companyId != null; }

    @SubscribeEvent
    public static void onNpcDeath(
            net.neoforged.neoforge.event.entity.living
                    .LivingDeathEvent event) {
        if (!(event.getEntity()
                instanceof TownspersonMob npc)) return;
        if (!(npc.level() instanceof ServerLevel level))
            return;

        // Only record deaths of notable professions
        boolean notable = switch (npc.getProfession()) {
            case VILLAGE_LEADER, KINGDOM_RULER,
                 GUILDMASTER, MERCHANT -> true;
            default -> false;
        };
        if (!notable) return;

        VillageSavedData data = VillageSavedData.get(level);
        npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(village ->
                        data.getKingdomForVillage(
                                        village.getId())
                                .ifPresent(k -> {
                                    k.getHistory().recordEvent(
                                            HistoryTextGenerator
                                                    .notableDeath(
                                                            npc.getNpcName(),
                                                            village.getName(),
                                                            npc.getProfession()
                                                                    .getDisplayName(),
                                                            level.getGameTime()),
                                            k.getName(),
                                            k.getRulerName(level));
                                    data.setDirty();
                                }));
    }

    public static Optional<TownspersonMob> findByUUID(ServerLevel level, UUID id) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000,
                        30000000,  2048,  30000000),
                mob -> mob.getUUID().equals(id)
        ).stream().findFirst();
    }





}

