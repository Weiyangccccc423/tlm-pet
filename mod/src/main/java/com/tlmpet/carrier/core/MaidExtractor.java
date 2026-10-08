package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidCarrierPolicy;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.tlmpet.carrier.state.SoulState;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.backups.MaidBackupsManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 抽离：把一位女仆从当前世界"取出来"，做成一个可携带的 {@link CarrierPayload}。
 * <p>
 * <b>这是破坏性操作</b> —— 成功后她会被 {@code discard()} 从世界里移除。
 * 因此流水线的<b>第一步是备份</b>，那是唯一可靠的后悔药。
 *
 * <h2>流水线</h2>
 * <ol>
 *   <li>用 {@code MaidBackupsManager.save} 落一份 TLM 原生备份</li>
 *   <li>确定/生成逻辑身份 {@code maidId}，并递增 {@code generation}</li>
 *   <li>{@code saveWithoutId} 序列化 → {@link MaidCarrierPolicy#stripForExtract} 剥离</li>
 *   <li>打包成载荷并落盘</li>
 *   <li>摘掉世界索引 → {@code discard()}</li>
 * </ol>
 *
 * @see MaidInjector
 */
public final class MaidExtractor {
    private MaidExtractor() {
    }

    /**
     * @param player 发起抽离的玩家（会据此做归属校验）
     * @param maid   目标女仆，必须存活且属于该玩家
     * @return 抽离结果
     */
    public static Result extract(ServerPlayer player, EntityMaid maid) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Result.failure("服务端不可用，抽离中止");
        }
        if (!maid.isOwnedBy(player)) {
            return Result.failure("她不属于你，无法抽离");
        }
        if (!maid.isAlive()) {
            return Result.failure("她已经不在了");
        }

        UUID playerId = player.getUUID();

        // ---- 状态判定。状态是权威，实体不是 ----
        // 只有"她正在世界里"才允许抽离。若状态已是 CARRIED / FILM_HELD，
        // 说明眼前这个实体是重复来源（兜底拦截正常工作时不该出现），
        // 抽离它会凭空制造出第二份她。
        MaidCarrierState state = MaidCarrierStateStore.get(server, playerId).orElse(null);
        if (state == null || !state.hasMaid()) {
            TlmPetCarrier.LOGGER.error("抽离被拒绝：玩家 {} 没有身份记录，但世界里有一位属于她的女仆", playerId);
            return Result.failure("没有找到她的身份记录，抽离中止（她没有被移除）");
        }

        CompoundTag persistent = maid.getPersistentData();
        UUID maidId = persistent.hasUUID(TlmNbtKeys.MAID_ID)
                ? persistent.getUUID(TlmNbtKeys.MAID_ID)
                : null;
        if (maidId == null || !state.isHer(maidId)) {
            TlmPetCarrier.LOGGER.error("抽离被拒绝：实体 maidId={} 与身份记录（{}）不一致",
                    maidId, state.getMaidId());
            return Result.failure("她的身份与记录不一致，抽离中止以免复制出第二只（她没有被移除）");
        }
        if (state.getSoulState() != SoulState.IN_WORLD) {
            return Result.failure("她当前的状态是「" + MaidSingletonGuard.describe(state.getSoulState())
                    + "」，不能重复抽离");
        }

        // 第一步必须是备份。抽离会把她从世界里删掉，没有备份就没有退路。
        MaidBackupsManager.save(server, maid);

        // generation 以状态记录为准来计算，而不是读实体 ——
        // 两个来源一旦分叉（例如实体被外部工具改过），就会出现"同一个她有两个代数"，
        // 那会让防重放判断失效。这里以状态为唯一权威，并在不一致时告警。
        int entityGeneration = persistent.getInt(TlmNbtKeys.GENERATION);
        if (entityGeneration != state.getGeneration()) {
            TlmPetCarrier.LOGGER.warn("女仆代数不一致：实体={}，身份记录={}，以身份记录为准（maidId={}）",
                    entityGeneration, state.getGeneration(), maidId);
        }
        int generation = state.getGeneration() + 1;
        persistent.putUUID(TlmNbtKeys.MAID_ID, maidId);
        persistent.putInt(TlmNbtKeys.GENERATION, generation);

        CompoundTag tag = new CompoundTag();
        maid.saveWithoutId(tag);
        MaidCarrierPolicy.stripForExtract(tag);
        if (MaidCarrierPolicy.ensureEntityTypeId(tag) == null) {
            return Result.failure("无法写出实体类型 id，抽离中止（她没有被移除）");
        }

        // 注意：payload 必须在 discard 之前构造，因为它要读实体的派生值（好感度等级）与所在位置。
        CarrierPayload payload = CarrierPayload.from(maid, player, maidId, generation, tag);
        if (payload == null) {
            return Result.failure("打包载荷失败，抽离中止（她没有被移除）");
        }

        Path file = MaidCarrierStore.fileFor(maidId);
        try {
            payload.write(file);
        } catch (IOException e) {
            TlmPetCarrier.LOGGER.error("写入载荷文件失败，抽离中止（她没有被移除）：{}", file, e);
            return Result.failure("写入载荷文件失败：" + e.getMessage());
        }

        // 到这里才真正动世界状态。
        MaidCarrierPolicy.unregister(maid);
        maid.discard();

        // 状态推进紧随其实，且 toCarried() 恰好 +1，与上面算出的 generation 相等。
        state.toCarried(worldName(server));
        MaidCarrierStateStore.write(server, playerId, state);

        TlmPetCarrier.LOGGER.info("已抽离女仆 maidId={} 第 {} 代，落点 {}（名字 {}，好感度 {}）",
                maidId, generation, file, payload.getProfile().getName(), payload.getBond().getFavorability());
        return Result.success(payload, file);
    }

    /** 用存档名而不是维度 ID —— 玩家心里的"世界"是那个存档。 */
    private static String worldName(MinecraftServer server) {
        return server.getWorldData().getLevelName();
    }

    /**
     * @param success 是否成功
     * @param message 失败原因（成功时为空串）
     * @param payload 成功时的载荷
     * @param file    成功时的落盘路径
     */
    public record Result(boolean success, String message, CarrierPayload payload, Path file) {
        static Result success(CarrierPayload payload, Path file) {
            return new Result(true, "", payload, file);
        }

        static Result failure(String message) {
            return new Result(false, message, null, null);
        }
    }
}
