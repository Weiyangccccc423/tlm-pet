package com.tlmpet.carrier.state;

import com.tlmpet.carrier.TlmPetCarrier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 身份记录的统一入口：把设计文档 §12.4 的两重存储合成一个查询面。
 *
 * <h2>两重存储各自解决什么</h2>
 * <table>
 *   <tr><th></th><th>位置</th><th>作用域</th><th>解决什么</th></tr>
 *   <tr><td>config 桥</td><td>{@code config/tlm_pet/pet.json}</td><td>机器级，按玩家 UUID 分键</td>
 *       <td><b>单机跨存档</b>。集成服务端与客户端同 JVM、同 CONFIGDIR，所以这份文件对每个存档都可见 ——
 *           这正是"她跟着你换世界"得以成立的原因</td></tr>
 *   <tr><td>SavedData</td><td>主世界的 DimensionDataStorage</td><td>存档级，随存档走</td>
 *       <td><b>多人服务器</b>，以及"把存档拷到另一台机器"的情况（config 桥带不走）</td></tr>
 * </table>
 *
 * <h2>合并规则（一条容易写错的地方）</h2>
 * {@code config} 桥里<b>存在该玩家的条目就以它为准，哪怕它是 {@code NONE}</b>。
 * <p>
 * 若写成"config 显示没有女仆就去看 SavedData"，那么玩家放手之后 config 是 {@code NONE}、
 * 而 SavedData 可能还残留 {@code IN_WORLD}（例如放手发生在另一个存档里），
 * 于是<b>她会被复活</b>。存在性判定必须能被 {@code NONE} 明确否决，所以条目本身的存在即权威。
 *
 * <h2>为什么只在单机下读 config 桥</h2>
 * 专用服务器上 {@code FMLPaths.CONFIGDIR} 是<b>服务端</b>的配置目录，对全服玩家共享。
 * 在那里读它会把"某台机器上的跨存档记录"错误地当成"这个服务器的全服记录"，
 * 语义完全不对。所以专用服务器只用 SavedData —— 与 §12.4"每个服务器各自维护"的意图一致。
 */
public final class MaidCarrierStateStore {
    private static final String DIR_NAME = "tlm_pet";
    private static final String FILE_NAME = "pet.json";

    /** 文件级信封的标识，与单条记录的 {@link MaidCarrierState#FORMAT} 区分开。 */
    private static final String FILE_FORMAT = "tlm-pet-state-file";
    private static final int FILE_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private MaidCarrierStateStore() {
    }

    // ==================================================================================
    // 读
    // ==================================================================================

    public static Optional<MaidCarrierState> get(MinecraftServer server, UUID playerId) {
        if (isConfigBridgeActive(server)) {
            MaidCarrierState bridged = readConfig().get(playerId.toString());
            if (bridged != null) {
                return Optional.of(bridged);
            }
        }
        return readServer(server, playerId);
    }

    /**
     * 单女仆规则的判定入口。
     * <p>
     * 语义是"她是否已经被某个世界/桌宠持有"，与她在不在场无关 ——
     * 正因如此，玩家在新世界里两手空空时也无法再收服第二只。
     */
    public static boolean hasMaid(MinecraftServer server, UUID playerId) {
        return get(server, playerId).filter(MaidCarrierState::hasMaid).isPresent();
    }

    /**
     * 她当前是否落在"世界里"。
     * <p>
     * 与 {@link #hasMaid} 的区别很重要：{@code hasMaid} 为真而此方法为假，
     * 意味着她在桌宠或胶卷里 —— 此时应当提示"先把她迎回来"，而不是"你已经有女仆了"。
     */
    public static boolean isInWorld(MinecraftServer server, UUID playerId) {
        return get(server, playerId)
                .map(state -> state.getSoulState() == SoulState.IN_WORLD)
                .orElse(false);
    }

    // ==================================================================================
    // 写
    // ==================================================================================

