package com.tlmpet.carrier;

/**
 * 上游 NBT 字段字面量集中表。
 * <p>
 * 为什么需要这个类：TLM 里大量 NBT 键常量是 {@code private}/{@code protected}，附属模组**无法符号引用**，
 * 只能用字面量。把字面量全部收敛到这一个文件，是为了在上游改名字段时只需要改一处，
 * 而不是散落到策略层的每个方法里。
 * <p>
 * 每个字面量都标注了它在 TLM 源码中的来源与可见性。标注为 <b>public</b> 的键其实可以直接写
 * {@code EntityMaid.XXX_TAG}，这里**仍然**保留字面量副本，目的是让本表成为唯一的对照清单；
 * 策略层可以选择用哪个。标注为 <b>private/protected</b> 的则别无选择。
 *
 * @see com.tlmpet.carrier.policy.MaidCarrierPolicy
 */
public final class TlmNbtKeys {
    private TlmNbtKeys() {
    }

    // ==================================================================================
    // 一、我们自己的键
    //
    // 这些键写在女仆的 Forge 持久化数据里（{@code Entity#getPersistentData()}），
    // 而不是直接塞进实体 NBT —— 因为 EntityMaid.addAdditionalSaveData 只会写它自己认识的那些键，
    // 我们手工塞进去的键在下一次存档时会被丢掉。Forge 的持久化数据由 Forge 自己负责存取。
    // ==================================================================================

    /** 逻辑身份 ID（UUID）。与实体 UUID 无关，实体每次注入都会被重新分配 UUID。 */
    public static final String MAID_ID = "TlmPetMaidId";

    /** 抽离次数，单调递增。用于校验载荷新旧，防重放。 */
    public static final String GENERATION = "TlmPetGeneration";

    /** 注入中标记。注入产生的女仆带上它，供 Phase 1b 的兜底拦截器放行；落地即清除。 */
    public static final String PENDING_INJECT = "TlmPetPendingInject";

    // ==================================================================================
    // 二、EntityMaid —— private（必须用字面量）
    // 来源：EntityMaid.java:252-259
    // ==================================================================================

    /** 当前工作模式的任务 uid。private。 */
    public static final String TASK = "MaidTask";
    /** 是否被雷击过。private。**保留**，见设计文档 §5.2。 */
    public static final String STRUCK_BY_LIGHTNING = "StruckByLightning";
    /** 无敌标记。private。**剥离** —— 不该把作弊状态带到新世界。 */
    public static final String INVULNERABLE = "Invulnerable";
    /** 饥饿值。private。保留。 */
    public static final String HUNGER = "MaidHunger";
    /** 好感度。private。**保留**（D3 方案 B）。 */
    public static final String FAVORABILITY = "MaidFavorability";
    /** 日程模式（DAY/NIGHT 等）。private。保留。 */
    public static final String SCHEDULE_MODE = "MaidScheduleMode";
    /** 背包内部数据（熔炉/储罐等）。private。**剥离**。 */
    public static final String MAID_BACKPACK_DATA = "MaidBackpackData";
    /** 是否由结构生成。private。**重置为 false**。 */
    public static final String STRUCTURE_SPAWN = "StructureSpawn";

    // ==================================================================================
    // 三、EntityMaid —— public
    // 来源：EntityMaid.java:177-193
    // 这些可以直接写 EntityMaid.MODEL_ID_TAG 等，此处保留副本便于对照。
    // ==================================================================================

    /** EntityMaid.java:177 */
    public static final String IS_YSM_MODEL = "IsYsmModel";
    /** EntityMaid.java:178 */
    public static final String YSM_MODEL_ID = "YsmModelId";
    /** EntityMaid.java:179 */
    public static final String YSM_MODEL_TEXTURE = "YsmModelTexture";
    /** EntityMaid.java:180 */
    public static final String YSM_MODEL_NAME = "YsmModelName";
    /** EntityMaid.java:181 */
    public static final String YSM_ROULETTE_ANIM = "YsmRouletteAnim";
    /** EntityMaid.java:182 */
    public static final String YSM_ROAMING_VARS = "YsmRoamingVars";
    /** EntityMaid.java:183 */
    public static final String YSM_ROAMING_UPDATE_FLAG = "YsmRoamingUpdateFlag";
    /** EntityMaid.java:186 */
    public static final String MODEL_ID = "ModelId";
    /** EntityMaid.java:187 */
    public static final String SOUND_PACK_ID = "SoundPackId";
    /** EntityMaid.java:188 */
    public static final String MAID_BACKPACK_TYPE = "MaidBackpackType";
    /** EntityMaid.java:189 */
    public static final String MAID_INVENTORY = "MaidInventory";
    /** EntityMaid.java:190 */
    public static final String MAID_BAUBLE_INVENTORY = "MaidBaubleInventory";
    /**
     * EntityMaid.java:191 —— 1 格隐藏栏。
     * <p>
     * ⚠️ 上游 {@code ItemFilm.removeMaidSomeData}（:80-101）**漏了这一项**，
     * 也漏了 {@link #MAID_TASK_INVENTORY}。设计文档 §4.2 特别标注过。
     */
    public static final String MAID_HIDE_INVENTORY = "MaidHideInventory";
    /**
     * EntityMaid.java:192 —— 9 格任务栏。
     * <p>
     * ⚠️ 同样被上游 {@code ItemFilm.removeMaidSomeData} 遗漏。
     */
    public static final String MAID_TASK_INVENTORY = "MaidTaskInventory";
    /** EntityMaid.java:193 */
    public static final String EXPERIENCE = "MaidExperience";

