package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;

/**
 * Phase 6.3.3.c.1 — three-tier employment hierarchy orthogonal to
 * {@link Business.WorkerRole} (the task category).
 *
 * <p>WorkerRole says "PRODUCER vs SELLER vs COURIER" — what the
 * employee does. EmploymentTier says "APPRENTICE vs JOURNEYMAN vs
 * MASTER" — how skilled they are. Both apply to every worker; the
 * pair drives wage multiplication, promotion eligibility, and content-
 * pack hooks (e.g. apprenticeship graduation flips a worker from
 * APPRENTICE to JOURNEYMAN).
 */
public enum EmploymentTier {
    APPRENTICE("Apprentice", 0.5f),
    JOURNEYMAN("Journeyman", 1.0f),
    MASTER("Master", 1.5f);

    private final String displayName;
    private final float wageMultiplier;

    EmploymentTier(String displayName, float wageMultiplier) {
        this.displayName = displayName;
        this.wageMultiplier = wageMultiplier;
    }

    public Component displayName() { return Component.literal(displayName); }
    public float wageMultiplier()  { return wageMultiplier; }

    public static final Codec<EmploymentTier> CODEC =
            Codec.STRING.xmap(EmploymentTier::valueOf, EmploymentTier::name);
}
