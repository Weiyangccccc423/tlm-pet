package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmPetCarrier;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 载荷文件的落盘位置。
 * <p>
 * <b>为什么放在 config 目录而不是存档目录</b>：本功能的主场景是"玩家去新世界，女仆跟过去"，
 * 也就是<b>跨存档</b>。若把载荷写进存档目录，跨存档导入就需要玩家手工拷贝文件。
 * 而 {@code FMLPaths.CONFIGDIR} 是机器级的：单人游戏里集成服务端与客户端同 JVM、同 CONFIGDIR，
 * 所以同一个文件天然对两个世界都可见。这正是设计文档 §12.4 把"跨存档身份"
 * 放在 {@code config/} 下的同一个理由。
 * <p>
 * 代价是：多存档下这里会混放所有世界的载荷。因此文件名用 {@code maidId}（逻辑身份，跨世界不变），
 * 而不是"世界名+名字"这类会冲突的命名。
 *
 * @see CarrierPayload
 */
public final class MaidCarrierStore {
    private static final String ROOT_DIR = "tlm_pet";
    private static final String MAIDS_DIR = "maids";
    private static final String EXTENSION = ".json";

    private MaidCarrierStore() {
    }

    public static Path maidsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ROOT_DIR).resolve(MAIDS_DIR);
    }

    public static Path fileFor(UUID maidId) {
        return maidsDir().resolve(maidId + EXTENSION);
    }

    /**
     * 按逻辑 ID 找载荷文件。
     * <p>
     * 先按规范文件名直查，找不到再做一次扫描 —— 这样玩家手工改过文件名也能被接受，
     * 因为 {@code maidId} 的权威来源是 JSON 内的字段，不是文件名。
     */
    public static Optional<Path> findById(String maidId) {
        if (maidId == null || maidId.isEmpty()) {
            return Optional.empty();
        }
        Path direct = maidsDir().resolve(maidId + EXTENSION);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        return listAll().stream()
                .filter(path -> CarrierPayload.read(path)
                        .map(payload -> maidId.equals(payload.getMaidId()))
                        .orElse(false))
                .findFirst();
    }

    /** 列出全部载荷文件。目录不存在时返回空表，不抛异常。 */
    public static List<Path> listAll() {
        Path dir = maidsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("列出载荷目录失败：{}", dir, e);
            return List.of();
        }
    }

    /**
     * 模糊查找，用于命令输入。
     * <p>
     * 让玩家手敲 36 位 UUID 是不现实的，所以接受 {@code maidId} 前缀或女仆名字。
     * 名字不唯一时返回多个候选，由调用方提示玩家改用前缀 —— 这里不替玩家猜。
     * <p>
     * 代价是要把目录里每个文件都解析一遍。载荷数量是"玩家有几个女仆"级别（按 D7 恒为 1），
     * 所以这个开销可以忽略；若将来真的大到有影响，应该加索引文件而不是牺牲可用性。
     */
    public static List<Path> search(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        String needle = token.toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        for (Path path : listAll()) {
            Optional<CarrierPayload> parsed = CarrierPayload.read(path);
            if (parsed.isEmpty()) {
                continue;
            }
            CarrierPayload payload = parsed.get();
            String maidId = payload.getMaidId();
            String name = payload.getProfile() == null ? null : payload.getProfile().getName();
            boolean idHit = maidId != null && maidId.toLowerCase(Locale.ROOT).startsWith(needle);
            boolean nameHit = name != null && !name.isEmpty() && name.equalsIgnoreCase(token);
            if (idHit || nameHit) {
                matches.add(path);
            }
        }
        return matches;
    }
}
