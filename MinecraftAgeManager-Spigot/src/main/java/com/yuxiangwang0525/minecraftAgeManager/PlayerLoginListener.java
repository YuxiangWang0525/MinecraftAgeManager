package com.yuxiangwang0525.minecraftAgeManager;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 玩家登录/加入/退出事件监听器
 * 实现 MCAP 实名认证检查与防沉迷策略执行
 */
public class PlayerLoginListener implements Listener {

    private final MinecraftAgeManager plugin;
    private final MCAPApiClient apiClient;
    private final PlayerTimeTracker timeTracker;
    private final LuckyPermsIntegration luckyPerms;

    /** 上次发送紧急提醒的分钟数 (防止重复提醒) */
    private final Map<UUID, Integer> lastUrgentWarnMin = new ConcurrentHashMap<>();

    /** 在线未成年玩家的定期监控任务 (UUID -> taskId) */
    private final Map<UUID, Integer> monitorTasks = new ConcurrentHashMap<>();

    public PlayerLoginListener(MinecraftAgeManager plugin, MCAPApiClient apiClient,
                               PlayerTimeTracker timeTracker, LuckyPermsIntegration luckyPerms) {
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.timeTracker = timeTracker;
        this.luckyPerms = luckyPerms;
    }

    // ==================== 登录事件 (核心鉴权逻辑) ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        String playerName = player.getName();

        CountDownLatch latch = new CountDownLatch(1);

