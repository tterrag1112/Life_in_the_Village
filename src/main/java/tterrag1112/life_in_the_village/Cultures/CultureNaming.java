package tterrag1112.life_in_the_village.Cultures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Phase 5 doc 31 — naming bundle on the {@link Culture} record. v1
 * ships a placeholder shape so the existing
 * {@code NpcNameRegistry} can keep generating from its bundled
 * pools; Phase 6 swaps in per-culture name lists.
 *
 * @param firstNamePool  descriptor for the first-name pool (resource
 *                       location key resolved by NpcNameRegistry)
 * @param surnamePool    descriptor for the surname pool
 * @param toponymPool    descriptor used for village naming
 */
public record CultureNaming(
        String firstNamePool,
        String surnamePool,
        String toponymPool,
        List<String> commonHonorifics
) {
    public CultureNaming {
        if (firstNamePool == null) firstNamePool = "default";
        if (surnamePool   == null) surnamePool   = "default";
        if (toponymPool   == null) toponymPool   = "default";
        commonHonorifics = List.copyOf(commonHonorifics != null ? commonHonorifics : List.of());
    }

    public static final CultureNaming DEFAULT = new CultureNaming(
            "default", "default", "default", List.of());

    public static final Codec<CultureNaming> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("firstNamePool", "default").forGetter(CultureNaming::firstNamePool),
            Codec.STRING.optionalFieldOf("surnamePool",   "default").forGetter(CultureNaming::surnamePool),
            Codec.STRING.optionalFieldOf("toponymPool",   "default").forGetter(CultureNaming::toponymPool),
            Codec.STRING.listOf().optionalFieldOf("commonHonorifics", List.of())
                    .forGetter(CultureNaming::commonHonorifics)
    ).apply(i, CultureNaming::new));
}
