package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 桌宠载荷（设计文档 §6.2）。
 * <p>
 * <b>双层结构</b>：可读 JSON 字段供桌宠建索引、供人阅读；{@code nbtBackup} 是唯一权威的还原载荷。
 * 前者出现偏差不影响还原正确性，后者解析失败才致命。
 *
 * <h2>与设计文档的一处刻意偏离</h2>
 * §6.3 写明"其余可读字段仅供桌宠索引与展示"，但 §6.2 又把完整的 {@code memory.history} 列进了可读区。
 * 二者其实冲突：完整历史已在 {@code nbtBackup} 里，再抄一份到可读区等于把数据翻倍
 * （512 条上限，原始 200–400 KB）。
 * <p>
 * 本实现的取舍是：<b>可读区仍然带完整历史</b>，理由是桌宠侧不应该为了读记忆而去实现一个 NBT 解析器 ——
 * 那会把"回忆"这件核心资产的可访问性绑死在游戏侧的数据格式上。代价是文件偏大，
 * 而这个代价只落在本地磁盘上。若将来体积成为问题，应该改的是"限制携带条数"，而不是砍掉可读性。
 *
 * @see com.tlmpet.carrier.core.MaidExtractor
 * @see com.tlmpet.carrier.core.MaidInjector
 */
public final class CarrierPayload {
    public static final String FORMAT = "tlm-pet-maid";
    public static final int SCHEMA_VERSION = 1;

    /** 必须 serializeNulls，否则 Gson 会把 {@code "carry": null} 整个省略，破坏 §6.2 的字段占位约定。 */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    // ---- 身份（永不变）----
    private String format = FORMAT;
    private int schemaVersion = SCHEMA_VERSION;
    private String maidId;
    private int generation;
    private String nonce;
    private long exportedAt;
    private Origin origin;

    // ---- 兼容性 ----
    private Compat compat;

    // ---- 可读画像（供桌宠索引，非权威）----
    private Profile profile;
    private Bond bond;
    private Duty duty;

    // ---- 记忆（知识库原料）----
    private Memory memory;

    private Records records;

    /** 穿越履历。由桌宠在每次抽离时追加，游戏侧只负责初始化成空数组。 */
    private List<Lineage> lineage = new ArrayList<>();

    /** 物品携带。方案 B 下恒为 null，保留字段是为将来配置化留兼容空间。 */
    private Object carry = null;

    // ---- 权威还原载荷 ----
    private String nbtBackup;
    private String checksum;

    private CarrierPayload() {
    }

    // ==================================================================================
    // 构造
    // ==================================================================================

    /**
     * 从已剥离的女仆 NBT 构造载荷。
     *
     * @param maid       女仆实体（用于取派生值与来源信息，此时**必须仍存活**）
     * @param player     抽离发起者
     * @param maidId     逻辑身份 ID（跨世界不变）
     * @param generation 本次抽离后的代数，单调递增
     * @param stripped   已经过 {@code MaidCarrierPolicy.stripForExtract} 的实体 NBT
     * @return 载荷；若 NBT 无法打包则返回 null
     */
    public static CarrierPayload from(EntityMaid maid, ServerPlayer player, UUID maidId, int generation,
                                      CompoundTag stripped) {
        String backup = encodeNbtBackup(stripped);
        if (backup == null) {
            return null;
        }

        CarrierPayload payload = new CarrierPayload();
        payload.maidId = maidId.toString();
        payload.generation = generation;
        payload.nonce = UUID.randomUUID().toString();
        payload.exportedAt = System.currentTimeMillis();
        payload.origin = Origin.from(maid);
        payload.compat = Compat.current();
        payload.profile = Profile.from(stripped);
        payload.bond = new Bond(stripped.getInt(TlmNbtKeys.FAVORABILITY), readFavorabilityLevel(maid));
        payload.duty = Duty.from(stripped);
        payload.memory = Memory.from(stripped);
        payload.records = Records.from(stripped);
        payload.nbtBackup = backup;
        payload.checksum = "sha256:" + sha256Hex(Base64.getDecoder().decode(backup));
        return payload;
    }

    /**
     * 取好感度等级。
     * <p>
     * 直接用 TLM 的权威派生（{@code FavorabilityManager.getLevel()}，被
     * {@code BaubleContainerScreen.java:38} 使用），而不是在这里复制一遍阈值表
     * —— 阈值会变（该类头挂着"未来需要重新设计"的 FIXME），复制一份必然会过期。
     */
    private static int readFavorabilityLevel(EntityMaid maid) {
        try {
            return maid.getFavorabilityManager().getLevel();
        } catch (Exception e) {
            TlmPetCarrier.LOGGER.error("读取好感度等级失败，可读画像中的 level 将退化为 0；该字段仅为展示值", e);
            return 0;
        }
    }

