package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Scribal.AuthorStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.BookRecord;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue;
import tterrag1112.life_in_the_village.Npc.Scribal.ScholarProgress;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 247. Cycles between research (study books in nearby
 * library) and authorship (mint a {@link BookRecord} when research
 * crosses {@link ScholarProgress#PUBLISH_THRESHOLD}).
 *
 * <p>Teaching and consultation phases (spec lines 256-260) are
 * stubbed: consultation already works via the existing knowledge
 * ledger consulted by ask_about; teaching slots in once schooling
 * (doc 15) lands.</p>
 */
public class ScholarBehavior extends Behavior<TownspersonMob> {

    /** Time between research-progress ticks. */
    public static final int RESEARCH_INTERVAL = 1200;
    /** Research points added per study tick (1/in-game day at the desk). */
    public static final int RESEARCH_POINTS_PER_TICK = 1;
    /** XP per research tick. */
    public static final int LITERACY_XP_PER_RESEARCH = 1;
    /** Bonus MEDICINE XP — scholar's secondary skill. */
    public static final int MEDICINE_XP_PER_RESEARCH = 1;

    private TownspersonMob entity;

    public ScholarBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Building workspace;
    private BlockPos studyPos;
    private int researchTimer;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;

        // Prefer the assigned SCHOLARS_RETREAT; spec line 244 allows
        // a scholar to fall back to the LIBRARY if no retreat exists.
        workspace = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.SCHOLARS_RETREAT
                        || b.getType() == BuildingType.LIBRARY)
                .orElseGet(() -> nearestLibraryInVillage(level));
        if (workspace == null) return false;
        studyPos = workspace.getShape().getOrigin();
        return studyPos != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return workspace != null && entity.isWorkTime();
    }

        @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        researchTimer = 0;
        entity.setCurrentActivity("Studying at the desk");
        if (studyPos != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    studyPos.getX() + 0.5, studyPos.getY(), studyPos.getZ() + 0.5, 0.8));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        researchTimer++;
        if (researchTimer < RESEARCH_INTERVAL) return;
        researchTimer = 0;

        ScholarProgress prog = entity.getScholarProgress();
        // Pick a topic — first existing in-flight topic, else seed a fresh one
        // from the NPC's surname for stable per-NPC working titles.
        String topic = prog.currentTopic().isEmpty()
                ? "Studies of " + entity.getNpcName()
                : prog.currentTopic();
        prog.addResearch(RESEARCH_POINTS_PER_TICK, topic);
        entity.getSkills().addXp(Skill.LITERACY, LITERACY_XP_PER_RESEARCH, level.getGameTime());
        entity.getSkills().addXp(Skill.MEDICINE, MEDICINE_XP_PER_RESEARCH, level.getGameTime());

        // Knowledge bump: scholar's research populates ledger at higher
        // fidelity. Spec line 253.
        if (entity.getKnowledge().get(topic).isEmpty()) {
            entity.getKnowledge().add(KnowledgeEntry.create(
                    topic,
                    KnowledgeCategory.LOCAL,
                    0.95f,
                    KnowledgeSource.EDUCATION,
                    level.getGameTime(),
                    "Drawn from extended study at " + workspace.getName(),
                    null));
        }

        // Authorship phase (spec line 254): mint a book once threshold reached
        // and cooldown elapsed.
        if (prog.readyToPublish() && prog.cooldownElapsed(level.getGameTime())) {
            publishBook(level, topic, prog);
        }
    }

    private void publishBook(ServerLevel level, String topic, ScholarProgress prog) {
        UUID bookId = UUID.randomUUID();
        BookRecord record = new BookRecord(
                bookId,
                topic,
                entity.getNpcName(),
                Optional.of(entity.getUUID()),
                List.copyOf(prog.coveredTopics()),
                Optional.of(Skill.LITERACY),
                Math.max(1, prog.coveredTopics().size()),
                level.getGameTime(),
                1);
        // Drop the book record into the scholar's village library if one exists.
        VillageSavedData data = VillageSavedData.get(level);
        UUID libId = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(v -> firstLibraryId(v, data))
                .orElse(workspace.getId());
        LibraryCatalogue cat = data.getOrCreateLibraryCatalogue(libId);
        cat.acquire(record);

        // AuthorStatus bookkeeping.
        AuthorStatus author = entity.getAuthorStatus();
        author.publish(bookId, prog.coveredTopics().size());
        prog.onPublished(level.getGameTime());

        // Bump skills for the milestone.
        entity.getSkills().addXp(Skill.LITERACY, 30, level.getGameTime());
        data.setDirty();
    }

    private static UUID firstLibraryId(Village v, VillageSavedData data) {
        for (UUID id : v.getBuildingIds()) {
            var b = data.getBuildingById(id).orElse(null);
            if (b != null && b.getType() == BuildingType.LIBRARY) return id;
        }
        return v.getId();
    }

    private Building nearestLibraryInVillage(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(v -> {
                    for (UUID bid : v.getBuildingIds()) {
                        var b = VillageSavedData.get(level).getBuildingById(bid).orElse(null);
                        if (b != null && b.getType() == BuildingType.LIBRARY) return b;
                    }
                    return null;
                })
                .orElse(null);
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        workspace = null;
        studyPos = null;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
