package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmPetCarrier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 档案库：被她<b>放手</b>或<b>宣告失散</b>之后，她的数据去的地方（设计文档 §12.6、§12.7）。
 *
 * <h2>为什么墓碑放在文件系统，而不是身份记录里</h2>
 * {@code MaidCarrierState} 每位玩家只存得下一位 {@code maidId}，因此也只存得下一块墓碑
 * （风险 R27）。于是会出现这条缝隙：
 *
 * <pre>
 * 放手 A → 获得 B → 放手 B   此时 A 的墓碑已被 B 覆盖
 * 而 A 的旧载荷文件若还在 → 重新导入即可把她复活，与「放手不可撤销」相悖
 * </pre>
 *
 * 把墓碑改成<b>目录的存在性</b>就天然解决了：目录可以无限累积，而且它正是放手仪式本来
 * 就要做的归档动作，不需要额外的 NBT schema 变更，也不会有"忘了同步"的可能。
 *
 * <h2>为什么归档保留数据而不是删除</h2>
 * D10 要求「不可撤销」，指的是<b>游戏内不可迎回</b>，而不是"把她从磁盘上抹掉"。
 * 这个功能的产品主题是"冒险回忆"，把回忆删掉是最不符合主题的做法。
 * 所以数据留着（玩家仍可离线打开看），但它<b>不再出现在可迎回列表里</b>。
 *
 * <p>两个目录都是 {@code maids/} 的子目录，而 {@link MaidCarrierStore#listAll()} 只收
 * {@code *.json} 的<b>常规文件</b>，因此目录不会被误当成载荷扫进去。
 */
public final class MaidArchive {
    /** 正式放手（走过仪式）。目录存在即墓碑，不可撤销。 */
    private static final String RELEASED_DIR = "_released";
    /** 宣告失散（数据已找不回来）。<b>不</b>作为墓碑 —— 她日后的数据若被找回，应当允许她回来。 */
    private static final String LOST_DIR = "_lost";
    private static final String PAYLOAD_NAME = "payload.json";

    private MaidArchive() {
    }

    public static Path releasedDir() {
        return MaidCarrierStore.maidsDir().resolve(RELEASED_DIR);
    }

    public static Path lostDir() {
        return MaidCarrierStore.maidsDir().resolve(LOST_DIR);
    }

    /**
     * 给定的 {@code maidId} 是否已被正式放手。
     * <p>
     * 这是「放手不可撤销」的<b>唯一</b>判据。刻意用目录存在性而不是文件内容：
     * 即便归档里的载荷被玩家删了，只要目录还在，她就仍然是"已告别"的。
     */
    public static boolean isReleased(UUID maidId) {
        return maidId != null && Files.isDirectory(releasedDir().resolve(maidId.toString()));
    }

    /**
     * 把她当前的载荷移入放手档案，并留下墓碑。
     *
     * @return 归档目录；失败时返回 {@code null}（调用方<b>必须</b>据此中止仪式）
     */
    public static Path archiveReleased(UUID maidId) {
        return archive(maidId, releasedDir());
    }

    /** 同上，但归档到失散目录（不构成墓碑）。 */
    public static Path archiveLost(UUID maidId) {
        return archive(maidId, lostDir());
    }

    /**
     * @return 归档目录；失败时 {@code null}
     */
    private static Path archive(UUID maidId, Path root) {
        if (maidId == null) {
            return null;
        }
        Path target = root.resolve(maidId.toString());
        try {
            Files.createDirectories(target);
            Path payload = MaidCarrierStore.fileFor(maidId);
            if (Files.isRegularFile(payload)) {
                // 用 REPLACE_EXISTING：同一个 maidId 重复归档时（例如失散后又放手）
                // 应当保留最新的一份，而不是失败。
                Files.move(payload, target.resolve(PAYLOAD_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            // 归档失败必须让仪式中止：否则"不可撤销"只是记录上的说法，磁盘上她的载荷
            // 还在可迎回的位置，玩家换个存档就能把她调回来。
            TlmPetCarrier.LOGGER.error("归档她的数据失败：maidId={}，目标={}", maidId, target, e);
            return null;
        }
    }
}
