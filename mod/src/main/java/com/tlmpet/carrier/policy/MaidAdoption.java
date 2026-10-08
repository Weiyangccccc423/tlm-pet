package com.tlmpet.carrier.policy;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.tlmpet.carrier.util.WorldIds;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 「把这位女仆认作他的她」这件事的单一实现（设计文档 §12.4 / §12.5）。
 *
 * <h2>为什么必须集中在一处</h2>
 * 有三个不同的入口需要做同一件事，而它们的判定必须完全一致 —— 否则同一位女仆会被
 * 登记成两个不同的 {@code maidId}，桌宠侧就会出现两个她：
 * <ol>
 *   <li>{@code EntityJoinLevelEvent}：她作为<b>新实体</b>进入世界（注入、祭坛、照片、胶卷还原）；</li>
 *   <li>{@code MaidTamedEvent}：她在世界里被<b>就地驯服</b> ——
 *       <b>这条路径不经过 ①</b>，因为驯服一只野生女仆不会 {@code addFreshEntity}。
 *       这是"记录根本不知道她存在"的根源；</li>
 *   <li>玩家手动指定（导入载荷时）。</li>
 * </ol>
 *
 * <h2>为什么 {@code arrivingMaidId} 非空时要顺水推舟</h2>
 * 那意味着"她带着身份来的，但玩家这边没有记录" —— 典型场景是导入一份别人分享的载荷，
 * 或玩家删过存档重来。此时若另起一个新身份，同一份数据在两边就会有不同的 {@code maidId}。
 */
public final class MaidAdoption {
    private MaidAdoption() {
    }

    /** 读她身上的逻辑身份；没有则返回 null（尚未登记过）。 */
    @Nullable
    public static UUID readMaidId(EntityMaid maid) {
        CompoundTag persistent = maid.getPersistentData();
        if (!persistent.hasUUID(TlmNbtKeys.MAID_ID)) {
            return null;
        }
        return persistent.getUUID(TlmNbtKeys.MAID_ID);
    }

    /**
     * 首次获得女仆时建立身份记录，并把 {@code maidId} 写到她身上。
     * <p>
     * <b>必须同步完成</b>，不能推迟到下一 tick 或交给调用点：同一 tick 内连续生成的第二只
     * 读到的仍是"没有女仆"，规则会被绕过。
     *
     * @return 最终采用的 {@code maidId}
     */
    public static UUID recordFirstAcquisition(MinecraftServer server, UUID ownerId,
                                             EntityMaid maid, @Nullable UUID arrivingMaidId) {
        UUID maidId = arrivingMaidId != null ? arrivingMaidId : UUID.randomUUID();
        CompoundTag persistent = maid.getPersistentData();
        persistent.putUUID(TlmNbtKeys.MAID_ID, maidId);
        if (!persistent.contains(TlmNbtKeys.GENERATION)) {
            persistent.putInt(TlmNbtKeys.GENERATION, 1);
        }

        MaidCarrierState state = MaidCarrierState.fresh(maidId, WorldIds.of(server));
        MaidCarrierStateStore.write(server, ownerId, state);
        TlmPetCarrier.LOGGER.info("已登记新的女仆身份：owner={}，maidId={}，世界=「{}」",
                ownerId, maidId, state.getLastSeenWorldId());
        return maidId;
    }

    /**
     * 给玩家一句"为什么不行、该怎么办"。
     * <p>
     * <b>通知失败绝不能让拦截失败。</b> 拦截是否生效是正确性问题，提示只是体验 ——
     * 所以这里吞掉异常并只记日志。反过来做（让提示异常冒泡）会导致"因为发消息失败，
     * 所以第二只女仆成功出现"这种荒谬结果。
     */
    public static void notifyDenied(MinecraftServer server, UUID ownerId, @Nullable MaidCarrierState current) {
        ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
        if (player == null) {
            return;
        }
        try {
            player.displayClientMessage(
                    Component.literal(MaidSingletonGuard.denyMessage(current))
                            .withStyle(ChatFormatting.LIGHT_PURPLE),
                    false);
        } catch (RuntimeException e) {
            TlmPetCarrier.LOGGER.error("发送女仆唯一性提示失败：owner={}", ownerId, e);
        }
    }
}
