package tterrag1112.life_in_the_village.Village.Planning.Features;

public record ReservedRegion(PolygonXZ shape,
                             ReservationKind kind,
                             String ownerSectorId) {}
