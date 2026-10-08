package com.tlmpet.carrier.command;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.core.CarrierPayload;
import com.tlmpet.carrier.core.MaidCarrierStore;
import com.tlmpet.carrier.core.MaidExtractor;
import com.tlmpet.carrier.core.MaidInjector;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.MaidRayTraceHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tlm-pet} 命令。
 *
 * <pre>
 * /tlm-pet export          抽离视线内（或 8 格内最近的）自己的女仆
 * /tlm-pet import &lt;标识&gt;   注入一位女仆，标识可以是 maidId 前缀或她的名字
 * /tlm-pet list            列出本机全部载荷
 * </pre>
 *
 * <h2>关于权限等级</h2>
 * 目前是 <b>0</b>（任何玩家可用）。这是 Phase 1 的临时状态，故意的：
 * Phase 1 只验证政策层（剥离/重映射/还原）是否正确，D7/D8 的「每位玩家仅一个女仆」规则
 * 要到 Phase 1b 才落地。在那之前，同一个载荷可以被反复注入 —— 也就是能刷出多个女仆。
 * 因此每次执行都会打一条 warning，提醒这个窗口是开着的。
 * <p>
 * Phase 1b 落地后，这里应改为按 {@code MaidCarrierState} 判定，而不是简单提权：
 * "她是否已在某个世界"是<b>状态问题</b>，不是权限问题，提权只是掩盖它。
 */
@Mod.EventBusSubscriber(modid = TlmPetCarrier.MOD_ID)
public final class TlmPetCommand {
    private static final String ROOT = "tlm-pet";
    private static final double SEARCH_RADIUS = 8.0D;

    private TlmPetCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("export")
                        .executes(TlmPetCommand::exportMaid))
                .then(Commands.literal("import")
                        .then(Commands.argument("who", StringArgumentType.string())
                                .suggests(TlmPetCommand::suggestPayloads)
                                .executes(TlmPetCommand::importMaid)))
                .then(Commands.literal("list")
                        .executes(TlmPetCommand::listPayloads)));
    }

    // ==================================================================================
    // list
    // ==================================================================================

    private static int listPayloads(CommandContext<CommandSourceStack> context) {
        List<Path> files = MaidCarrierStore.listAll();
        if (files.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "还没有任何载荷。目录：" + MaidCarrierStore.maidsDir()), false);
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(
                "共 " + files.size() + " 份载荷：" + MaidCarrierStore.maidsDir()), false);
        for (Path file : files) {
            Optional<CarrierPayload> parsed = CarrierPayload.read(file);
            if (parsed.isEmpty()) {
                context.getSource().sendSuccess(() -> Component.literal("  [损坏] " + file.getFileName())
                        .withStyle(ChatFormatting.RED), false);
                continue;
            }
            CarrierPayload payload = parsed.get();
            boolean intact = payload.verifyChecksum();
            context.getSource().sendSuccess(() -> Component.literal("  " + payload.describe()
                    + (intact ? "" : "  [校验和不符]"))
                    .withStyle(intact ? ChatFormatting.GRAY : ChatFormatting.RED), false);
        }
        return files.size();
    }

    // ==================================================================================
    // export
    // ==================================================================================

    private static int exportMaid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        warnWhileSingletonNotEnforced(context);

        Optional<EntityMaid> target = findTargetMaid(player);
        if (target.isEmpty()) {
            context.getSource().sendFailure(Component.literal(
                    "没有找到你的女仆。请看着她，或站到她 " + (int) SEARCH_RADIUS + " 格以内。"));
            return 0;
        }

        MaidExtractor.Result result = MaidExtractor.extract(player, target.get());
        if (!result.success()) {
            context.getSource().sendFailure(Component.literal("抽离失败：" + result.message()));
            return 0;
        }

        CarrierPayload payload = result.payload();
        context.getSource().sendSuccess(() -> Component.literal("已抽离：" + payload.describe()), false);
        context.getSource().sendSuccess(() -> Component.literal("载荷：" + result.file())
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "maidId：" + payload.getMaidId()).withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /**
     * 找目标女仆：先按视线射线，失败再退化为"附近最近的一个"。
     * <p>
     * 之所以要退化，是因为射线要求玩家瞄得足够准，而"我面前就她一个"是最常见的实际情形，
     * 让玩家为了对准而反复微调视角没有意义。
     */
    private static Optional<EntityMaid> findTargetMaid(ServerPlayer player) {
        Optional<EntityMaid> traced = MaidRayTraceHelper.rayTraceMaid(player, (int) SEARCH_RADIUS);
        if (traced.isPresent() && traced.get().isOwnedBy(player)) {
            return traced;
        }
        List<EntityMaid> nearby = player.level().getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(SEARCH_RADIUS), maid -> maid.isOwnedBy(player));
        return nearby.stream().findFirst();
    }

    // ==================================================================================
    // import
    // ==================================================================================

    private static int importMaid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        warnWhileSingletonNotEnforced(context);

        String token = StringArgumentType.getString(context, "who");
        List<Path> candidates = MaidCarrierStore.search(token);
        if (candidates.isEmpty()) {
            context.getSource().sendFailure(Component.literal("找不到匹配的载荷：" + token));
            return 0;
        }
        if (candidates.size() > 1) {
            // 不替玩家猜。名字重名或前缀太短时，宁可让他自己收敛输入。
            context.getSource().sendFailure(Component.literal(
                    "有 " + candidates.size() + " 份载荷匹配「" + token + "」，请提供更长的 maidId 前缀："));
            for (Path candidate : candidates) {
                CarrierPayload.read(candidate).ifPresent(payload -> context.getSource().sendSuccess(
                        () -> Component.literal("  " + payload.getMaidId() + "  " + payload.describe())
                                .withStyle(ChatFormatting.GRAY), false));
            }
            return 0;
        }

        Path file = candidates.get(0);
        Optional<CarrierPayload> parsed = CarrierPayload.read(file);
        if (parsed.isEmpty()) {
            context.getSource().sendFailure(Component.literal("载荷文件无法解析：" + file));
            return 0;
        }

        MaidInjector.Result result = MaidInjector.inject(player, parsed.get(), player.blockPosition());
        if (!result.success()) {
            context.getSource().sendFailure(Component.literal("注入失败：" + result.message()));
            return 0;
        }

        CarrierPayload payload = parsed.get();
        context.getSource().sendSuccess(() -> Component.literal("她回来了：" + payload.describe()), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestPayloads(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<String> ids = MaidCarrierStore.listAll().stream()
                .map(CarrierPayload::read)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(CarrierPayload::getMaidId)
                .toList();
        return SharedSuggestionProvider.suggest(ids, builder);
    }

    private static void warnWhileSingletonNotEnforced(CommandContext<CommandSourceStack> context) {
        TlmPetCarrier.LOGGER.warn("玩家 {} 在「单女仆规则尚未生效」的阶段使用了 /{}",
                context.getSource().getTextName(), ROOT);
    }
}
