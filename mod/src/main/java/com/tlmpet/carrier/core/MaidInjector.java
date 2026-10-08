package com.tlmpet.carrier.core;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.advancement.TlmPetAdvancements;
import com.tlmpet.carrier.policy.MaidCarrierPolicy;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.tlmpet.carrier.util.WorldIds;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
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

        UUID maidId = parseMaidId(payload);
        if (maidId == null) {
            return Result.failure("载荷中的 maidId 不是合法 UUID，注入中止");
        }

        MinecraftServer server = level.getServer();
        MaidCarrierState current = MaidCarrierStateStore.get(server, player.getUUID()).orElse(null);

        // 单女仆规则的前置检查。这里做的是<b>体验</b>而非正确性：
        // 正确性由 MaidSingletonEvents 的兜底拦截保证，但如果只靠它，
        // 玩家会看到女仆"闪一下就没了"却不知道原因，也不知道该怎么办。
        MaidSingletonGuard.Decision decision = MaidSingletonGuard.decide(current, maidId);
        if (!MaidSingletonGuard.isAllowed(decision)) {
            return Result.failure(MaidSingletonGuard.denyMessage(current));
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

        // 身份必须在入世界之前写到实体上：兜底拦截是在 addFreshEntity 内部<b>同步</b>触发的，
        // 它正是靠这个 maidId 区分"来者就是她"（放行）与"多出了一只"（拦截）。
        // 若沿用 Phase 1 写在 addFreshEntity 之后的顺序，我们自己的注入会被自己拦掉。
        CompoundTag persistent = maid.getPersistentData();
        persistent.putUUID(TlmNbtKeys.MAID_ID, maidId);
        persistent.putInt(TlmNbtKeys.GENERATION, payload.getGeneration());

        maid.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0.0F);

        if (!level.addFreshEntity(maid)) {
            TlmPetCarrier.LOGGER.error("女仆实体加入世界失败：maidId={}，落点 {}", maidId, pos);
            return Result.failure("女仆实体加入世界失败，注入中止（载荷未被消耗，可重试）");
        }

        // 必须在入世界之后调用：SchedulePos.clear 会读 maid.level 来记录所属维度。
        MaidCarrierPolicy.clearWorldBinding(maid);

        // 状态推进刻意放在"实体确认入世界之后"。若提前写，一旦 addFreshEntity 失败，
        // 记录就会说"她在世界里"而世界找不到她 —— 玩家既迎不回也放手不了。
        // 放在之后则最坏情况只是状态停留在 CARRIED，重试一次即可。
        String worldId = WorldIds.of(server);
        MaidCarrierState next;
        if (current != null && current.isHer(maidId)) {
            // 她的数据回来了（例如曾被宣告失散）。沿用原记录，保住她的身份与代数连续性。
            next = current;
        } else {
            // 两种情形：首次获得（current == null），或记录属于另一位已被失散的女仆。
            // 后者必须另起身份 —— 沿用旧记录会让记录 / 实体 / 载荷三方的 maidId 分叉，
            // 而 maidId 是"她回来"与"多出一只"之间唯一的判据。
            next = MaidCarrierState.fresh(maidId, worldId);
        }
        next.adoptGeneration(payload.getGeneration());
        next.toInWorld(worldId);
        MaidCarrierStateStore.write(server, player.getUUID(), next);

        TlmPetCarrier.LOGGER.info("已注入女仆 maidId={} 第 {} 代，落点 {}（名字 {}，好感度 {}）",
                maidId, payload.getGeneration(), pos,
                payload.getProfile().getName(), payload.getBond().getFavorability());

        // 成就：第一次迎回 + 跨世界重逢。放最后 —— 世界状态已经确定，此时发奖励才对玩家是"可信的"。
        // 两者顺序无关紧要：award 幂等，重复调用不会重复发奖（reunion 也由成就接管的 mixin 授予）。
        TlmPetAdvancements.award(player, TlmPetAdvancements.FIRST_REUNION);
        TlmPetAdvancements.award(player, TlmPetAdvancements.REUNION);
        return Result.success(maid);
    }

    /**
     * @return 合法则返回 maidId，否则 null。
     *         <p>
     *         非法 maidId 必须中止注入而不是随机补一个：那会让同一个人在不同世界里
     *         拥有不同身份，桌宠侧会认为是两个她。
     */
    @Nullable
    private static UUID parseMaidId(CarrierPayload payload) {
        try {
            return UUID.fromString(payload.getMaidId());
        } catch (IllegalArgumentException | NullPointerException e) {
            TlmPetCarrier.LOGGER.error("载荷中的 maidId 不是合法 UUID：{}", payload.getMaidId(), e);
            return null;
        }
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
