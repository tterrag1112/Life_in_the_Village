package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

/**
 * Lightweight string handle for a sector declaration. Decouples
 * intention declaration from sector lookup — intentions reference
 * sectors by ID, the planner registers sectors and the SlotEmitter
 * looks them up at emission time. See spec §3.
 */
public record SectorRef(String id) {
    public static SectorRef of(String id) { return new SectorRef(id); }
}
