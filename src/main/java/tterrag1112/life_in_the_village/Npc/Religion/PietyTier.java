package tterrag1112.life_in_the_village.Npc.Religion;

/** Spec line 163. */
public enum PietyTier {
    UNAFFILIATED, FAITHFUL, DEVOUT, PIOUS;

    public String displayName() {
        return switch (this) {
            case UNAFFILIATED -> "Unaffiliated";
            case FAITHFUL     -> "Faithful";
            case DEVOUT       -> "Devout";
            case PIOUS        -> "Pious";
        };
    }
}
