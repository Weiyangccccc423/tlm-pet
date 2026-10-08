package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidCarrierPolicy;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Optional;
import java.util.UUID;

/**
 * 注入：把载荷还原成一位真实的女仆，放进当前世界。
 * <p>
 * 与 {@link MaidExtractor} 相反的方向，但**不是**简单的逆运算：
 * <ul>
 *   <li>实体 UUID 会重新分配 —— 所以任何长期绑定都必须挂在 {@code maidId} 上，绝不能挂实体 UUID。</li>
 *   <li>归属与 AI 称呼要重映射到本次注入的玩家。</li>
 *   <li>世界绑定（家、日程坐标）要以当前世界为准重新清空。</li>
 * </ul>
 */
public final class MaidInjector {
    private MaidInjector() {
    }

    /**
     * @param player  接收女仆的玩家
     * @param payload 载荷
     * @param pos     落点
     * @return 注入结果
     */
    public static Result inject(ServerPlayer player, CarrierPayload payload, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)) {
            return Result.failure("当前不在服务端世界，注入中止");
        }
        if (!payload.verifyChecksum()) {
            return Result.failure("载荷校验和不匹配，文件可能已损坏，注入中止");
        }
        if (payload.getGeneration() < 1) {
            return Result.failure("载荷代数非法，注入中止");
        }

        warnOnCompatDrift(payload);

        Optional<CompoundTag> decoded = payload.decodeMaidNbt();
        if (decoded.isEmpty()) {
            return Result.failure("载荷内的 NBT 无法解析，注入中止");
        }
        CompoundTag tag = decoded.get();

        // 在造实体之前改写归属：这样 readAdditionalSaveData 会直接把她还原成
        // "已驯服且属于该玩家"，不需要事后补 setOwnerUUID，也就不会有一瞬间的无主状态。
        MaidCarrierPolicy.remapOwner(tag, player.getUUID(), player.getName().getString());

        Optional<Entity> created = EntityType.create(tag, level);
        if (created.isEmpty() || !(created.get() instanceof EntityMaid maid)) {
            return Result.failure("载荷无法还原为女仆实体（id 键可能被破坏），注入中止");
        }

        maid.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0.0F);

        if (!level.addFreshEntity(maid)) {
            TlmPetCarrier.LOGGER.error("女仆实体加入世界失败：maidId={}，落点 {}", payload.getMaidId(), pos);
            return Result.failure("女仆实体加入世界失败，注入中止（载荷未被消耗，可重试）");
        }

        // 必须在入世界之后调用：SchedulePos.clear 会读 maid.level 来记录所属维度。
        MaidCarrierPolicy.clearWorldBinding(maid);

        // 身份回写。这样她下一次被抽离时能认得出自己是谁，generation 才会继续递增
        // 而不是从 1 重新开始（否则每次都生成新身份，"同一个她"就断了）。
        CompoundTag persistent = maid.getPersistentData();
        try {
            persistent.putUUID(TlmNbtKeys.MAID_ID, UUID.fromString(payload.getMaidId()));
        } catch (IllegalArgumentException e) {
            TlmPetCarrier.LOGGER.error("载荷中的 maidId 不是合法 UUID，身份回写跳过：{}", payload.getMaidId(), e);
        }
        persistent.putInt(TlmNbtKeys.GENERATION, payload.getGeneration());

        TlmPetCarrier.LOGGER.info("已注入女仆 maidId={} 第 {} 代，落点 {}（名字 {}，好感度 {}）",
                payload.getMaidId(), payload.getGeneration(), pos,
                payload.getProfile().getName(), payload.getBond().getFavorability());
        return Result.success(maid);
    }

    /**
     * 版本漂移只告警、不拦截。
     * <p>
     * 理由是跨版本迁移本来就是本功能的使用场景之一（换大型整合包），
     * 因为 mcVersion 或 tlmVersion 不同就硬性拒绝，会把"她跟着你走"这件事变成一句空话。
     * 真正的硬门槛是 {@code schemaVersion}（在 {@link CarrierPayload#read} 里校验格式名）与校验和，
     * 那两个一旦不符就是数据不可读，没有继续的意义。
     */
    private static void warnOnCompatDrift(CarrierPayload payload) {
        CarrierPayload.Compat current = CarrierPayload.Compat.current();
        String mcVersion = payload.getCompat() == null ? null : payload.getCompat().getMcVersion();
        String tlmVersion = payload.getCompat() == null ? null : payload.getCompat().getTlmVersion();
        if (mcVersion != null && !mcVersion.equals(current.getMcVersion())) {
            TlmPetCarrier.LOGGER.warn("载荷来自 Minecraft {}，当前为 {}，可能存在兼容性问题",
                    mcVersion, current.getMcVersion());
        }
        if (tlmVersion != null && !tlmVersion.equals(current.getTlmVersion())) {
            TlmPetCarrier.LOGGER.warn("载荷来自 Touhou Little Maid {}，当前为 {}，可能存在兼容性问题",
                    tlmVersion, current.getTlmVersion());
        }
    }

    /**
     * @param success 是否成功
     * @param message 失败原因（成功时为空串）
     * @param maid    成功时注入的女仆实体
     */
    public record Result(boolean success, String message, EntityMaid maid) {
        static Result success(EntityMaid maid) {
            return new Result(true, "", maid);
        }

        static Result failure(String message) {
            return new Result(false, message, null);
        }
    }
}
