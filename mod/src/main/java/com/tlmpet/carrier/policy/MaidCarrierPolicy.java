package com.tlmpet.carrier.policy;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * 四态政策：剥 / 留 / 重映射 / 清理+登记。
 * <p>
 * 本类是 Phase 1 的核心，也是整个功能里唯一"有判断"的地方。全部方法都**只操作 NBT**，
 * 不依赖 TLM 的私有类结构 —— 因为 TLM 大量 NBT 键是 private/protected，符号引用做不到，
 * 且 {@code FavorabilityManager} 类头挂着
 * {@code FIXME：这个好感度机制太落伍了，未来需要重新设计一个更合理的好感度系统}（{@code :23}），
 * 说明上游自己都认为这块会变。只认字面量的风险被收敛在 {@link TlmNbtKeys} 一个文件里。
 *
 * <h2>政策总览（设计文档 §4.2）</h2>
 * <ul>
 *   <li><b>剥</b>：物品、经验、战斗状态、坐标、世界绑定。理由见各方法注释。</li>
 *   <li><b>留</b>：名字、模型、音色、任务、日程模式、饥饿、好感度、属性、对话历史与摘要、击杀记录、雷击标记。
 *       注意——"留"在本类里表现为<b>什么都不做</b>，不逐一枚举。这样上游新增字段时默认被保留，
 *       而不是默认被丢弃。这是一个刻意的失败方向选择。</li>
 *   <li><b>重映射</b>：{@code Owner} 与 AI 的 {@code OwnerName}。</li>
 *   <li><b>清理+登记</b>：{@code clearRestriction()} 由 {@link #clearWorldBinding} 负责。</li>
 * </ul>
 */
public final class MaidCarrierPolicy {
    private MaidCarrierPolicy() {
    }

    /**
     * 抽离阶段：对 {@code maid.saveWithoutId(tag)} 得到的实体 NBT 做剥离与归一化，原地修改。
     * <p>
     * <b>调用时机</b>：必须在女仆还存活、且已经完成备份之后调用。
     *
     * @param tag 实体 NBT（由 saveWithoutId 产生，不含 id 键）
     */
    public static void stripForExtract(CompoundTag tag) {
        stripItems(tag);
        stripCombatState(tag);
        stripPosition(tag);
        stripWorldBinding(tag);
        applyResets(tag);
        normalizeKillRecord(tag);
    }

    /**
     * 剥：所有物品容器与装备。
     * <p>
     * <b>为什么连背包类型都要剥</b>：{@code MaidBackpackType} 与 {@code MaidBackpackData} 是配套的，
     * 只留类型会得到一个空背包；留数据又会把熔炉里的东西带过去。两者一起剥，她以"刚被收服"的装备状态出发。
     * <p>
     * ⚠️ 注意 {@code MaidHideInventory}（1 格隐藏栏）与 {@code MaidTaskInventory}（9 格任务栏）——
     * 上游 {@code ItemFilm.removeMaidSomeData}（{@code ItemFilm.java:80-101}）**漏了这两个**，
     * 那是个真实的信息泄漏：玩家可以把东西塞进隐藏栏，用胶卷绕过"不带物品"的语义。
     */
    private static void stripItems(CompoundTag tag) {
        tag.remove(TlmNbtKeys.MAID_INVENTORY);
        tag.remove(TlmNbtKeys.MAID_BAUBLE_INVENTORY);
        tag.remove(TlmNbtKeys.MAID_HIDE_INVENTORY);
        tag.remove(TlmNbtKeys.MAID_TASK_INVENTORY);
        tag.remove(TlmNbtKeys.MAID_BACKPACK_TYPE);
        tag.remove(TlmNbtKeys.MAID_BACKPACK_DATA);
        tag.remove(TlmNbtKeys.ARMOR_ITEMS);
        tag.remove(TlmNbtKeys.HAND_ITEMS);
        tag.remove(TlmNbtKeys.LEASH);
        tag.remove(TlmNbtKeys.PASSENGERS);
    }

    /**
     * 剥：经验与一切"当下正在发生"的战斗状态。
     * <p>
     * <b>为什么经验必须剥</b>：TLM 会把女仆经验折算成附魔之瓶掉落
     * （{@code GetExpBottleEvent}：12 点经验换 1 瓶）。留着经验就等于留着一笔可兑现的资产，
     * 与"不带物品"的设定矛盾。
     * <p>
     * 战斗状态（血量/着火/氧气/摔落/药水/冰冻/受击闪烁）全部剥掉，落地时由 {@code EntityType.create}
     * 按满血重建。这样她不会带着"穿越前的半血和一身负面效果"出现在新世界。
     */
    private static void stripCombatState(CompoundTag tag) {
        tag.remove(TlmNbtKeys.EXPERIENCE);

        tag.remove(TlmNbtKeys.HEALTH);
        tag.remove(TlmNbtKeys.FIRE);
        tag.remove(TlmNbtKeys.AIR);
        tag.remove(TlmNbtKeys.FALL_DISTANCE);
        tag.remove(TlmNbtKeys.ACTIVE_EFFECTS);
        tag.remove(TlmNbtKeys.TICKS_FROZEN);
        tag.remove(TlmNbtKeys.HAS_VISUAL_FIRE);
        tag.remove(TlmNbtKeys.HURT_TIME);
        tag.remove(TlmNbtKeys.DEATH_TIME);
        tag.remove(TlmNbtKeys.HURT_BY_TIMESTAMP);

        // 无敌状态是作弊/调试产生的，不该跨世界继承。
        tag.remove(TlmNbtKeys.INVULNERABLE);
    }

    /**
     * 剥：坐标与运动。
     * <p>
     * 剥掉后由注入方用 {@code moveTo} 指定落点。若不剥，{@code EntityType.create} 会把她放在旧坐标，
     * 而那个坐标在新世界要么悬空、要么卡在方块里。
     */
    private static void stripPosition(CompoundTag tag) {
        tag.remove(TlmNbtKeys.POS);
        tag.remove(TlmNbtKeys.MOTION);
    }

    /**
     * 剥：世界绑定。
     * <p>
     * <ul>
     *   <li>{@code MaidSchedulePos}：绝对坐标（{@code SchedulePos.java:75-83}）。不清的话她会朝旧世界的工作点跑。</li>
     *   <li>{@code HomePos}/{@code HomeRadius}：原版限制点，同一个道理。</li>
     * </ul>
     * 注意这里<b>只剥 NBT</b>；实体侧还要调 {@link #clearWorldBinding}，
     * 因为 {@code EntityMaid.clearRestriction()} 被覆写成了
     * {@code schedulePos.clear(this)}（{@code EntityMaid.java:2141-2143}），并不清原版的 restrictCenter。
     */
    private static void stripWorldBinding(CompoundTag tag) {
        tag.remove(TlmNbtKeys.SCHEDULE_POS);
        tag.remove(TlmNbtKeys.HOME_POS);
        tag.remove(TlmNbtKeys.HOME_RADIUS);
    }

    /**
     * 重置：三项布尔与一项计数。
     * <p>
     * <ul>
     *   <li>{@code StructureSpawn} → false：她不再"由结构生成"，否则会触发结构相关判定。</li>
     *   <li>{@code MaidIsHome} → false：<b>关键</b>。这一项决定了
     *       {@code EntityMaid.hasRestriction()}（{@code :2146-2148} 返回 {@code isHomeModeEnable()}）
     *       为假，进而使 {@code isWithinRestriction()} 恒为真（{@code :2117-2121}），
     *       于是 {@code SchedulePos.tick} 在 {@code SchedulePos.java:59} 直接返回，
     *       <b>传送分支不可达</b>。这是"导入后女仆不会乱跑"的实现依据。</li>
     *   <li>{@code Sitting} → false：落地时站着，而不是保持旧世界的坐姿。</li>
     *   <li>{@code MaidLastChatTokenUsage} → 0：上一个世界的 token 计量没有延续意义。</li>
     * </ul>
     */
    private static void applyResets(CompoundTag tag) {
        tag.putBoolean(TlmNbtKeys.STRUCTURE_SPAWN, false);
        tag.putBoolean(TlmNbtKeys.MAID_IS_HOME, false);
        tag.putBoolean(TlmNbtKeys.SITTING, false);
        tag.putInt(TlmNbtKeys.MAID_LAST_CHAT_TOKEN_USAGE, 0);
    }

    /**
     * 补偿一个上游 bug。
     * <p>
     * <b>上游缺陷（已在 TLM 1.5.3 源码核实）</b>：
     * {@code MaidKillRecordManager} 的写入端 {@code :28} 是
     * {@code killRecord.putInt(KILL_RECORD, totalCount)} —— 用错了常量，
     * 而读取端 {@code :38} 是 {@code killRecord.getInt(TOTAL_COUNT)}。两边键名不匹配，
     * 全仓库搜索确认 {@code "TotalCount"} <b>从未被写入过</b>。
     * 后果：{@code totalCount} 每次实体 NBT 加载都归零（区块卸载再加载即触发），
     * 而 {@code :49} 的 {@code totalCount >= 100} 是 {@code TriggerType.KILL_100} 成就的唯一触发条件
     * —— 也就是说 <b>{@code challenge/kill_100} 在实践中几乎不可能达成</b>。
     * 注意 {@code Slime}/{@code Wither}/{@code EnderDragon} 三个键是配对的，所以 {@code kill_slime_300} 正常。
     * <p>
     * 这里把 {@code TotalCount} 补写一份，使注入后 {@code readAdditionalSaveData} 能取回正确值。
     * <b>但这只是补偿，不是修复</b>：下一次原版存档时 {@code addAdditionalSaveData} 仍然只写
     * {@code KILL_RECORD} 键，所以数值会在再下一次加载时再次丢失。彻底修复需要 mixin 或上游改动，
     * 已记入设计文档的风险登记。
     */
    private static void normalizeKillRecord(CompoundTag tag) {
        if (!tag.contains(TlmNbtKeys.KILL_RECORD, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag killRecord = tag.getCompound(TlmNbtKeys.KILL_RECORD);
        if (killRecord.contains(TlmNbtKeys.KILL_RECORD, Tag.TAG_INT)
                && !killRecord.contains(TlmNbtKeys.KILL_RECORD_TOTAL, Tag.TAG_INT)) {
            int total = killRecord.getInt(TlmNbtKeys.KILL_RECORD);
            killRecord.putInt(TlmNbtKeys.KILL_RECORD_TOTAL, total);
            TlmPetCarrier.LOGGER.debug("已补偿上游 KillRecord.TotalCount 键名缺陷，击杀总数 = {}", total);
        }
    }

    /**
     * 重映射：把归属与 AI 称呼改成本次注入的玩家。原地修改。
     * <p>
     * <b>两个都要改，只改一个是最常见的疏漏</b>：
     * <ul>
     *   <li>{@code Owner} 决定她认谁、跟随谁、谁能开她的界面。</li>
     *   <li>{@code MaidAIChat.OwnerName} 是喂给大模型的提示词里的主人称呼
     *       （{@code MaidAIChatSerializable.java:69}）。不改的话，她换了新世界、
     *       认了新主人，却仍然张口叫旧主人的名字 —— 这是玩家感知最强的穿帮。</li>
     * </ul>
     * 在 {@code EntityType.create} <b>之前</b>写入 NBT，这样 {@code readAdditionalSaveData}
     * 会直接把她还原成"已驯服且属于该玩家"的状态，不需要事后补 {@code setOwnerUUID}。
     * <p>
     * 这里刻意接收 {@code UUID + String} 而不是 {@code Player}：政策层因此不依赖实体类，
     * 可以在没有世界的单元测试里直接验证（见 {@code MaidCarrierPolicyTest}）。
     *
     * @param tag       实体 NBT
     * @param ownerId   注入者的 UUID
     * @param ownerName 注入者的名字，用于 AI 提示词中的称呼
     */
    public static void remapOwner(CompoundTag tag, UUID ownerId, String ownerName) {
        tag.putUUID(TlmNbtKeys.OWNER, ownerId);

        if (tag.contains(TlmNbtKeys.AI_CHAT, Tag.TAG_COMPOUND)) {
            CompoundTag aiChat = tag.getCompound(TlmNbtKeys.AI_CHAT);
            String previous = aiChat.getString(TlmNbtKeys.AI_CHAT_OWNER_NAME);
            if (!previous.isEmpty() && !previous.equals(ownerName)) {
                TlmPetCarrier.LOGGER.debug("AI 主人称呼重映射：{} -> {}", previous, ownerName);
            }
            aiChat.putString(TlmNbtKeys.AI_CHAT_OWNER_NAME, ownerName);
            tag.put(TlmNbtKeys.AI_CHAT, aiChat);
        }
    }

    /**
     * 注入打包前补齐 {@code id} 键。
     * <p>
     * {@code saveWithoutId} 刻意不写 {@code id}，而 {@code EntityType.create(tag, level)}
     * 依赖它才能知道该造哪种实体（参考 {@code ItemCamera.java:78} 的同类处理）。
     *
     * @return 实际写入的类型 id；若女仆实体类型未注册则返回 null
     */
    public static String ensureEntityTypeId(CompoundTag tag) {
        EntityType<EntityMaid> type = InitEntities.MAID.get();
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (key == null) {
            TlmPetCarrier.LOGGER.error("女仆实体类型未在 Forge 注册表中找到，无法写出 id 键");
            return null;
        }
        tag.putString(TlmNbtKeys.ID, key.toString());
        return key.toString();
    }

    /**
     * 清理：把实体的世界绑定摘掉。
     * <p>
     * <b>顺序极其重要：必须先关家模式，再清日程。</b>
     * {@code SchedulePos.clear(maid)} 内部会调 {@code restrictTo(maid)}，而 {@code restrictTo}
     * 只在 {@code isHomeModeEnable()} 为真时才动手。如果家模式还是 true，
     * 它会把 {@code restrictCenter} 设成 {@code workPos}（剥离 NBT 后可能是 {@code BlockPos.ZERO}），
     * 于是 {@code isWithinRestriction()} 变成假、传送分支被激活 —— 女仆会被强行拉去 (0,0,0)。
     * <p>
     * 本方法在设计上假定调用方已经通过 {@code stripForExtract}/{@code applyResets} 把
     * {@code MaidIsHome} 写成 false，但这里仍然再关一次，理由是：注入路径将来可能被
     * 别的入口复用，而漏掉这一步的代价（女仆被传送到世界原点）远大于多一次赋值的成本。
     *
     * @param maid 已加入世界的女仆
     */
    public static void clearWorldBinding(EntityMaid maid) {
        maid.setHomeModeEnable(false);
        maid.clearRestriction();
    }

    /**
     * 登记：把女仆从 {@code MaidWorldData} 索引里摘掉。
     * <p>
     * <b>这里推翻了设计文档 §4.2 的原始写法</b>。文档写的是"注入后 {@code addInfo(maid)}"，
     * 但核实 TLM 源码后可见实际语义正好相反：
     * <ul>
     *   <li>{@code EntityMaid.onAddedToWorld}（{@code :1012-1021}）调 {@code removeInfo(this)}</li>
     *   <li>{@code EntityMaid.onRemovedFromWorld}（{@code :1023-1032}）调 {@code addInfo(this)}</li>
     * </ul>
     * 也就是说 {@code MaidWorldData} 是「<b>当前未加载</b>的女仆」的定位索引，
     * 消费方只有 {@code ItemTrumpet}（喇叭召回）、{@code ItemServantBell}（仆人铃）、
     * {@code ItemFoxScroll}（狐之卷列表）三个物品，并不存在以它为准的花名册 GUI。
     * 而 {@code addInfo}（{@code :124-129}）只是往 List 里追加，<b>不去重</b>。
     * <p>
     * 因此注入时手动 {@code addInfo} 只会制造一个假的"未加载"条目；
     * 等她真的卸载时 {@code onRemovedFromWorld} 会再登记一次 → 上述三个物品出现重复项。
     * 正确做法是<b>完全不碰</b>，交给 TLM 自己的生命周期。
     * <p>
     * 本方法只在<b>抽离</b>路径使用：在她被 {@code discard()} 之前主动摘掉索引，
     * 避免留下一个指向"已经不存在的女仆"的条目。
     *
     * @param maid 即将被收走的女仆
     */
    public static void unregister(EntityMaid maid) {
        MaidWorldData data = MaidWorldData.get(maid.level());
        if (data == null) {
            return;
        }
        data.removeInfo(maid);
    }
}
