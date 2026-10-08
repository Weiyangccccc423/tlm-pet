package com.tlmpet.carrier.event;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidAdoption;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.tlmpet.carrier.state.SoulState;
import com.tlmpet.carrier.util.WorldIds;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 单女仆规则的兜底拦截（设计文档 §12.5，风险 R12）。
 *
 * <h2>为什么兜底拦截才是正确性的载体</h2>
 * 调用点拦截（祭坛 / 照片 / 胶卷 / 驯服 / 智能方块）是为了<b>体验</b> —— 别让玩家白扣材料。
 * 但正确性不能建立在"6 个入口都堵住了"这个假设上：漏一个就等于没有。
 * 而所有路径最终都必须把女仆实体加进世界，所以在这一个动作上裁决是收敛的。
 *
 * <h2>使用 {@code EntityJoinLevelEvent} 的三个致命陷阱</h2>
 * 这三点都来自 Forge 的事件文档，任何一条写错都会造成灾难性后果：
 * <ol>
 *   <li><b>必须跳过 {@code loadedFromDisk() == true}</b>。该事件不只在 {@code addFreshEntity} 时触发，
 *       也在<b>从磁盘加载区块</b>时触发（{@code PersistentEntitySectionManager#addNewEntity}）。
 *       若不跳过，玩家每次靠近一位已存在的女仆都会命中拦截 —— <b>等于把世界里原有的女仆删掉</b>。
 *       这是整个方案最容易造成灾难的一点。</li>
 *   <li><b>必须判断服务端</b>。该事件在客户端与服务端两侧都会触发。</li>
 *   <li><b>不能做世界交互</b>。触发时 {@code LevelChunk} 可能还没提升到 {@code ChunkStatus.FULL}，
 *       读写方块或调用会加载区块的 API 会<b>死锁</b>。本类只读实体自身的 NBT、
 *       读写 {@code SavedData}、给玩家发消息 —— 三者都不碰方块，也不触发区块加载。</li>
 * </ol>
 *
 * <h2>为什么不能让 {@code MaidTamedEvent} 承担这个职责</h2>
 * 它的触发位置在 {@code EntityMaid.java:701}，位于 {@code this.tame(player)} 与 {@code cap.add()}
 * <b>之后</b>，此时女仆已经属于玩家了。它只能用于登记或纠正，无法阻止。
 */
@Mod.EventBusSubscriber(modid = TlmPetCarrier.MOD_ID)
public final class MaidSingletonEvents {
    private MaidSingletonEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // ---- 陷阱 ②：两侧都会触发，只在服务端裁决 ----
        if (event.getLevel().isClientSide()) {
            return;
        }
        // ---- 陷阱 ①：区块加载也会触发，必须跳过，否则会删掉世界里已有的女仆 ----
        if (event.loadedFromDisk()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }

        UUID ownerId = maid.getOwnerUUID();
        // 野生女仆、或被清过归属的女仆与"玩家的唯一女仆"无关，一律放行。
        // 注意方法名是 isTame() 而非 Yarn 的 isTamed() —— Mojang 映射少一个 d。
        if (ownerId == null || !maid.isTame()) {
            return;
        }

        MinecraftServer server = level.getServer();
        MaidCarrierState current = MaidCarrierStateStore.get(server, ownerId).orElse(null);
        UUID arrivingMaidId = MaidAdoption.readMaidId(maid);

        MaidSingletonGuard.Decision decision = MaidSingletonGuard.decide(current, arrivingMaidId);

        if (MaidSingletonGuard.isAllowed(decision)) {
            // 放行。但"首次获得"必须在这里立刻登记身份 ——
            // 若推迟到下一 tick 或交给调用点，同一 tick 内连续生成的第二只读到的
            // 仍是"没有女仆"，规则会被绕过。
            if (decision == MaidSingletonGuard.Decision.ALLOW_FIRST_ACQUISITION) {
                MaidAdoption.recordFirstAcquisition(server, ownerId, maid, arrivingMaidId);
                return;
            }
            // ALLOW_IS_HER：她本来在桌宠里或胶卷里，现在真的落进世界了 ——
            // 把记录推进到 IN_WORLD。
            //
            // 这件事必须在这里做，不能放在 MaidAndItemTransformEvent.ToMaid 里：
            // 那个事件在 addFreshEntity <b>之前</b>触发，若那时就把状态改成 IN_WORLD，
            // 紧接着本事件就会看到她"已经在世界里"，把一个合法的还原判成复制品。
            // 而放在这里，判定用的是"她进来之前"的状态，正是我们想要的语义。
            promoteToInWorld(server, ownerId, current);
            return;
        }

        // ---- 陷阱 ③：以下动作都不碰方块、不加载区块 ----
        event.setCanceled(true);
        TlmPetCarrier.LOGGER.warn("已拦截第二位女仆：owner={}，来者 maidId={}，玩家当前状态={}，判定={}",
                ownerId, arrivingMaidId, current == null ? "(无记录)" : current.getSoulState(), decision);
        MaidAdoption.notifyDenied(server, ownerId, current);
    }

    /**
     * 她落进世界了，把记录从 {@code CARRIED} / {@code FILM_HELD} 推进到 {@code IN_WORLD}。
     * <p>
     * 只在状态确实需要推进时才写盘 —— 这个方法会在她每次<b>新</b>进入世界时被调用，
     * 而绝大多数情况（野外生成、祭坛新造）走的是首次获得那条分支。
     */
    private static void promoteToInWorld(MinecraftServer server, UUID ownerId,
                                         @Nullable MaidCarrierState current) {
        if (current == null || current.getSoulState() == SoulState.IN_WORLD) {
            return;
        }
        if (current.toInWorld(WorldIds.of(server))) {
            MaidCarrierStateStore.write(server, ownerId, current);
            TlmPetCarrier.LOGGER.info("她已回到世界：owner={}，maidId={}", ownerId, current.getMaidId());
        }
    }
}
