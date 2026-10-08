package com.tlmpet.carrier.item;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.core.CarrierPayload;
import com.tlmpet.carrier.core.MaidCarrierStore;
import com.tlmpet.carrier.core.MaidInjector;
import com.tlmpet.carrier.init.InitItems;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.tlmpet.carrier.util.WorldIds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * 迎回之铃：让玩家在世界里直接把她唤回来（设计文档 §14.3、§14.5）。
 *
 * <h2>它是便利，不是门槛</h2>
 * 设计上<b>刻意</b>让"迎回"有第二条路径（桌宠界面）。铃只是省事的那条 —— 如果它是唯一入口，
 * 那么任何一个成就没拿到、材料没凑齐的玩家就永久玩不下去，而这是个关于陪伴的功能，
 * 不该被资源卡住。
 *
 * <h2>绑定 maidId，绝不绑实体 UUID（风险 R21）</h2>
 * 注入之后她的实体 UUID 会被重新分配。任何以实体 UUID 为键的绑定（TLM 的 {@code ItemServantBell}
 * 就是这种做法）在跨世界之后必然失联。这里全程只认 {@link MaidCarrierState#getMaidId()}。
 */
public class RecallBellItem extends Item {
    /** 基础冷却 30 秒。 */
    private static final int COOLDOWN_TICKS = 20 * 30;
    /** 带着归乡灵玉时降到 5 秒。 */
    private static final int COOLDOWN_TICKS_WITH_JADE = 20 * 5;

    public RecallBellItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            recall(serverLevel, serverPlayer);
        }
        // 两侧都返回"成功"，让手臂动作在客户端播放；服务端用 CONSUME 表示这次交互处理完毕，
        // 它不会消耗物品（可重复使用正是设计意图）。
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private void recall(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        MaidCarrierState state = MaidCarrierStateStore.get(server, player.getUUID()).orElse(null);

        if (state == null || !state.hasMaid() || state.getMaidId() == null) {
            reject(player, "她不在你身边。先让桌宠把她的数据迎回来，铃才有回应的对象。");
            return;
        }

        // 只有 CARRIED（她在桌宠里）才能被叫回来。其余状态各有各的正当理由，逐个说清楚 ——
        // 一句笼统的"现在不能用"会让玩家以为是 bug。
        //
        // IN_WORLD 要再分两种：她可能就在这个存档里（那没什么可做的），也可能在<b>另一个存档</b>里
        // （玩家换世界时没带她）。后者是玩家最容易困惑的情形，必须把她所在的世界名说出来 ——
        // 这正是 lastSeenWorldId 存在的唯一用途。
        String blocked = switch (state.getSoulState()) {
            case CARRIED -> null;
            case IN_WORLD -> isElsewhere(state, level)
                    ? "她还在「" + state.getLastSeenWorldId() + "」里。先用桌宠把她收起来，才能带她来这边。"
                    : "她已经在身边了。";
            case FILM_HELD -> "她已经是一卷胶卷了 —— 先想好要怎么对待这卷胶卷，铃叫不回一张胶卷。";
            case NONE -> "她不在你身边。";
        };
        if (blocked != null) {
            reject(player, blocked);
            return;
        }

        UUID maidId = state.getMaidId();
        Optional<Path> file = MaidCarrierStore.findById(maidId.toString());
        if (file.isEmpty()) {
            reject(player, "找不到她的数据（" + shortId(maidId) + "…）。去桌宠里看看她还在不在。");
            return;
        }
        Optional<CarrierPayload> parsed = CarrierPayload.read(file.get());
        if (parsed.isEmpty()) {
            reject(player, "她的数据文件读不出来，可能已经损坏。原始文件仍在：" + file.get().getFileName());
            return;
        }

        MaidInjector.Result result = MaidInjector.inject(player, parsed.get(), findSpawnPos(level, player));
        if (!result.success()) {
            reject(player, result.message());
            return;
        }

        player.getCooldowns().addCooldown(this, cooldownTicks(player));
        player.displayClientMessage(
                Component.literal("她回来了。").withStyle(ChatFormatting.LIGHT_PURPLE), false);
        TlmPetCarrier.LOGGER.info("玩家 {} 用迎回之铃召回了 maidId={}",
                player.getName().getString(), maidId);
    }

    /**
     * 她是不是在<b>另一个存档</b>里。
     * <p>
     * 只用来决定提示怎么写，<b>不参与任何判定</b> —— 判定一律走 {@code soulState}。
     * 所以即便两个存档重名（都会导致这里判成"就在这边"），也只会让提示略显含糊。
     */
    private static boolean isElsewhere(MaidCarrierState state, ServerLevel level) {
        String seen = state.getLastSeenWorldId();
        return seen != null && !seen.isEmpty() && !seen.equals(WorldIds.of(level.getServer()));
    }

    /**
     * 带着归乡灵玉时冷却大幅缩短（§14.4）。
     * <p>
     * 刻意<b>不消耗</b>灵玉：它是纪念物兼便利品，每用一次就磨损掉会让玩家舍不得用，
     * 那就把"便利"变成了新的焦虑。
     */
    private static int cooldownTicks(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(InitItems.HOMING_JADE.get())) {
                return COOLDOWN_TICKS_WITH_JADE;
            }
        }
        return COOLDOWN_TICKS;
    }

    /**
     * 尽量把她放在玩家面前的实心地面上。
     * <p>
     * 找不到合适的地面就退回玩家自身所在的位置 —— 宁可和她重叠一瞬，也不要让她落进岩浆或悬空。
     */
    private static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player) {
        BlockPos ahead = player.blockPosition().relative(player.getDirection());
        BlockPos below = ahead.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return ahead;
        }
        return player.blockPosition();
    }

    /** 只显示前 8 位 —— 完整 UUID 对玩家没有意义，但截断部分足以和日志对上。 */
    private static String shortId(UUID maidId) {
        return maidId.toString().substring(0, 8);
    }

    private static void reject(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.GRAY), false);
    }
}
