package com.tlmpet.carrier.advancement;

import com.tlmpet.carrier.TlmPetCarrier;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 本模组成就的 ID 与授予入口（设计文档 §13、§14.4）。
 *
 * <h2>为什么用 {@code minecraft:impossible} 作为 criterion</h2>
 * 这些成就记录的是"关系事件"（第一次抽离、跨世界重逢、亲手告别），没有哪个原版触发器
 * 能表达它们，所以我们不去注册自定义 {@code CriterionTrigger}，而是用
 * {@code minecraft:impossible} —— 它永不自行触发，只能靠这里的 {@link #award} 显式授予。
 * 好处是成就 JSON 不需要任何额外注册表项，且语义上正好是"只能被外力授予"。
 *
 * <p>成就模板里对应的 criterion 名固定为 {@link #CRITERION}。
 */
public final class TlmPetAdvancements {
    /** 与成就 JSON 中 {@code criteria} 的键名一致。 */
    public static final String CRITERION = "tlm_pet";

    /** 根节点，也是成就页签的标题。 */
    public static final ResourceLocation ROOT = id("root");

    // ---- 接管原版成就后的替代品（§13.3）----

    /** 替代 {@code touhou_little_maid:base/spawn_maid}。 */
    public static final ResourceLocation FIRST_MAID = id("first_maid");

    /** 替代 {@code touhou_little_maid:base/tamed_maid}。 */
    public static final ResourceLocation SHE_CHOSE_YOU = id("she_chose_you");

    /** 替代 {@code touhou_little_maid:maid_base/reborn_maid} 与 {@code .../shrine_reborn_maid}。 */
    public static final ResourceLocation REUNION = id("reunion");

    /** 补上上游那个从未生成的成就（见 {@link AchievementTakeover}）。 */
    public static final ResourceLocation FOUND_HER = id("found_her");

    // ---- 羁绊历程（§14.4 奖励阶梯）----

    public static final ResourceLocation FIRST_CARRY = id("first_carry");
    public static final ResourceLocation FIRST_REUNION = id("first_reunion");
    public static final ResourceLocation THREE_WORLDS = id("three_worlds");
    public static final ResourceLocation MEMORY_KEEPER = id("memory_keeper");
    public static final ResourceLocation BOND_PRESERVED = id("bond_preserved");
    public static final ResourceLocation LET_GO = id("let_go");

    private TlmPetAdvancements() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TlmPetCarrier.MOD_ID, path);
    }

    /**
     * 授予成就。已经拥有时返回 {@code false}（不是错误 —— 例如 {@code reborn_maid} 与
     * {@code shrine_reborn_maid} 两条路径都指向 {@link #REUNION}）。
     *
     * @return 本次调用是否真的推进了进度
     */
    public static boolean award(ServerPlayer player, ResourceLocation achievementId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        Advancement advancement = server.getAdvancements().getAdvancement(achievementId);
        if (advancement == null) {
            // 缺一个成就不会让游戏崩，但玩家会安静地少一个奖励，很难查 —— 所以这里要吵。
            TlmPetCarrier.LOGGER.error("成就 {} 不存在，无法授予。请确认 data/tlm_pet/advancements 已随模组打包", achievementId);
            return false;
        }
        if (!player.getAdvancements().award(advancement, CRITERION)) {
            return false;
        }
        TlmPetCarrier.LOGGER.info("玩家 {} 达成成就 {}", player.getName().getString(), achievementId);
        return true;
    }
}
