package com.tlmpet.carrier.policy;

import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.SoulState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 单女仆规则的判定核心（设计文档 §12.5 与风险 R12）。
 *
 * <h2>为什么需要一个"到达即裁决"的判定</h2>
 * TLM 共有 <b>6 条</b>产生女仆的路径，其中 <b>4 条完全没有数量检查</b>
 * （祭坛 {@code spawn_box}、祭坛 {@code reborn_maid}、照片、胶卷），
 * 另外 2 条虽有 {@code canAdd()} 检查，却被写在 {@code canAdd() || player.isCreative()} 里
 * （{@code EntityMaid.java:679}、{@code ItemSmartSlab.java:153}），创造模式下形同不存在。
 * <p>
 * 也就是说，<b>没有任何一个中心点可以挂载唯一性规则</b>。与其逐条堵 6 个入口
 * （漏一个就前功尽弃），不如在"女仆实体进入世界"这一个动作上裁决 ——
 * 所有路径最终都要经过它。调用点拦截仍然要做，但那是为了体验（别白扣材料），不是为了正确性。
 *
 * <h2>判定规则（只有三条）</h2>
 * <ol>
 *   <li>玩家还没有女仆 → <b>放行</b>。这是正常的首次获得。</li>
 *   <li>来者<b>就是她</b>（{@code maidId} 相同）→ <b>放行</b>。这是迎回 / 用胶卷召唤 / 跨世界注入。</li>
 *   <li>玩家已经有女仆，来者不是她 → <b>拒绝</b>。这才是"第二只"。</li>
 * </ol>
 *
 * <p>规则 2 是整件事的关键：它让"她回来"和"多出一只"在数据上可区分。
 * 若没有 {@code maidId}，这两件事在实体层面完全一样，唯一性就只能靠"拒绝一切女仆实体"来实现，
 * 而那会让玩家永远无法迎回她。
 *
 * <p>本类是纯函数，不碰世界，因此可以被单测直接覆盖 —— 这条规则一旦错了，
 * 后果是"玩家凭空多出一只她"或"她永远回不来"，两种都不能靠人工点几下测出来。
 */
public final class MaidSingletonGuard {
    private MaidSingletonGuard() {
    }

    public enum Decision {
        /** 玩家还没有女仆，放行。 */
        ALLOW_FIRST_ACQUISITION,
        /** 来者就是她本人，放行。 */
        ALLOW_IS_HER,
        /** 玩家已经有女仆，而来者不是她 —— 必须拒绝。 */
        DENY_ALREADY_BOUND,
        /** 来者是已经被正式放手的那一位 —— 仪式不可撤销，拒绝复活。 */
        DENY_RELEASED
    }

    /**
     * @param current         玩家当前的身份记录，可为 null（表示从未有过）
     * @param arrivingMaidId  即将进入世界的女仆所携带的 maidId，可为 null
     *                        （野生女仆、照片产物、祭坛新造的女仆都没有这个标记）
     */
    public static Decision decide(@Nullable MaidCarrierState current, @Nullable UUID arrivingMaidId) {
        // 墓碑判定必须排在"没有女仆就放行"之前。放手之后 soulState 是 NONE，
        // 若先判 hasMaid() 就会走进 ALLOW_FIRST_ACQUISITION，旧载荷一导入她就复活了。
        if (current != null && current.isTombstoned(arrivingMaidId)) {
            return Decision.DENY_RELEASED;
        }
        if (current == null || !current.hasMaid()) {
            return Decision.ALLOW_FIRST_ACQUISITION;
        }
        if (arrivingMaidId != null && current.isHer(arrivingMaidId)) {
            return Decision.ALLOW_IS_HER;
        }
        return Decision.DENY_ALREADY_BOUND;
    }

    public static boolean isAllowed(Decision decision) {
        return decision == Decision.ALLOW_FIRST_ACQUISITION || decision == Decision.ALLOW_IS_HER;
    }

    /**
     * 给玩家的拒绝文案。
     * <p>
     * 按她当前所处的形态分别措辞，因为玩家真正需要知道的不是"规则不允许"，
     * 而是<b>她现在在哪里、我该做什么</b>。直接说"每个玩家只能有一个女仆"
     * 会让玩家以为自己的女仆没了。
     */
    public static String denyMessage(@Nullable MaidCarrierState current) {
        if (current == null) {
            return "这里不会再出现第二位女仆。";
        }
        if (current.isReleased()) {
            return "你已经与她正式告别过了。那段故事已经结束 —— 现在开始的是新的一页，"
                    + "去与她重新相遇吧。";
        }
        if (!current.hasMaid()) {
            return "这里不会再出现第二位女仆。";
        }
        String world = current.getLastSeenWorldId();
        String where = (world == null || world.isBlank()) ? "另一个世界" : "「" + world + "」";
        return switch (current.getSoulState()) {
            case CARRIED -> "你已经拥有她了 —— 她正在桌宠里休息。先把她迎回这个世界，"
                    + "而不是在这里寻找替代品。";
            case FILM_HELD -> "她还在你手中的那卷胶卷里。使用胶卷让她现身，"
                    + "或者去祭坛与她正式告别。";
            case IN_WORLD -> "你已经拥有她了。她正在" + where + "等着你 —— "
                    + "每位玩家只有一位女仆，而那位已经是你的人了。";
            case NONE -> "这里不会再出现第二位女仆。";
        };
    }

    /** 简短状态说明，用于命令与调试输出。 */
    public static String describe(SoulState state) {
        return switch (state) {
            case NONE -> "未拥有";
            case IN_WORLD -> "在世界中";
            case CARRIED -> "在桌宠里";
            case FILM_HELD -> "以胶卷形式持有";
        };
    }
}
