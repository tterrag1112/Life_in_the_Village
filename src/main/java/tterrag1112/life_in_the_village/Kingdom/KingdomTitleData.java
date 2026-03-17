package tterrag1112.life_in_the_village.Kingdom;

import java.util.Map;

public class KingdomTitleData {

    private final String culture;
    private final Map<String, String> titles;

    public KingdomTitleData(String culture, Map<String, String> titles) {
        this.culture = culture;
        this.titles  = Map.copyOf(titles);
    }

    public String getCulture() { return culture; }

    public String getTitle(String key) {
        return titles.getOrDefault(key, key);
    }

    public String getRulerTitle(boolean isMale) {
        return isMale ? getTitle("ruler_male") : getTitle("ruler_female");
    }

    public String getLordTitle(boolean isMale) {
        return isMale ? getTitle("lord_male") : getTitle("lord_female");
    }

    public String getKnightTitle(boolean isMale) {
        return isMale ? getTitle("knight_male") : getTitle("knight_female");
    }
}