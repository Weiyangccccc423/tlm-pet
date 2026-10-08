package com.tlmpet.carrier.state;

import com.tlmpet.carrier.TlmPetCarrier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.UUID;

/**
 * 跨存档身份记录（设计文档 §12.4）。
 * <p>
 * <b>为什么需要它</b>：D7 要求「跨存档恒为同一个她」，但游戏里没有任何现成机制能表达这件事 ——
 * 实体 UUID 每次注入都会重分配；{@code MaidNumCapability} 既不跨存档、又被 4 条路径绕过（§12.2）。
 * 所以必须有一层属于我们自己的权威记录。
 *
 * <h2>与实体 UUID 的关系</h2>
 * {@link #maidId} 是<b>逻辑身份</b>，永不变。任何长期绑定（迎回之铃、归档目录、桌宠主键）
 * 都必须挂在它上面，<b>绝不能挂实体 UUID</b> —— 那是风险 R21 描述的失联场景。
 * <p>
 * 本类是可变数据持有者，转换方法自带守卫与日志。之所以把守卫放在这里而不是散落到调用方，
 * 是因为状态机的正确性是 R20（复制路径）的唯一防线，不能依赖每个调用点都记得检查。
 */
public final class MaidCarrierState {
    public static final String FORMAT = "tlm-pet-state";
    public static final int SCHEMA_VERSION = 1;

    private static final String NBT_FORMAT = "Format";
    private static final String NBT_SCHEMA = "SchemaVersion";
    private static final String NBT_MAID_ID = "MaidId";
    private static final String NBT_SOUL_STATE = "SoulState";
    private static final String NBT_GENERATION = "Generation";
    private static final String NBT_LAST_WORLD = "LastSeenWorldId";
    private static final String NBT_RELEASED = "Released";

    /** 逻辑身份 ID。为 null 表示从未拥有过女仆。 */
    private UUID maidId;
    /** 她当前所处的形态。 */
    private SoulState soulState = SoulState.NONE;
    /** 抽离代数，与载荷的 generation 同步，用于防重放。 */
    private int generation;
    /**
     * 她最后所在的世界标识。
     * <p>
     * 唯一用途是给玩家一句有用的提示（"她还在『幻想乡』里"），
     * 而不是让他去猜自己上一局玩的是哪个存档。<b>不参与任何判定</b>。
     */
    private String lastSeenWorldId;

    /**
     * 她是否已经被<b>正式放手</b>（走过祭坛仪式）。
     * <p>
     * 这个标记存在的唯一理由，是堵住一个很容易被忽略的漏洞：放手之后 {@code soulState} 变成
     * {@code NONE}，而单女仆规则对 {@code NONE} 是放行的（那是正常的首次获得）。
     * 于是玩家只要把当初抽离出的载荷文件留着，仪式之后重新导入就能<b>把她复活</b> ——
     * "不可撤销"和"放手不是刷材料的手段"（§14.5 原则 3）就都成了空话。
     * <p>
     * 所以 {@code NONE} 必须能携带墓碑语义：保留 {@code maidId} 并置此标记，
     * 让规则知道"这个 id 已经结束了"。而"宣告失散"（§12.7）<b>不</b>置此标记 ——
     * 失散是承认失去，如果玩家后来找回了数据，让她回来反而是对的。
     */
    private boolean released;

    private MaidCarrierState() {
    }

    /** 全新女仆（驯服 / 祭坛合成得到）。 */
    public static MaidCarrierState fresh(UUID maidId, String worldId) {
        MaidCarrierState state = new MaidCarrierState();
        state.maidId = maidId;
        state.soulState = SoulState.IN_WORLD;
        state.generation = 1;
        state.lastSeenWorldId = worldId;
        return state;
    }

    // ==================================================================================
    // 查询
    // ==================================================================================

    /**
     * 是否已经拥有女仆。
     * <p>
     * 这是单女仆规则的判定核心：为真时，任何"再产生一只"的路径都要被拒绝。
     */
    public boolean hasMaid() {
        return maidId != null && soulState != null && soulState != SoulState.NONE;
    }

