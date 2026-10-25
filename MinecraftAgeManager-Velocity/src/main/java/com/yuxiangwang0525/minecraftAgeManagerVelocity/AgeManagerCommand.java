package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;

/**
 * /agemanager 命令处理器
 * 子命令:
 *   timeleft - 未成年玩家查询剩余游戏时长
 *   info     - 查看玩家信息与服务器策略
 *   reload   - 重载插件配置 (需要 agemanager.admin 权限)
 */
public class AgeManagerCommand {

    private final MinecraftAgeManagerVelocity plugin;
    private final PlayerTimeTracker timeTracker;
    private final MCAPApiClient apiClient;

    public AgeManagerCommand(MinecraftAgeManagerVelocity plugin, PlayerTimeTracker timeTracker,
                             MCAPApiClient apiClient) {
        this.plugin = plugin;
        this.timeTracker = timeTracker;
        this.apiClient = apiClient;
    }

    /**
     * 注册命令到 Velocity
     */
    public void register(CommandManager commandManager) {
        LiteralArgumentBuilder<CommandSource> builder = LiteralArgumentBuilder.<CommandSource>literal("agemanager")
                .executes(context -> {
                    sendHelp(context.getSource());
                    return Command.SINGLE_SUCCESS;
                })
                .then(LiteralArgumentBuilder.<CommandSource>literal("timeleft")
                        .executes(context -> {
                            handleTimeLeft(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("info")
                        .executes(context -> {
                            handleInfo(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .requires(source -> source.hasPermission("agemanager.admin"))
                        .executes(context -> {
                            handleReload(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }));

        BrigadierCommand brigadierCommand = new BrigadierCommand(builder);
        CommandMeta meta = commandManager.metaBuilder("agemanager")
                .plugin(plugin)
                .build();
        commandManager.register(meta, brigadierCommand);
    }

    /**
     * /agemanager timeleft - 查询剩余游戏时长
     */
    private void handleTimeLeft(CommandSource sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
            return;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (!timeTracker.isTrackedMinor(uuid)) {
            player.sendMessage(Component.text("该命令仅对未成年玩家可用", NamedTextColor.YELLOW));
            return;
        }

        String policy = plugin.getConfigString("Policy", "CNNPPA");

        if ("CNNPPA".equals(policy)) {
            int played = timeTracker.getPlayedSeconds(uuid);
            int remaining = Math.max(0, 3600 - played);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(Component.text("[防沉迷] CNNPPA 政策限制: 本次剩余 "
                    + remMin + "分" + remSec + "秒 (总限60分钟)", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("允许时段: 周五/六/日及法定节假日 20:00-21:00", NamedTextColor.GRAY));
        } else if ("TimeLimited".equals(policy)) {
            int remaining = timeTracker.getRemainingSeconds(uuid);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            int maxTime = plugin.getConfigInt("MaxTime", 90);
            player.sendMessage(Component.text("[防沉迷] 今日剩余游戏时间: "
                    + remMin + "分" + remSec + "秒", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("每日上限: " + maxTime + "分钟, 每日0:00重置", NamedTextColor.GRAY));
        }
    }

    /**
     * /agemanager info - 查看玩家信息与服务器策略
     */
    private void handleInfo(CommandSource sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
            return;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        PlayerTimeTracker.MinorInfo info = timeTracker.getMinorInfo(uuid);

        sender.sendMessage(Component.text("════════ 年龄管理信息 ════════", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 玩家: ", NamedTextColor.YELLOW)
                .append(Component.text(player.getUsername(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(" UUID: ", NamedTextColor.YELLOW)
                .append(Component.text(uuid.toString(), NamedTextColor.GRAY)));

        if (info != null) {
            sender.sendMessage(Component.text(" 实名认证: ", NamedTextColor.YELLOW)
                    .append(Component.text("已认证", NamedTextColor.GREEN)));
            sender.sendMessage(Component.text(" 年龄段: ", NamedTextColor.YELLOW)
                    .append(Component.text(info.ageRange, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text(" 未成年: ", NamedTextColor.YELLOW)
                    .append(info.isMinor ? Component.text("是", NamedTextColor.RED)
                            : Component.text("否", NamedTextColor.GREEN)));

            if (info.isMinor && timeTracker.isTrackedMinor(uuid)) {
                String policy = plugin.getConfigString("Policy", "CNNPPA");
                sender.sendMessage(Component.text(" 限制策略: ", NamedTextColor.YELLOW)
                        .append(Component.text(policy, NamedTextColor.WHITE)));

                if ("TimeLimited".equals(policy)) {
                    int remaining = timeTracker.getRemainingSeconds(uuid);
                    int remMin = remaining / 60;
                    int remSec = remaining % 60;
                    sender.sendMessage(Component.text(" 今日剩余: ", NamedTextColor.YELLOW)
                            .append(Component.text(remMin + "分" + remSec + "秒", NamedTextColor.WHITE)));
                } else if ("CNNPPA".equals(policy)) {
                    int played = timeTracker.getPlayedSeconds(uuid);
                    int remaining = Math.max(0, 3600 - played);
                    int remMin = remaining / 60;
                    int remSec = remaining % 60;
                    sender.sendMessage(Component.text(" 本次剩余: ", NamedTextColor.YELLOW)
                            .append(Component.text(remMin + "分" + remSec + "秒", NamedTextColor.WHITE)));
                }
            }
        } else {
            sender.sendMessage(Component.text(" 实名认证: ", NamedTextColor.YELLOW)
                    .append(Component.text("未查询 (未登录或成年)", NamedTextColor.RED)));
        }

        sender.sendMessage(Component.text(" 服务器策略: ", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getConfigString("Policy", "CNNPPA"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("════════════════════════════", NamedTextColor.GOLD));
    }

    /**
     * /agemanager reload - 重载配置
     */
    private void handleReload(CommandSource sender) {
        if (!sender.hasPermission("agemanager.admin")) {
            sender.sendMessage(Component.text("权限不足, 需要 agemanager.admin", NamedTextColor.RED));
            return;
        }

        plugin.reloadConfig();
        sender.sendMessage(Component.text("[MinecraftAgeManager] 配置已重新加载!", NamedTextColor.GREEN));

        // 重新初始化 API 客户端并检查连接
        sender.sendMessage(Component.text("[MinecraftAgeManager] 正在检查 MCAP 后端连接...", NamedTextColor.YELLOW));
        boolean connected = plugin.reinitializeApiClient();

        if (connected) {
            String mcapAddress = plugin.getConfigString("MCAP", "http://localhost:3000");
            sender.sendMessage(Component.text("[MinecraftAgeManager] MCAP 后端连接成功: " + mcapAddress, NamedTextColor.GREEN));
        } else {
            if (plugin.isDefaultMcapAddress()) {
                sender.sendMessage(Component.text("[MinecraftAgeManager] 无法连接到 MCAP 后端!", NamedTextColor.RED));
                sender.sendMessage(Component.text("检测到您使用的是默认配置 (localhost:3000)", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("请修改配置文件: plugins/minecraftagemanager/config.yml", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("设置正确的 MCAP 地址和 APIKEY 后再次执行 /agemanager reload", NamedTextColor.GRAY));
            } else {
                String mcapAddress = plugin.getConfigString("MCAP", "http://localhost:3000");
                sender.sendMessage(Component.text("[MinecraftAgeManager] 无法连接到 MCAP 后端: " + mcapAddress, NamedTextColor.RED));
                sender.sendMessage(Component.text("请检查服务是否运行、地址和 APIKEY 是否正确", NamedTextColor.GRAY));
            }
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSource sender) {
        sender.sendMessage(Component.text("════ MinecraftAgeManager 命令帮助 ════", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/agemanager timeleft", NamedTextColor.YELLOW)
                .append(Component.text(" - 查询剩余游戏时长", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/agemanager info", NamedTextColor.YELLOW)
                .append(Component.text(" - 查看玩家信息与策略", NamedTextColor.WHITE)));
        if (sender.hasPermission("agemanager.admin")) {
            sender.sendMessage(Component.text("/agemanager reload", NamedTextColor.YELLOW)
                    .append(Component.text(" - 重载插件配置", NamedTextColor.WHITE)));
        }
        sender.sendMessage(Component.text("════════════════════════════════════", NamedTextColor.GOLD));
    }
}
