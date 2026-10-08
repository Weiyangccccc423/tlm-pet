package com.tlmpet.carrier.advancement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * 成就接管规则（设计文档 §13）。
 *
 * <h2>为什么必须接管，而不是并存</h2>
 * 装上本模组后，"收服女仆"从可重复行为变成一生一次的必然事件。原版那套成就的渐进性
 * 随之消失 —— 玩家第一次驯服就同时把 {@code tamed_maid} 拿到了，后面再无同类目标。
 * 所以把它们换成记录"关系节点"的成就，而不是让它们变成白送。
 *
 * <h2>为什么是"取消"而不是"改写"</h2>
 * Forge 1.20.1 的 {@code AdvancementEarnEvent} 不可取消（只能 revoke，那会让成就先跳出来
 * 再消失），所以唯一的干净做法是在触发器入口把它拦下 —— 见
 * {@code MaidEventTriggerMixin} 与 {@code AltarCraftTriggerMixin}。
 *
 * <h2>为什么只有两个注入点</h2>
 * 全部 4 个需要接管的成就只经由两个触发器：{@code MaidEventTrigger} 与
 * {@code AltarCraftTrigger}。特别注意 {@code reborn_maid} 走的是祭坛合成，<b>不</b>经
 * {@code MAID_EVENT}；只有 {@code shrine_reborn_maid} 走事件。
 */
public final class AchievementTakeover {
    /** {@code MaidEventTrigger} 的事件名 → 替代成就。 */
    private static final Map<String, ResourceLocation> MAID_EVENTS = Map.of(
            "tamed_maid", TlmPetAdvancements.SHE_CHOSE_YOU,
            "shrine_reborn_maid", TlmPetAdvancements.REUNION);

    /** {@code AltarCraftTrigger} 的配方 ID → 替代成就。 */
    private static final Map<ResourceLocation, ResourceLocation> ALTAR_RECIPES = Map.of(
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "altar/spawn_box"), TlmPetAdvancements.FIRST_MAID,
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "altar/reborn_maid"), TlmPetAdvancements.REUNION);

    /**
     * 上游那个"孤儿事件"。
     * <p>
     * {@code EntityMaid} 会在掠夺者前哨站驯服女仆时触发 {@code tamed_maid_from_structure}，
     * 而 datagen 的 {@code ChallengeAdvancement} 确实写了生成
     * {@code challenge/tamed_maid_in_pillager_outpost} 的代码 —— 但那个文件从未出现在发布 jar 里
     * （已核对 1.5.3 的 jar：challenge 分组只有 9 个成就，不含它）。
     * 也就是说事件在触发，却没有任何成就监听它。
     */
    private static final String ORPHAN_EVENT_TAMED_FROM_STRUCTURE = "tamed_maid_from_structure";

    private AchievementTakeover() {
    }

    /**
     * 处理 {@code MaidEventTrigger.trigger}。
     *
     * @return {@code true} 表示已接管，调用方应取消原版成就的授予
     */
    public static boolean interceptMaidEvent(ServerPlayer player, String eventName) {
        ResourceLocation replacement = MAID_EVENTS.get(eventName);
        if (replacement != null) {
            TlmPetAdvancements.award(player, replacement);
            return true;
        }
        if (ORPHAN_EVENT_TAMED_FROM_STRUCTURE.equals(eventName)) {
            // 没有成就需要取消，但我们可以顺势把上游漏掉的这一格补上。
            TlmPetAdvancements.award(player, TlmPetAdvancements.FOUND_HER);
        }
        return false;
    }

    /**
     * 处理 {@code AltarCraftTrigger.trigger}。
     *
     * @return {@code true} 表示已接管，调用方应取消原版成就的授予
     */
    public static boolean interceptAltarCraft(ServerPlayer player, ResourceLocation recipeId) {
        ResourceLocation replacement = ALTAR_RECIPES.get(recipeId);
        if (replacement == null) {
            return false;
        }
        TlmPetAdvancements.award(player, replacement);
        return true;
    }
}
