package com.tlmpet.carrier.state;

import com.tlmpet.carrier.policy.MaidSingletonGuard;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份记录的状态机测试（设计文档 §12.6.1）。
 * <p>
 * 状态机的每一步都是"防复制"的一道闸门，所以每个非法迁移都必须被明确拒绝，
 * 而不是静默接受。这里逐条覆盖允许与禁止的迁移。
 */
class MaidCarrierStateTest {
    private static final String WORLD = "幻想乡";

    private static MaidCarrierState carried(UUID maidId) {
        MaidCarrierState state = MaidCarrierState.fresh(maidId, WORLD);
        assertTrue(state.toCarried(WORLD));
        return state;
    }

    private static MaidCarrierState filmHeld(UUID maidId) {
        MaidCarrierState state = carried(maidId);
        assertTrue(state.toFilmHeld());
        return state;
    }

    // ==================================================================================
    // 允许的迁移
    // ==================================================================================

    @Test
    @DisplayName("全新女仆从 fresh() 开始就处于 IN_WORLD，且立即拥有身份")
    void freshStartsInWorld() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = MaidCarrierState.fresh(maidId, WORLD);

        assertTrue(state.hasMaid());
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertEquals(1, state.getGeneration());
        assertTrue(state.isHer(maidId));
        assertFalse(state.isReleased());
    }

    @Test
    @DisplayName("抽离会递增代数 —— 这是防重放的基础")
    void extractBumpsGeneration() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = MaidCarrierState.fresh(maidId, WORLD);
        int before = state.getGeneration();

        assertTrue(state.toCarried(WORLD));
        assertEquals(SoulState.CARRIED, state.getSoulState());
        assertEquals(before + 1, state.getGeneration());
    }

    @Test
    @DisplayName("CARRIED → FILM_HELD → IN_WORLD 是她现身与告别的完整路径")
    void fullLifecycle() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = MaidCarrierState.fresh(maidId, WORLD);

        assertTrue(state.toCarried(WORLD));
        assertTrue(state.toFilmHeld());
        assertEquals(SoulState.FILM_HELD, state.getSoulState());

        assertTrue(state.toInWorld(WORLD));
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertFalse(state.isReleased(), "普通召唤不应留下墓碑");
    }

    // ==================================================================================
    // 禁止的迁移
    // ==================================================================================

    @Test
    @DisplayName("不能在 IN_WORLD 下直接放手 —— 必须先亲手取出胶卷")
    void cannotReleaseWithoutFilm() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);

        assertFalse(state.release(), "IN_WORLD 下放手必须被拒绝");
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertFalse(state.isReleased());
    }

    @Test
    @DisplayName("不能在 CARRIED 下直接放手 —— 这正是仪式那一步的重量所在")
    void cannotReleaseWhileCarried() {
        MaidCarrierState state = carried(UUID.randomUUID());

        assertFalse(state.release(), "CARRIED 下放手必须被拒绝（§12.6.1）");
        assertEquals(SoulState.CARRIED, state.getSoulState());
    }

    @Test
    @DisplayName("重复抽离必须被拒绝，否则会出现两只她的实体来源")
    void cannotExtractTwice() {
        MaidCarrierState state = carried(UUID.randomUUID());

        assertFalse(state.toCarried(WORLD), "已经 CARRIED 时再次抽离必须被拒绝");
        assertEquals(SoulState.CARRIED, state.getSoulState());
    }

    @Test
    @DisplayName("CARRIED 之外的任何状态都不能取胶卷 —— 否则能拿到两张卷")
    void cannotTakeFilmUnlessCarried() {
        MaidCarrierState inWorld = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertFalse(inWorld.toFilmHeld(), "IN_WORLD 下不应能取胶卷");

        MaidCarrierState film = filmHeld(UUID.randomUUID());
        assertFalse(film.toFilmHeld(), "FILM_HELD 下再次取卷必须被拒绝（两张卷＝两只她）");
        assertEquals(SoulState.FILM_HELD, film.getSoulState());
    }

    @Test
    @DisplayName("没有女仆时宣告失散必须被拒绝")
    void cannotDeclareLostWithoutMaid() {
        MaidCarrierState fresh = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertTrue(fresh.declareLost(), "IN_WORLD 下宣告失散应当允许（她可能随存档一起没了）");

        assertFalse(fresh.declareLost(), "已经是 NONE 时再次宣告失散必须被拒绝");
    }

    // ==================================================================================
    // 序列化
    // ==================================================================================

    @Test
    @DisplayName("NBT 往返必须保住身份、状态、代数、世界与墓碑")
    void nbtRoundTrip() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState original = filmHeld(maidId);

        Optional<MaidCarrierState> restored = MaidCarrierState.fromNbt(original.toNbt());
        assertTrue(restored.isPresent(), "自己写出的 NBT 必须能被自己读回");

        MaidCarrierState copy = restored.get();
        assertEquals(maidId, copy.getMaidId());
        assertEquals(SoulState.FILM_HELD, copy.getSoulState());
        assertEquals(original.getGeneration(), copy.getGeneration());
        assertEquals(WORLD, copy.getLastSeenWorldId());
        assertEquals(original.isReleased(), copy.isReleased());
    }

    @Test
    @DisplayName("墓碑必须能活过存读档 —— 否则重启一次就能复活她")
    void tombstoneSurvivesRoundTrip() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState original = filmHeld(maidId);
        assertTrue(original.release());

        MaidCarrierState copy = MaidCarrierState.fromNbt(original.toNbt()).orElseThrow();
        assertTrue(copy.isReleased(), "released 标记必须被持久化");
        assertTrue(copy.isTombstoned(maidId), "往返后仍应认得出这是她的墓碑");
    }

    @Test
    @DisplayName("格式名不符的 NBT 必须被拒绝，不能读进半截数据")
    void rejectsWrongFormat() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Format", "something-else");
        tag.putString("SoulState", "IN_WORLD");

        assertTrue(MaidCarrierState.fromNbt(tag).isEmpty());
    }

    @Test
    @DisplayName("来自更新版本的数据必须被拒绝，而不是按旧规则误读")
    void rejectsNewerSchema() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Format", MaidCarrierState.FORMAT);
        tag.putInt("SchemaVersion", MaidCarrierState.SCHEMA_VERSION + 1);

        assertTrue(MaidCarrierState.fromNbt(tag).isEmpty());
    }

    @Test
    @DisplayName("未知的 soulState 应保守退化为 IN_WORLD，而不是 NONE")
    void unknownSoulStateFailsSafe() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Format", MaidCarrierState.FORMAT);
        tag.putInt("SchemaVersion", MaidCarrierState.SCHEMA_VERSION);
        tag.putUUID("MaidId", UUID.randomUUID());
        tag.putString("SoulState", "SOMETHING_FROM_THE_FUTURE");

        MaidCarrierState state = MaidCarrierState.fromNbt(tag).orElseThrow();
        // 退化为 NONE 会让玩家"凭空多出一次收服机会"，那是比报错更严重的后果
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertTrue(state.hasMaid());
    }

    @Test
    @DisplayName("每次 fromNbt 都应产出独立对象，避免共享可变状态")
    void producesIndependentInstances() {
        MaidCarrierState original = carried(UUID.randomUUID());
        CompoundTag tag = original.toNbt();

        MaidCarrierState a = MaidCarrierState.fromNbt(tag).orElseThrow();
        MaidCarrierState b = MaidCarrierState.fromNbt(tag).orElseThrow();

        assertNotSame(a, b);
        assertNotNull(a.getMaidId());
        assertTrue(a.declareLost());
        assertTrue(b.hasMaid(), "改一个不应影响另一个");
    }

    // ==================================================================================
    // 注入时的身份重新认领
    // ==================================================================================

    @Test
    @DisplayName("失散后得到另一位女仆的载荷，记录必须改指向新身份，而不是留着旧的 maidId")
    void adoptingAnotherMaidRepointsIdentity() {
        UUID lost = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        MaidCarrierState state = carried(lost);
        assertTrue(state.declareLost());

        // 玩家现在确实没有女仆，所以规则对这位新来者是放行的
        assertEquals(MaidSingletonGuard.Decision.ALLOW_FIRST_ACQUISITION,
                MaidSingletonGuard.decide(state, newcomer));

        state.adoptIdentity(newcomer, "新的世界");

        // 三方（记录 / 实体 / 载荷）必须一致，否则"她回来"与"多出一只"就再也分不清了
        assertTrue(state.isHer(newcomer));
        assertFalse(state.isHer(lost), "旧身份必须被彻底替换掉");
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertTrue(state.hasMaid());
    }

    @Test
    @DisplayName("重新认领身份必须清掉旧墓碑，否则会把新她误判成旧碑")
    void adoptingIdentityClearsTombstone() {
        UUID newcomer = UUID.randomUUID();
        MaidCarrierState state = filmHeld(UUID.randomUUID());
        assertTrue(state.release());
        assertTrue(state.isReleased());

        state.adoptIdentity(newcomer, WORLD);

        assertFalse(state.isReleased());
        assertFalse(state.isTombstoned(newcomer), "她现在是新身份，不是旧墓碑");
        assertTrue(state.hasMaid());

        // 已知残余风险：旧 id 的墓碑随身份替换一起消失了 —— 记录里只存得下一位 maidId，
        // 也就只存得下一块墓碑。彻底封堵需要把墓碑改为<b>文件系统级</b>
        // （maids/_released/<maidId>/，见设计文档 §12.6），留待放手仪式落地时处理。
    }

    @Test
    @DisplayName("注入时以载荷代数为权威，避免实体与记录当场分叉")
    void adoptsPayloadGeneration() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), "世界");
        assertEquals(1, state.getGeneration());

        assertTrue(state.adoptGeneration(7));
        assertEquals(7, state.getGeneration());

        // 分叉会让下一次抽离打印一条难懂的"代数不一致"告警，而那条告警本该只用于真异常
        assertTrue(state.toCarried("世界"));
        assertEquals(8, state.getGeneration(), "抽离应在新代数上继续递增");
    }

    @Test
    @DisplayName("损坏的载荷代数（小于 1）必须被拒绝，不能污染记录")
    void rejectsCorruptGeneration() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), "世界");

        assertFalse(state.adoptGeneration(0));
        assertFalse(state.adoptGeneration(-3));
        assertEquals(1, state.getGeneration(), "被拒绝后记录应保持原值");
    }

    @Test
    @DisplayName("adoptIdentity 与整条生命周期相容：替换身份后依然能正常抽离")
    void adoptedIdentityStillFollowsLifecycle() {
        MaidCarrierState state = carried(UUID.randomUUID());
        assertTrue(state.declareLost());

        UUID newcomer = UUID.randomUUID();
        state.adoptIdentity(newcomer, "世界");
        state.adoptGeneration(4);

        assertTrue(state.toCarried("世界"));
        assertEquals(5, state.getGeneration());
        assertEquals(SoulState.CARRIED, state.getSoulState());
        assertTrue(state.isHer(newcomer));
    }
}
