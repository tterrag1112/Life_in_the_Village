package tterrag1112.life_in_the_village.Village.Buildings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.UUID;

public class ExpansionRequest {

    public enum RequestStatus {
        PENDING,     // waiting for builder to start
        IN_PROGRESS, // builder is working on it
        COMPLETE,    // building placed and registered
        FAILED       // builder could not complete
    }

    public static final Codec<ExpansionRequest> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(ExpansionRequest::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId").forGetter(ExpansionRequest::getVillageId),
                    Codec.STRING.xmap(BuildingType::valueOf, BuildingType::name)
                            .fieldOf("buildingType").forGetter(ExpansionRequest::getBuildingType),
                    BlockPos.CODEC
                            .optionalFieldOf("targetPos", BlockPos.ZERO)
                            .forGetter(ExpansionRequest::getTargetPos),
                    Codec.STRING.xmap(RequestStatus::valueOf, RequestStatus::name)
                            .fieldOf("status").forGetter(ExpansionRequest::getStatus),
                    Codec.LONG
                            .fieldOf("createdAtTick").forGetter(ExpansionRequest::getCreatedAtTick)
            ).apply(instance, ExpansionRequest::new)
    );

    private final UUID id;
    private final UUID villageId;
    private final BuildingType buildingType;
    private BlockPos targetPos;
    private RequestStatus status;
    private final long createdAtTick;

    public ExpansionRequest(UUID id, UUID villageId, BuildingType buildingType,
                            BlockPos targetPos, RequestStatus status, long createdAtTick) {
        this.id = id;
        this.villageId = villageId;
        this.buildingType = buildingType;
        this.targetPos = targetPos;
        this.status = status;
        this.createdAtTick = createdAtTick;
    }

    public static ExpansionRequest create(UUID villageId, BuildingType type,
                                          long currentTick) {
        return new ExpansionRequest(
                UUID.randomUUID(), villageId, type,
                BlockPos.ZERO, RequestStatus.PENDING, currentTick
        );
    }

    public UUID getId()                      { return id; }
    public UUID getVillageId()               { return villageId; }
    public BuildingType getBuildingType() { return buildingType; }
    public BlockPos getTargetPos()           { return targetPos; }
    public RequestStatus getStatus()         { return status; }
    public long getCreatedAtTick()           { return createdAtTick; }

    public void setTargetPos(BlockPos pos)   { this.targetPos = pos; }
    public void setStatus(RequestStatus s)   { this.status = s; }

    public boolean isPending()     { return status == RequestStatus.PENDING; }
    public boolean isInProgress()  { return status == RequestStatus.IN_PROGRESS; }
}