        // 使用 CompletableFuture 而非 Bukkit 调度器, 因为当前线程阻塞了主 tick 循环,
        // Bukkit 调度器无法在主线程暂停时派发新任务
        CompletableFuture.runAsync(() -> {
            try {
                processLogin(event, uuid, playerName);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "处理玩家登录时发生异常: " + playerName, e);
                disallowLogin(event, ChatColor.RED + "登录验证时发生内部错误, 请稍后重试");
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                disallowLogin(event, ChatColor.RED + "登录验证超时, 请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disallowLogin(event, ChatColor.RED + "登录验证被中断");
        }
    }

    /**
     * 处理登录验证逻辑 (在异步线程中执行)
     */
    private void processLogin(PlayerLoginEvent event, String uuid, String playerName) {
        // 1. 查询用户信息
        JsonObject userInfo = apiClient.getUserInfo(uuid);

        if (userInfo == null) {
            // API 不可达 → 谢绝新用户登录, 允许已知用户
            handleApiUnavailable(event, uuid, playerName);
            return;
        }

        // 2. 检查是否已注册/绑定
        boolean registered = userInfo.has("registered") && userInfo.get("registered").getAsBoolean();

        if (!registered) {
            // 未注册 → 拒绝登录, 获取绑定信息
            handleUnregistered(event, uuid, playerName);
            return;
        }

        // 3. 检查是否成年
        boolean isMinor = userInfo.has("is_minor") && userInfo.get("is_minor").getAsBoolean();
        String ageRange = userInfo.has("ageRange") ? userInfo.get("ageRange").getAsString() : "unknown";

        if (!isMinor) {
            // 成年玩家 → 直接放行, 并移除 Minor 权限组
            cacheMinorStatus(uuid, false, ageRange);
            event.allow();
            plugin.getLogger().info("成年玩家 " + playerName + " (" + ageRange + ") 登录放行");

            // 异步移除 Minor 权限组 (如果存在)
            if (luckyPerms.isAvailable()) {
                luckyPerms.removeFromMinorGroup(UUID.fromString(uuid))
                        .thenAccept(success -> {
                            if (success) {
                                plugin.getLogger().info("已将成年玩家 " + playerName + " 从 Minor 权限组移除");
                            }
                        });
            }
            return;
        }

        // 4. 未成年玩家 → 执行限制策略
        cacheMinorStatus(uuid, true, ageRange);

        // 异步添加到 Minor 权限组
        if (luckyPerms.isAvailable()) {
            luckyPerms.addToMinorGroup(UUID.fromString(uuid))
                    .thenAccept(success -> {
                        if (success) {
                            plugin.getLogger().info("已将未成年玩家 " + playerName + " 加入 Minor 权限组");
                        }
                    });
        }

        handleMinorLogin(event, uuid, playerName, ageRange);
    }

    /**
     * API 不可达时的处理: 谢绝新用户, 放行已知用户
     */
    private void handleApiUnavailable(PlayerLoginEvent event, String uuid, String playerName) {
        if (timeTracker.hasSavedData(UUID.fromString(uuid))) {
            // 已知用户 → 放行 (保守策略, 避免影响老玩家)
            event.allow();
            plugin.getLogger().warning("MCAP 服务不可达, 已知用户 " + playerName + " 放行登录");
        } else {
            // 新用户 → 拒绝
            disallowLogin(event, ChatColor.RED + "MCAP 认证服务暂时不可用, 暂不接待新玩家\n"
                    + "请稍后重试或联系服务器管理员");
            plugin.getLogger().warning("MCAP 服务不可达, 新用户 " + playerName + " 被拒绝登录");
        }
    }

    /**
     * 未注册玩家处理: 获取绑定链接并展示
     */
    private void handleUnregistered(PlayerLoginEvent event, String uuid, String playerName) {
        JsonObject regInfo = apiClient.registerUserID(uuid, playerName);

        if (regInfo != null) {
            boolean alreadyBound = regInfo.has("alreadyBound") && regInfo.get("alreadyBound").getAsBoolean();

            if (alreadyBound) {
                // 已绑定但 getUserInfo 返回未注册 → 数据不一致, 重试查询
                JsonObject retryInfo = apiClient.getUserInfo(uuid);
                if (retryInfo != null && retryInfo.has("registered")
                        && retryInfo.get("registered").getAsBoolean()) {
                    boolean isMinor = retryInfo.get("is_minor").getAsBoolean();
                    String ageRange = retryInfo.get("ageRange").getAsString();
                    cacheMinorStatus(uuid, isMinor, ageRange);
                    if (!isMinor) {
                        event.allow();
                        return;
                    }
                    handleMinorLogin(event, uuid, playerName, ageRange);
                    return;
                }
            }

            // 展示绑定信息
            String bindLink = regInfo.has("bindLink") ? regInfo.get("bindLink").getAsString() : "未知";
            String bindCode = (regInfo.has("bindCode") && !regInfo.get("bindCode").isJsonNull())
                    ? regInfo.get("bindCode").getAsString() : "未知";
            String expiresIn = regInfo.has("expiresIn") ? regInfo.get("expiresIn").getAsString() : "30分钟";

            // 提取基础绑定页面 URL (去掉 ?code=...&uid=... 参数)
            String baseUrl = bindLink;
            int queryIndex = bindLink.indexOf('?');
            if (queryIndex > 0) {
                baseUrl = bindLink.substring(0, queryIndex);
            }

            // Minecraft 断开连接界面不支持文本选择, 因此将绑定码突出显示,
            // 引导玩家手动打开基础链接并输入绑定码
            String message = ChatColor.RED + "═══════════════════════════════════════\n"
                    + ChatColor.GOLD + "  您尚未完成实名认证, 无法登录游戏!\n"
                    + ChatColor.RED + "═══════════════════════════════════════\n"
                    + ChatColor.YELLOW + " 您的绑定码:\n"
                    + ChatColor.AQUA + "  " + bindCode + "\n"
                    + ChatColor.YELLOW + " 有效期: " + ChatColor.WHITE + expiresIn + "\n"
                    + ChatColor.YELLOW + " 绑定页面: " + ChatColor.GREEN + baseUrl + "\n"
                    + ChatColor.RED + "═══════════════════════════════════════\n"
                    + ChatColor.WHITE + " 操作步骤:\n"
                    + ChatColor.GRAY + " 1. 打开上方绑定页面链接\n"
                    + ChatColor.GRAY + " 2. 登录/注册并完成实名认证\n"
                    + ChatColor.GRAY + " 3. 在绑定页面输入上方绑定码\n"
                    + ChatColor.RED + "═══════════════════════════════════════";

            disallowLogin(event, message);
            plugin.getLogger().info("未注册玩家 " + playerName + " 被拒绝, 已提供绑定信息 (绑定码: " + bindCode + ")");
        } else {
            disallowLogin(event, ChatColor.RED + "无法获取绑定信息, 请稍后重试");
        }
    }

    /**
     * 未成年玩家登录处理: 根据策略执行限制
     */
    private void handleMinorLogin(PlayerLoginEvent event, String uuid, String playerName, String ageRange) {
        String policy = plugin.getConfig().getString("Policy", "CNNPPA");

        // 先检查宵禁
        String curfewKick = checkCurfew();
        if (curfewKick != null) {
            disallowLogin(event, curfewKick);
            plugin.getLogger().info("未成年玩家 " + playerName + " 处于宵禁时段, 拒绝登录");
            return;
        }

        switch (policy) {
            case "CNNPPA":
                handleCNNPPA(event, playerName);
                break;
            case "NoMinor":
                disallowLogin(event, ChatColor.RED + "本服务器不对未成年人开放\n"
                        + ChatColor.YELLOW + "如需帮助请联系服务器管理员");
                plugin.getLogger().info("未成年玩家 " + playerName + " 被 NoMinor 策略拒绝");
                break;
            case "TimeLimited":
                handleTimeLimited(event, uuid, playerName);
                break;
            default:
                plugin.getLogger().warning("未知的限制策略: " + policy + ", 默认使用 CNNPPA");
                handleCNNPPA(event, playerName);
                break;
        }
    }

    // ==================== 策略实现 ====================

    /**
     * CNNPPA 策略: 仅周五/周六/周日及法定节假日 20:00-21:00 可游玩
     */
    private void handleCNNPPA(PlayerLoginEvent event, String playerName) {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek day = now.getDayOfWeek();
        int hour = now.getHour();

        // 检查是否为允许日 (周五/六/日)
        boolean isAllowedDay = (day == DayOfWeek.FRIDAY || day == DayOfWeek.SATURDAY
                || day == DayOfWeek.SUNDAY);

        // 检查是否为法定节假日
        if (!isAllowedDay) {
            List<String> holidays = plugin.getConfig().getStringList("CNNPPAHolidays");
            String todayStr = now.format(DateTimeFormatter.ofPattern("MM-dd"));
            for (String holiday : holidays) {
                // 只取前5个字符 (MM-dd 格式)
                String h = holiday.trim();
                if (h.length() > 5) h = h.substring(0, 5);
                if (h.equals(todayStr)) {
                    isAllowedDay = true;
                    break;
                }
            }
        }

        if (!isAllowedDay) {
            disallowLogin(event, ChatColor.RED + "根据《国家新闻出版署》防沉迷规定,\n"
                    + ChatColor.YELLOW + "未成年人仅可在周五、周六、周日及法定节假日\n"
                    + ChatColor.YELLOW + "20:00-21:00 进行游戏。\n"
                    + ChatColor.GRAY + "今日不在允许游玩日期范围内。");
            plugin.getLogger().info("未成年玩家 " + playerName + " 被 CNNPPA 策略拒绝 (非允许日)");
            return;
        }

        // 检查是否在允许时段 (20:00-21:00)
        if (hour < 20 || hour >= 21) {
            disallowLogin(event, ChatColor.RED + "根据《国家新闻出版署》防沉迷规定,\n"
                    + ChatColor.YELLOW + "未成年人仅可在周五、周六、周日及法定节假日\n"
                    + ChatColor.YELLOW + "20:00-21:00 进行游戏。\n"
                    + ChatColor.GRAY + "当前时间不在允许游玩时段内。");
            plugin.getLogger().info("未成年玩家 " + playerName + " 被 CNNPPA 策略拒绝 (非允许时段)");
            return;
        }

        // 允许登录, CNNPPA 限制为 1 小时
        event.allow();
        plugin.getLogger().info("未成年玩家 " + playerName + " 通过 CNNPPA 策略验证, 允许登录 (限时60分钟)");
    }

    /**
     * TimeLimited 策略: 自定义时长限制
     */
    private void handleTimeLimited(PlayerLoginEvent event, String uuid, String playerName) {
        int maxTime = plugin.getConfig().getInt("MaxTime", 90);
        int remaining = timeTracker.getRemainingSeconds(UUID.fromString(uuid));

        if (remaining <= 0) {
            disallowLogin(event, ChatColor.RED + "您今日的游戏时间已用完!\n"
                    + ChatColor.YELLOW + "每日 0:00 将重置游戏时长, 请明天再来。");
            plugin.getLogger().info("未成年玩家 " + playerName + " 今日时间已耗尽, 拒绝登录");
            return;
        }

        // 允许登录
        event.allow();
        int minutes = remaining / 60;
        int seconds = remaining % 60;
        plugin.getLogger().info("未成年玩家 " + playerName + " 通过 TimeLimited 策略验证, 剩余 "
                + minutes + "分" + seconds + "秒");
    }

    /**
     * 检查宵禁时段
     *
     * @return 如果在宵禁时段返回踢出消息, 否则返回 null
     */
    private String checkCurfew() {
        if (!plugin.getConfig().contains("CurfewStart") || !plugin.getConfig().contains("CurfewEnd")) {
            return null; // 未配置宵禁
        }

        int curfewStart = plugin.getConfig().getInt("CurfewStart", 22);
        int curfewEnd = plugin.getConfig().getInt("CurfewEnd", 6);
        int currentHour = LocalDateTime.now().getHour();

        boolean inCurfew;
        if (curfewStart > curfewEnd) {
            // 跨午夜, 如 22:00 - 06:00
            inCurfew = currentHour >= curfewStart || currentHour < curfewEnd;
        } else {
            // 不跨午夜, 如 01:00 - 06:00
            inCurfew = currentHour >= curfewStart && currentHour < curfewEnd;
        }

        if (inCurfew) {
            return ChatColor.RED + "当前处于未成年人宵禁时段 ("
                    + String.format("%02d:00", curfewStart) + " - "
                    + String.format("%02d:00", curfewEnd) + ")\n"
                    + ChatColor.YELLOW + "未成年人禁止在此时段内登录游戏。";
        }
        return null;
    }

    // ==================== 加入事件 (启动监控 & 欢迎消息 & 诚实奖励) ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!timeTracker.isTrackedMinor(uuid)) {
            return; // 非未成年玩家, 不处理
        }

