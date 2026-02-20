package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import tterrag1112.life_in_the_village.Utilities.PosUtil;

public class ResourceManager {
    private PosUtil sleepingPos = null;
    private final Building building;

    public ResourceManager(Building building){
        this.building = building;
    }

    public PosUtil getSleepingPos(){
        return this.sleepingPos;
    }

    public void setSleepingPos(PosUtil pos){
        this.sleepingPos = pos;
    }

    /*public void readFromNBT(CompoundTag compoundTag){
        this.sleepingPos = PosUtil.read(compoundTag, "sleepingPos");
        if(this.sleepingPos == null){
            this.sleepingPos = this.building.getShape().getOrigin();
        }
    }

     */

    public void writeToNBT(CompoundTag compoundTag){
        if(this.sleepingPos != null){
            this.sleepingPos.write(compoundTag, "sleepingPos");
        }
    }



}
