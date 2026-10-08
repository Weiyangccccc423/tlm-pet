package com.tlmpet.carrier.event;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidAdoption;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 让她在「世界里」与「胶卷里」之间切换时，把记录同步过去（设计文档 §12.6.1）。
 *
 * <h2>这个事件为什么正好够用</h2>
 * {@code MaidAndItemTransformEvent} 是 TLM 自己的扩展点，其注释列出的触发场景是
 * 「魂符收取女仆、相机拍照、女仆死亡掉落胶片」。也就是说 TLM <b>所有</b>把女仆与物品
 * 互相转换的路径都会经过它 —— 我们不需要去 mixin 相机、魂符、死亡掉落中的任何一个。
 *
 * <h2>一个意外的好性质：胶卷天然带着她的身份</h2>
 * {@code ItemFilm.maidToFilm} 用 {@code maid.saveWithoutId()} 写入 {@code MaidInfo}，
 * 而 {@code filmToMaid} 用 {@code maid.readAdditionalSaveData()} 读回。我们的
 * {@code MAID_ID} 就存在实体的持久数据里（{@code ForgeData}），因此：
 *
 * <pre>
 * 她 → 胶卷 → 她'
 * maidId 一路不变，于是复原出来的她会被唯一性规则判成 ALLOW_IS_HER 而放行
 * </pre>
 *
 * 换句话说，TLM 自己的胶卷机制与我们的身份体系<b>开箱即兼容</b>，这里要做的只是
 * 把 {@code soulState} 跟着改过去，否则记录会一直以为"她还在世界里"。
 *
 * <h2>只处理服务端</h2>
 * 事件在两侧都会 post，而 {@code SavedData} 与玩家 capability 都只存在于服务端。
 * 在客户端同步状态会造成分叉，所以客户端直接返回。
 */
@Mod.EventBusSubscriber(modid = TlmPetCarrier.MOD_ID)
public final class MaidFilmEvents {
    private MaidFilmEvents() {
    }

    /** 她 → 物品（相机拍照 / 魂符收取 / 死亡掉落）。 */
    @SubscribeEvent
    public static void onToItem(MaidAndItemTransformEvent.ToItem event) {
        EntityMaid maid = event.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        // 事件本身不携带玩家，归属从女仆身上读 —— 这比"最近的玩家"可靠得多。
        UUID ownerId = maid.getOwnerUUID();
        if (ownerId == null) {
            // 野生女仆被相机拍照、或未驯服的女仆死亡掉落：与"他的唯一女仆"无关。
            return;
        }

        MinecraftServer server = maid.getServer();
        if (server == null) {
            return;
        }
        MaidCarrierState current = MaidCarrierStateStore.get(server, ownerId).orElse(null);
        if (current == null || !current.isHer(MaidAdoption.readMaidId(maid))) {
            // 没有记录，或来者不是她（例如玩家在用别人的胶卷）—— 都不该改状态。
            // 前者会由 EntityJoinLevelEvent 在她重新落进世界时登记。
            return;
        }
        if (current.toFilmHeld()) {
            MaidCarrierStateStore.write(server, ownerId, current);
            TlmPetCarrier.LOGGER.info("她已被收进物品：owner={}，maidId={}，状态={}",
                    ownerId, current.getMaidId(), current.getSoulState());
        }
    }

    // 刻意**不**订阅 MaidAndItemTransformEvent.ToMaid。
    //
    // 那个事件在 addFreshEntity <b>之前</b>触发，若在那里就把状态从 FILM_HELD 改成 IN_WORLD，
    // 紧接着 EntityJoinLevelEvent 就会看到她"已经在世界里"，从而把一个合法的胶卷还原
    // 误判成「复制品」而拦掉。
    //
    // 正确的做法是让"她真的落进世界"这个事实由 EntityJoinLevelEvent 去记录 ——
    // 那里既知道她进来了，也在正确的时序上。见 MaidSingletonEvents#promoteToInWorld。
}