        // 发送自定义未成年玩家欢迎消息 (MinorMotd)
        if (plugin.getConfig().contains("MinorMotd")) {
            String motd = plugin.getConfig().getString("MinorMotd", "");
            if (!motd.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', motd));
            }
        }

        // 发送剩余时间提示
        sendRemainingTimeMessage(player);

        // 启动定期监控任务
        startMonitorTask(player);

        // 执行诚实奖励命令
        executeHonestyReward(player);
    }

    /**
     * 发送剩余时间提示给玩家
     */
    private void sendRemainingTimeMessage(Player player) {
        UUID uuid = player.getUniqueId();
        String policy = plugin.getConfig().getString("Policy", "CNNPPA");

        if ("CNNPPA".equals(policy)) {
            // CNNPPA: 显示本次会话已游玩时间和剩余时间 (总限60分钟)
            int played = timeTracker.getPlayedSeconds(uuid);
            int remaining = Math.max(0, 3600 - played);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(ChatColor.YELLOW + "[防沉迷] 根据CNNPPA政策, 您本次可游玩60分钟。"
                    + "剩余: " + remMin + "分" + remSec + "秒");
        } else if ("TimeLimited".equals(policy)) {
            int remaining = timeTracker.getRemainingSeconds(uuid);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(ChatColor.YELLOW + "[防沉迷] 今日剩余游戏时间: "
                    + remMin + "分" + remSec + "秒");
        }
    }

    /**
     * 启动未成年玩家定期监控任务 (每秒执行)
     */
    private void startMonitorTask(Player player) {
        UUID uuid = player.getUniqueId();
        String policy = plugin.getConfig().getString("Policy", "CNNPPA");
        int remindTime = plugin.getConfig().getInt("RemindTime", 60);
        int maxTime = plugin.getConfig().getInt("MaxTime", 90);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            // 检查宵禁 (中途进入宵禁时段也应踢出)
            String curfewMsg = checkCurfew();
            if (curfewMsg != null) {
                player.kickPlayer(curfewMsg);
                stopMonitorTask(uuid);
                return;
            }

            if ("CNNPPA".equals(policy)) {
                // CNNPPA: 本次会话限60分钟
                int played = timeTracker.getPlayedSeconds(uuid);

                // 45分钟时提醒
                if (played >= 2700 && played < 2760 && !timeTracker.isRemindSent(uuid)) {
                    player.sendMessage(ChatColor.RED + "[防沉迷] 您已游玩45分钟, 请注意时间! 还剩15分钟。");
                    timeTracker.markRemindSent(uuid);
                }

                // 55分钟时再次提醒
                if (played >= 3300 && played < 3360) {
                    int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                    if (lastWarn != 55) {
                        player.sendMessage(ChatColor.RED + "[防沉迷] 警告! 您仅剩5分钟游戏时间!");
                        lastUrgentWarnMin.put(uuid, 55);
                    }
                }

                // 60分钟强制下线
                if (played >= 3600) {
                    player.kickPlayer(ChatColor.RED + "根据《国家新闻出版署》防沉迷规定,\n"
                            + ChatColor.YELLOW + "您的本次游戏时间已达1小时上限。\n"
                            + ChatColor.GRAY + "请休息后再来, 每日20:00-21:00开放。");
                    plugin.getLogger().info("未成年玩家 " + player.getName() + " 达到CNNPPA 60分钟上限, 强制下线");
                    stopMonitorTask(uuid);
                }

                // 检查是否仍在允许时段
                int currentHour = LocalDateTime.now().getHour();
                if (currentHour >= 21) {
                    player.kickPlayer(ChatColor.RED + "根据《国家新闻出版署》防沉迷规定,\n"
                            + ChatColor.YELLOW + "当前时间 (21:00后) 不在未成年人允许游玩时段。\n"
                            + ChatColor.GRAY + "允许时段: 周五/六/日及法定节假日 20:00-21:00");
                    plugin.getLogger().info("未成年玩家 " + player.getName() + " 超出CNNPPA允许时段, 强制下线");
                    stopMonitorTask(uuid);
                }

            } else if ("TimeLimited".equals(policy)) {
                int remaining = timeTracker.getRemainingSeconds(uuid);
                int remainingMinutes = remaining / 60;

                // 提醒时间到达时发送提醒
                if (remaining <= remindTime * 60 && !timeTracker.isRemindSent(uuid)) {
                    player.sendMessage(ChatColor.YELLOW + "[防沉迷] 您今日剩余游戏时间还有 "
                            + remainingMinutes + " 分钟, 请合理安排时间!");
                    timeTracker.markRemindSent(uuid);
                }

                // 剩余5分钟紧急提醒 (每分钟一次)
                if (remaining <= 300 && remaining > 240) {
                    int warnMin = remaining / 60;
                    int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                    if (lastWarn != warnMin) {
                        player.sendMessage(ChatColor.RED + "[防沉迷] 警告! 您今日仅剩" + warnMin + "分钟游戏时间!");
                        lastUrgentWarnMin.put(uuid, warnMin);
                    }
                }

                // 剩余1分钟最后提醒 (每分钟一次)
                if (remaining <= 60 && remaining > 0) {
                    int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                    if (lastWarn != 1) {
                        player.sendMessage(ChatColor.RED + "[防沉迷] 最后1分钟! 请立即保存游戏进度!");
                        lastUrgentWarnMin.put(uuid, 1);
                    }
                }

                // 时间耗尽, 强制下线
                if (remaining <= 0) {
                    player.kickPlayer(ChatColor.RED + "您今日的游戏时间已用完!\n"
                            + ChatColor.YELLOW + "每日 0:00 将重置游戏时长。");
                    plugin.getLogger().info("未成年玩家 " + player.getName()
                            + " 今日时间耗尽, 强制下线");
                    stopMonitorTask(uuid);
                }
            }
        }, 0L, 20L); // 每1秒执行一次

        monitorTasks.put(uuid, task.getTaskId());
        lastUrgentWarnMin.put(uuid, -1);
    }

    /**
     * 停止某玩家的监控任务
     */
    private void stopMonitorTask(UUID uuid) {
        Integer taskId = monitorTasks.remove(uuid);
        lastUrgentWarnMin.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    /**
     * 执行诚实奖励命令
     */
    private void executeHonestyReward(Player player) {
        if (!plugin.getConfig().contains("HonestyReward")) {
            return;
        }

        List<String> commands = plugin.getConfig().getStringList("HonestyReward");
        if (commands.isEmpty()) {
            return;
        }

        // 延迟1秒执行, 确保玩家完全进入世界
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String cmd : commands) {
                String processed = cmd
                        .replace("{player}", player.getName())
                        .replace("{uuid}", player.getUniqueId().toString());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
            }
            plugin.getLogger().info("已为玩家 " + player.getName() + " 执行诚实奖励命令");
        }, 20L);
    }

    // ==================== 退出事件 ====================

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 停止监控任务
        stopMonitorTask(uuid);

        // 保存剩余时间
        timeTracker.onPlayerQuit(uuid);
    }

    // ==================== 辅助方法 ====================

    /**
     * 设置登录拒绝 (直接在当前线程操作, 事件尚未返回所以线程安全)
     */
    private void disallowLogin(PlayerLoginEvent event, String message) {
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, message);
    }

    /**
     * 缓存玩家未成年状态
     */
    private void cacheMinorStatus(String uuid, boolean isMinor, String ageRange) {
        try {
            UUID playerUuid = UUID.fromString(uuid);
            if (isMinor) {
                timeTracker.onPlayerJoin(playerUuid, true, ageRange,
                        plugin.getConfig().getInt("MaxTime", 90));
            } else {
                // 成年玩家也缓存状态 (用于 info 命令)
                timeTracker.onPlayerJoin(playerUuid, false, ageRange, 0);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("缓存玩家状态失败: " + e.getMessage());
        }
    }
}
