package com.tlmpet.carrier.init;

import com.tlmpet.carrier.TlmPetCarrier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册（设计文档 §14.4）。
 *
 * <h2>这批物品在玩法里的位置</h2>
 * 它们是成就奖励，而成就记录的是"关系事件"而不是"资源积累"。所以这些材料不是装备，
 * 而是<b>纪念与便利</b>：唯一的实用品是迎回之铃，而它只是省事的捷径，不是硬门槛
 * （§14.5 原则 1 —— GUI 迎回路径始终可用，避免玩家因为漏了成就就玩不下去）。
 *
 * <p>刻意只有一件可再用的实用物品（铃），其余是一次性的、纯纪念的、或只影响桌宠侧容量。
 * §14.5 原则 3 特别强调<b>放手（{@code let_go}）不能给可再用资源</b>，
 * 否则告别会变成刷材料的操作，叙事重量就没了。
 */
public final class InitItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TlmPetCarrier.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TlmPetCarrier.MOD_ID);

    // ---- 召回路线的材料 ----

    /** 羁绊之核：迎回之铃的核心材料。来自「第一次抽离」，即"解锁召回能力"。 */
    public static final RegistryObject<Item> BOND_CORE = simple("bond_core");

    /** 归乡灵玉：来自「第一次迎回」，用于升级铃、降低冷却。 */
    public static final RegistryObject<Item> HOMING_JADE = simple("homing_jade");

    /** 寻踪符：加速定位她，辅助类。 */
    public static final RegistryObject<Item> TRACKING_CHARM = simple("tracking_charm");

    /** 三途之钥：进阶，允许迎回时保留目标世界的坐标记忆。 */
    public static final RegistryObject<Item> SANZU_KEY = simple("sanzu_key");

    // ---- 桌宠侧与纪念 ----

    /** 回忆碎片：扩展桌宠侧记忆容量。 */
    public static final RegistryObject<Item> MEMORY_SHARD = simple("memory_shard");

    /** 不灭之绊：羁绊满级的纪念饰品。 */
    public static final RegistryObject<Item> UNDYING_BOND = simple("undying_bond");

    /**
     * 彼岸花纪念物：放手仪式的唯一产物。
     * <p>
     * <b>刻意没有任何用途</b> —— 它只记录她的名字与"她存在过"。
     * 这是 §14.5 原则 3 的直接体现。
     */
    public static final RegistryObject<Item> HIGANBANA_KEEPSAKE = simple("higanbana_keepsake");

    // ---- 唯一的实用品 ----

    /** 迎回之铃：快速召回她。绑定 maidId 而非实体 UUID（风险 R21）。 */
    public static final RegistryObject<Item> RECALL_BELL = simple("recall_bell");

    public static final RegistryObject<CreativeModeTab> MAIN_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tlm_pet.main"))
                    .icon(() -> new ItemStack(RECALL_BELL.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(BOND_CORE.get());
                        output.accept(HOMING_JADE.get());
                        output.accept(TRACKING_CHARM.get());
                        output.accept(SANZU_KEY.get());
                        output.accept(MEMORY_SHARD.get());
                        output.accept(UNDYING_BOND.get());
                        output.accept(HIGANBANA_KEEPSAKE.get());
                        output.accept(RECALL_BELL.get());
                    })
                    .build());

    private InitItems() {
    }

    private static RegistryObject<Item> simple(String name) {
        return ITEMS.register(name, () -> new Item(new Item.Properties()));
    }

    public static void init(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
    }
}