    /**
     * 写入身份记录。两处都写，让下次启动时无论走哪条路径都能读到。
     */
    public static void write(MinecraftServer server, UUID playerId, MaidCarrierState state) {
        ServerMaidCarrierData data = readServerData(server);
        if (data != null) {
            data.set(playerId, state);
        }
        if (isConfigBridgeActive(server)) {
            Map<String, MaidCarrierState> players = readConfig();
            players.put(playerId.toString(), state);
            writeConfig(players);
        }
    }

    /** 管理员逃生口 {@code /tlm-pet reset} 使用：两处都清掉。 */
    public static void clear(MinecraftServer server, UUID playerId) {
        ServerMaidCarrierData data = readServerData(server);
        if (data != null) {
            data.clear(playerId);
        }
        if (isConfigBridgeActive(server)) {
            Map<String, MaidCarrierState> players = readConfig();
            if (players.remove(playerId.toString()) != null) {
                writeConfig(players);
            }
        }
    }

    // ==================================================================================
    // SavedData 侧
    // ==================================================================================

    private static Optional<MaidCarrierState> readServer(MinecraftServer server, UUID playerId) {
        ServerMaidCarrierData data = readServerData(server);
        return data == null ? Optional.empty() : data.get(playerId);
    }

    @Nullable
    private static ServerMaidCarrierData readServerData(MinecraftServer server) {
        return ServerMaidCarrierData.get(server.overworld());
    }

    // ==================================================================================
    // config 桥侧
    // ==================================================================================

    /**
     * 只有集成服务端（单机）才把 config 目录当作跨存档桥。
     * <p>
     * 专用服务器上该目录是全服共享的，读它会把机器级记录误当成服务器记录。
     */
    private static boolean isConfigBridgeActive(MinecraftServer server) {
        return server.isSingleplayer();
    }

    public static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(FILE_NAME);
    }

    private static Map<String, MaidCarrierState> readConfig() {
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ConfigFile parsed = GSON.fromJson(reader, ConfigFile.class);
            if (parsed == null) {
                TlmPetCarrier.LOGGER.error("身份记录文件为空，按「无记录」处理：{}", file);
                return new HashMap<>();
            }
            if (!FILE_FORMAT.equals(parsed.format)) {
                TlmPetCarrier.LOGGER.error("身份记录文件格式不匹配（期望 {}，实际 {}），按无记录处理：{}",
                        FILE_FORMAT, parsed.format, file);
                return new HashMap<>();
            }
            if (parsed.schemaVersion > FILE_SCHEMA_VERSION) {
                TlmPetCarrier.LOGGER.error("身份记录文件来自更新的版本（schema {} > {}），按无记录处理：{}",
                        parsed.schemaVersion, FILE_SCHEMA_VERSION, file);
                return new HashMap<>();
            }
            return parsed.players == null ? new HashMap<>() : parsed.players;
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("读取身份记录文件失败，按无记录处理：{}", file, e);
            return new HashMap<>();
        } catch (RuntimeException e) {
            TlmPetCarrier.LOGGER.error("身份记录文件 JSON 结构异常，按无记录处理：{}", file, e);
            return new HashMap<>();
        }
    }

    private static void writeConfig(Map<String, MaidCarrierState> players) {
        Path file = configFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ConfigFile envelope = new ConfigFile();
            envelope.players = players;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(envelope, writer);
            }
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("写入身份记录文件失败：{}。"
                    + "注意：跨存档能力依赖这个文件，写入失败会导致她无法被带到新世界", file, e);
        }
    }

    /**
     * config 文件的外层信封。
     * <p>
     * 信封放在这里而不是 {@link MaidCarrierState} 上，是为了让"单条记录"的序列化
     * 只有 NBT 一条路径（{@code toNbt}/{@code fromNbt}），避免两套格式各自演化。
     */
    private static final class ConfigFile {
        private String format = FILE_FORMAT;
        private int schemaVersion = FILE_SCHEMA_VERSION;
        private Map<String, MaidCarrierState> players = new HashMap<>();
    }
}
