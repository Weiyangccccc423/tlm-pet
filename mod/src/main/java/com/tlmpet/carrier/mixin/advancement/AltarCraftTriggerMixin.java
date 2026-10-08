package com.tlmpet.carrier.mixin.advancement;

import com.github.tartaricacid.touhoulittlemaid.advancements.altar.AltarCraftTrigger;
import com.tlmpet.carrier.advancement.AchievementTakeover;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 祭坛合成成就的接管（设计文档 §13.4）。
 *
 * <p>这条路径容易漏：{@code maid_base/reborn_maid} 走的是祭坛合成，
 * <b>不</b>经过 {@code MaidEventTrigger}。只堵事件触发器会漏掉它。
 *
 * <p>{@code remap = false} 的理由同 {@code MaidEventTriggerMixin}：目标是 TLM 自己的方法，
 * 不在混淆映射表内。
 */
@Mixin(AltarCraftTrigger.class)
public abstract class AltarCraftTriggerMixin {
    @Inject(
            method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void tlmPet$replaceAdvancement(ServerPlayer player, ResourceLocation recipeId, CallbackInfo ci) {
        if (AchievementTakeover.interceptAltarCraft(player, recipeId)) {
            ci.cancel();
        }
    }
}
