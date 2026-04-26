package tterrag1112.life_in_the_village.Items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Life_in_the_village.MODID);

    public static final DeferredItem<Item> DENIER = ITEMS.registerSimpleItem("denier");
    public static final DeferredItem<Item> DENIER_ARGENT = ITEMS.registerSimpleItem("denier_argent");
    public static final DeferredItem<Item> DENIER_OR = ITEMS.registerSimpleItem("denier_or");
    public static final DeferredItem<Item> PURSE = ITEMS.registerItem("purse", PurseItem::new, props -> new Item.Properties().stacksTo(1).component(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY));
    public static final DeferredItem<Item> MONEYBIN = ITEMS.registerItem("moneybin", PurseItem::new, props -> new Item.Properties().stacksTo(1).component(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY));
    public static final DeferredItem<Item> VILLAGE_MAP = ITEMS.registerItem("village_map", VillageMapItem::new, props -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> KINGDOM_BOOK = ITEMS.registerItem("kingdom_book", KingdomBookItem::new, props -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> COMPANY_LEDGER = ITEMS.registerItem("company_ledger", CompanyLedgerItem::new, props -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> WHEAT_FLOUR = ITEMS.registerSimpleItem("wheat_flour");

    /** Phase 2 task 18 — sealed letter item carrying a LetterContent component. */
    public static final DeferredItem<Item> WRITTEN_LETTER = ITEMS.registerItem(
            "written_letter",
            WrittenLetterItem::new,
            props -> new Item.Properties().stacksTo(1));






}
