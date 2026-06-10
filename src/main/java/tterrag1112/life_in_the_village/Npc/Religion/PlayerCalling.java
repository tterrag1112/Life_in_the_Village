package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.FavourAct;

/**
 * Divine Layer V3 — a player's current <b>divine calling</b>: a light, tracked
 * sacred task a {@link DivineVision} laid on them, fulfilled by an existing
 * religious act (the V1 favour hooks). One active calling per player at a time;
 * persisted on {@code RiteSavedData} so it survives a relog. Fulfilment grants
 * bonus favour + a lore vision (see {@link DivineVision#onFavourAct}).
 *
 * @param religionId  the calling deity's faith (the favour pool rewarded)
 * @param act         the {@link FavourAct} that fulfils it (OFFERING / TITHE /
 *                    ATTEND_RITE / COMMISSION_RITE — acts the player can already do)
 * @param issuedTick  when it was laid (for display / future expiry)
 */
public record PlayerCalling(String religionId, FavourAct act, long issuedTick) {

    public static final Codec<PlayerCalling> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("religionId").forGetter(PlayerCalling::religionId),
            Codec.STRING.xmap(FavourAct::valueOf, FavourAct::name)
                    .fieldOf("act").forGetter(PlayerCalling::act),
            Codec.LONG.optionalFieldOf("issuedTick", 0L).forGetter(PlayerCalling::issuedTick)
    ).apply(i, PlayerCalling::new));

    /** A short player-facing description of the task. */
    public String describe() {
        return switch (act) {
            case OFFERING        -> "Leave an offering at the temple.";
            case TITHE           -> "Tithe to your temple.";
            case ATTEND_RITE     -> "Attend a rite of your faith.";
            case COMMISSION_RITE -> "Commission a rite from a priest.";
            case PILGRIMAGE      -> "Undertake a pilgrimage.";
            case VIRTUE          -> "Live your faith's virtue.";
            case PRAYER          -> "Pray for a saint's intercession.";
        };
    }
}
