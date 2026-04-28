package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket;
import tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket.BuildingEntry;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Doc 15 §"Player-requested repaint" — server-side gatherer for the
 * right-click → repaint UI flow. Called from {@code
 * NpcInteractionHandler} when a player plain-right-clicks a builder
 * NPC; produces an {@link OpenRepaintScreenPacket} listing every
 * repaint-allowed building in the builder's village and sends it
 * to the player.
 */
public final class RepaintRequestDispatch {

    private RepaintRequestDispatch() {}

    public static InteractionResult handle(TownspersonMob npc,
                                           ServerPlayer player,
                                           ServerLevel level) {
        Optional<Village> villageOpt = npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name));
        if (villageOpt.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "[" + npc.getNpcName() + "] I'm not assigned to a village yet."), false);
            return InteractionResult.SUCCESS;
        }
        Village village = villageOpt.get();
        VillageSavedData data = VillageSavedData.get(level);

        List<BuildingEntry> entries = new ArrayList<>();
        for (UUID id : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(id);
            if (bOpt.isEmpty()) continue;
            Building b = bOpt.get();
            if (!RepaintAuthorization.isRepaintAllowed(level, b, player)) continue;

            int slotsMask = colorSlotsMaskFor(b);
            entries.add(new BuildingEntry(
                    b.getId(),
                    b.getName(),
                    b.getType().name(),
                    b.getVariantId() != null
                            ? b.getVariantId()
                            : BuildingVariant.defaultVariantId(b.getType()),
                    slotsMask,
                    asByte(b.getPrimaryColor()),
                    asByte(b.getAccentColor()),
                    asByte(b.getRoofColor()),
                    b.getShape().getWidth(),
                    b.getShape().getLength(),
                    /* hasJob: */ hasPendingJobFor(level, b.getId())));
        }

        if (entries.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "[" + npc.getNpcName() + "] No buildings here are yours to repaint right now."),
                    false);
            return InteractionResult.SUCCESS;
        }

        PacketDistributor.sendToPlayer(player, new OpenRepaintScreenPacket(
                npc.getUUID(),
                npc.getNpcName(),
                village.getName(),
                entries));
        return InteractionResult.SUCCESS;
    }

    /** Bitmask over {@link ColorSlot#ordinal()} of the variant's
     *  declared {@code colorSlots}. Defaults to PRIMARY-only when
     *  the variant isn't registered (matches the synthetic default). */
    private static int colorSlotsMaskFor(Building b) {
        var variantOpt = VariantRegistry.INSTANCE
                .resolve(b.getType(), b.getVariantId());
        java.util.Set<ColorSlot> slots = variantOpt
                .map(BuildingVariant::colorSlots)
                .orElse(java.util.EnumSet.of(ColorSlot.PRIMARY));
        int mask = 0;
        for (ColorSlot s : slots) mask |= (1 << s.ordinal());
        return mask;
    }

    /**
     * Doc 15: "One repaint job per building at a time". A job is in
     * progress when any builder NPC in the level carries a
     * {@link RepaintJob} whose {@code buildingId} matches.
     */
    private static boolean hasPendingJobFor(ServerLevel level, UUID buildingId) {
        for (var ent : level.getAllEntities()) {
            if (ent instanceof TownspersonMob npc) {
                RepaintJob j = npc.getRepaintJob();
                if (j != null && buildingId.equals(j.buildingId())
                        && j.state() != RepaintJob.State.COMPLETE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte asByte(DyeColor c) {
        return c == null ? -1 : (byte) c.ordinal();
    }
}
