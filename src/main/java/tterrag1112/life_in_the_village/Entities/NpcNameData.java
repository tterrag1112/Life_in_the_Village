package tterrag1112.life_in_the_village.Entities;

import java.util.List;

public class NpcNameData {

    private final String culture;
    private final List<String> maleNames;
    private final List<String> femaleNames;
    private final List<String> surnames;

    public NpcNameData(String culture, List<String> maleNames,
                       List<String> femaleNames, List<String> surnames) {
        this.culture = culture;
        this.maleNames = List.copyOf(maleNames);
        this.femaleNames = List.copyOf(femaleNames);
        this.surnames = List.copyOf(surnames);
    }

    public String getCulture()          { return culture; }
    public List<String> getMaleNames()  { return maleNames; }
    public List<String> getFemaleNames(){ return femaleNames; }
    public List<String> getSurnames()   { return surnames; }

    public String randomMaleName(net.minecraft.util.RandomSource random) {
        if (maleNames.isEmpty()) return "John";
        return maleNames.get(random.nextInt(maleNames.size()));
    }

    public String randomFemaleName(net.minecraft.util.RandomSource random) {
        if (femaleNames.isEmpty()) return "Jane";
        return femaleNames.get(random.nextInt(femaleNames.size()));
    }

    public String randomSurname(net.minecraft.util.RandomSource random) {
        if (surnames.isEmpty()) return "Smith";
        return surnames.get(random.nextInt(surnames.size()));
    }

    public String randomFirstName(boolean isMale,
                                  net.minecraft.util.RandomSource random) {
        return isMale ? randomMaleName(random) : randomFemaleName(random);
    }
}
