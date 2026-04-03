// New file: src/main/java/tterrag1112/life_in_the_village/Village/Agriculture/AnimalProductProcessor.java

package tterrag1112.life_in_the_village.Village.Agriculture;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Processes animal products into higher-value goods.
 *
 * Processing options:
 * - Wool → Dyed wool (various colors, +50% value)
 * - Milk → Cheese (requires aging time, +100% value)
 * - Leather → Tanned leather (+50% value)
 * - Eggs → Supply bakery chain (steady income)
 */
public class AnimalProductProcessor {

    public enum ProcessingType {
        DYE_WOOL(Items.WHITE_WOOL, Items.RED_WOOL, 100, 0L),
        MAKE_CHEESE(Items.MILK_BUCKET, Items.BREAD, 200, 48000L), // Placeholder: bread for cheese
        TAN_LEATHER(Items.LEATHER, Items.LEATHER, 150, 0L),
        SUPPLY_BAKERY(Items.EGG, Items.EGG, 100, 0L);

        private final Item inputItem;
        private final Item outputItem;
        private final int valueIncrease; // Percentage
        private final long processingTime; // Ticks required

        ProcessingType(Item inputItem, Item outputItem, int valueIncrease, long processingTime) {
            this.inputItem = inputItem;
            this.outputItem = outputItem;
            this.valueIncrease = valueIncrease;
            this.processingTime = processingTime;
        }

        public Item getInputItem() { return inputItem; }
        public Item getOutputItem() { return outputItem; }
        public int getValueIncrease() { return valueIncrease; }
        public long getProcessingTime() { return processingTime; }

        public long getProcessedValue(long baseValue) {
            return baseValue * (100 + valueIncrease) / 100;
        }
    }

    // Active processing jobs
    private static class ProcessingJob {
        Item inputItem;
        int quantity;
        ProcessingType type;
        long startTime;
        long finishTime;

        ProcessingJob(Item inputItem, int quantity, ProcessingType type, long startTime) {
            this.inputItem = inputItem;
            this.quantity = quantity;
            this.type = type;
            this.startTime = startTime;
            this.finishTime = startTime + type.getProcessingTime();
        }

        boolean isComplete(long currentTime) {
            return currentTime >= finishTime;
        }

        ItemStack getResult() {
            return new ItemStack(type.getOutputItem(), quantity);
        }
    }

    private final Map<Integer, ProcessingJob> activeJobs = new HashMap<>();
    private int nextJobId = 0;

    /**
     * Start processing animal products
     */
    public int startProcessing(Item item, int quantity, ProcessingType type, long currentTime) {
        if (type.getInputItem() != item) {
            return -1; // Wrong item for this processing type
        }

        ProcessingJob job = new ProcessingJob(item, quantity, type, currentTime);
        int jobId = nextJobId++;
        activeJobs.put(jobId, job);
        return jobId;
    }

    /**
     * Check if processing job is complete
     */
    public boolean isJobComplete(int jobId, long currentTime) {
        ProcessingJob job = activeJobs.get(jobId);
        return job != null && job.isComplete(currentTime);
    }

    /**
     * Collect processed product
     */
    public ItemStack collectProduct(int jobId) {
        ProcessingJob job = activeJobs.remove(jobId);
        return job != null ? job.getResult() : ItemStack.EMPTY;
    }

    /**
     * Get all active jobs
     */
    public Map<Integer, ProcessingJob> getActiveJobs() {
        return activeJobs;
    }
}