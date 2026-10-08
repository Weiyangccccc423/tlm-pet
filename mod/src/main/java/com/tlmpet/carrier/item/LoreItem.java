package com.tlmpet.carrier.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 带一句说明文字的纪念材料。
 *
 * <h2>为什么这些物品需要说明</h2>
 * 它们**没有合成表可查**（是成就奖励，不是合成产物），所以玩家拿到手时唯一能知道用途的途径
 * 就是这一行字。尤其 {@code higanbana_keepsake} <b>刻意没有任何用途</b> —— 不说明的话，
 * 玩家一定会把它当成"还没做完的物品"，然后来报 bug。
 *
 * <p>文字本身走翻译键，不硬编码 —— TLM 自己的提示也都是
 * {@code Component.translatable}（例如 {@code message.touhou_little_maid.altar.not_enough_power}），
 * 硬编码中文会让英文客户端看到中英混排。
 */
public class LoreItem extends Item {
    private final String loreKey;

    public LoreItem(String loreKey) {
        super(new Item.Properties());
        this.loreKey = loreKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(loreKey).withStyle(ChatFormatting.GRAY));
    }
}
