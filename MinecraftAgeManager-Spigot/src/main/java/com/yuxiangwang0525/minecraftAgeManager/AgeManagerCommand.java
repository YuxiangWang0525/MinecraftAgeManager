package com.yuxiangwang0525.minecraftAgeManager;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * /agemanager 命令处理器
 * 子命令:
 *   timeleft - 未成年玩家查询剩余游戏时长
 *   info     - 查看玩家信息与服务器策略
 *   reload   - 重载插件配置 (需要 agemanager.admin 权限)
 */
public class AgeManagerCommand implements CommandExecutor {

    private final MinecraftAgeManager plugin;
    private final PlayerTimeTracker timeTracker;
    private final MCAPApiClient apiClient;

    public AgeManagerCommand(MinecraftAgeManager plugin, PlayerTimeTracker timeTracker,
                             MCAPApiClient apiClient) {
        this.plugin = plugin;
        this.timeTracker = timeTracker;
        this.apiClient = apiClient;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "timeleft":
                handleTimeLeft(sender);
                break;
            case "info":
                handleInfo(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知子命令: " + args[0]);
                sendHelp(sender);
                break;
        }
        return true;
    }

    /**
     * /agemanager timeleft - 查询剩余游戏时长
     */
    private void handleTimeLeft(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
            return;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (!timeTracker.isTrackedMinor(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "该命令仅对未成年玩家可用");
            return;
        }

        String policy = plugin.getConfig().getString("Policy", "CNNPPA");

        if ("CNNPPA".equals(policy)) {
            int played = timeTracker.getPlayedSeconds(uuid);
            int remaining = Math.max(0, 3600 - played);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(ChatColor.YELLOW + "[防沉迷] CNNPPA 政策限制: 本次剩余 "
                    + remMin + "分" + remSec + "秒 (总限60分钟)");
            player.sendMessage(ChatColor.GRAY + "允许时段: 周五/六/日及法定节假日 20:00-21:00");
        } else if ("TimeLimited".equals(policy)) {
            int remaining = timeTracker.getRemainingSeconds(uuid);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            int maxTime = plugin.getConfig().getInt("MaxTime", 90);
            player.sendMessage(ChatColor.YELLOW + "[防沉迷] 今日剩余游戏时间: "
                    + remMin + "分" + remSec + "秒");
            player.sendMessage(ChatColor.GRAY + "每日上限: " + maxTime + "分钟, 每日0:00重置");
        }
    }

    /**
     * /agemanager info - 查看玩家信息与服务器策略
     */
    private void handleInfo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
            return;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        PlayerTimeTracker.MinorInfo info = timeTracker.getMinorInfo(uuid);

        sender.sendMessage(ChatColor.GOLD + "════════ 年龄管理信息 ════════");
        sender.sendMessage(ChatColor.YELLOW + " 玩家: " + ChatColor.WHITE + player.getName());
        sender.sendMessage(ChatColor.YELLOW + " UUID: " + ChatColor.GRAY + uuid.toString());

        if (info != null) {
            sender.sendMessage(ChatColor.YELLOW + " 实名认证: " + ChatColor.GREEN + "已认证");
            sender.sendMessage(ChatColor.YELLOW + " 年龄段: " + ChatColor.WHITE + info.ageRange);
            sender.sendMessage(ChatColor.YELLOW + " 未成年: "
                    + (info.isMinor ? ChatColor.RED + "是" : ChatColor.GREEN + "否"));

            if (info.isMinor && timeTracker.isTrackedMinor(uuid)) {
                String policy = plugin.getConfig().getString("Policy", "CNNPPA");
                sender.sendMessage(ChatColor.YELLOW + " 限制策略: " + ChatColor.WHITE + policy);

                if ("TimeLimited".equals(policy)) {
                    int remaining = timeTracker.getRemainingSeconds(uuid);
                    int remMin = remaining / 60;
                    int remSec = remaining % 60;
                    sender.sendMessage(ChatColor.YELLOW + " 今日剩余: "
                            + ChatColor.WHITE + remMin + "分" + remSec + "秒");
                } else if ("CNNPPA".equals(policy)) {
                    int played = timeTracker.getPlayedSeconds(uuid);
                    int remaining = Math.max(0, 3600 - played);
                    int remMin = remaining / 60;
                    int remSec = remaining % 60;
                    sender.sendMessage(ChatColor.YELLOW + " 本次剩余: "
                            + ChatColor.WHITE + remMin + "分" + remSec + "秒");
                }
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + " 实名认证: " + ChatColor.RED + "未查询 (未登录或成年)");
        }

        sender.sendMessage(ChatColor.YELLOW + " 服务器策略: " + ChatColor.WHITE
                + plugin.getConfig().getString("Policy", "CNNPPA"));
        sender.sendMessage(ChatColor.GOLD + "════════════════════════════");
    }

    /**
     * /agemanager reload - 重载配置
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("agemanager.admin")) {
            sender.sendMessage(ChatColor.RED + "权限不足, 需要 agemanager.admin");
            return;
        }

        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "[MinecraftAgeManager] 配置已重新加载!");

        // 重新初始化 API 客户端并检查连接
        sender.sendMessage(ChatColor.YELLOW + "[MinecraftAgeManager] 正在检查 MCAP 后端连接...");
        boolean connected = plugin.reinitializeApiClient();

        if (connected) {
            String mcapAddress = plugin.getConfig().getString("MCAP", "http://localhost:3000");
            sender.sendMessage(ChatColor.GREEN + "[MinecraftAgeManager] MCAP 后端连接成功: " + mcapAddress);
        } else {
            if (plugin.isDefaultMcapAddress()) {
                sender.sendMessage(ChatColor.RED + "[MinecraftAgeManager] 无法连接到 MCAP 后端!");
                sender.sendMessage(ChatColor.YELLOW + "检测到您使用的是默认配置 (localhost:3000)");
                sender.sendMessage(ChatColor.GRAY + "请修改配置文件: plugins/MinecraftAgeManager/config.yml");
                sender.sendMessage(ChatColor.GRAY + "设置正确的 MCAP 地址和 APIKEY 后再次执行 /agemanager reload");
            } else {
                String mcapAddress = plugin.getConfig().getString("MCAP", "http://localhost:3000");
                sender.sendMessage(ChatColor.RED + "[MinecraftAgeManager] 无法连接到 MCAP 后端: " + mcapAddress);
                sender.sendMessage(ChatColor.GRAY + "请检查服务是否运行、地址和 APIKEY 是否正确");
            }
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "════ MinecraftAgeManager 命令帮助 ════");
        sender.sendMessage(ChatColor.YELLOW + "/agemanager timeleft" + ChatColor.WHITE + " - 查询剩余游戏时长");
        sender.sendMessage(ChatColor.YELLOW + "/agemanager info" + ChatColor.WHITE + " - 查看玩家信息与策略");
        if (sender.hasPermission("agemanager.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/agemanager reload" + ChatColor.WHITE + " - 重载插件配置");
        }
        sender.sendMessage(ChatColor.GOLD + "════════════════════════════════════");
    }
}
