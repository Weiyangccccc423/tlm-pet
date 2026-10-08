package com.tlmpet.carrier.policy;

import com.tlmpet.carrier.TlmNbtKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 政策层（设计文档 §4.2）的单元测试。
 * <p>
 * <b>为什么这一层要单测</b>：§4.2 的「剥」名单有 25 个以上字段，全部靠游戏里肉眼核对
 * 既慢又容易漏 —— 漏一个的后果是隐性的（比如漏剥 {@code MaidHideInventory}，
 * 玩家就能把东西藏在隐藏栏里穿越世界）。而这一层是**纯 NBT 变换**，
 * 不需要世界、不需要实体，用单测兜住是成本最低的做法。
 * <p>
 * 这里只测政策层。抽离/注入的端到端行为（实体 UUID 重分配、备份、世界绑定清理）
 * 仍然必须在游戏里验证，见设计文档 §10 的 Phase 1 验收清单。
 */
class MaidCarrierPolicyTest {
    private static final UUID NEW_OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String NEW_OWNER_NAME = "新主人";

    /**
     * §4.2「剥」的全部键。
     * <p>
     * 清单化是为了让断言失败时能直接指出是哪一个键没被剥掉，而不是只报"有个字段漏了"。
     */
    private static final List<String> STRIPPED_KEYS = List.of(
            TlmNbtKeys.MAID_INVENTORY,
            TlmNbtKeys.MAID_BAUBLE_INVENTORY,
            TlmNbtKeys.MAID_HIDE_INVENTORY,
            TlmNbtKeys.MAID_TASK_INVENTORY,
            TlmNbtKeys.MAID_BACKPACK_TYPE,
            TlmNbtKeys.MAID_BACKPACK_DATA,
            TlmNbtKeys.ARMOR_ITEMS,
            TlmNbtKeys.HAND_ITEMS,
            TlmNbtKeys.LEASH,
            TlmNbtKeys.PASSENGERS,
            TlmNbtKeys.EXPERIENCE,
            TlmNbtKeys.HEALTH,
            TlmNbtKeys.FIRE,
            TlmNbtKeys.AIR,
            TlmNbtKeys.FALL_DISTANCE,
            TlmNbtKeys.ACTIVE_EFFECTS,
            TlmNbtKeys.TICKS_FROZEN,
            TlmNbtKeys.HAS_VISUAL_FIRE,
            TlmNbtKeys.HURT_TIME,
            TlmNbtKeys.DEATH_TIME,
            TlmNbtKeys.HURT_BY_TIMESTAMP,
            TlmNbtKeys.INVULNERABLE,
            TlmNbtKeys.POS,
            TlmNbtKeys.MOTION,
            TlmNbtKeys.SCHEDULE_POS,
            TlmNbtKeys.HOME_POS,
            TlmNbtKeys.HOME_RADIUS);

    /** §4.2「留」的全部键，值应与剥离前完全一致。 */
    private static final List<String> KEPT_KEYS = List.of(
            TlmNbtKeys.CUSTOM_NAME,
            TlmNbtKeys.MODEL_ID,
            TlmNbtKeys.SOUND_PACK_ID,
            TlmNbtKeys.IS_YSM_MODEL,
            TlmNbtKeys.YSM_MODEL_ID,
            TlmNbtKeys.YSM_MODEL_TEXTURE,
            TlmNbtKeys.YSM_MODEL_NAME,
            TlmNbtKeys.YSM_ROULETTE_ANIM,
            TlmNbtKeys.YSM_ROAMING_VARS,
            TlmNbtKeys.YSM_ROAMING_UPDATE_FLAG,
            TlmNbtKeys.TASK,
            TlmNbtKeys.SCHEDULE_MODE,
            TlmNbtKeys.MAID_SUB_CONFIG,
            TlmNbtKeys.HUNGER,
            TlmNbtKeys.FAVORABILITY,
            TlmNbtKeys.ATTRIBUTES,
            TlmNbtKeys.FAVORABILITY_MANAGER_COUNTER,
            TlmNbtKeys.STRUCK_BY_LIGHTNING,
            TlmNbtKeys.MAID_HISTORY_CHAT,
            TlmNbtKeys.MAID_HISTORY_SUMMARY,
            TlmNbtKeys.AI_CHAT,
            TlmNbtKeys.MAID_GAME_SKILL_DATA,
            TlmNbtKeys.KILL_RECORD);

    @Test
    @DisplayName("剥：物品/经验/战斗状态/坐标/世界绑定全部消失")
    void stripsEveryKeyOnTheStripList() {
        CompoundTag tag = fullMaidTag();

        MaidCarrierPolicy.stripForExtract(tag);

        for (String key : STRIPPED_KEYS) {
            assertFalse(tag.contains(key), "「剥」名单中的键在剥离后仍然存在：" + key);
        }
    }

