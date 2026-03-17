package tterrag1112.life_in_the_village.Village.Needs;

public enum NeedLevel {
    SURPLUS,    // more than enough
    SATISFIED,  // needs met
    LOW,        // running low — apply debuffs
    CRITICAL;   // near empty — stop non-essential work

    public boolean isDeficient() {
        return this == LOW || this == CRITICAL;
    }

    public boolean isCritical() {
        return this == CRITICAL;
    }

    public static NeedLevel fromRatio(double ratio) {
        if (ratio > 1.5)      return SURPLUS;
        if (ratio >= 1.0)     return SATISFIED;
        if (ratio >= 0.5)     return LOW;
        return CRITICAL;
    }
}
