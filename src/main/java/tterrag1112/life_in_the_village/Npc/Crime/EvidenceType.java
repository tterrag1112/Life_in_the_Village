package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;

/** Spec line 83. */
public enum EvidenceType {
    WITNESS_TESTIMONY,
    PHYSICAL_ITEM,
    DOCUMENT,
    FORENSIC,
    CIRCUMSTANTIAL;

    public static final Codec<EvidenceType> CODEC =
            Codec.STRING.xmap(EvidenceType::valueOf, EvidenceType::name);
}
