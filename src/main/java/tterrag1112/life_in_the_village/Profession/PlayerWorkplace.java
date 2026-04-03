package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.*;

public class PlayerWorkplace {

    public enum AssignmentType {
        TASK,   // simple timed task — low levels
        QUOTA   // produce/sell X items — high levels
    }

    public record WorkAssignment(
            AssignmentType type,
            String description,
            String targetItem,   // null for TASK
            int targetCount,     // 1 for simple TASK
            int currentCount,
            long issuedTick,
            long deadlineTick,
            int xpReward,
            long coinReward
    ) {
        public static final Codec<WorkAssignment> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(
                                        AssignmentType::valueOf,
                                        AssignmentType::name)
                                .fieldOf("type")
                                .forGetter(WorkAssignment::type),
                        Codec.STRING.fieldOf("description")
                                .forGetter(WorkAssignment::description),
                        Codec.STRING.optionalFieldOf(
                                        "targetItem", "")
                                .forGetter(WorkAssignment::targetItem),
                        Codec.INT.fieldOf("targetCount")
                                .forGetter(WorkAssignment::targetCount),
                        Codec.INT.fieldOf("currentCount")
                                .forGetter(WorkAssignment::currentCount),
                        Codec.LONG.fieldOf("issuedTick")
                                .forGetter(WorkAssignment::issuedTick),
                        Codec.LONG.fieldOf("deadlineTick")
                                .forGetter(WorkAssignment::deadlineTick),
                        Codec.INT.fieldOf("xpReward")
                                .forGetter(WorkAssignment::xpReward),
                        Codec.LONG.fieldOf("coinReward")
                                .forGetter(WorkAssignment::coinReward)
                ).apply(i, WorkAssignment::new));

        public boolean isComplete() {
            return currentCount >= targetCount;
        }

        public boolean isExpired(long currentTick) {
            return currentTick > deadlineTick;
        }

        public WorkAssignment withProgress(int count) {
            return new WorkAssignment(type, description,
                    targetItem, targetCount, count,
                    issuedTick, deadlineTick,
                    xpReward, coinReward);
        }
    }

    public record WorkplaceEntry(
            UUID buildingId,
            UUID villageId,
            PlayerProfession profession,
            boolean isOwner,
            long assignedTick,
            long lastPayTick,
            WorkAssignment currentAssignment
    ) {
        public static final Codec<WorkplaceEntry> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("buildingId")
                                .forGetter(WorkplaceEntry::buildingId),
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("villageId")
                                .forGetter(WorkplaceEntry::villageId),
                        PlayerProfession.CODEC
                                .fieldOf("profession")
                                .forGetter(WorkplaceEntry::profession),
                        Codec.BOOL.fieldOf("isOwner")
                                .forGetter(WorkplaceEntry::isOwner),
                        Codec.LONG.fieldOf("assignedTick")
                                .forGetter(WorkplaceEntry::assignedTick),
                        Codec.LONG.fieldOf("lastPayTick")
                                .forGetter(WorkplaceEntry::lastPayTick),
                        WorkAssignment.CODEC
                                .optionalFieldOf("currentAssignment")
                                .forGetter(e -> Optional.ofNullable(
                                        e.currentAssignment()))
                ).apply(i, (bid, vid, prof, owner,
                            assigned, lastPay, assignment) ->
                        new WorkplaceEntry(bid, vid, prof,
                                owner, assigned, lastPay,
                                assignment.orElse(null))));

        public WorkplaceEntry withAssignment(
                WorkAssignment a) {
            return new WorkplaceEntry(buildingId, villageId,
                    profession, isOwner, assignedTick,
                    lastPayTick, a);
        }

        public WorkplaceEntry withLastPayTick(long tick) {
            return new WorkplaceEntry(buildingId, villageId,
                    profession, isOwner, assignedTick,
                    tick, currentAssignment);
        }
        public WorkplaceEntry(UUID buildingId,
                              UUID villageId,
                              PlayerProfession profession,
                              boolean isOwner,
                              WorkAssignment currentAssignment){

            return new WorkplaceEntry(buildingId, villageId, profession, isOwner, );

        }
    }
}