package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.serialization.Codec;

/**
 * What an NPC actually does once they've reached the hobby
 * {@link HobbyLocation}. Each value drives a small behaviour clip in
 * {@link HobbyGoal#performTick}: held item, look pose, occasional
 * gesture. Spec line 200 calls for animation reuse only — no new
 * animation assets in v1.
 *
 * <p>Spec: {@code docs/npc_redesign/14-hobby-activities.md} (line 58).</p>
 */
public enum HobbyActivity {
    SIT_AND_READ,
    SIT_AND_CARVE,
    CARD_GAMES,
    DRINK_AND_TALK,
    FISH,
    GARDEN,
    TEND_FLOWERS,
    PRAY,
    MEDITATE,
    SWORD_PRACTICE,
    ARCHERY_PRACTICE,
    WALK,
    COOK,
    WRITE_LETTER,
    COPY_BOOK,
    TELL_STORY,
    SHOP_AIMLESSLY,
    VISIT_FRIEND,
    VISIT_GRAVE;

    public static final Codec<HobbyActivity> CODEC =
            Codec.STRING.xmap(HobbyActivity::valueOf, HobbyActivity::name);
}