    /** 给定 maidId 是否就是她。 */
    public boolean isHer(UUID other) {
        return maidId != null && maidId.equals(other);
    }

    /** 她是否已经被正式放手。 */
    public boolean isReleased() {
        return released;
    }

    /**
     * 给定的 maidId 是否是一块"她已经结束了"的墓碑。
     * <p>
     * 用于阻止放手仪式之后通过重新导入旧载荷把她复活（详见 {@link #released} 的说明）。
     */
    public boolean isTombstoned(UUID candidate) {
        return released && candidate != null && candidate.equals(maidId);
    }

    public UUID getMaidId() {
        return maidId;
    }

    public SoulState getSoulState() {
        return soulState == null ? SoulState.NONE : soulState;
    }

    public int getGeneration() {
        return generation;
    }

    public String getLastSeenWorldId() {
        return lastSeenWorldId;
    }

    /** 给玩家看的状态描述。 */
    public String describe() {
        if (!hasMaid()) {
            return "尚未拥有女仆";
        }
        return switch (getSoulState()) {
            case IN_WORLD -> "她在世界里" + (lastSeenWorldId == null ? "" : "（" + lastSeenWorldId + "）");
            case CARRIED -> "她在桌宠里";
            case FILM_HELD -> "她以胶卷形式在你手中";
            case NONE -> "尚未拥有女仆";
        };
    }

    // ==================================================================================
    // 状态迁移
    //
    // 每个方法都返回是否成功，失败时打日志说明被拒绝的原因。
    // 静默失败比抛异常更糟：它会让状态机悄悄跑偏，而"悄悄复制出一只她"是最严重的后果。
    // ==================================================================================

    /**
     * 她出现在世界里的总入口。允许三种来源：
     * <ul>
     *   <li>{@code NONE → IN_WORLD}：全新的她（驯服 / 祭坛）</li>
     *   <li>{@code CARRIED → IN_WORLD}：从桌宠迎回</li>
     *   <li>{@code FILM_HELD → IN_WORLD}：用胶卷召唤</li>
     * </ul>
     */
    public boolean toInWorld(String worldId) {
        SoulState current = getSoulState();
        if (current == SoulState.NONE && maidId == null) {
            TlmPetCarrier.LOGGER.error("状态迁移被拒绝：NONE → IN_WORLD 需要先有 maidId（请用 fresh()）");
            return false;
        }
        this.soulState = SoulState.IN_WORLD;
        this.lastSeenWorldId = worldId;
        return true;
    }

    /**
     * 把记录重新指向另一位女仆（注入时使用）。
     * <p>
     * <b>为什么必须有这个方法</b>：状态是"每人一个槽位"。玩家可能先宣告失散（留下一条
     * {@code soulState=NONE} 但<b>仍保留 maidId</b> 的记录），之后却从别处得到了<b>另一位</b>
     * 女仆的载荷。单女仆规则对这种情形是放行的（玩家现在确实没有女仆），但如果沿用旧记录，
     * 就会出现<b>记录里的 maidId ≠ 实体上的 maidId ≠ 载荷里的 maidId</b> 三方不一致 ——
     * 而 maidId 是整条唯一性规则唯一的判据，分叉之后"她回来"与"多出一只"再也无法区分。
     * <p>
     * 注意墓碑（{@code released}）的处理在调用方：被正式放手的 maidId 会在规则的入口
     * 就被拒绝，根本走不到这里。
     */
    public void adoptIdentity(UUID newMaidId, String worldId) {
        this.maidId = newMaidId;
        this.soulState = SoulState.IN_WORLD;
        this.lastSeenWorldId = worldId;
        // 新身份不是"已被放手"的那一位，墓碑必须清掉，否则会把新她误判成旧碑。
        this.released = false;
    }