    // ==================================================================================
    // NBT 编解码
    // ==================================================================================

    /** gzip + base64。与 {@code MaidBackupsManager} 的压缩方式保持一致。 */
    private static String encodeNbtBackup(CompoundTag tag) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, buffer);
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("打包女仆 NBT 失败，抽离中止", e);
            return null;
        }
    }

    /**
     * 还原权威载荷。
     *
     * @return 实体 NBT；解码或解析失败时返回 empty
     */
    public Optional<CompoundTag> decodeMaidNbt() {
        if (this.nbtBackup == null || this.nbtBackup.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(this.nbtBackup);
            return Optional.of(NbtIo.readCompressed(new ByteArrayInputStream(compressed)));
        } catch (IOException | IllegalArgumentException e) {
            TlmPetCarrier.LOGGER.error("解析 nbtBackup 失败，载荷不可用：maidId={}", this.maidId, e);
            return Optional.empty();
        }
    }

    /**
     * 校验完整性。
     * <p>
     * 哈希对象是 <b>base64 解码后的 gzip 字节</b>，而不是重新序列化的 NBT —— 后者的字节序
     * 依赖 {@code CompoundTag} 内部 HashMap 的迭代顺序，虽然对同一组键是确定的，
     * 但把正确性建立在"键集永不变"上没有必要。
     * <p>
     * 注意这是<b>完整性</b>检查，不是安全措施：无法防住有意的篡改（设计文档 §6.3）。
     */
    public boolean verifyChecksum() {
        if (this.checksum == null || this.nbtBackup == null) {
            return false;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(this.nbtBackup);
            String actual = "sha256:" + sha256Hex(compressed);
            boolean matches = actual.equals(this.checksum);
            if (!matches) {
                TlmPetCarrier.LOGGER.error("载荷校验和不匹配：maidId={}，期望 {}，实际 {}",
                        this.maidId, this.checksum, actual);
            }
            return matches;
        } catch (IllegalArgumentException e) {
            TlmPetCarrier.LOGGER.error("载荷校验失败，base64 无法解码：maidId={}", this.maidId, e);
            return false;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境已经不可信。
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    // ==================================================================================
    // 文件读写（可读 JSON）
    // ==================================================================================

    public void write(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    /**
     * @return 解析出的载荷；文件缺失或格式不符时返回 empty
     */
    public static Optional<CarrierPayload> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            CarrierPayload payload = GSON.fromJson(json, CarrierPayload.class);
            if (payload == null) {
                TlmPetCarrier.LOGGER.error("载荷文件解析为空：{}", file);
                return Optional.empty();
            }
            if (!FORMAT.equals(payload.format)) {
                TlmPetCarrier.LOGGER.error("载荷文件格式不匹配：期望 {}，实际 {}（{}）", FORMAT, payload.format, file);
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("读取载荷文件失败：{}", file, e);
            return Optional.empty();
        } catch (RuntimeException e) {
            TlmPetCarrier.LOGGER.error("载荷文件 JSON 结构异常：{}", file, e);
            return Optional.empty();
        }
    }

    // ==================================================================================
    // 访问器
    // ==================================================================================

    public String getMaidId() {
        return maidId;
    }

    public int getGeneration() {
        return generation;
    }

    public long getExportedAt() {
        return exportedAt;
    }

    public String getNonce() {
        return nonce;
    }

    public Origin getOrigin() {
        return origin;
    }

    public Compat getCompat() {
        return compat;
    }

    public Profile getProfile() {
        return profile;
    }

    public Bond getBond() {
        return bond;
    }

    public Duty getDuty() {
        return duty;
    }

    public Memory getMemory() {
        return memory;
    }

    public Records getRecords() {
        return records;
    }

    public List<Lineage> getLineage() {
        return lineage;
    }

    /** 展示用的一句话摘要，供命令输出使用。 */
    public String describe() {
        String name = profile == null || profile.name == null || profile.name.isEmpty()
                ? "(无名)" : profile.name;
        int favorability = bond == null ? 0 : bond.favorability;
        int historyCount = memory == null || memory.history == null ? 0 : memory.history.size();
        return String.format("%s | 好感度 %d | 对话 %d 条 | 第 %d 代 | 来自 %s",
                name, favorability, historyCount, generation,
                origin == null ? "未知世界" : origin.worldName);
    }

    // ==================================================================================
    // 嵌套结构（字段名即 §6.2 的 JSON 键名，Gson 直接按字段名映射）
    // ==================================================================================

    public static final class Origin {
        private String worldName;
        private String dimension;
        private int[] pos;

        static Origin from(EntityMaid maid) {
            Origin origin = new Origin();
            MinecraftServer server = maid.level().getServer();
            origin.worldName = server == null ? "unknown" : server.getWorldData().getLevelName();
            origin.dimension = maid.level().dimension().location().toString();
            origin.pos = new int[]{maid.blockPosition().getX(), maid.blockPosition().getY(), maid.blockPosition().getZ()};
            return origin;
        }

        public String getWorldName() {
            return worldName;
        }

        public String getDimension() {
            return dimension;
        }
    }

    public static final class Compat {
        private String mcVersion;
        private String tlmVersion;
        private int nbtDataVersion;
        private List<String> requiredMods;

        static Compat current() {
            Compat compat = new Compat();
            compat.mcVersion = SharedConstants.getCurrentVersion().getName();
            compat.tlmVersion = ModList.get().getModContainerById("touhou_little_maid")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            compat.nbtDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            compat.requiredMods = List.of("touhou_little_maid");
            return compat;
        }

        public String getMcVersion() {
            return mcVersion;
        }

        public String getTlmVersion() {
            return tlmVersion;
        }
    }

    public static final class Profile {
        private String name;
        private String modelId;
        private String soundPackId;
        private boolean isYsmModel;

        static Profile from(CompoundTag tag) {
            Profile profile = new Profile();
            profile.name = readCustomName(tag);
            profile.modelId = tag.getString(TlmNbtKeys.MODEL_ID);
            profile.soundPackId = tag.getString(TlmNbtKeys.SOUND_PACK_ID);
            profile.isYsmModel = tag.getBoolean(TlmNbtKeys.IS_YSM_MODEL);
            return profile;
        }

        /**
         * 名字存在 {@code CustomName} 里，是 JSON 序列化的聊天组件而不是纯文本，
         * 直接当字符串用会把 {@code {"text":"..."}} 原样显示出来。
         */
        private static String readCustomName(CompoundTag tag) {
            if (!tag.contains(TlmNbtKeys.CUSTOM_NAME, Tag.TAG_STRING)) {
                return "";
            }
            String raw = tag.getString(TlmNbtKeys.CUSTOM_NAME);
            try {
                Component component = Component.Serializer.fromJson(raw);
                return component == null ? raw : component.getString();
            } catch (RuntimeException e) {
                TlmPetCarrier.LOGGER.warn("CustomName 不是合法的聊天组件 JSON，按原文记录：{}", raw);
                return raw;
            }
        }

        public String getName() {
            return name;
        }

        public String getModelId() {
            return modelId;
        }

        public boolean isYsmModel() {
            return isYsmModel;
        }
    }

    public static final class Bond {
        /** 权威值。方案 B 下原样保留，不折算。 */
        private int favorability;
        /** 派生 cache，仅供展示。阈值可能变化，不作为权威。 */
        private int level;

        Bond(int favorability, int level) {
            this.favorability = favorability;
            this.level = level;
        }

        public int getFavorability() {
            return favorability;
        }

        public int getLevel() {
            return level;
        }
    }

    public static final class Duty {
        private String task;
        private String schedule;

        static Duty from(CompoundTag tag) {
            Duty duty = new Duty();
            duty.task = tag.getString(TlmNbtKeys.TASK);
            duty.schedule = tag.getString(TlmNbtKeys.SCHEDULE_MODE);
            return duty;
        }

        public String getTask() {
            return task;
        }
    }

    public static final class Memory {
        private List<HistoryEntry> history;
        private String summary;
        private int lastTokenUsage;

        /**
         * 解码对话历史。
         * <p>
         * {@code LLMMessage} 是 public record，其 {@code CODEC} 也是 public，
         * 所以这里可以走 TLM 的官方序列化路径，而不需要自己拼接 NBT 结构 —— 上游改字段时我们自动跟随。
         * 解析失败只降级为"历史为空"，不阻断抽离：权威数据仍完整躺在 nbtBackup 里。
         */
        static Memory from(CompoundTag tag) {
            Memory memory = new Memory();
            memory.history = decodeHistory(tag);
            memory.summary = tag.getString(TlmNbtKeys.MAID_HISTORY_SUMMARY);
            memory.lastTokenUsage = tag.getInt(TlmNbtKeys.MAID_LAST_CHAT_TOKEN_USAGE);
            return memory;
        }

        private static List<HistoryEntry> decodeHistory(CompoundTag tag) {
            List<HistoryEntry> history = new ArrayList<>();
            if (!tag.contains(TlmNbtKeys.MAID_HISTORY_CHAT, Tag.TAG_LIST)) {
                return history;
            }
            LLMMessage.CODEC.listOf()
                    .parse(NbtOps.INSTANCE, tag.get(TlmNbtKeys.MAID_HISTORY_CHAT))
                    .resultOrPartial(error -> TlmPetCarrier.LOGGER.error(
                            "解析 MaidHistoryChat 失败，可读历史将为空（nbtBackup 不受影响）：{}", error))
                    .ifPresent(messages -> messages.forEach(message -> history.add(new HistoryEntry(
                            message.role().name().toLowerCase(Locale.ROOT),
                            message.message(),
                            message.gameTime()))));
            return history;
        }

        public List<HistoryEntry> getHistory() {
            return history;
        }

        public String getSummary() {
            return summary;
        }
    }

    /** 单条对话。{@code gameTime} 保留是为了让桌宠能还原"这段回忆发生在冒险的哪个阶段"。 */
    public static final class HistoryEntry {
        private String role;
        private String content;
        private long gameTime;

        HistoryEntry(String role, String content, long gameTime) {
            this.role = role;
            this.content = content;
            this.gameTime = gameTime;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public long getGameTime() {
            return gameTime;
        }
    }

    public static final class Records {
        private Kill kill;
        private Map<String, String> game;

        static Records from(CompoundTag tag) {
            Records records = new Records();
            records.kill = Kill.from(tag);
            records.game = readGameRecords(tag);
            return records;
        }

        /**
         * 转存棋局记录。
         * <p>
         * 各小游戏的内部结构由各自实现决定（{@code MaidGameRecordManager.java:13} 只固定了
         * {@code Gomoku} 一个键），这里不做假设，如实转成 SNBT 字符串。
         * 等桌宠侧明确需要哪些字段时再改成结构化投影。
         */
        private static Map<String, String> readGameRecords(CompoundTag tag) {
            Map<String, String> games = new LinkedHashMap<>();
            if (tag.contains(TlmNbtKeys.MAID_GAME_SKILL_DATA, Tag.TAG_COMPOUND)) {
                CompoundTag gameData = tag.getCompound(TlmNbtKeys.MAID_GAME_SKILL_DATA);
                for (String key : gameData.getAllKeys()) {
                    // 各小游戏的内部结构由各自实现决定，这里不做假设，如实转存 SNBT。
                    games.put(key.toLowerCase(Locale.ROOT), gameData.get(key).toString());
                }
            }
            return games;
        }

        public Kill getKill() {
            return kill;
        }

        public Map<String, String> getGame() {
            return game;
        }
    }

    public static final class Kill {
        /**
         * 击杀总数。
         * <p>
         * ⚠️ 键位错位是上游 bug 的直接产物：写入端 {@code MaidKillRecordManager.java:28}
         * 把总数写进了 {@link TlmNbtKeys#KILL_RECORD} 键，而读取端 {@code :38} 读的是
         * {@link TlmNbtKeys#KILL_RECORD_TOTAL}。这里两个都试，让可读画像与实体上的真实值一致，
         * 而不是照抄读取端的逻辑显示一个恒为 0 的假值。
         */
        private int total;
        private int slime;
        private int wither;
        private int enderDragon;

        static Kill from(CompoundTag tag) {
            Kill kill = new Kill();
            if (!tag.contains(TlmNbtKeys.KILL_RECORD, Tag.TAG_COMPOUND)) {
                return kill;
            }
            CompoundTag record = tag.getCompound(TlmNbtKeys.KILL_RECORD);
            kill.total = record.contains(TlmNbtKeys.KILL_RECORD_TOTAL, Tag.TAG_INT)
                    ? record.getInt(TlmNbtKeys.KILL_RECORD_TOTAL)
                    : record.getInt(TlmNbtKeys.KILL_RECORD);
            kill.slime = record.getInt(TlmNbtKeys.KILL_RECORD_SLIME);
            kill.wither = record.getInt(TlmNbtKeys.KILL_RECORD_WITHER);
            kill.enderDragon = record.getInt(TlmNbtKeys.KILL_RECORD_ENDER_DRAGON);
            return kill;
        }

        public int getTotal() {
            return total;
        }

        public int getSlime() {
            return slime;
        }

        public int getWither() {
            return wither;
        }

        public int getEnderDragon() {
            return enderDragon;
        }
    }

    /**
     * 穿越履历的一条。
     * <p>
     * 游戏侧只写得进 {@code leftAt} 之前的字段；{@code enteredAt} 与 {@code stats}
     * 由桌宠在下次抽离时补全，因为只有桌宠知道"她在这个世界待了多久、攒了什么"。
     */
    public static final class Lineage {
        private String worldName;
        private String dimension;
        private long enteredAt;
        private long leftAt;
        private Map<String, Object> stats;

        public String getWorldName() {
            return worldName;
        }

        public long getLeftAt() {
            return leftAt;
        }
    }
}