    @Test
    @DisplayName("留：羁绊、身份、记忆、战绩逐字段原样保留")
    void keepsEveryKeyOnTheKeepList() {
        CompoundTag before = fullMaidTag();

        CompoundTag after = fullMaidTag();
        MaidCarrierPolicy.stripForExtract(after);

        for (String key : KEPT_KEYS) {
            assertTrue(after.contains(key), "「留」名单中的键被误剥：" + key);
            if (TlmNbtKeys.KILL_RECORD.equals(key)) {
                // 这个键仍然属于「留」，但值是**有意改动**过的：stripForExtract 会在其中
                // 补写 TotalCount 以绕过上游键名缺陷（见 compensatesUpstreamKillRecordKeyBug）。
                // 所以它不该参与"逐字节相等"的断言，其内容正确性由专项测试覆盖。
                continue;
            }
            assertEquals(before.get(key), after.get(key), "「留」名单中的键值被改动：" + key);
        }
    }

    @Test
    @DisplayName("重置：家模式/坐姿/结构生成转为 false，token 计量归零")
    void appliesResets() {
        CompoundTag tag = fullMaidTag();
        // 先构造成"三个都是 true、token 非零"的状态，确保测的是真的被改写
        tag.putBoolean(TlmNbtKeys.MAID_IS_HOME, true);
        tag.putBoolean(TlmNbtKeys.SITTING, true);
        tag.putBoolean(TlmNbtKeys.STRUCTURE_SPAWN, true);
        tag.putInt(TlmNbtKeys.MAID_LAST_CHAT_TOKEN_USAGE, 4321);

        MaidCarrierPolicy.stripForExtract(tag);

        // MaidIsHome 是"导入后女仆不会乱跑"的关键：它为 false 时
        // EntityMaid.hasRestriction() 为假 → isWithinRestriction() 恒真 →
        // SchedulePos.tick 提前返回，传送分支不可达（EntityMaid.java:2117-2121 / SchedulePos.java:59）
        assertFalse(tag.getBoolean(TlmNbtKeys.MAID_IS_HOME), "家模式必须重置为 false，否则女仆会被传送逻辑拉走");
        assertFalse(tag.getBoolean(TlmNbtKeys.SITTING), "坐姿必须重置为 false");
        assertFalse(tag.getBoolean(TlmNbtKeys.STRUCTURE_SPAWN), "结构生成标记必须重置为 false");
        assertEquals(0, tag.getInt(TlmNbtKeys.MAID_LAST_CHAT_TOKEN_USAGE), "token 计量必须归零");
    }

