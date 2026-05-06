package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;

/**
 * Declarative plaza specification. The planner translates each
 * declaration to a {@code PlazaSpec} and calls
 * {@code PlazaGenerator.generate} during realisation. The
 * {@code civicSector} reference tells the SlotEmitter which sector
 * PRIME_CIVIC / SECONDARY_CIVIC slots produced by the
 * {@link Anchor.PlazaPerimeter} resolver should be attributed to
 * (Phase B).
 */
public record PlazaDeclaration(
        BlockPos center,
        int targetRadius,
        PlazaShape shape,
        PlazaPurpose purpose,
        SectorRef civicSector
) {}
