package tterrag1112.life_in_the_village.Profession;

public enum ProfessionLevel {
    APPRENTICE,
    JOURNEYMAN,
    EXPERT,
    ARTISAN,
    MASTER,
    GRAND_MASTER;


    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
