package tterrag1112.life_in_the_village.Village.Reputation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Records a single player's standing in a single village.
 * Stored as a list inside {@link tterrag1112.life_in_the_village.Networking.VillageSavedData}.
 *
 * <h3>Score range</h3>
 * -100 (OUTCAST) … 100 (PILLAR).  All modifications are clamped.
 *
 * <h3>Tier transitions</h3>
 * <pre>
 *   OUTCAST   -100 to -51   Guards hostile; merchants refuse trade
 *   STRANGER   -50 to  -1   Unknown or unwelcome; no benefits
 *   NEWCOMER     0 to  24   Default on first visit; neutral
 *   RESIDENT    25 to  49   Owns property or has a job; 5% discount
 *   TRUSTED     50 to  74   Regulary completes tasks; 10% discount
 *   PILLAR      75 to 100   Major contributor; 15% discount + greeted by name
 * </pre>
 */
public class VillageReputation {

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<VillageReputation> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUID_CODEC.fieldOf("playerId")
                            .forGetter(r -> r.playerId),
                    UUID_CODEC.fieldOf("villageId")
                            .forGetter(r -> r.villageId),
                    Codec.INT.fieldOf("score")
                            .forGetter(r -> r.score)
            ).apply(i, VillageReputation::new));

    // -------------------------------------------------------------------------
    // Tier enum
    // -------------------------------------------------------------------------

    public enum Tier {
        OUTCAST (-100, -51, "Outcast",   ChatFormatting.DARK_RED),
        DISTRUSTED(-50, -26, "Distrusted", ChatFormatting.RED),
        STRANGER( -25,  -1, "Stranger",  ChatFormatting.RED),
        NEWCOMER(   0,  24, "Newcomer",  ChatFormatting.GRAY),
        RESIDENT(  25,  49, "Resident",  ChatFormatting.WHITE),
        TRUSTED (  50,  74, "Trusted",   ChatFormatting.GREEN),
        PILLAR  (  75, 100, "Pillar",    ChatFormatting.GOLD);

        public final int minScore;
        public final int maxScore;
        public final String displayName;
        public final ChatFormatting colour;

        Tier(int minScore, int maxScore,
             String displayName, ChatFormatting colour) {
            this.minScore    = minScore;
            this.maxScore    = maxScore;
            this.displayName = displayName;
            this.colour      = colour;
        }

        /** Market price discount fraction (0.0 = no discount). */
        public double getPriceDiscount() {
            return switch (this) {
                case OUTCAST, DISTRUSTED, STRANGER, NEWCOMER -> 0.0;
                case RESIDENT                    -> 0.05;
                case TRUSTED                     -> 0.10;
                case PILLAR                      -> 0.15;
            };
        }

        /**
         * True if guards should treat this player as a hostile threat.
         * Only OUTCAST triggers guard hostility.
         */
        public boolean isGuardHostile() {
            return this == OUTCAST;
        }

        /** True if merchants will refuse to trade with this player. */
        public boolean isMerchantHostile() {
            return this == OUTCAST;
        }

        /** True if job postings are visible without visiting the town hall. */
        public boolean hasRemoteJobAccess() {
            return this == TRUSTED || this == PILLAR;
        }

        public Component toComponent() {
            return Component.literal(displayName).withStyle(colour);
        }
    }

    // -------------------------------------------------------------------------
    // Change reasons — used for logging and event hooks
    // -------------------------------------------------------------------------

    public enum ChangeReason {
        JOB_POSTING_COMPLETE  (  2, "Completed a job posting"),
        TRADE_WITH_MERCHANT   (  1, "Traded with a merchant"),
        OWN_PROPERTY          (  5, "Purchased property"),
        PROPERTY_TAX_PAID     (  1, "Paid weekly property tax"),
        COMPANY_WAGES_PAID    (  3, "Paid business workers"),
        CRAFTING_ORDER_FILLED (  3, "Fulfilled a crafting order"),
        DONATED_TO_VILLAGE    ( 10, "Donated to the village treasury"),
        VILLAGE_EVENT_HELPED  (  5, "Helped during a village event"),
        ROAD_PROPOSAL_COMPLETE(  5, "Completed a road-construction proposal"),

        STOLE_FROM_CONTAINER  (-20, "Stole from village property"),
        ATTACKED_NPC          (-50, "Attacked a villager"),
        FAILED_TO_PAY_TAX     ( -5, "Failed to pay property tax"),
        BOUNTY_ISSUED         (-15, "A bounty was issued against you"),
        TRESPASSED            ( -5, "Trespassed in a restricted area");

        public final int scoreDelta;
        public final String description;

        ChangeReason(int scoreDelta, String description) {
            this.scoreDelta  = scoreDelta;
            this.description = description;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID playerId;
    private final UUID villageId;
    private int score;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public VillageReputation(UUID playerId, UUID villageId, int score) {
        this.playerId  = playerId;
        this.villageId = villageId;
        this.score     = clamp(score);
    }

    /** Creates a fresh reputation record for a player entering a village. */
    public static VillageReputation createNew(UUID playerId, UUID villageId) {
        return new VillageReputation(playerId, villageId, 0);
    }

    // -------------------------------------------------------------------------
    // Score management
    // -------------------------------------------------------------------------

    /**
     * Applies a change reason's score delta to this reputation.
     *
     * @return true if a tier boundary was crossed (caller should notify player)
     */
    public boolean apply(ChangeReason reason) {
        return applyDelta(reason.scoreDelta);
    }

    /**
     * Applies a raw score delta.
     *
     * @return true if a tier boundary was crossed
     */
    public boolean applyDelta(int delta) {
        Tier before = getTier();
        score = clamp(score + delta);
        return getTier() != before;
    }

    public int getScore() { return score; }

    public Tier getTier() {
        for (Tier t : Tier.values()) {
            if (score >= t.minScore && score <= t.maxScore) return t;
        }
        return Tier.NEWCOMER; // fallback, should never happen
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    public UUID getPlayerId()  { return playerId; }
    public UUID getVillageId() { return villageId; }

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Computes the sell price a merchant should charge this player,
     * after applying their tier discount.
     *
     * @param basePrice base sell price in bronze
     */
    public long applyDiscount(long basePrice) {
        double discount = getTier().getPriceDiscount();
        return Math.max(1L, Math.round(basePrice * (1.0 - discount)));
    }

    @Override
    public String toString() {
        return "VillageReputation[player=" + playerId
                + ", village=" + villageId
                + ", score=" + score
                + ", tier=" + getTier().displayName + "]";
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}