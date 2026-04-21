package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Profession.Tasks.WorkTaskType;

import java.util.*;

public class PlayerWorkplace {

    /**
     * Legacy category kept for codec backward-compatibility.
     * New code should prefer {@link WorkTaskType} and {@link TaskPriority}.
     */
    public enum AssignmentType {
        TASK,   // simple timed task
        QUOTA   // produce/sell X items
    }

    // =========================================================================
    // WorkAssignment
    // =========================================================================

    /**
     * A single unit of work assigned to a player at their workplace.
     *
     * <h3>Typed tasks</h3>
     * Every assignment now carries a {@link WorkTaskType} so completion
     * detection in {@code TaskCompletionEvents} can route correctly, and a
     * {@link TaskPriority} so the player's task list can be sorted and
     * filtered (busywork hidden when real work exists).
     *
     * <h3>Optional location fields</h3>
     * {@code sourceBuildingId} — UUID string of the building where the player
     * should pick up items (DELIVER tasks only). Empty string = not set.
     *
     * {@code destinationBuildingId} — UUID string of the building where items
     * should be deposited (GATHER, DELIVER, RESTOCK tasks). Empty = not set.
     *
     * <h3>Backward compatibility</h3>
     * The codec uses {@code optionalFieldOf} for all new fields so existing
     * saves with only the legacy fields continue to deserialise correctly.
     * Old assignments without a taskType are assigned {@link WorkTaskType#BUSYWORK}
     * as a safe default (they have no completion detection, which matches
     * the original behaviour of the {@code TASK} type).
     */
    public record WorkAssignment(
            AssignmentType type,
            String description,
            String targetItem,          // registry ID, empty string = none
            int targetCount,
            int currentCount,
            long issuedTick,
            long deadlineTick,
            int xpReward,
            long coinReward,
            WorkTaskType taskType,      // drives completion detection
            TaskPriority priority,      // drives task issuance priority
            String sourceBuildingId,    // DELIVER source; empty = not set
            String destinationBuildingId // deposit target; empty = not set
    ) {
        public static final Codec<WorkAssignment> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(AssignmentType::valueOf, AssignmentType::name)
                                .fieldOf("type").forGetter(WorkAssignment::type),
                        Codec.STRING.fieldOf("description").forGetter(WorkAssignment::description),
                        Codec.STRING.optionalFieldOf("targetItem", "")
                                .forGetter(WorkAssignment::targetItem),
                        Codec.INT.fieldOf("targetCount").forGetter(WorkAssignment::targetCount),
                        Codec.INT.fieldOf("currentCount").forGetter(WorkAssignment::currentCount),
                        Codec.LONG.fieldOf("issuedTick").forGetter(WorkAssignment::issuedTick),
                        Codec.LONG.fieldOf("deadlineTick").forGetter(WorkAssignment::deadlineTick),
                        Codec.INT.fieldOf("xpReward").forGetter(WorkAssignment::xpReward),
                        Codec.LONG.fieldOf("coinReward").forGetter(WorkAssignment::coinReward),
                        WorkTaskType.CODEC.optionalFieldOf("taskType", WorkTaskType.BUSYWORK)
                                .forGetter(WorkAssignment::taskType),
                        TaskPriority.CODEC.optionalFieldOf("priority", TaskPriority.NORMAL)
                                .forGetter(WorkAssignment::priority),
                        Codec.STRING.optionalFieldOf("sourceBuildingId", "")
                                .forGetter(WorkAssignment::sourceBuildingId),
                        Codec.STRING.optionalFieldOf("destinationBuildingId", "")
                                .forGetter(WorkAssignment::destinationBuildingId)
                ).apply(i, WorkAssignment::new));

        /**
         * Legacy 9-arg constructor used by existing call sites in
         * {@link WorkplaceAssignmentManager}. Delegates to the canonical
         * constructor and fills in sensible defaults for the new fields:
         * QUOTA assignments default to GATHER type, TASK to BUSYWORK, both
         * at NORMAL priority with no source/destination building set.
         *
         * <p>New call sites should use the canonical constructor or one of
         * the typed factory methods ({@link #quota}, {@link #deliver},
         * {@link #busywork}) so the task can be completion-detected by
         * {@code TaskCompletionEvents}.</p>
         */
        public WorkAssignment(AssignmentType type, String description,
                              String targetItem, int targetCount, int currentCount,
                              long issuedTick, long deadlineTick,
                              int xpReward, long coinReward) {
            this(type, description, targetItem, targetCount, currentCount,
                    issuedTick, deadlineTick, xpReward, coinReward,
                    type == AssignmentType.QUOTA
                            ? WorkTaskType.GATHER
                            : WorkTaskType.BUSYWORK,
                    TaskPriority.NORMAL, "", "");
        }

        // ── Completion & expiry ───────────────────────────────────────────────

        public boolean isComplete() {
            return currentCount >= targetCount;
        }

        public boolean isExpired(long currentTick) {
            return currentTick > deadlineTick;
        }

        // ── Mutation helpers ──────────────────────────────────────────────────

        public WorkAssignment withProgress(int count) {
            return new WorkAssignment(type, description, targetItem,
                    targetCount, count, issuedTick, deadlineTick,
                    xpReward, coinReward, taskType, priority,
                    sourceBuildingId, destinationBuildingId);
        }

        // ── Convenience accessors ─────────────────────────────────────────────

        public boolean hasTargetItem() {
            return targetItem != null && !targetItem.isBlank();
        }

        public boolean hasDestination() {
            return destinationBuildingId != null && !destinationBuildingId.isBlank();
        }

        public boolean hasSource() {
            return sourceBuildingId != null && !sourceBuildingId.isBlank();
        }

        // ── Factory methods ───────────────────────────────────────────────────

        /** Simple QUOTA task with item delivery completion. */
        public static WorkAssignment quota(
                String description, String targetItemId,
                int targetCount, long issuedTick, long deadlineTick,
                int xpReward, long coinReward,
                WorkTaskType taskType, TaskPriority priority,
                String destinationBuildingId) {
            return new WorkAssignment(
                    AssignmentType.QUOTA, description, targetItemId,
                    targetCount, 0, issuedTick, deadlineTick,
                    xpReward, coinReward, taskType, priority,
                    "", destinationBuildingId);
        }

        /** Delivery task (from source building to destination building). */
        public static WorkAssignment deliver(
                String description, String targetItemId,
                int targetCount, long issuedTick, long deadlineTick,
                int xpReward, long coinReward,
                TaskPriority priority,
                String sourceBuildingId, String destinationBuildingId) {
            return new WorkAssignment(
                    AssignmentType.QUOTA, description, targetItemId,
                    targetCount, 0, issuedTick, deadlineTick,
                    xpReward, coinReward, WorkTaskType.DELIVER, priority,
                    sourceBuildingId, destinationBuildingId);
        }

        /** Low-priority busywork — proximity/timer based, no items. */
        public static WorkAssignment busywork(
                String description,
                int proximityTicks, long issuedTick, long deadlineTick,
                int xpReward, long coinReward) {
            return new WorkAssignment(
                    AssignmentType.TASK, description, "",
                    proximityTicks, 0, issuedTick, deadlineTick,
                    xpReward, coinReward, WorkTaskType.BUSYWORK, TaskPriority.LOW,
                    "", "");
        }
    }

    // =========================================================================
    // WorkplaceEntry
    // =========================================================================

    /**
     * A player's employment record at one building for one profession.
     *
     * <h3>Multi-task support</h3>
     * {@code activeTasks} holds the full list of current assignments sorted
     * by {@link TaskPriority} (highest first). Multiple tasks can be active
     * simultaneously. The player's highest-priority task is {@code activeTasks.get(0)}.
     *
     * {@code currentAssignment} is retained for backward compatibility with
     * existing saved data. On load, old saves will have an empty
     * {@code activeTasks} list; code that reads assignments should prefer
     * {@code activeTasks} when non-empty and fall back to {@code currentAssignment}.
     */
    public record WorkplaceEntry(
            UUID buildingId,
            UUID villageId,
            PlayerProfession profession,
            boolean isOwner,
            long assignedTick,
            long lastPayTick,
            WorkAssignment currentAssignment,       // legacy — kept for save compat
            List<WorkAssignment> activeTasks        // multi-task list (primary)
    ) {
        public static final Codec<WorkplaceEntry> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(UUID::fromString, UUID::toString)
                                .fieldOf("buildingId").forGetter(WorkplaceEntry::buildingId),
                        Codec.STRING.xmap(UUID::fromString, UUID::toString)
                                .fieldOf("villageId").forGetter(WorkplaceEntry::villageId),
                        PlayerProfession.CODEC.fieldOf("profession")
                                .forGetter(WorkplaceEntry::profession),
                        Codec.BOOL.fieldOf("isOwner").forGetter(WorkplaceEntry::isOwner),
                        Codec.LONG.fieldOf("assignedTick").forGetter(WorkplaceEntry::assignedTick),
                        Codec.LONG.fieldOf("lastPayTick").forGetter(WorkplaceEntry::lastPayTick),
                        WorkAssignment.CODEC.optionalFieldOf("currentAssignment")
                                .forGetter(e -> Optional.ofNullable(e.currentAssignment())),
                        WorkAssignment.CODEC.listOf()
                                .optionalFieldOf("activeTasks", List.of())
                                .forGetter(WorkplaceEntry::activeTasks)
                ).apply(i, (bid, vid, prof, owner, assigned, lastPay, assignment, tasks) ->
                        new WorkplaceEntry(bid, vid, prof, owner, assigned, lastPay,
                                assignment.orElse(null), new ArrayList<>(tasks))));

        /**
         * Legacy 7-arg constructor — kept so existing call sites compile without
         * needing to pass an explicit empty task list. New entries start with
         * no active tasks; the manager issues them on demand.
         */
        public WorkplaceEntry(UUID buildingId, UUID villageId,
                              PlayerProfession profession, boolean isOwner,
                              long assignedTick, long lastPayTick,
                              WorkAssignment currentAssignment) {
            this(buildingId, villageId, profession, isOwner,
                    assignedTick, lastPayTick, currentAssignment,
                    new ArrayList<>());
        }

        // ── Mutation helpers ──────────────────────────────────────────────────

        /** Returns the highest-priority active task, or null if the task list is empty. */
        public WorkAssignment highestPriorityTask() {
            if (!activeTasks.isEmpty()) return activeTasks.get(0);
            return currentAssignment; // legacy fallback
        }

        /** True if the player has any active tasks (new list or legacy field). */
        public boolean hasTasks() {
            return !activeTasks.isEmpty() || currentAssignment != null;
        }

        /** True if ANY active task has CRITICAL or HIGH priority. */
        public boolean hasUrgentWork() {
            return activeTasks.stream().anyMatch(t ->
                    t.priority() == TaskPriority.CRITICAL
                            || t.priority() == TaskPriority.HIGH);
        }

        /** Replace the active task list. Automatically sorts by priority (highest first). */
        public WorkplaceEntry withTasks(List<WorkAssignment> tasks) {
            List<WorkAssignment> sorted = new ArrayList<>(tasks);
            sorted.sort(Comparator.comparingInt(t -> -t.priority().weight()));
            return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                    assignedTick, lastPayTick, currentAssignment, sorted);
        }

        /** Add a task and re-sort. */
        public WorkplaceEntry withAddedTask(WorkAssignment task) {
            List<WorkAssignment> updated = new ArrayList<>(activeTasks);
            updated.add(task);
            updated.sort(Comparator.comparingInt(t -> -t.priority().weight()));
            return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                    assignedTick, lastPayTick, currentAssignment, updated);
        }

        /** Remove a specific task by matching on issued tick (used as a stable ID). */
        public WorkplaceEntry withTaskRemoved(WorkAssignment task) {
            List<WorkAssignment> updated = new ArrayList<>(activeTasks);
            updated.removeIf(t -> t.issuedTick() == task.issuedTick()
                    && t.description().equals(task.description()));
            return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                    assignedTick, lastPayTick, currentAssignment, updated);
        }

        /** Update progress on a specific task (matched by issued tick). */
        public WorkplaceEntry withTaskProgress(WorkAssignment task, int newCount) {
            List<WorkAssignment> updated = new ArrayList<>(activeTasks);
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).issuedTick() == task.issuedTick()
                        && updated.get(i).description().equals(task.description())) {
                    updated.set(i, task.withProgress(newCount));
                    break;
                }
            }
            return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                    assignedTick, lastPayTick, currentAssignment, updated);
        }

        // ── Legacy compat helpers (used by existing call sites) ───────────────

        /** @deprecated Use {@link #withAddedTask} or {@link #withTasks}. */
        @Deprecated
        public WorkplaceEntry withAssignment(WorkAssignment a) {
            if (a == null) {
                return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                        assignedTick, lastPayTick, null, activeTasks);
            }
            return withAddedTask(a);
        }

        public WorkplaceEntry withLastPayTick(long tick) {
            return new WorkplaceEntry(buildingId, villageId, profession, isOwner,
                    assignedTick, tick, currentAssignment, activeTasks);
        }
    }
}
