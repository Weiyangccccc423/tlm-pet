package com.tlmpet.carrier.mixin.entity;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.policy.MaidAdoption;
import com.tlmpet.carrier.policy.MaidSingletonGuard;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 驯服的入口拦截（设计文档 §12.2 途径 1、§12.3）。
 *
 * <h2>为什么必须 mixin，事件做不到</h2>
 * TLM 把数量检查写在 {@code EntityMaid.tameMaid} 的方法体内部：
 *
 * <pre>
 * if (cap.canAdd() || player.isCreative()) { ... this.tame(player); cap.add(); }
 * </pre>
 *
 * 这里有两个各自独立的问题：
 * <ol>
 *   <li><b>创造模式绕过</b>：{@code canAdd()} 返回 false 后，短路逻辑继续看 {@code isCreative()}，
 *       于是创造模式玩家可以无限驯服；而且创造模式下<b>不会执行 {@code cap.add()}</b>，
 *       他的计数器停在旧值，退出创造后还能再驯服一只。</li>
 *   <li><b>这条路径完全不经兜底</b>：驯服一只已经站在世界里的野生女仆不会
 *       {@code addFreshEntity}，所以 {@code EntityJoinLevelEvent} 根本不触发。</li>
 * </ol>
 *
 * 两者叠加的后果是：<b>任何玩家第一次驯服野生女仆都不会被我们记录</b>，
 * 于是规则以为自己面对的是一个"还没有女仆"的玩家。
 *
 * <h2>为什么在 HEAD 注入，而不是改写 {@code isCreative()}</h2>
 * §12.3 给了两个备选（{@code @ModifyExpressionValue} 改 {@code isCreative()}，
 * 或 {@code @Redirect} 改 {@code canAdd()}）。两者都是在<b>修补 TLM 的判断</b>，
 * 于是"创造模式"与"生存模式"仍然走在两条不同的分支上。
 * 在 HEAD 直接<b>用我们自己的权威记录裁决</b>更彻底：两种模式走同一条逻辑，
 * 规则只有一处，而且与兜底事件用的是同一个 {@link MaidSingletonGuard}。
 *
 * <h2>为什么要复刻那三行物品判断</h2>
 * {@code tameMaid} 的入口拿到的只是一个 {@code ItemStack}。若不问青红皂白地拒绝，
 * 那么"手持空手右键野生女仆"（这是一次<b>不会驯服</b>的交互）也会被我们拦下并弹出
 * "你已经有女仆了"—— 这会破坏玩家与野生女仆的所有普通交互。
 * 所以先按 TLM 自己的口径判断"这次交互到底会不会驯服/改变归属"，只有会的时候才插手。
 *
 * <p>{@code remap = false} 是必需的：目标是<b>模组方法</b>，不在混淆映射表里。
 * 代价是 refmap 保持为空（{@code {"mappings":{},"data":{}}}）—— 那是正确状态，不是配置错误。
 */
@Mixin(EntityMaid.class)
public abstract class EntityMaidTameMixin {
    @Inject(method = "tameMaid", at = @At("HEAD"), cancellable = true, remap = false)
    private void tlmPet$guardTaming(ItemStack stack, Player player,
                                   CallbackInfoReturnable<InteractionResult> cir) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        EntityMaid maid = (EntityMaid) (Object) this;

        // 复刻 EntityMaid.tameMaid 里的三行判断：这次交互会不会驯服她、或强行改变归属。
        // isNtr 不要求 !isTame()，因为主人转换工具本来就是对"已经是别人的女仆"用的。
        boolean isNormal = !maid.isTame() && maid.getTamedItem().test(stack);
        boolean isNtr = EntityMaid.getNtrItem().test(stack);
        if (!isNormal && !isNtr) {
            // 不会产生归属变化，交还给 TLM 自己判断。
            return;
        }

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return;
        }
        UUID ownerId = serverPlayer.getUUID();
        MaidCarrierState current = MaidCarrierStateStore.get(server, ownerId).orElse(null);
        UUID arrivingMaidId = MaidAdoption.readMaidId(maid);

        if (MaidSingletonGuard.isAllowed(MaidAdoption.guardDecide(current, arrivingMaidId))) {
            return;
        }

        // 在 HEAD 取消，所以：物品没被消耗、没有驯服音效与粒子、
        // 也没有触发 TAMED_MAID 成就 —— 玩家看到的是"这次交互没发生"，
        // 而不是"驯服成功后又被撤销"（后者会把成就与音效一起发出去，再想收回就晚了）。
        TlmPetCarrier.LOGGER.info("已在入口拦截驯服：owner={}，目标 maidId={}，玩家当前状态={}",
                ownerId, arrivingMaidId, current == null ? "(无记录)" : current.getSoulState());
        MaidAdoption.notifyDenied(server, ownerId, current);
        cir.setReturnValue(InteractionResult.PASS);
    }
}
