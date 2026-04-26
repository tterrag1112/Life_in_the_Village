package tterrag1112.life_in_the_village.Npc.BusinessFront;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-player snapshot of "what building (if any) is the player currently
 * inside?" Spec line 24.
 *
 * <p>Held by {@link BuildingPresenceTracker}; rebuilt every server tick
 * for every connected player. Session-only — server restart wipes.</p>
 */
public record BuildingPresence(
        UUID playerId,
        @Nullable UUID buildingId,
        long enteredAtTick
) {
}
