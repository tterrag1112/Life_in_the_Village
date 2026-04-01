package tterrag1112.life_in_the_village.Kingdom.Castle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a checklist of which NBT design files are needed for a given
 * style and which already exist on disk.
 *
 * Printed by /castle design checklist <style>.
 */
public class DesignChecklist {

    private static final String RESOURCES_ROOT =
            "src/main/resources/data/life_in_the_village/structures/";

    // Minimum number of variants recommended per type
    private static final int MIN_VARIANTS = 3;

    public record CheckItem(
            DesignType type,
            String styleId,
            int existingCount,
            int recommendedCount,
            boolean complete
    ) {
        public String format() {
            String status = complete ? "§a✔" : "§c✗";
            return String.format("%s §r%s / %s — %d/%d variants",
                    status, type.name().toLowerCase(), styleId,
                    existingCount, recommendedCount);
        }
    }

    /**
     * Build a checklist for all design types for a given style.
     * @param styleId  e.g. "norman", "moorish"
     * @param minVariants  how many variants to recommend (default 3)
     */
    public static List<CheckItem> build(String styleId, int minVariants) {
        List<CheckItem> items = new ArrayList<>();
        for (DesignType type : DesignType.values()) {
            int count = countExisting(type, styleId);
            boolean complete = count >= minVariants;
            items.add(new CheckItem(type, styleId, count, minVariants, complete));
        }
        return items;
    }

    public static List<CheckItem> build(String styleId) {
        return build(styleId, MIN_VARIANTS);
    }

    private static int countExisting(DesignType type, String styleId) {
        int count = 0;
        for (int i = 0; i <= 99; i++) {
            Path path = Paths.get(RESOURCES_ROOT
                    + type.nbtPath(styleId, i) + ".nbt");
            if (Files.exists(path)) count++;
            else if (count > 0) break; // stop at first gap
        }
        return count;
    }
}