    @Test
    @DisplayName("补偿上游缺陷：把写错键位的击杀总数补写成 TotalCount")
    void compensatesUpstreamKillRecordKeyBug() {
        CompoundTag tag = new CompoundTag();
        CompoundTag killRecord = new CompoundTag();
        // 复刻上游 MaidKillRecordManager.java:28 的实际写入结果：总数落在 "KillRecord" 键下，
        // 而读取端 :38 读的是 "TotalCount" —— 于是每次读档总数都归零。
        killRecord.putInt(TlmNbtKeys.KILL_RECORD, 143);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_SLIME, 20);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_WITHER, 1);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_ENDER_DRAGON, 0);
        tag.put(TlmNbtKeys.KILL_RECORD, killRecord);

        MaidCarrierPolicy.stripForExtract(tag);

        CompoundTag result = tag.getCompound(TlmNbtKeys.KILL_RECORD);
        assertEquals(143, result.getInt(TlmNbtKeys.KILL_RECORD_TOTAL),
                "未补写 TotalCount，注入后 readAdditionalSaveData 会把击杀总数读成 0");
        // 原有键保持不变，注入前的 NBT 仍是上游认识的样子
        assertEquals(143, result.getInt(TlmNbtKeys.KILL_RECORD), "不应改动上游原本写入的键");
        assertEquals(20, result.getInt(TlmNbtKeys.KILL_RECORD_SLIME));
        assertEquals(1, result.getInt(TlmNbtKeys.KILL_RECORD_WITHER));
    }

    @Test
    @DisplayName("补偿是幂等的：已有 TotalCount 时不覆盖")
    void killRecordCompensationIsIdempotent() {
        CompoundTag tag = new CompoundTag();
        CompoundTag killRecord = new CompoundTag();
        killRecord.putInt(TlmNbtKeys.KILL_RECORD, 143);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_TOTAL, 143);
        tag.put(TlmNbtKeys.KILL_RECORD, killRecord);

        MaidCarrierPolicy.stripForExtract(tag);
        MaidCarrierPolicy.stripForExtract(tag);

        assertEquals(143, tag.getCompound(TlmNbtKeys.KILL_RECORD).getInt(TlmNbtKeys.KILL_RECORD_TOTAL));
    }

    @Test
    @DisplayName("无击杀记录时不凭空造一个")
    void doesNotInventKillRecord() {
        CompoundTag tag = new CompoundTag();

        MaidCarrierPolicy.stripForExtract(tag);

        assertFalse(tag.contains(TlmNbtKeys.KILL_RECORD), "没有击杀记录时不应创建该键");
    }

    @Test
    @DisplayName("重映射：归属与 AI 主人称呼同时改成本次注入者")
    void remapsBothOwnerAndAiOwnerName() {
        CompoundTag tag = fullMaidTag();
        UUID oldOwner = UUID.fromString("99999999-8888-7777-6666-555555555555");
        tag.putUUID(TlmNbtKeys.OWNER, oldOwner);

        MaidCarrierPolicy.remapOwner(tag, NEW_OWNER, NEW_OWNER_NAME);

        assertEquals(NEW_OWNER, tag.getUUID(TlmNbtKeys.OWNER), "实体归属未重映射");
        // 只改实体归属、不改 OwnerName 是最常见的疏漏：她会认新主人却仍叫旧主人的名字
        assertEquals(NEW_OWNER_NAME, tag.getCompound(TlmNbtKeys.AI_CHAT).getString(TlmNbtKeys.AI_CHAT_OWNER_NAME),
                "AI 主人称呼未重映射，她会用旧主人的名字称呼新玩家");
    }

    @Test
    @DisplayName("重映射：载荷里没有 MaidAIChat 时不报错、不凭空创建")
    void remapToleratesMissingAiChat() {
        CompoundTag tag = new CompoundTag();

        MaidCarrierPolicy.remapOwner(tag, NEW_OWNER, NEW_OWNER_NAME);

        assertEquals(NEW_OWNER, tag.getUUID(TlmNbtKeys.OWNER));
        assertFalse(tag.contains(TlmNbtKeys.AI_CHAT), "没有 MaidAIChat 时不应创建它");
    }

    /**
     * 构造一份"字段尽量齐全"的女仆 NBT，模拟 {@code saveWithoutId} 的输出。
     * <p>
     * 值的具体内容不重要，重要的是每个键都存在且值可比较 —— 这样"留"名单的断言
     * 才能验出"值被改动"而不只是"键还在"。
     */
    private static CompoundTag fullMaidTag() {
        CompoundTag tag = new CompoundTag();

        // ---- 剥：物品 ----
        tag.put(TlmNbtKeys.MAID_INVENTORY, new CompoundTag());
        tag.put(TlmNbtKeys.MAID_BAUBLE_INVENTORY, new CompoundTag());
        tag.put(TlmNbtKeys.MAID_HIDE_INVENTORY, new CompoundTag());
        tag.put(TlmNbtKeys.MAID_TASK_INVENTORY, new CompoundTag());
        tag.putString(TlmNbtKeys.MAID_BACKPACK_TYPE, "touhou_little_maid:backpack");
        tag.put(TlmNbtKeys.MAID_BACKPACK_DATA, new CompoundTag());
        tag.put(TlmNbtKeys.ARMOR_ITEMS, new ListTag());
        tag.put(TlmNbtKeys.HAND_ITEMS, new ListTag());
        tag.put(TlmNbtKeys.LEASH, new CompoundTag());
        tag.put(TlmNbtKeys.PASSENGERS, new ListTag());
        tag.putInt(TlmNbtKeys.EXPERIENCE, 1200);

        // ---- 剥：战斗状态 ----
        tag.putFloat(TlmNbtKeys.HEALTH, 37.5F);
        tag.putShort(TlmNbtKeys.FIRE, (short) 40);
        tag.putShort(TlmNbtKeys.AIR, (short) 100);
        tag.putFloat(TlmNbtKeys.FALL_DISTANCE, 12.0F);
        tag.put(TlmNbtKeys.ACTIVE_EFFECTS, new ListTag());
        tag.putInt(TlmNbtKeys.TICKS_FROZEN, 200);
        tag.putBoolean(TlmNbtKeys.HAS_VISUAL_FIRE, true);
        tag.putShort(TlmNbtKeys.HURT_TIME, (short) 10);
        tag.putShort(TlmNbtKeys.DEATH_TIME, (short) 5);
        tag.putInt(TlmNbtKeys.HURT_BY_TIMESTAMP, 999);
        tag.putBoolean(TlmNbtKeys.INVULNERABLE, true);

        // ---- 剥：坐标与世界绑定 ----
        tag.put(TlmNbtKeys.POS, new ListTag());
        tag.put(TlmNbtKeys.MOTION, new ListTag());
        tag.put(TlmNbtKeys.SCHEDULE_POS, new CompoundTag());
        tag.put(TlmNbtKeys.HOME_POS, new CompoundTag());
        tag.putFloat(TlmNbtKeys.HOME_RADIUS, 12.0F);

        // ---- 重置 ----
        tag.putBoolean(TlmNbtKeys.STRUCTURE_SPAWN, false);
        tag.putBoolean(TlmNbtKeys.MAID_IS_HOME, false);
        tag.putBoolean(TlmNbtKeys.SITTING, false);
        tag.putInt(TlmNbtKeys.MAID_LAST_CHAT_TOKEN_USAGE, 0);

        // ---- 留：身份 ----
        tag.putString(TlmNbtKeys.CUSTOM_NAME, "{\"text\":\"灵梦\"}");
        tag.putString(TlmNbtKeys.MODEL_ID, "touhou_little_maid:reimu");
        tag.putString(TlmNbtKeys.SOUND_PACK_ID, "touhou_little_maid:reimu");

        // ---- 留：YSM 模型 ----
        tag.putBoolean(TlmNbtKeys.IS_YSM_MODEL, false);
        tag.putString(TlmNbtKeys.YSM_MODEL_ID, "");
        tag.putString(TlmNbtKeys.YSM_MODEL_TEXTURE, "");
        tag.putString(TlmNbtKeys.YSM_MODEL_NAME, "");
        tag.putString(TlmNbtKeys.YSM_ROULETTE_ANIM, "");
        tag.put(TlmNbtKeys.YSM_ROAMING_VARS, new CompoundTag());
        tag.putInt(TlmNbtKeys.YSM_ROAMING_UPDATE_FLAG, 0);

        // ---- 留：职责 ----
        tag.putString(TlmNbtKeys.TASK, "touhou_little_maid:fishing");
        tag.putString(TlmNbtKeys.SCHEDULE_MODE, "DAY");
        tag.put(TlmNbtKeys.MAID_SUB_CONFIG, new CompoundTag());
        tag.putInt(TlmNbtKeys.HUNGER, 12);

        // ---- 留：羁绊（方案 B：数值与属性都保留）----
        tag.putInt(TlmNbtKeys.FAVORABILITY, 384);
        tag.put(TlmNbtKeys.ATTRIBUTES, new ListTag());
        tag.putInt(TlmNbtKeys.FAVORABILITY_MANAGER_COUNTER, 7);
        tag.putBoolean(TlmNbtKeys.STRUCK_BY_LIGHTNING, true);

        // ---- 留：记忆 ----
        tag.put(TlmNbtKeys.MAID_HISTORY_CHAT, new ListTag());
        tag.putString(TlmNbtKeys.MAID_HISTORY_SUMMARY, "上一个世界的长期摘要");
        CompoundTag aiChat = new CompoundTag();
        aiChat.putString(TlmNbtKeys.AI_CHAT_LLM_SITE, "openai");
        aiChat.putString(TlmNbtKeys.AI_CHAT_LLM_MODEL, "gpt-4o");
        aiChat.putString(TlmNbtKeys.AI_CHAT_TTS_SITE, "system");
        aiChat.putString(TlmNbtKeys.AI_CHAT_TTS_MODEL, "default");
        aiChat.putString(TlmNbtKeys.AI_CHAT_TTS_LANGUAGE, "zh");
        aiChat.putString(TlmNbtKeys.AI_CHAT_CHAT_LANGUAGE, "zh");
        aiChat.putString(TlmNbtKeys.AI_CHAT_OWNER_NAME, "旧主人");
        aiChat.putString(TlmNbtKeys.AI_CHAT_CUSTOM_SETTING, "");
        tag.put(TlmNbtKeys.AI_CHAT, aiChat);

        // ---- 留：战绩 ----
        tag.put(TlmNbtKeys.MAID_GAME_SKILL_DATA, new CompoundTag());
        CompoundTag killRecord = new CompoundTag();
        killRecord.putInt(TlmNbtKeys.KILL_RECORD, 143);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_SLIME, 20);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_WITHER, 1);
        killRecord.putInt(TlmNbtKeys.KILL_RECORD_ENDER_DRAGON, 0);
        tag.put(TlmNbtKeys.KILL_RECORD, killRecord);

        return tag;
    }
}
