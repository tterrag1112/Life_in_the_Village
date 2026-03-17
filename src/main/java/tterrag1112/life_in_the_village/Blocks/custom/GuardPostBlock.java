package tterrag1112.life_in_the_village.Blocks.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public class GuardPostBlock extends Block {
    public GuardPostBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel) {
            registerPost(serverLevel, pos);
        }
    }




    private void registerPost(ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        // Find which village this post belongs to by checking bounds
        Optional<Village> village = data.getVillageAt(pos);
        village.ifPresent(v -> {
            v.addGuardPost(pos);
            data.setDirty();
            System.out.println("Guard post registered at " + pos + " in " + v.getName());
        });
    }

    private void unregisterPost(ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        data.getAllVillages().forEach(v -> {
            v.removeGuardPost(pos);
        });
        data.setDirty();
    }
}

