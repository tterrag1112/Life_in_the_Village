package tterrag1112.life_in_the_village.Entities;

public enum LifeStage {
    CHILD(0, 12),
    TEEN(13, 17),
    ADULT(18, 60),
    ELDERLY(61, Integer.MAX_VALUE);

    public final int minAge;
    public final int maxAge;

    LifeStage(int minAge, int maxAge) {
        this.minAge = minAge;
        this.maxAge = maxAge;
    }

    public static LifeStage fromAge(int age) {
        for (LifeStage stage : values()) {
            if (age >= stage.minAge && age <= stage.maxAge) return stage;
        }
        return ADULT;
    }

    public boolean canWork()   { return this == ADULT || this == TEEN; }
    public boolean canCourt()  { return this == ADULT; }
    public boolean isDependent() { return this == CHILD; }
}
