package com.tlmpet.carrier.policy;

import com.tlmpet.carrier.policy.MaidSingletonGuard.Decision;
import com.tlmpet.carrier.state.MaidCarrierState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单女仆规则的判定测试。
 * <p>
 * 这一层必须自动化，原因是它错了以后<b>人工点几下测不出来</b>：
 * 漏判的表现是"玩家某天凭空多出一只她"，而误判的表现是"她永远回不来"，
 * 两者都不会在验收时立刻暴露。风险 R12 / R20 都落在这里。
 */
class MaidSingletonGuardTest {
    private static final String WORLD = "幻想乡";

    private static MaidCarrierState inWorld(UUID maidId) {
        return MaidCarrierState.fresh(maidId, WORLD);
    }

    /** 造一个"她已被抽离、在桌宠里"的状态。 */
    private static MaidCarrierState carried(UUID maidId) {
        MaidCarrierState state = inWorld(maidId);
        assertTrue(state.toCarried(WORLD), "前置条件：应能从 IN_WORLD 转入 CARRIED");
        return state;
    }

    /** 造一个"她已被正式放手"的状态（含墓碑）。 */
    private static MaidCarrierState released(UUID maidId) {
        MaidCarrierState state = carried(maidId);
        assertTrue(state.toFilmHeld(), "前置条件：应能从 CARRIED 转入 FILM_HELD");
        assertTrue(state.release(), "前置条件：应能从 FILM_HELD 放手");
        return state;
    }

    // ==================================================================================
    // 正常路径
    // ==================================================================================

    @Test
    @DisplayName("没有身份记录时放行 —— 这是正常的首次获得")
    void allowsFirstAcquisitionWithoutRecord() {
        assertEquals(Decision.ALLOW_FIRST_ACQUISITION, MaidSingletonGuard.decide(null, null));
        assertTrue(MaidSingletonGuard.isAllowed(Decision.ALLOW_FIRST_ACQUISITION));
    }

    @Test
    @DisplayName("已有她的记录时，来者不是她 —— 必须拒绝")
    void deniesSecondMaid() {
        UUID her = UUID.randomUUID();
        MaidCarrierState state = carried(her);

        assertEquals(Decision.DENY_ALREADY_BOUND, MaidSingletonGuard.decide(state, UUID.randomUUID()));
        // 祭坛/照片/驯服造出来的女仆没有 maidId，这是最常见的"第二只"
        assertEquals(Decision.DENY_ALREADY_BOUND, MaidSingletonGuard.decide(state, null));
        assertFalse(MaidSingletonGuard.isAllowed(Decision.DENY_ALREADY_BOUND));
    }

    @Test
    @DisplayName("来者就是她 —— 放行，否则她永远回不来")
    void allowsHerOwnReturn() {
        UUID her = UUID.randomUUID();
        MaidCarrierState state = carried(her);

        assertEquals(Decision.ALLOW_IS_HER, MaidSingletonGuard.decide(state, her));
        assertTrue(MaidSingletonGuard.isAllowed(Decision.ALLOW_IS_HER));
    }

    @Test
    @DisplayName("抓不到 owner 的野生女仆没有 maidId，也不能被误判成她")
    void deniesWildMaidWhenPlayerAlreadyBound() {
        UUID her = UUID.randomUUID();
        MaidCarrierState state = inWorld(her);

        // 野生驯服路径：arrivingMaidId 为 null，而玩家已经有她 → 拦截
        assertEquals(Decision.DENY_ALREADY_BOUND, MaidSingletonGuard.decide(state, null));
    }

    // ==================================================================================
    // 墓碑：放手仪式不可撤销
    // ==================================================================================

    @Test
    @DisplayName("放手之后，拿着旧载荷重新导入不能让她复活")
    void deniesResurrectionAfterRelease() {
        UUID her = UUID.randomUUID();
        MaidCarrierState state = released(her);

        // 关键：此时 soulState 是 NONE。若先判 hasMaid() 就会走进"首次获得"放行，
        // 玩家留着当初抽离出的文件就能把仪式变成一次刷纪念品的操作。
        assertFalse(state.hasMaid(), "放手后 hasMaid() 应为 false");
        assertEquals(Decision.DENY_RELEASED, MaidSingletonGuard.decide(state, her));
        assertFalse(MaidSingletonGuard.isAllowed(Decision.DENY_RELEASED));
    }

    @Test
    @DisplayName("放手之后仍然可以重新获得一位全新的女仆")
    void allowsFreshMaidAfterRelease() {
        MaidCarrierState state = released(UUID.randomUUID());

        // 新 maidId 不等于墓碑，所以是正常的新开始（§12.6.2 结尾）。
        assertEquals(Decision.ALLOW_FIRST_ACQUISITION,
                MaidSingletonGuard.decide(state, UUID.randomUUID()));
        assertEquals(Decision.ALLOW_FIRST_ACQUISITION, MaidSingletonGuard.decide(state, null));
    }

    @Test
    @DisplayName("宣告失散不立墓碑 —— 若日后找回她的数据，她应该能回来")
    void lostIsNotTombstoned() {
        UUID her = UUID.randomUUID();
        MaidCarrierState state = carried(her);
        assertTrue(state.declareLost());

        assertFalse(state.isReleased(), "失散不应被标记为已放手");
        assertFalse(state.hasMaid(), "失散之后玩家处于未拥有状态");

        // 走的是「首次获得」而非「就是她」：状态确实已经回到 NONE，
        // 所以放行的理由是"玩家现在没有女仆"。她能被认回来靠的是另一条机制 ——
        // 兜底拦截拿到载荷里的 maidId 后，会用**她原本的 id** 重新登记，
        // 而不是另起一个新身份（见 MaidSingletonEvents.recordFirstAcquisition）。
        assertEquals(Decision.ALLOW_FIRST_ACQUISITION, MaidSingletonGuard.decide(state, her));
        assertTrue(MaidSingletonGuard.isAllowed(Decision.ALLOW_FIRST_ACQUISITION));

        // 与放手的关键差别：失散是允许她回来的，放手不是。
        assertEquals(Decision.DENY_RELEASED, MaidSingletonGuard.decide(released(her), her));
    }

    // ==================================================================================
    // 文案
    // ==================================================================================

    @Test
    @DisplayName("拒绝文案要告诉她现在在哪，而不是只说规则不允许")
    void denyMessageTellsWhereSheIs() {
        UUID her = UUID.randomUUID();

        String carriedMsg = MaidSingletonGuard.denyMessage(carried(her));
        assertTrue(carriedMsg.contains("桌宠"), "CARRIED 的文案应指明她在桌宠里：" + carriedMsg);

        String inWorldMsg = MaidSingletonGuard.denyMessage(inWorld(her));
        assertTrue(inWorldMsg.contains(WORLD), "IN_WORLD 的文案应指明她在哪个世界：" + inWorldMsg);

        String releasedMsg = MaidSingletonGuard.denyMessage(released(her));
        assertTrue(releasedMsg.contains("告别"), "已放手的文案应与「已有女仆」区分开：" + releasedMsg);
        assertNotEquals(inWorldMsg, releasedMsg);
    }
}
