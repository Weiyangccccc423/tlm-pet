package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.advancement.TlmPetAdvancements;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * 放手仪式（设计文档 §12.6、D10）。
 *
 * <h2>它做什么</h2>
 * <ol>
 *   <li>把她的载荷移入 {@code maids/_released/<maidId>/} —— <b>这一步同时立下墓碑</b>，
 *       于是"不可撤销"由磁盘上的目录存在性保证（见 {@link MaidArchive}，风险 R27）；</li>
 *   <li>身份记录转为 {@code NONE} 并置 {@code released}；</li>
 *   <li>授予 {@code tlm_pet:let_go}。</li>
 * </ol>
 * 数据<b>不删除</b>：D10 的"不可撤销"指的是游戏内不再能迎回她，而不是把回忆从磁盘上抹掉。
 *
 * <h2>为什么必须从 {@code FILM_HELD} 进入</h2>
 * {@link MaidCarrierState#release()} 只接受 {@code FILM_HELD}。这个"多一步"就是仪式的重量：
 * 玩家得先亲手把她封进胶卷，才能谈告别。也从结构上排除了"在世界里站着时手滑点了确认"。
 *
 * <h2>顺序为什么是"先归档、后改状态"</h2>
 * 反过来的话，一旦归档失败（磁盘满、权限、文件被占用），记录已经说"她走了"，
 * 而载荷还在 {@code maids/} 下可被导入的位置 —— 那就等于她既能回来、又已经被正式放手，
 * 两条路同时成立。所以归档失败必须<b>整体中止</b>，让玩家可以重试。
 *
 * <h2>为什么不需要她在场</h2>
 * 这是本次设计修订的核心（§12.6.3）。仪式只读玩家的身份记录与她<b>已经存在的</b>载荷文件，
 * 完全不触碰世界里的实体。因此哪怕她还在一个玩家再也不打算打开的旧存档里，
 * 告别依然可行 —— 原设计"要求她在场"会让玩家被绝对唯一规则永久锁死。
 */
public final class MaidReleaseRitual {
    private MaidReleaseRitual() {
    }

    /** @return 是否成功放手 */
    public static boolean perform(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        UUID playerId = player.getUUID();
        MaidCarrierState state = MaidCarrierStateStore.get(server, playerId).orElse(null);

        if (state == null || state.getMaidId() == null) {
            reject(player, "message.tlm_pet.release.no_maid");
            return false;
        }
        UUID maidId = state.getMaidId();
        // 必须是**胶卷**，而不只是"被收在某个物品里"。
        //
        // 这条区分是被真实 bug 逼出来的：TLM 有胶卷 / 照片 / 魂符三种载体，而我们只有一个
        // FILM_HELD 状态。若不看载体，把女仆收进魂符的玩家摆一张无关的胶卷就能办放手仪式 ——
        // 而她其实还好端端地躺在魂符里。仪式的配方要的就是胶卷，判定必须与配方一致。
        if (!state.isInFilm()) {
            reject(player, state.getHeldItemId() == null
                    ? "message.tlm_pet.release.not_film_held"
                    : "message.tlm_pet.release.wrong_carrier");
            TlmPetCarrier.LOGGER.info("放手仪式被拒绝：owner={}，当前状态={}，载体={}",
                    playerId, state.getSoulState(), state.getHeldItemId());
            return false;
        }

        Optional<Path> archived = Optional.ofNullable(MaidArchive.archiveReleased(maidId));        if (archived.isEmpty()) {
            reject(player, "message.tlm_pet.release.archive_failed");
            return false;
        }
        if (!state.release()) {
            // 上面刚校验过状态，走到这里说明状态在两步之间被改了 —— 记日志暴露，不要静默。
            TlmPetCarrier.LOGGER.error("放手仪式状态推进失败：owner={}，maidId={}，state={}",
                    playerId, maidId, state);
            reject(player, "message.tlm_pet.release.failed");
            return false;
        }
        MaidCarrierStateStore.write(server, playerId, state);
        TlmPetAdvancements.award(player, TlmPetAdvancements.LET_GO);

        TlmPetCarrier.LOGGER.info("放手仪式完成：owner={}，maidId={}，归档到 {}", playerId, maidId, archived.get());
        player.displayClientMessage(
                Component.translatable("message.tlm_pet.release.success")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return true;
    }

    private static void reject(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), false);
    }
}