    /**
     * 用载荷里的代数覆盖记录中的代数（注入时调用）。
     * <p>
     * 注入时<b>载荷的代数是权威</b>：它是产生这份载荷的那次抽离写下的值，也正是我们马上要写到
     * 实体身上的值。若沿用记录里的旧值，实体与记录会当场分叉，下一次抽离就会打印一条难懂的
     * "代数不一致"告警 —— 而那条告警本该只用于真正的异常。
     *
     * @return 是否被接受；代数小于 1 视为损坏（与 {@code MaidInjector} 的校验口径一致）
     */
    public boolean adoptGeneration(int payloadGeneration) {
        if (payloadGeneration < 1) {
            TlmPetCarrier.LOGGER.error("代数同步被拒绝：载荷代数 {} 小于 1，视为损坏", payloadGeneration);
            return false;
        }
        if (this.generation != payloadGeneration) {
            TlmPetCarrier.LOGGER.info("代数同步：记录 {} → 载荷 {}", this.generation, payloadGeneration);
        }
        this.generation = payloadGeneration;
        return true;
    }

    /**
     * 抽离：{@code IN_WORLD → CARRIED}。
     * <p>
     * 只允许从 {@code IN_WORLD} 转出。若她已经在桌宠里或已是胶卷，
     * 再次抽离意味着<b>存在两只她</b>的实体来源，必须拒绝。
     */
    public boolean toCarried(String worldId) {
        if (getSoulState() != SoulState.IN_WORLD) {
            TlmPetCarrier.LOGGER.error("抽离被拒绝：当前状态为 {}，只有 IN_WORLD 才能抽离", getSoulState());
            return false;
        }
        this.soulState = SoulState.CARRIED;
        this.lastSeenWorldId = worldId;
        this.generation++;
        return true;
    }

    /**
     * 取出胶卷：{@code CARRIED → FILM_HELD}。单向。
     * <p>
     * 这是复制路径的最后一道闸门。若允许反向或允许在 {@code FILM_HELD} 下再次取卷，
     * 玩家就能拿到两张胶卷 —— 也就是两只她（R20）。
     */
    public boolean toFilmHeld() {
        SoulState now = getSoulState();
        // 两个合法来源，理由不同：
        //   CARRIED  —— §12.6.1 的"亲手取出胶卷"，是放手仪式的必经一步；
        //   IN_WORLD —— TLM 自带的相机/胶卷机制：拍照会把她的数据存进物品并 discard 实体。
        //               这条路径不经过我们，若不接受它，我们的记录会一直以为"她还在世界里"，
        //               而实际上她已经是一张照片躺在背包里了。
        if (now != SoulState.CARRIED && now != SoulState.IN_WORLD) {
            TlmPetCarrier.LOGGER.error("转为 FILM_HELD 被拒绝：当前状态为 {}，只接受 CARRIED 或 IN_WORLD", now);
            return false;
        }
        this.soulState = SoulState.FILM_HELD;
        return true;
    }

    /**
     * 放手仪式：{@code FILM_HELD → NONE}。
     * <p>
     * 刻意只允许从 {@code FILM_HELD} 转出 —— {@code CARRIED} 下不能直接放手，
     * 必须先亲手取出胶卷（§12.6.1）。这个"多一步"就是仪式的重量所在。
     */
    public boolean release() {
        if (getSoulState() != SoulState.FILM_HELD) {
            TlmPetCarrier.LOGGER.error("放手被拒绝：当前状态为 {}，必须先取出胶卷成为 FILM_HELD", getSoulState());
            return false;
        }
        clearToNone(true);
        return true;
    }