    // ==================================================================================
    // 四、日程坐标
    // 来源：SchedulePos.java:75-83（save）与 :86-94（load）
    // ⚠️ 这是绝对坐标。不清掉她会在新世界里朝旧世界的坐标跑。
    // ==================================================================================

    /** 外层键。内含见下面四个。 */
    public static final String SCHEDULE_POS = "MaidSchedulePos";
    /** 工作点（BlockPos）。 */
    public static final String SCHEDULE_POS_WORK = "Work";
    /** 闲逛点（BlockPos）。 */
    public static final String SCHEDULE_POS_IDLE = "Idle";
    /** 睡觉点（BlockPos）。 */
    public static final String SCHEDULE_POS_SLEEP = "Sleep";
    /** 所属维度（ResourceLocation 字符串）。 */
    public static final String SCHEDULE_POS_DIMENSION = "Dimension";
    /** 是否被玩家配置过。 */
    public static final String SCHEDULE_POS_CONFIGURED = "Configured";

    // ==================================================================================
    // 五、AI 对话数据
    // 来源：MaidAIChatData.java:30-32（protected）与 MaidAIChatSerializable.java:61-70 / 75-88
    // ==================================================================================

    /** 对话历史（ListTag&lt;LLMMessage&gt;，受携带上限约束，见 MaidAIChatData.java:43 的 512）。**保留**。 */
    public static final String MAID_HISTORY_CHAT = "MaidHistoryChat";
    /** 压缩摘要（String）。**保留** —— 跨世界连续性的关键通道，见设计文档 §9。 */
    public static final String MAID_HISTORY_SUMMARY = "MaidHistorySummary";
    /** 上次对话 token 计量（int）。**重置为 0**。 */
    public static final String MAID_LAST_CHAT_TOKEN_USAGE = "MaidLastChatTokenUsage";

    /** AI 站点配置外层键（CompoundTag）。保留。 */
    public static final String AI_CHAT = "MaidAIChat";
    /** AI 配置：LLM 站点。 */
    public static final String AI_CHAT_LLM_SITE = "LLMSite";
    /** AI 配置：LLM 模型。 */
    public static final String AI_CHAT_LLM_MODEL = "LLMModel";
    /** AI 配置：TTS 站点。 */
    public static final String AI_CHAT_TTS_SITE = "TTSSiteName";
    /** AI 配置：TTS 模型。 */
    public static final String AI_CHAT_TTS_MODEL = "TTSModel";
    /** AI 配置：TTS 语言。 */
    public static final String AI_CHAT_TTS_LANGUAGE = "TTSLanguage";
    /** AI 配置：对话语言。 */
    public static final String AI_CHAT_CHAT_LANGUAGE = "ChatLanguage";
    /**
     * AI 配置：提示词里的主人称呼。
     * <p>
     * ⚠️ **必须重映射**为注入者的名字，否则她会用旧主人的称呼叫新玩家。
     * 这是本功能里最容易被忽略、玩家感知最强的一处，见设计文档 §4.4。
     */
    public static final String AI_CHAT_OWNER_NAME = "OwnerName";
    /** AI 配置：自定义设定。 */
    public static final String AI_CHAT_CUSTOM_SETTING = "CustomSetting";

    // ==================================================================================
    // 六、其它管理器
    // ==================================================================================

    /** MaidConfigManager.java:13 —— 家模式开关。private。**重置为 false**。 */
    public static final String MAID_IS_HOME = "MaidIsHome";
    /** MaidConfigManager.java:12 —— 是否可被拾起。private。保留。 */
    public static final String MAID_IS_PICKUP = "MaidIsPickup";
    /** MaidConfigManager.java:14 —— 是否可骑乘。private。保留。 */
    public static final String MAID_IS_RIDEABLE = "MaidIsRideable";
    /** MaidConfigManager.java:16 —— 子配置外层键。private。保留。 */
    public static final String MAID_SUB_CONFIG = "MaidSubConfig";

