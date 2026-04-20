package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

import javax.annotation.Nullable;

/**
 * Creates a detached, client-only {@link TownspersonMob} whose appearance
 * mirrors an NPC snapshot. The entity is never ticked or rendered in the
 * world — it exists solely as a render target for the NPC portrait.
 */
public final class ClientNpcPreview {

    private ClientNpcPreview() {}

    /**
     * Builds a preview entity from {@code snap}. Returns {@code null} when
     * the client level is not yet available (e.g. before world join).
     */
    @Nullable
    public static TownspersonMob create(Portrait.AppearanceSnapshot snap) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;

        TownspersonMob preview = new TownspersonMob(ModEntities.TOWNSPERSON.get(), level);
        preview.setNpcName(snap.name());
        preview.setMale(snap.isMale());
        preview.setAge(snap.age());
        preview.setAppearance(snap.skinId(), snap.hairId(), snap.hairColor());

        try {
            preview.setProfession(Profession.valueOf(snap.profession()));
        } catch (IllegalArgumentException ignored) {
            preview.setProfession(Profession.NONE);
        }

        preview.setNoAi(true);
        preview.setSilent(true);
        return preview;
    }
}
