// ============================================================
// FILE: src/main/java/tterrag1112/life_in_the_village/Guilds/Companies/SupplyContract.java
// ============================================================
package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * A recurring supply agreement between two player-owned companies.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>The <em>buyer</em> proposes the contract via the management screen.</li>
 *   <li>The <em>supplier</em> accepts (or declines) via their screen.</li>
 *   <li>Every payroll cycle ({@code CompanySavedData.tickPayroll}) the
 *       contract is checked: if the supplier has {@code weeklyQuantity} of
 *       {@code itemId} in storage, the goods are transferred and
 *       {@code bronzePricePerUnit × weeklyQuantity} is debited from the
 *       buyer's treasury and credited to the supplier.</li>
 *   <li>Three consecutive missed deliveries set status to {@code BREACHED};
 *       the owning players are notified.</li>
 * </ol>
 */
public record SupplyContract(
        UUID           contractId,
        UUID           buyerCompanyId,
        UUID           supplierCompanyId,
        String         itemId,
        int            weeklyQuantity,
        long           bronzePricePerUnit,
        ContractStatus status,
        int            missedDeliveries   // 0–3 before BREACH
) {

    public enum ContractStatus {
        PENDING,    // supplier has not yet accepted
        ACTIVE,     // running normally
        SUSPENDED,  // paused by either party — no transfers, no breach counter
        BREACHED,   // 3 consecutive misses — no transfers until renegotiated
        CANCELLED;  // permanently closed

        public static final Codec<ContractStatus> CODEC =
                Codec.STRING.xmap(ContractStatus::valueOf,
                        ContractStatus::name);
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<SupplyContract> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("contractId")
                            .forGetter(SupplyContract::contractId),
                    UUIDUtil.CODEC.fieldOf("buyerCompanyId")
                            .forGetter(SupplyContract::buyerCompanyId),
                    UUIDUtil.CODEC.fieldOf("supplierCompanyId")
                            .forGetter(SupplyContract::supplierCompanyId),
                    Codec.STRING.fieldOf("itemId")
                            .forGetter(SupplyContract::itemId),
                    Codec.INT.fieldOf("weeklyQuantity")
                            .forGetter(SupplyContract::weeklyQuantity),
                    Codec.LONG.fieldOf("bronzePricePerUnit")
                            .forGetter(SupplyContract::bronzePricePerUnit),
                    ContractStatus.CODEC.fieldOf("status")
                            .forGetter(SupplyContract::status),
                    Codec.INT.optionalFieldOf("missedDeliveries", 0)
                            .forGetter(SupplyContract::missedDeliveries)
            ).apply(i, SupplyContract::new));

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static SupplyContract propose(UUID buyerCompanyId,
                                         UUID supplierCompanyId,
                                         String itemId,
                                         int weeklyQuantity,
                                         long bronzePricePerUnit) {
        return new SupplyContract(
                UUID.randomUUID(),
                buyerCompanyId,
                supplierCompanyId,
                itemId,
                weeklyQuantity,
                bronzePricePerUnit,
                ContractStatus.PENDING,
                0);
    }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    public long totalWeeklyCost() {
        return bronzePricePerUnit * weeklyQuantity;
    }

    public boolean isActive() {
        return status == ContractStatus.ACTIVE;
    }

    public boolean isPending() {
        return status == ContractStatus.PENDING;
    }

    // -------------------------------------------------------------------------
    // Wither-style updaters (records are immutable)
    // -------------------------------------------------------------------------

    public SupplyContract withStatus(ContractStatus newStatus) {
        return new SupplyContract(contractId, buyerCompanyId, supplierCompanyId,
                itemId, weeklyQuantity, bronzePricePerUnit, newStatus, missedDeliveries);
    }

    public SupplyContract withMissedDeliveries(int missed) {
        return new SupplyContract(contractId, buyerCompanyId, supplierCompanyId,
                itemId, weeklyQuantity, bronzePricePerUnit, status, missed);
    }
}