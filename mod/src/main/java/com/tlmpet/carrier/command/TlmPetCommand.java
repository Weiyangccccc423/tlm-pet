package com.tlmpet.carrier.command;

import com.tlmpet.carrier.TlmPetCarrier;
import com.tlmpet.carrier.core.CarrierPayload;
import com.tlmpet.carrier.core.MaidArchive;
import com.tlmpet.carrier.core.MaidCarrierStore;
import com.tlmpet.carrier.core.MaidExtractor;
import com.tlmpet.carrier.core.MaidInjector;
import com.tlmpet.carrier.state.MaidCarrierState;
import com.tlmpet.carrier.state.MaidCarrierStateStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.MaidRayTraceHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tlm-pet} 命令。
 *
 * <pre>
 * /tlm-pet export            抽离视线内（或 8 格内最近的）自己的女仆
 * /tlm-pet import &lt;标识&gt;     注入一位女仆，标识可以是 maidId 前缀或她的名字
 * /tlm-pet list              列出本机全部载荷
 * /tlm-pet status [玩家]     查看身份记录（她在哪个世界、什么状态、第几代）
 * /tlm-pet reset &lt;玩家&gt;      清除身份记录（权限 2，管理/调试用逃生口）
 * </pre>
 *
 * <h2>为什么 {@code export} / {@code import} 是权限 0</h2>
 * 唯一性已经由 {@code MaidCarrierState} 的状态机强制，不再依赖权限：
 * <ul>
 *   <li>{@code export} 只允许抽离<b>自己</b>的女仆（{@code isOwnedBy} 校验），
 *       且要求她的状态必须是 {@code IN_WORLD}；</li>
 *   <li>{@code import} 会先过 {@link com.tlmpet.carrier.policy.MaidSingletonGuard}，
 *       她已经在别处时会被拒绝。</li>
 * </ul>
 * 所以权限 0 不会开出口子 —— "她是否已被某个世界持有"是<b>状态问题</b>，
 * 用权限去管只会掩盖它，而且会让单机玩家为了用一个陪伴功能去找 OP。
 *
 * <p>{@code reset} 是另一回事：它是刻意保留的后门（§12.7），能绕过全部规则，
 * 所以必须权限 2，且每次执行都会记一条 warning 日志。
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
                        .executes(TlmPetCommand::listPayloads))
                .then(Commands.literal("status")
                        .executes(TlmPetCommand::showOwnStatus)
                        .then(Commands.argument("player", StringArgumentType.string())
                                .suggests(TlmPetCommand::suggestPlayers)
                                .executes(TlmPetCommand::showPlayerStatus)))
                .then(Commands.literal("lost")
                        // 刻意要求玩家逐字打出确认串，而不是一个 flag 或一次回车。
                        // 这是不可逆操作里最容易造成"平白失去她"的一个（§12.7），
                        // 所以让它无法被误触、也无法被脚本化地一把梭。
                        .then(Commands.literal("declare")
                                .then(Commands.literal("confirm")
                                        .executes(TlmPetCommand::declareLost))))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .suggests(TlmPetCommand::suggestPlayers)
                                .executes(TlmPetCommand::resetPlayer))));
    }

    // ==================================================================================
    // status
    // ==================================================================================

    private static int showOwnStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return showStatusFor(context, context.getSource().getPlayerOrException());
    }

    private static int showPlayerStatus(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = findPlayer(context, StringArgumentType.getString(context, "player"));
        if (target == null) {
            return 0;
        }
        return showStatusFor(context, target);
    }

    private static int showStatusFor(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        MinecraftServer server = context.getSource().getServer();
        String who = player.getName().getString();
        Optional<MaidCarrierState> found = MaidCarrierStateStore.get(server, player.getUUID());

        if (found.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.literal(who + "：还没有身份记录（尚未拥有女仆）"), false);
            return 1;
        }

        MaidCarrierState state = found.get();
        String line = who + "：" + state.describe()
                + "\n  状态=" + state.getSoulState()
                + "  代数=" + state.getGeneration()
                + (state.isReleased() ? "  [已正式放手，此 maidId 已封存]" : "")
                + "\n  maidId=" + state.getMaidId();
        context.getSource().sendSuccess(
                () -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // ==================================================================================
    // reset（刻意保留的后门）
    // ==================================================================================

    private static int resetPlayer(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer target = findPlayer(context, name);
        if (target == null) {
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        MaidCarrierStateStore.clear(server, target.getUUID());

        // 后门必须留痕：这是唯一能绕过唯一性规则的入口，出问题时要能查出是谁用的。
        TlmPetCarrier.LOGGER.warn("管理员「{}」清除了玩家 {}（{}）的女仆身份记录",
                context.getSource().getTextName(), name, target.getUUID());

        context.getSource().sendSuccess(() -> Component.literal(
                "已清除 " + name + " 的女仆身份记录。该玩家现在可以重新获得一位女仆。"), true);
        return 1;
    }

    // ==================================================================================
    // 工具
    // ==================================================================================

    /** @return 在线玩家；找不到时已发出失败消息并返回 null */
    private static ServerPlayer findPlayer(CommandContext<CommandSourceStack> context, String name) {
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            context.getSource().sendFailure(Component.literal("找不到在线玩家：" + name));
        }
        return target;
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context,
                                                                 SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                context.getSource().getServer().getPlayerList().getPlayers().stream()
                        .map(player -> player.getName().getString())
                        .toList(),
                builder);
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
    // lost（宣告失散，§12.7）
    // ==================================================================================

    /**
     * 承认已经失去她，让她回到 {@code NONE}，从而可以重新开始。
     *
     * <h2>这里为什么没有"桌宠是否在线"的检查</h2>
     * 因为桌宠（Phase 2）还不存在。而 §12.7 注意点 1 是整份设计里最不可接受的一条错误来源：
     * <blockquote>
     * 不能把"桌宠没启动"误判为"数据丢失"。判定失散的前提是桌宠<b>明确在线</b>并
     * <b>明确返回"无此 maidId"</b>。
     * </blockquote>
     * 所以当前的实现<b>故意只依赖玩家的显式动作</b>（逐字打出 {@code declare confirm}），
     * 不做任何自动判定 —— 宁可让玩家自己按下去，也不要让一个还没实现的在线检查
     * 替他把女仆判死。桌宠落地后，这个命令应当降级为"仅在桌宠确认无此数据时可用"的兜底，
     * 而不是主要入口。
     */
    private static int declareLost(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        MaidCarrierState state = MaidCarrierStateStore.get(server, player.getUUID()).orElse(null);
        if (state == null || state.getMaidId() == null || !state.hasMaid()) {
            context.getSource().sendFailure(Component.literal("你没有可宣告失散的女仆。"));
            return 0;
        }

        UUID maidId = state.getMaidId();
        // 归档到 _lost/：与放手不同，这里**不**立墓碑 —— 失散是承认失去，
        // 若玩家日后找回了她的数据，让她回来应当是被允许的（§12.6 的 released 说明）。
        java.nio.file.Path archived = MaidArchive.archiveLost(maidId);
        if (archived == null) {
            context.getSource().sendFailure(Component.literal("归档失败，未做任何改动。请检查日志。"));
            return 0;
        }
        if (!state.declareLost()) {
            context.getSource().sendFailure(Component.literal("状态推进失败，未做任何改动。"));
            return 0;
        }
        MaidCarrierStateStore.write(server, player.getUUID(), state);

        TlmPetCarrier.LOGGER.info("玩家宣告失散：player={}，maidId={}，归档到 {}",
                player.getName().getString(), maidId, archived);
        context.getSource().sendSuccess(() -> Component.literal(
                "已宣告失散。她的数据留在了 " + archived + " 作为纪念，没有删除。")
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "你可以重新获得一位女仆了 —— 那会是一个全新的她。"), false);
        return 1;
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
}
