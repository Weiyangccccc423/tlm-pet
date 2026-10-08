package com.tlmpet.carrier.mixin.advancement;

import com.github.tartaricacid.touhoulittlemaid.advancements.maid.MaidEventTrigger;
import com.tlmpet.carrier.advancement.AchievementTakeover;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 TLM 的事件触发器入口接管成就授予（设计文档 §13.4）。
 *
 * <p>用完整方法描述符而不是裸方法名：{@code SimpleCriterionTrigger} 自己也有一个
 * {@code trigger} 方法，裸名字将来可能因为上游加重载而指错目标。
 *
 * <p>在 {@code HEAD} 处 {@code cancel()} 而不是在尾部改写：原版那条授予一旦发生就无法
 * 撤销（{@code AdvancementEarnEvent} 不可取消），所以必须让它根本不发生。
 *
 * <p>{@code remap = false}：{@code trigger} 是 TLM 自己的方法，从不在 Minecraft 的混淆映射
 * 表里，所以没有东西可重映射（不加会直接编译失败：{@code Unable to locate obfuscation
 * mapping}）。描述符里的 {@code net.minecraft.*} 是官方类名 —— Forge 1.20.1 在生产环境同样
 * 使用官方类名，因此这一处也不需要重映射。
 */
@Mixin(MaidEventTrigger.class)
public abstract class MaidEventTriggerMixin {
    @Inject(
            method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void tlmPet$replaceAdvancement(ServerPlayer player, String eventName, CallbackInfo ci) {
        if (AchievementTakeover.interceptMaidEvent(player, eventName)) {
            ci.cancel();
        }
    }
}
