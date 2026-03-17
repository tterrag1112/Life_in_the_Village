package tterrag1112.life_in_the_village.Kingdom;

public enum KingdomLaw {
    OPEN_BORDERS,        // other kingdoms can travel freely
    TRADE_TARIFFS,       // tax on merchant trades with outsiders
    CONSCRIPTION,        // guards automatically recruited
    PRICE_CONTROLS,      // merchant prices fixed by kingdom
    PROPERTY_RIGHTS,     // buildings can't be claimed by outsiders
    FREE_TRADE,         // no restrictions on merchant activity
    KINGS_PEACE;

    public String getDescription() {
        return switch (this) {
            case OPEN_BORDERS    -> "Citizens of other kingdoms may travel freely";
            case TRADE_TARIFFS   -> "Outsiders pay extra on trades";
            case CONSCRIPTION    -> "Able-bodied citizens may be called to guard duty";
            case PRICE_CONTROLS  -> "Merchant prices are regulated by the crown";
            case PROPERTY_RIGHTS -> "Only kingdom citizens may own buildings";
            case FREE_TRADE      -> "All trade restrictions are lifted";
            case KINGS_PEACE -> "Violence of any kind is forbidden amongs citizens";
        };
    }
}