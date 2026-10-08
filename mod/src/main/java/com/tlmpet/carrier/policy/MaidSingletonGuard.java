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
        /** 她本来就该回来（在桌宠里 / 在胶卷里），放行。 */
        ALLOW_IS_HER,
        /** 玩家已经有女仆，而来者不是她 —— 必须拒绝。 */
        DENY_ALREADY_BOUND,
        /** 来者是已经被正式放手的那一位 —— 仪式不可撤销，拒绝复活。 */
        DENY_RELEASED,
        /** 来者自称是她，但她<b>已经在世界里</b>了 —— 这是复制品，必须拒绝。 */
        DENY_DUPLICATE
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
            // 「是她」本身不足以放行 —— 还要看她的记录<b>期不期待</b>她回来。
            //
            // CARRIED / FILM_HELD 表示她此刻不在世界里（在桌宠里、或在一张胶卷里），
            // 所以来者正是她本人。
            //
            // 而 IN_WORLD 表示她<b>已经在这个世界里站着</b>。此时再来一个自称是同一个 maidId
            // 的实体，就只可能是复制品 —— 创造模式中键复制胶卷、NBT 复制、或把载荷文件
            // 复制一份再导入。这条判定不需要扫描世界里的实体，因此也不受"她在未加载区块里"
            // 的影响：记录里的 soulState 就是权威。
            SoulState now = current.getSoulState();
            if (now == SoulState.CARRIED || now == SoulState.FILM_HELD) {
                return Decision.ALLOW_IS_HER;
            }
            return Decision.DENY_DUPLICATE;
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
            case FILM_HELD -> "她已经被收起来了（" + carrierName(current) + "）。把她放出来，"
                    + "或者去祭坛与她正式告别。";
            case IN_WORLD -> "你已经拥有她了。她正在" + where + "等着你 —— "
                    + "每位玩家只有一位女仆，而那位已经是你的人了。";
            case NONE -> "这里不会再出现第二位女仆。";
        };
    }

    /**
     * 她当前被收在哪种物品里的人类可读名字。
     * <p>
     * TLM 有胶卷 / 照片 / 魂符三种载体，而 {@code soulState} 只有一个 {@code FILM_HELD}。
     * 所以这个名字必须从记录里读，不能靠状态名去猜 —— 否则把女仆收进魂符的玩家
     * 会被指向一卷他根本没有的胶片。
     */
    /**
     * 她当前被收在哪种物品里的人类可读名字。
     * <p>
     * 委托给 {@link MaidCarrierState#carrierName(String)} —— 载体字段在那里，
     * 命名规则也只该有一份。
     */
    public static String carrierName(MaidCarrierState current) {
        return current.describeCarrier();
    }

    /**
     * 简短状态说明，用于命令与调试输出。
     * <p>
     * 刻意接收整个记录而不是 {@code SoulState}：{@code FILM_HELD} 这个名字本身是有误导性的
     * （TLM 有胶卷 / 照片 / 魂符三种载体，而我们只有一个状态），只有记录里才知道她到底在哪。
     * 之前固定输出"以胶卷形式持有"，导致把女仆收进魂符的玩家被指向一卷不存在的胶片。
     */
    public static String describe(MaidCarrierState current) {
        return switch (current.getSoulState()) {
            case NONE -> "未拥有";
            case IN_WORLD -> "在世界中";
            case CARRIED -> "在桌宠里";
            case FILM_HELD -> "被收在" + carrierName(current);
        };
    }
}
