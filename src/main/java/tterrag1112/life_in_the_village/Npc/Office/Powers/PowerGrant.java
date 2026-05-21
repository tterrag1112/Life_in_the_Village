package tterrag1112.life_in_the_village.Npc.Office.Powers;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficePower;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Capability lookup over the office system. Replaces ad-hoc
 * "is this NPC the leader / owner / king" checks scattered across the
 * codebase with a single shared utility:
 *
 * <pre>
 *   if (PowerGrant.hasPower(player.getUUID(), OfficePower.ENACT_LAW, kingdom)) { ... }
 *   PowerGrant.holderOf(OfficePower.ACCESS_TREASURY, village).ifPresent(...);
 * </pre>
 *
 * <p>Spec: {@code 06-office-framework.md} → "Powers and gates". Treats
 * NPC and player UUIDs identically — the {@link OfficeHolding} carries
 * its own player/NPC discriminator.</p>
 */
public final class PowerGrant {

    private PowerGrant() {}

    // ── Power lookup over a single org ─────────────────────────────────────

    /** True when {@code uuid} (NPC or player) holds an office on the org granting {@code power}. */
    public static boolean hasPower(UUID uuid, OfficePower power, OfficeState state) {
        if (uuid == null || power == null || state == null) return false;
        for (OfficeHolding h : state.snapshot().values()) {
            if (!h.isHeldBy(uuid)) continue;
            OfficeDefinition def = OfficeRegistry.get(h.officeId());
            if (def != null && def.powers().contains(power)) return true;
        }
        return false;
    }

    public static boolean hasPower(UUID uuid, OfficePower power, Village v) {
        return v != null && hasPower(uuid, power, v.getOffices());
    }
    public static boolean hasPower(UUID uuid, OfficePower power, Kingdom k) {
        return k != null && hasPower(uuid, power, k.getOffices());
    }
    public static boolean hasPower(UUID uuid, OfficePower power, GuildData g) {
        return g != null && hasPower(uuid, power, g.offices());
    }
    public static boolean hasPower(UUID uuid, OfficePower power, Business c) {
        return c != null && hasPower(uuid, power, c.getOffices());
    }

    /**
     * UUID of the (single) holder of the first office on this state that
     * grants {@code power}. Returns empty when vacant or unheld.
     */
    public static Optional<UUID> holderOf(OfficePower power, OfficeState state) {
        if (power == null || state == null) return Optional.empty();
        for (OfficeHolding h : state.snapshot().values()) {
            if (h.isVacant()) continue;
            OfficeDefinition def = OfficeRegistry.get(h.officeId());
            if (def != null && def.powers().contains(power)) {
                return h.holderUuid();
            }
        }
        return Optional.empty();
    }

    public static Optional<UUID> holderOf(OfficePower power, Village v) {
        return v == null ? Optional.empty() : holderOf(power, v.getOffices());
    }
    public static Optional<UUID> holderOf(OfficePower power, Kingdom k) {
        return k == null ? Optional.empty() : holderOf(power, k.getOffices());
    }
    public static Optional<UUID> holderOf(OfficePower power, GuildData g) {
        return g == null ? Optional.empty() : holderOf(power, g.offices());
    }
    public static Optional<UUID> holderOf(OfficePower power, Business c) {
        return c == null ? Optional.empty() : holderOf(power, c.getOffices());
    }

    // ── Cross-entity walker (server-wide) ──────────────────────────────────

    /** All powers held by {@code uuid} across every loaded org in the level. */
    public static Set<OfficePower> allPowers(UUID uuid, ServerLevel level) {
        Set<OfficePower> out = new LinkedHashSet<>();
        if (uuid == null) return out;
        for (var match : OfficeRegistry.findOfficesHeldBy(uuid, level)) {
            OfficeDefinition def = OfficeRegistry.get(match.holding().officeId());
            if (def != null) out.addAll(def.powers());
        }
        return Collections.unmodifiableSet(out);
    }

    /** All offices currently held by {@code uuid} on the supplied level. */
    public static List<OfficeRegistry.Match> allOffices(UUID uuid, ServerLevel level) {
        if (uuid == null) return List.of();
        return new ArrayList<>(OfficeRegistry.findOfficesHeldBy(uuid, level));
    }
}
