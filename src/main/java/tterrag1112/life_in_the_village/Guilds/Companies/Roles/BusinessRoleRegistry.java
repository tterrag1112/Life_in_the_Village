package tterrag1112.life_in_the_village.Guilds.Companies.Roles;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Office.OfficePower;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 6.3.3.c.3 — static registry of business-internal roles.
 *
 * <p>The two roles registered in 6.3.3.c absorb the legacy
 * {@code BUSINESS_FOREMAN} and {@code BUSINESS_BOOKKEEPER} offices
 * (dropped from {@code OfficeRegistry}). Future content packs add
 * more by registering at mod init.
 */
public final class BusinessRoleRegistry {

    private BusinessRoleRegistry() {}

    private static final Map<Identifier, BusinessRoleDefinition> BY_ID = new LinkedHashMap<>();

    private static BusinessRoleDefinition register(String path,
                                                   String displayName,
                                                   Set<OfficePower> powers) {
        Identifier id = Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
        BusinessRoleDefinition def = new BusinessRoleDefinition(
                id, Component.literal(displayName), powers, 1);
        BY_ID.put(id, def);
        return def;
    }

    public static final BusinessRoleDefinition FOREMAN = register(
            "business/foreman", "Foreman",
            Set.of(OfficePower.COMMAND_CITIZENS));

    public static final BusinessRoleDefinition BOOKKEEPER = register(
            "business/bookkeeper", "Bookkeeper",
            Set.of(OfficePower.VIEW_BUDGET, OfficePower.ACCESS_TREASURY));

    public static Optional<BusinessRoleDefinition> byId(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * Resolves a legacy office-id string to its new BusinessRole id.
     * Used by Business.fromCodec's one-shot migration.
     */
    public static Optional<Identifier> fromLegacyOfficeId(String officeId) {
        if (officeId == null) return Optional.empty();
        return switch (officeId) {
            case "business_foreman", "company_foreman"       -> Optional.of(FOREMAN.id());
            case "business_bookkeeper", "company_bookkeeper" -> Optional.of(BOOKKEEPER.id());
            default -> Optional.empty();
        };
    }
}
