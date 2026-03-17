package tterrag1112.life_in_the_village.Entities.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;

import java.util.List;

public class TownspersonModel<S extends TownspersonRenderer.TownspersonRenderState> extends HumanoidModel<TownspersonRenderer.TownspersonRenderState> {


    public TownspersonModel(ModelPart root) {
        super(root);

    }

    public static LayerDefinition createBodyLayer(CubeDeformation cubeDeformation) {
        MeshDefinition meshdefinition = HumanoidModel.createMesh(cubeDeformation, 0.0F);
        PartDefinition partdefinition = meshdefinition.getRoot();
        partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, cubeDeformation), PartPose.offset(5.0F, 2.0F, 0.0F));
        partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, cubeDeformation), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    public void setupAnim(TownspersonRenderer.TownspersonRenderState renderState) {
        super.setupAnim(renderState);
        if (renderState.leftArmPose == ArmPose.THROW_TRIDENT) {
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - 3.1415927F;
            this.leftArm.yRot = 0.0F;
        }

        if (renderState.rightArmPose == ArmPose.THROW_TRIDENT) {
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - 3.1415927F;
            this.rightArm.yRot = 0.0F;
        }

        float f = renderState.swimAmount;
        if (f > 0.0F) {
            this.rightArm.xRot = Mth.rotLerpRad(f, this.rightArm.xRot, -2.5132742F) + f * 0.35F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.leftArm.xRot = Mth.rotLerpRad(f, this.leftArm.xRot, -2.5132742F) - f * 0.35F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.rightArm.zRot = Mth.rotLerpRad(f, this.rightArm.zRot, -0.15F);
            this.leftArm.zRot = Mth.rotLerpRad(f, this.leftArm.zRot, 0.15F);
            this.leftLeg.xRot -= f * 0.55F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.rightLeg.xRot += f * 0.55F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.head.xRot = 0.0F;
        }

    }
}
