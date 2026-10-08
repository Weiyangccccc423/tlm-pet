package com.tlmpet.carrier.event;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidAdoption;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTamedEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 驯服登记：把"就地被驯服的野生女仆"纳入我们的身份记录（设计文档 §12.2 途径 1、§12.5）。
 *
 * <h2>为什么这条路径必须单独处理</h2>
 * {@link MaidSingletonEvents} 挂的是 {@code EntityJoinLevelEvent}，而它只在实体
 * <b>被加进世界</b>时触发。驯服一只<b>已经站在世界里</b>的野生女仆不会 {@code addFreshEntity} ——
 * 她一直是那个实体，只是归属变了。于是：
 *
 * <pre>
 * 玩家驯服野生女仆 → 世界里的实体没变 → 兜底事件不触发 → 我们这边"没有女仆"
 *                  → 她可以做任何"只有一只女仆时"才被禁止的事
 * </pre>
 *
 * 这不是创造模式才有的问题，任何玩家第一次驯服野生女仆都会命中。
 *
 * <h2>为什么用 {@code MaidTamedEvent} 而不是调用点拦截</h2>
 * 这里的职责是<b>登记</b>，不是阻止 —— 而 {@code MaidTamedEvent} 恰好只能登记：
 * 它的注释明确写着「事件无法取消」，触发位置在 {@code EntityMaid.tameMaid} 里
 * {@code this.tame(player)} 与 {@code cap.add()} <b>之后</b>。
 * 阻止由 {@code EntityMaidTameMixin} 在方法入口完成 —— 两者分工明确，
 * 且都改不动对方的前提（登记发生在"已经确定驯服成功"之后，所以不会登记一位没驯成的女仆）。
 *
 * <p>注意本事件对<b>主人转换工具</b>（{@code isOwnerConversion() == true}）同样触发：
 * 那是把<b>别人的</b>女仆强行变成自己的。这也是"获得女仆"的一种，同样要纳入记录，
 * 否则玩家可以先抢一只、再驯一只。
 */
@Mod.EventBusSubscriber(modid = TlmPetCarrier.MOD_ID)
public final class MaidTamingEvents {
    private MaidTamingEvents() {
    }

    @SubscribeEvent
    public static void onMaidTamed(MaidTamedEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        // 只处理服务端。玩家的 capability 与 SavedData 都只存在于服务端，
        // 客户端侧触发时读到的状态是空的，登记会造成分叉。
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (maid.level().isClientSide()) {
            return;
        }

        UUID ownerId = player.getUUID();
        MaidCarrierState current = MaidCarrierStateStore.get(server, ownerId).orElse(null);
        UUID arrivingMaidId = MaidAdoption.readMaidId(maid);

        // 走到这里说明驯服已经发生，正常情况下入口的 mixin 已经放行过。
        // 但仍要重新判一次：mixin 是"尽可能早"的防线，而这里是最后一道 ——
        // 若两者判断不一致（例如状态在这一瞬间被别处改了），宁可在这里记日志暴露出来，
        // 也不要静默地产生第二位她。
        if (current != null && current.hasMaid() && !current.isHer(arrivingMaidId)) {
            TlmPetCarrier.LOGGER.error(
                    "登记时发现玩家已有女仆却仍驯服成功：owner={}，来的 maidId={}，当前={}。"
                            + "这说明入口拦截被绕过了，请连同 EntityMaidTameMixin 一起排查",
                    ownerId, arrivingMaidId, current.getSoulState());
            return;
        }

        if (arrivingMaidId == null) {
            MaidAdoption.recordFirstAcquisition(server, ownerId, maid, null);
            return;
        }
        // 她已经带着身份（例如从胶卷/照片还原而来，或主人转换工具抢来的）。
        // 若玩家记录里没有她，就把她认作他的她 —— 这与兜底事件的口径完全一致。
        if (MaidAdoption.guardDecide(current, arrivingMaidId)
                == MaidSingletonGuard.Decision.ALLOW_FIRST_ACQUISITION) {
            MaidAdoption.recordFirstAcquisition(server, ownerId, maid, arrivingMaidId);
        }
    }
}
