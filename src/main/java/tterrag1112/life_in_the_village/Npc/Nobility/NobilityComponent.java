package tterrag1112.life_in_the_village.Npc.Nobility;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.2a — kingdom-tier nobility overlay on a
 * {@code TownspersonMob}. Holds the noble's house membership,
 * culture-keyed nobility rank, and prestige scalar. Inert at
 * D3.2a close: D3.2b reads + writes these fields to drive
 * succession, fealty, marriage, and dynasty-tree mechanics.
 *
 * <h3>Component pattern</h3>
 * Mirrors {@code SkillComponent} / {@code TraitVector} —
 * codec-driven save/load via the {@code TownspersonMob}'s
 * {@code addAdditionalSaveData} / {@code readAdditionalSaveData}.
 * Default-constructed instance carries the "common citizen" baseline:
 * no house, rank index 0 (the COMMONER slot in every culture's
 * rank list per {@code CultureKingdomDefaults.nobilityRanks}),
 * prestige 0.
 *
 * <h3>Naming clarification</h3>
 * The pre-existing {@code FamilyComponent.houseId} is the UUID of
 * the {@code BuildingType.HOUSE} the NPC physically lives in
 * (consumed by {@code HouseholdManager}). This component's
 * {@link #dynastyHouseId} is a DIFFERENT concept — the UUID of a
 * {@code Kingdom.Houses.House} record (kingdom-tier noble
 * dynasty). The two never share a value.
 */
public class NobilityComponent {

    private static final String SAVE_KEY = "nobility";

    // ── State ─────────────────────────────────────────────────────────────

    /**
     * UUID of the noble {@code House} the NPC belongs to, or null if
     * unaffiliated. Set when an NPC founds or marries into a house;
     * cleared on annulment / disinheritance (D3.2b territory).
     */
    private UUID dynastyHouseId = null;

    /**
     * Index into the NPC's culture's
     * {@code CultureKingdomDefaults.nobilityRanks} list. 0 = the
     * culture's lowest rank (typically a generic commoner / yeoman
     * label); the rank list's last entry is the highest. Names are
     * resolved via {@link NobilityRanks#nameFor(String, int)}.
     *
     * <p>Stays 0 for non-monarchy cultures (which declare an empty
     * rank list); meaningful only when the NPC's culture has ranks.
     */
    private int rankIndex = 0;

    /**
     * 0..100 prestige scalar. D3.2a stores; D3.2b accumulates from
     * achievements (office grants, marriage above rank, estate
     * income surplus, completed quests). The founding threshold
     * (D3.2b) is {@link #FOUNDING_THRESHOLD}.
     */
    private int prestige = 0;

    /**
     * Track D3.2a — recommended threshold for founding a noble
     * house. Phase 2.1 ships the constant; D3.2b's behaviour
     * subsystems read it to gate house creation.
     */
    public static final int FOUNDING_THRESHOLD = 30;

    // ── Queries ─────────────────────────────────────────────────────────

    public Optional<UUID> getDynastyHouseId() {
        return Optional.ofNullable(dynastyHouseId);
    }

    public boolean hasDynastyHouse() { return dynastyHouseId != null; }
    public int  getRankIndex()       { return rankIndex; }
    public int  getPrestige()        { return prestige; }

    /** True when prestige meets the founding threshold AND the NPC isn't already in a house. */
    public boolean isFoundingEligible() {
        return dynastyHouseId == null && prestige >= FOUNDING_THRESHOLD;
    }

    // ── Mutations ────────────────────────────────────────────────────────

    public void setDynastyHouseId(UUID id) { this.dynastyHouseId = id; }
    public void clearDynastyHouseId()      { this.dynastyHouseId = null; }

    public void setRankIndex(int index)    { this.rankIndex = Math.max(0, index); }

    public void setPrestige(int v)         { this.prestige = clampPrestige(v); }
    public int  addPrestige(int delta)     {
        this.prestige = clampPrestige(this.prestige + delta);
        return this.prestige;
    }

    private static int clampPrestige(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NobilityComponent loaded = read.get();
        this.dynastyHouseId = loaded.dynastyHouseId;
        this.rankIndex      = loaded.rankIndex;
        this.prestige       = loaded.prestige;
        return true;
    }

    // ── Codec ───────────────────────────────────────────────────────────

    public static final Codec<NobilityComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("dynastyHouseId")
                    .forGetter(c -> Optional.ofNullable(c.dynastyHouseId)),
            Codec.INT.optionalFieldOf("rankIndex", 0)
                    .forGetter(NobilityComponent::getRankIndex),
            Codec.INT.optionalFieldOf("prestige", 0)
                    .forGetter(NobilityComponent::getPrestige)
    ).apply(i, NobilityComponent::fromCodec));

    private static NobilityComponent fromCodec(Optional<UUID> dynastyHouseId,
                                                 int rankIndex, int prestige) {
        NobilityComponent c = new NobilityComponent();
        dynastyHouseId.ifPresent(c::setDynastyHouseId);
        c.setRankIndex(rankIndex);
        c.setPrestige(prestige);
        return c;
    }
}
