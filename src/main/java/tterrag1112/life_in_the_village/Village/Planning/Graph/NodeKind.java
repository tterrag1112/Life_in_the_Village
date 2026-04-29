package tterrag1112.life_in_the_village.Village.Planning.Graph;

public enum NodeKind {
    JUNCTION,        // 3+ edges meet
    GATE,            // village boundary, road exits here
    TERMINUS,        // dead end (spur tip)
    FOCAL,           // plaza centre, town hall pad, etc.
    CASTLE_ANCHOR,   // kingdom rework
    MANOR_ANCHOR,    // kingdom rework
    BRIDGE_HEAD,     // node where a road meets a Bridge primitive
    SHORE_HEAD       // node where a road terminates at water without a bridge
}
