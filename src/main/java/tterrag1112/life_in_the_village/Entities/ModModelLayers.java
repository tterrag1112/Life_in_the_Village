package tterrag1112.life_in_the_village.Entities;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class ModModelLayers {
    public static final ModelLayerLocation TOWNSPERSON = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "townsperson"), "main");
}
