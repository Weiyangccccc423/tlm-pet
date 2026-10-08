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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("已有胶卷时不能再取第二张 —— 两张卷就是两只她")
    void cannotTakeSecondFilm() {
        MaidCarrierState film = filmHeld(UUID.randomUUID());
        assertFalse(film.toFilmHeld(), "FILM_HELD 下再次取卷必须被拒绝");
        assertEquals(SoulState.FILM_HELD, film.getSoulState());

        // IN_WORLD 下取卷是允许的，因为 TLM 的相机就是这样工作的（见 inWorldCanBecomeFilmHeld）。
        // 但"一次只能有一张卷"这条仍然由上面的拒绝保证 ——
        // 而"两张卷能不能变成两只她"由 MaidSingletonGuard 的 DENY_DUPLICATE 兜住。
        assertTrue(MaidCarrierState.fresh(UUID.randomUUID(), WORLD).toFilmHeld());
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

    // ==================================================================================
    // 胶卷：TLM 相机路径
    // ==================================================================================

    @Test
    @DisplayName("世界里也能直接转成 FILM_HELD —— TLM 的相机拍照就是这条路径")
    void inWorldCanBecomeFilmHeld() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);

        // 相机拍照会把她的数据存进物品并 discard 实体。这条路径不经过我们的抽离流程，
        // 若不接受它，记录会一直以为"她还在世界里"，而实际上她已经是一张照片躺在背包里。
        assertTrue(state.toFilmHeld());
        assertEquals(SoulState.FILM_HELD, state.getSoulState());
    }

    @Test
    @DisplayName("FIRM_HELD 可以回到世界（胶卷还原 / 祭坛重生）")
    void filmHeldCanReturnToWorld() {
        MaidCarrierState state = filmHeld(UUID.randomUUID());

        assertTrue(state.toInWorld(WORLD));
        assertEquals(SoulState.IN_WORLD, state.getSoulState());
        assertTrue(state.hasMaid());
    }

    @Test
    @DisplayName("NONE 不能转成 FILM_HELD：没有她，就没有胶卷")
    void noneCannotBecomeFilmHeld() {
        MaidCarrierState state = filmHeld(UUID.randomUUID());
        assertTrue(state.release());
        assertEquals(SoulState.NONE, state.getSoulState());

        // 放手之后若还能转 FILM_HELD，仪式就白做了
        assertFalse(state.toFilmHeld());
        assertEquals(SoulState.NONE, state.getSoulState());
    }

    @Test
    @DisplayName("她已经在世界里时，再来一个自称是她 maidId 的实体必须被拒绝")
    void duplicateOfHerInWorldIsDenied() {        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = MaidCarrierState.fresh(maidId, WORLD);
        assertEquals(SoulState.IN_WORLD, state.getSoulState());

        // 这条判定不需要扫描世界里的实体：记录里的 soulState 就是权威。
        // 因此也不受"她正好在未加载区块里"的影响 —— 那种情况下扫描是找不到她的。
        // 复制来源：创造模式中键复制胶卷、NBT 复制、或把载荷文件复制一份再导入。
        assertEquals(MaidSingletonGuard.Decision.DENY_DUPLICATE,
                MaidSingletonGuard.decide(state, maidId));
        assertFalse(MaidSingletonGuard.isAllowed(MaidSingletonGuard.Decision.DENY_DUPLICATE));
    }

    @Test
    @DisplayName("她在桌宠里 / 在胶卷里时，她本人回来必须放行")
    void herReturnIsAllowedWhenExpected() {
        UUID maidId = UUID.randomUUID();

        MaidCarrierState carried = carried(maidId);
        assertEquals(MaidSingletonGuard.Decision.ALLOW_IS_HER,
                MaidSingletonGuard.decide(carried, maidId));

        MaidCarrierState film = filmHeld(maidId);
        assertEquals(MaidSingletonGuard.Decision.ALLOW_IS_HER,
                MaidSingletonGuard.decide(film, maidId));
    }

    @Test
    @DisplayName("放手只接受 FILM_HELD：CARRIED 下不能直接告别")
    void releaseRequiresFilmFirst() {
        MaidCarrierState state = carried(UUID.randomUUID());

        // 这个"多一步"就是仪式的重量：必须先亲手把她封进胶卷
        assertFalse(state.release());
        assertEquals(SoulState.CARRIED, state.getSoulState());
        assertFalse(state.isReleased());

        assertTrue(state.toFilmHeld());
        assertTrue(state.release());
        assertTrue(state.isReleased());
    }

    @Test
    @DisplayName("放手后立墓碑，且墓碑先于 hasMaid 生效（否则旧载荷能把她复活）")
    void releaseTombstonesBeforeHasMaid() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = filmHeld(maidId);
        assertTrue(state.release());

        // 关键：放手之后 soulState 是 NONE，而 NONE 对"首次获得"是放行的。
        // 若墓碑判定排在 hasMaid() 之后，玩家只要留着当初抽离出的载荷文件就能把她复活。
        assertFalse(state.hasMaid());
        assertTrue(state.isTombstoned(maidId));
        assertEquals(MaidSingletonGuard.Decision.DENY_RELEASED,
                MaidSingletonGuard.decide(state, maidId));
    }

    @Test
    @DisplayName("宣告失散不立墓碑：日后找回她的数据，让她回来应当被允许")
    void declareLostDoesNotTombstone() {
        UUID maidId = UUID.randomUUID();
        MaidCarrierState state = filmHeld(maidId);
        assertTrue(state.declareLost());

        assertFalse(state.hasMaid());
        assertFalse(state.isReleased());
        assertFalse(state.isTombstoned(maidId));
        // 与放手形成对照：失散是承认失去，不是主动告别
        assertEquals(MaidSingletonGuard.Decision.ALLOW_FIRST_ACQUISITION,
                MaidSingletonGuard.decide(state, maidId));
    }

    // ==================================================================================
    // 载体区分：TLM 有胶卷 / 照片 / 魂符三种，而 soulState 只有一个 FILM_HELD
    // ==================================================================================

    @Test
    @DisplayName("收在魂符里 ≠ 收在胶卷里：状态是同一个，但放手仪式的判定必须区分")
    void soulTalismanIsNotAFilm() {
        // 这是被真实 bug 逼出来的用例：TLM 的 SlabClickEvent 允许用空魂符右键女仆把她收走，
        // 而 spawnNewMaid 放下女仆后会把空魂符塞进玩家手里 —— 所以"放下她再右键她"几乎必然发生。
        // 只看 soulState 的话，玩家会被告知"她还在你手中的那卷胶卷里"，然后翻遍背包找不到胶片。
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertTrue(state.toFilmHeld("touhou_little_maid:smart_slab_has_maid"));

        assertEquals(SoulState.FILM_HELD, state.getSoulState());
        assertEquals("touhou_little_maid:smart_slab_has_maid", state.getHeldItemId());
        assertFalse(state.isInFilm(), "在魂符里不能被当成在胶卷里 —— 放手仪式的配方要的是胶卷");
    }

    @Test
    @DisplayName("在真正的胶卷里才允许办放手仪式")
    void realFilmAllowsRelease() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertTrue(state.toFilmHeld("touhou_little_maid:film"));

        assertTrue(state.isInFilm());
        assertTrue(state.release());
    }

    @Test
    @DisplayName("载体未知（老存档无该字段）时保守放行，不阻断已有玩家的仪式")
    void unknownCarrierStillAllowsRelease() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertTrue(state.toFilmHeld());

        assertNull(state.getHeldItemId());
        // 老存档里没有 HeldItem 字段。宁可当作"是胶卷"（维持原行为），
        // 也不要因为读不到新字段就把玩家的放手仪式锁死。
        assertTrue(state.isInFilm());
    }

    @Test
    @DisplayName("载体信息必须活过存读档，且脱离物品时被清掉")
    void heldItemSurvivesRoundTripAndClearsOnRelease() {
        MaidCarrierState state = MaidCarrierState.fresh(UUID.randomUUID(), WORLD);
        assertTrue(state.toFilmHeld("touhou_little_maid:photo"));

        MaidCarrierState reloaded = MaidCarrierState.fromNbt(state.toNbt()).orElseThrow();
        assertEquals("touhou_little_maid:photo", reloaded.getHeldItemId());

        assertTrue(reloaded.release());
        // 她已经不在物品里了，留着会让之后的措辞继续指着那张照片
        assertNull(reloaded.getHeldItemId());
    }
}
