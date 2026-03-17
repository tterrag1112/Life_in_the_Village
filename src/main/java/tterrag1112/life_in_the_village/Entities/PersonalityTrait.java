package tterrag1112.life_in_the_village.Entities;

public enum PersonalityTrait {
    HARDWORKING,
    LAZY,
    FRIENDLY,
    GREEDY,
    BRAVE,
    TIMID;

    // How much this trait modifies work speed (multiplier)
    public double workSpeedModifier() {
        return switch (this) {
            case HARDWORKING -> 1.25;
            case LAZY        -> 0.75;
            default          -> 1.0;
        };
    }

    // How much this trait modifies reputation gain per trade
    public int reputationModifier() {
        return switch (this) {
            case FRIENDLY -> 2;
            case GREEDY   -> -1;
            default       -> 0;
        };
    }

    // How much this trait modifies prices (multiplier on coin cost)
    public double priceModifier() {
        return switch (this) {
            case GREEDY   -> 1.25;
            case FRIENDLY -> 0.9;
            default       -> 1.0;
        };
    }


    public boolean conflictsWith(PersonalityTrait other) {
        return switch (this) {
            case HARDWORKING -> other == LAZY;
            case LAZY        -> other == HARDWORKING;
            case FRIENDLY    -> other == GREEDY;
            case GREEDY      -> other == FRIENDLY;
            case BRAVE       -> other == TIMID;
            case TIMID       -> other == BRAVE;
        };
    }
}
