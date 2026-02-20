package tterrag1112.life_in_the_village.Utilities;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;

public class PosUtil {
    public final double x;
    public final double y;
    public final double z;

    public static final PosUtil read(CompoundTag compoundTag, String string){
        double x = compoundTag.getDouble(string + "_xCoord").get();
        double y = compoundTag.getDouble(string + "_yCoord").get();
        double z = compoundTag.getDouble(string + "_zCoord").get();
        return x == 0.0 && y == 0.0 && z == 0.0 ? null : new PosUtil(x, y, z);
    }


    public PosUtil(BlockPos blockPos){
        this.x = blockPos.getX();
        this.y = blockPos.getY();
        this.z = blockPos.getZ();
    }

    public PosUtil(double i, double j, double k) {
        this.x = i;
        this.y = j;
        this.z = k;
    }

    public PosUtil(Entity entity) {
        this.x = entity.position().x;
        this.y = entity.position().y;
        this.z = entity.position().z;
    }

    public PosUtil(String s) {
        String[] scoord = s.split("/");
        this.x = Double.parseDouble(scoord[0]);
        this.y = Double.parseDouble(scoord[1]);
        this.z = Double.parseDouble(scoord[2]);
    }


    public Block getBlock(Level level) {
        return level.getBlockState(this.getBlockPos()).getBlock();
    }

    /*public BlockState getBlockActualState(Level level) {
        Block block = this.getBlock(level);
        BlockPos pos = this.getBlockPos();
        BlockState state = level.getBlockState(pos);
        return block.getStateForPlacement(state, level, pos);
    }

     */

    public BlockPos getBlockPos() {
        return new BlockPos((((int) this.x)), (int) this.y, (int) this.z);
    }
    public String getChunkString() {
        return this.getChunkX() + "/" + this.getChunkZ();
    }

    public int getChunkX() {
        return this.getintX() >> 4;
    }

    public int getChunkZ() {
        return this.getintZ() >> 4;
    }
    public PosUtil getAbove() {
        return new PosUtil(this.x, this.y + 1.0, this.z);
    }

    public PosUtil getBelow() {
        return new PosUtil(this.x, this.y - 1.0, this.z);
    }
    public PosUtil getEast() {
        return new PosUtil(this.x + 1.0, this.y, this.z);
    }
    public PosUtil getSouth() {
        return new PosUtil(this.x, this.y, this.z + 1.0);
    }
    public PosUtil getWest() {
        return new PosUtil(this.x - 1.0, this.y, this.z);
    }

    public PosUtil getNorth() {
        return new PosUtil(this.x, this.y, this.z - 1.0);
    }

    public List<PosUtil> getNeighbors() {
        return Arrays.asList(this.getAbove(), this.getBelow(), this.getNorth(), this.getEast(), this.getSouth(), this.getWest());
    }
    public String getIntString() {
        return this.getintX() + "/" + this.getintY() + "/" + this.getintZ();
    }


    public int getintX() {
        return (int) Math.floor(this.x);
    }

    public int getintY() {
        return (int) Math.floor(this.y);
    }

    public int getintZ() {
        return (int) Math.floor(this.z);
    }

    public String getPathString() {
        return this.getintX() + "_" + this.getintY() + "_" + this.getintZ();
    }
    public PosUtil getRelative(double dx, double dy, double dz) {
        return new PosUtil(this.x + dx, this.y + dy, this.z + dz);
    }

    public SignBlockEntity getSign(Level level) {
        BlockEntity ent = level.getBlockEntity(this.getBlockPos());
        return ent != null && ent instanceof SignBlockEntity ? (SignBlockEntity) ent : null;
    }
    public BlockEntity getBlockEntity(Level level) {
        return level.getBlockEntity(this.getBlockPos());
    }
    public double horizontalDistanceTo(BlockPos bp) {
        return this.horizontalDistanceTo(bp.getX(), bp.getZ());
    }

    public double horizontalDistanceTo(double px, double pz) {
        double d = px - this.x;
        double d2 = pz - this.z;
        return Math.sqrt(d * d + d2 * d2);
    }

    public double horizontalDistanceTo(Entity entity) {
        return this.horizontalDistanceTo(entity.position().x, entity.position().z);
    }

    /*public double horizontalDistanceTo(PathPoint p) {
        return p == null ? 0.0 : this.horizontalDistanceTo(p.x, p.z);
    }

     */

    public double horizontalDistanceTo(PosUtil p) {
        return p == null ? 0.0 : this.horizontalDistanceTo(p.x, p.z);
    }

    public double horizontalDistanceToSquared(double px, double pz) {
        double d = px - this.x;
        double d2 = pz - this.z;
        return d * d + d2 * d2;
    }

    public double horizontalDistanceToSquared(Entity entity) {
        return this.horizontalDistanceToSquared(entity.position().x, entity.position().z);
    }

    public double horizontalDistanceToSquared(PosUtil p) {
        return this.horizontalDistanceToSquared(p.x, p.z);
    }

    public boolean isBlockPassable(Level level) {
        return !this.getBlock(level).defaultBlockState().isSolid();
    }

    /*public boolean sameBlock(PathPoint p) {
        return p == null ? false : this.getiX() == p.x && this.getiY() == p.y && this.getiZ() == p.z;
    }

     */

    public boolean sameBlock(PosUtil p) {
        return p == null ? false : this.getintX() == p.getintX() && this.getintY() == p.getintY() && this.getintZ() == p.getintZ();
    }

    /*public void setBlock(Level level, Block block, int meta, boolean notify, boolean sound) {
        WorldUtilities.setBlockAndMetadata(level, this, block, meta, notify, sound);
    }

     */

    public void setBlockState(Level level, BlockState state) {
        level.setBlock(this.getBlockPos(), state, 0);
    }

    public int squareRadiusDistance(PosUtil p) {
        return (int)Math.max(Math.abs(this.x - p.x), Math.abs(this.z - p.z));
    }

    @Override
    public String toString() {
        return Math.round(this.x * 100.0) / 100L + "/" + Math.round(this.y * 100.0) / 100L + "/" + Math.round(this.z * 100.0) / 100L;
    }
    public void write(CompoundTag compoundTag, String string) {
        compoundTag.putDouble(string + "_xCoord", this.x);
        compoundTag.putDouble(string + "_yCoord", this.y);
        compoundTag.putDouble(string + "_zCoord", this.z);
    }



}