    /**
     * 宣告失散：承认已经失去她（§12.7）。
     * <p>
     * 与{@link #release()} 的区别是语义而非机制：放手是主动告别（需要祭品与确认），
     * 失散是被动承认（她的数据已经找不回来了，所以不需要任何代价）。
     * 两者都回到 {@code NONE}，但给玩家的文案完全不同，并且失散<b>不立墓碑</b> ——
     * 若玩家日后找回了她的数据，让她回来应当是允许的。
     * <p>
     * <b>调用方必须先确认"桌宠明确在线且明确返回无此 maidId"</b>。
     * 把"桌宠没启动"误判为"数据丢失"会让玩家平白丢掉她 —— 这是本功能最不可接受的错误。
     */
    public boolean declareLost() {
        if (getSoulState() == SoulState.NONE) {
            TlmPetCarrier.LOGGER.error("宣告失散被拒绝：当前已经是 NONE，没有可失去的对象");
            return false;
        }
        clearToNone(false);
        return true;
    }

    private void clearToNone(boolean releasedByRitual) {
        this.soulState = SoulState.NONE;
        this.released = releasedByRitual;
        // 刻意保留 maidId 与 generation 不清零：
        // ① released 的墓碑判定需要 maidId；
        // ② 归档目录名（_released / _lost）用它命名；
        // ③ 防止旧载荷被重放。
        // hasMaid() 靠 soulState 判定，所以保留不会让唯一性规则失效。
    }

    // ==================================================================================
    // 序列化
    // ==================================================================================

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_FORMAT, FORMAT);
        tag.putInt(NBT_SCHEMA, SCHEMA_VERSION);
        if (maidId != null) {
            tag.putUUID(NBT_MAID_ID, maidId);
        }
        tag.putString(NBT_SOUL_STATE, getSoulState().name());
        tag.putInt(NBT_GENERATION, generation);
        if (lastSeenWorldId != null) {
            tag.putString(NBT_LAST_WORLD, lastSeenWorldId);
        }
        if (released) {
            tag.putBoolean(NBT_RELEASED, true);
        }
        return tag;
    }

    /**
     * @return 解析出的状态；格式或版本不符时返回 empty（宁可当作"没有女仆"也不要读进半截数据）
     */
    public static Optional<MaidCarrierState> fromNbt(CompoundTag tag) {
        if (!tag.contains(NBT_FORMAT, Tag.TAG_STRING) || !FORMAT.equals(tag.getString(NBT_FORMAT))) {
            TlmPetCarrier.LOGGER.error("女仆身份记录格式不匹配，已忽略：{}",
                    tag.contains(NBT_FORMAT) ? tag.getString(NBT_FORMAT) : "(缺失)");
            return Optional.empty();
        }
        int schema = tag.getInt(NBT_SCHEMA);
        if (schema > SCHEMA_VERSION) {
            TlmPetCarrier.LOGGER.error("女仆身份记录来自更新的版本（schema {} > {}），已忽略以免误读",
                    schema, SCHEMA_VERSION);
            return Optional.empty();
        }

        MaidCarrierState state = new MaidCarrierState();
        if (tag.contains(NBT_MAID_ID)) {
            state.maidId = tag.getUUID(NBT_MAID_ID);
        }
        state.soulState = readSoulState(tag.getString(NBT_SOUL_STATE));
        state.generation = tag.getInt(NBT_GENERATION);
        state.lastSeenWorldId = tag.contains(NBT_LAST_WORLD) ? tag.getString(NBT_LAST_WORLD) : null;
        state.released = tag.getBoolean(NBT_RELEASED);
        return Optional.of(state);
    }

    private static SoulState readSoulState(String raw) {
        try {
            return SoulState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 未知状态退化为 NONE 会让玩家"凭空多出一次收服机会"，比报错更危险。
            // 所以退化为 IN_WORLD：宁可偏保守（认为她存在），也不要放开唯一性。
            TlmPetCarrier.LOGGER.error("未知的 soulState「{}」，退化为 IN_WORLD 以保守处理", raw, e);
            return SoulState.IN_WORLD;
        }
    }

    /** 供日志与命令输出使用，不含敏感信息。 */
    @Override
    public String toString() {
        return "MaidCarrierState{maidId=" + maidId + ", soulState=" + getSoulState()
                + ", generation=" + generation + ", world=" + lastSeenWorldId + "}";
    }
}
