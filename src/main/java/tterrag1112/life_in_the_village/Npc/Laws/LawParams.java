package tterrag1112.life_in_the_village.Npc.Laws;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Numeric + string parameters attached to a single
 * {@link VillagePolicy} entry. Spec line 67.
 *
 * <p>Empty maps are valid (most laws are parameter-free). The codec is
 * {@code optionalFieldOf}-friendly so a load that's missing the
 * params node falls back to {@link #empty()}.</p>
 */
public record LawParams(Map<String, Float> numericParams,
                        Map<String, String> stringParams) {

    public LawParams {
        numericParams = numericParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(numericParams));
        stringParams  = stringParams  == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stringParams));
    }

    public static LawParams empty() {
        return new LawParams(Map.of(), Map.of());
    }

    /**
     * Returns a copy populated with this law's defaults — used at enact
     * time when a caller doesn't override every parameter.
     */
    public static LawParams withDefaults(VillageLaw law) {
        Map<String, Float> nums = new LinkedHashMap<>();
        for (VillageLaw.ParamSpec spec : law.paramSpecs()) {
            nums.put(spec.key(), spec.defaultValue());
        }
        return new LawParams(nums, Map.of());
    }

    /** Reads a numeric parameter, falling back to the law's default. */
    public float numeric(VillageLaw law, String key) {
        Float v = numericParams.get(key);
        if (v != null) return v;
        for (VillageLaw.ParamSpec spec : law.paramSpecs()) {
            if (spec.key().equals(key)) return spec.defaultValue();
        }
        return 0f;
    }

    /** Reads a string parameter; empty string when missing. */
    public String string(String key) {
        return stringParams.getOrDefault(key, "");
    }

    /** Returns a new LawParams with the supplied numeric override applied. */
    public LawParams withNumeric(String key, float value) {
        Map<String, Float> next = new LinkedHashMap<>(numericParams);
        next.put(key, value);
        return new LawParams(next, stringParams);
    }

    public static final Codec<LawParams> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT)
                    .optionalFieldOf("numeric", Map.of())
                    .forGetter(LawParams::numericParams),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("string", Map.of())
                    .forGetter(LawParams::stringParams)
    ).apply(i, LawParams::new));
}