    /** FavorabilityManager.java:58 —— 好感度冷却计数器。保留。 */
    public static final String FAVORABILITY_MANAGER_COUNTER = "FavorabilityManagerCounter";

    /** MaidGameRecordManager.java:12 —— 棋局记录外层键。保留。 */
    public static final String MAID_GAME_SKILL_DATA = "MaidGameSkillData";
    /** MaidGameRecordManager.java:13 —— 五子棋。 */
    public static final String MAID_GAME_GOMOKU = "Gomoku";

    /** MaidKillRecordManager.java:15 —— 击杀记录外层键。保留。 */
    public static final String KILL_RECORD = "KillRecord";
    /**
     * MaidKillRecordManager.java:16 —— 击杀总数。
     * <p>
     * ⚠️ <b>上游 bug（已在 1.5.3 源码核实）</b>：写入端
     * {@code MaidKillRecordManager.java:28} 用的是 {@link #KILL_RECORD} 而不是本键，
     * 而读取端 {@code :38} 读的是本键。两边不匹配，导致 {@code totalCount} 每次读档归零，
     * 进而使 {@code TriggerType.KILL_100}（{@code :49} 的 {@code totalCount >= 100}）几乎无法达成。
     * <p>
     * 我们的对策见 {@code MaidCarrierPolicy#normalizeKillRecord}：抽离时把本键一并补写，
     * 这样注入后 {@code readAdditionalSaveData} 能取回正确值。
     */
    public static final String KILL_RECORD_TOTAL = "TotalCount";
    /** MaidKillRecordManager.java:17 —— 史莱姆击杀数。键是配对的，正常。 */
    public static final String KILL_RECORD_SLIME = "Slime";
    /** MaidKillRecordManager.java:18 —— 凋灵击杀数。键是配对的，正常。 */
    public static final String KILL_RECORD_WITHER = "Wither";
    /** MaidKillRecordManager.java:19 —— 末影龙击杀数。键是配对的，正常。 */
    public static final String KILL_RECORD_ENDER_DRAGON = "EnderDragon";

    // ==================================================================================
    // 七、原版字段
    // ==================================================================================

    /** 实体类型 id。{@code EntityType.create(tag, level)} 依赖它存在。 */
    public static final String ID = "id";
    /** 主人 UUID。**重映射**为注入者。 */
    public static final String OWNER = "Owner";
    /** 是否坐下。**重置为 false**。 */
    public static final String SITTING = "Sitting";
    /** 自定义名字。保留 —— 名字是她的身份，不能丢。 */
    public static final String CUSTOM_NAME = "CustomName";
    /** 限制点中心。**剥离**（对应 {@code EntityMaid.clearRestriction()}）。 */
    public static final String HOME_POS = "HomePos";
    /** 限制半径。**剥离**。 */
    public static final String HOME_RADIUS = "HomeRadius";
    /** 坐标。**剥离**（按新坐标 setPos）。 */
    public static final String POS = "Pos";
    /** 速度。**剥离**。 */
    public static final String MOTION = "Motion";
    /** 属性（含 80 血 / 6 攻的基础值）。**保留**（D3）。 */
    public static final String ATTRIBUTES = "Attributes";
    /** 护甲栏。**剥离**。 */
    public static final String ARMOR_ITEMS = "ArmorItems";
    /** 手持栏。**剥离**。 */
    public static final String HAND_ITEMS = "HandItems";
    /** 拴绳。**剥离**。 */
    public static final String LEASH = "Leash";
    /** 乘客（被骑乘的实体）。**剥离**。 */
    public static final String PASSENGERS = "Passengers";

    // ---------- 状态类：全部剥离，让她以满状态回归 ----------

    /** 血量。剥离后由满血重建。 */
    public static final String HEALTH = "Health";
    /** 着火时间。 */
    public static final String FIRE = "Fire";
    /** 氧气。 */
    public static final String AIR = "Air";
    /** 摔落距离。 */
    public static final String FALL_DISTANCE = "FallDistance";
    /** 药水效果。 */
    public static final String ACTIVE_EFFECTS = "ActiveEffects";
    /** 冰冻计时。 */
    public static final String TICKS_FROZEN = "TicksFrozen";
    /** 视觉着火标记。 */
    public static final String HAS_VISUAL_FIRE = "HasVisualFire";
    /** 受击闪红计时。 */
    public static final String HURT_TIME = "HurtTime";
    /** 死亡计时。 */
    public static final String DEATH_TIME = "DeathTime";
    /** 上次受击时间戳。 */
    public static final String HURT_BY_TIMESTAMP = "HurtByTimestamp";
}
