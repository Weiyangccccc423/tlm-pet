package com.tlmpet.carrier.event;

import com.tlmpet.carrier.TlmNbtKeys;
import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        UUID arrivingMaidId = readMaidId(maid);

        MaidSingletonGuard.Decision decision = MaidSingletonGuard.decide(current, arrivingMaidId);

        if (MaidSingletonGuard.isAllowed(decision)) {
            // 放行。但"首次获得"必须在这里立刻登记身份 ——
            // 若推迟到下一 tick 或交给调用点，同一 tick 内连续生成的第二只读到的
            // 仍是"没有女仆"，规则会被绕过。
            if (decision == MaidSingletonGuard.Decision.ALLOW_FIRST_ACQUISITION) {
                recordFirstAcquisition(server, ownerId, maid, arrivingMaidId);
            }
            return;
        }

        // ---- 陷阱 ③：以下动作都不碰方块、不加载区块 ----
        event.setCanceled(true);
        TlmPetCarrier.LOGGER.warn("已拦截第二位女仆：owner={}，来者 maidId={}，玩家当前状态={}",
                ownerId, arrivingMaidId, current == null ? "(无记录)" : current.getSoulState());
        notifyOwner(server, ownerId, current);
    }

    @Nullable
    private static UUID readMaidId(EntityMaid maid) {
        CompoundTag persistent = maid.getPersistentData();
        if (!persistent.hasUUID(TlmNbtKeys.MAID_ID)) {
            return null;
        }
        return persistent.getUUID(TlmNbtKeys.MAID_ID);
    }

    /**
     * 首次获得女仆时建立身份记录。
     * <p>
     * {@code arrivingMaidId} 非空的情况是"她带着身份来的，但玩家还没有记录" ——
     * 典型场景是导入一份别人分享的载荷，或玩家删过存档重来。
     * 此时顺水推舟把她认作"他的她"，而不是另起一个新身份：
     * 后者会让同一份数据在两边拥有不同的 {@code maidId}，桌宠侧就会出现两个她。
     */
    private static void recordFirstAcquisition(MinecraftServer server, UUID ownerId,
                                               EntityMaid maid, @Nullable UUID arrivingMaidId) {
        UUID maidId = arrivingMaidId != null ? arrivingMaidId : UUID.randomUUID();
        CompoundTag persistent = maid.getPersistentData();
        persistent.putUUID(TlmNbtKeys.MAID_ID, maidId);
        if (!persistent.contains(TlmNbtKeys.GENERATION)) {
            persistent.putInt(TlmNbtKeys.GENERATION, 1);
        }

        MaidCarrierState state = MaidCarrierState.fresh(maidId, worldName(server));
        MaidCarrierStateStore.write(server, ownerId, state);
        TlmPetCarrier.LOGGER.info("已登记新的女仆身份：owner={}，maidId={}，世界=「{}」",
                ownerId, maidId, state.getLastSeenWorldId());
    }

    /**
     * 给玩家一句"为什么不行、该怎么办"。
     * <p>
     * 通知失败绝不能让拦截失败 —— 拦截是否生效是正确性问题，提示只是体验，
     * 所以这里吞掉异常并只记日志。
     */
    private static void notifyOwner(MinecraftServer server, UUID ownerId, @Nullable MaidCarrierState current) {
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

    /**
     * 给玩家看的世界名。
     * <p>
     * 用存档名而不是维度 ID：玩家心里的"世界"是那个存档（「幻想乡」），
     * 而不是 {@code minecraft:overworld}。
     */
    private static String worldName(MinecraftServer server) {
        return server.getWorldData().getLevelName();
    }
}
