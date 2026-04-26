package tterrag1112.life_in_the_village.Npc.BusinessFront;

/**
 * Per-building greeter state. Spec line 80.
 *
 * <ul>
 *   <li>{@link #NONE} — no greeter active. Default.</li>
 *   <li>{@link #GREETER_APPROACHING} — a worker has been assigned and is
 *   walking to the player. Bark fires on transition into this state.</li>
 *   <li>{@link #GREETER_ATTENDING} — greeter has reached the player and
 *   is offering services. Right-click opens the business-front screen.</li>
 *   <li>{@link #CLOSED} — non-work hours; refusal dialogue, mood
 *   penalty on repeated entries.</li>
 * </ul>
 */
public enum BusinessFrontStatus {
    NONE,
    GREETER_APPROACHING,
    GREETER_ATTENDING,
    CLOSED
}
