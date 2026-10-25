package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 玩家登录/加入/退出事件监听器
 * 实现 MCAP 实名认证检查与防沉迷策略执行
 */
public class PlayerLoginListener {

    private final MinecraftAgeManagerVelocity plugin;
    private final ProxyServer proxy;
    private final MCAPApiClient apiClient;
    private final PlayerTimeTracker timeTracker;
    private final LuckyPermsIntegration luckyPerms;
    private final Logger logger;

    /** 上次发送紧急提醒的分钟数 (防止重复提醒) */
    private final Map<UUID, Integer> lastUrgentWarnMin = new ConcurrentHashMap<>();

    /** 在线未成年玩家的定期监控任务 (UUID -> task) */
    private final Map<UUID, com.velocitypowered.api.scheduler.ScheduledTask> monitorTasks = new ConcurrentHashMap<>();

    public PlayerLoginListener(MinecraftAgeManagerVelocity plugin, ProxyServer proxy,
                               MCAPApiClient apiClient, PlayerTimeTracker timeTracker,
                               LuckyPermsIntegration luckyPerms) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.apiClient = apiClient;
        this.timeTracker = timeTracker;
        this.luckyPerms = luckyPerms;
        this.logger = plugin.getLogger();
    }

    // ==================== 登录事件 (核心鉴权逻辑) ====================

    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getUsername();

        // LoginEvent 已经是异步的, 可以直接进行网络请求
        try {
            processLogin(event, uuid.toString(), playerName, uuid);
        } catch (Exception e) {
            logger.error("处理玩家登录时发生异常: {}", playerName, e);
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text("登录验证时发生内部错误, 请稍后重试").color(NamedTextColor.RED)));
        }
    }

    /**
     * 处理登录验证逻辑
     */
    private void processLogin(LoginEvent event, String uuid, String playerName, UUID playerUuid) {
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
            logger.info("成年玩家 {} ({}) 登录放行", playerName, ageRange);

            // 异步移除 Minor 权限组 (如果存在)
            if (luckyPerms.isAvailable()) {
                luckyPerms.removeFromMinorGroup(playerUuid)
                        .thenAccept(success -> {
                            if (success) {
                                logger.info("已将成年玩家 {} 从 Minor 权限组移除", playerName);
                            }
                        });
            }
            return;
        }

        // 4. 未成年玩家 → 执行限制策略
        cacheMinorStatus(uuid, true, ageRange);

        // 异步添加到 Minor 权限组
        if (luckyPerms.isAvailable()) {
            luckyPerms.addToMinorGroup(playerUuid)
                    .thenAccept(success -> {
                        if (success) {
                            logger.info("已将未成年玩家 {} 加入 Minor 权限组", playerName);
                        }
                    });
        }

        handleMinorLogin(event, uuid, playerName, ageRange);
    }

    /**
     * API 不可达时的处理: 谢绝新用户, 放行已知用户
     */
    private void handleApiUnavailable(LoginEvent event, String uuid, String playerName) {
        if (timeTracker.hasSavedData(UUID.fromString(uuid))) {
            // 已知用户 → 放行 (保守策略, 避免影响老玩家)
            logger.warn("MCAP 服务不可达, 已知用户 {} 放行登录", playerName);
        } else {
            // 新用户 → 拒绝
            event.setResult(ResultedEvent.ComponentResult
                    .denied(Component.text("MCAP 认证服务暂时不可用, 暂不接待新玩家\n请稍后重试或联系服务器管理员")
                            .color(NamedTextColor.RED)));
            logger.warn("MCAP 服务不可达, 新用户 {} 被拒绝登录", playerName);
        }
    }

    /**
     * 未注册玩家处理: 获取绑定链接并展示
     */
    private void handleUnregistered(LoginEvent event, String uuid, String playerName) {
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
                        return; // 成年, 放行
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

            // Minecraft 断开连接界面不支持文本选择, 因此将绑定码突出显示
            Component message = Component.text("═══════════════════════════════════════\n", NamedTextColor.RED)
                    .append(Component.text("  您尚未完成实名认证, 无法登录游戏!\n", NamedTextColor.GOLD))
                    .append(Component.text("═══════════════════════════════════════\n", NamedTextColor.RED))
                    .append(Component.text(" 您的绑定码:\n", NamedTextColor.YELLOW))
                    .append(Component.text("  " + bindCode + "\n", NamedTextColor.AQUA))
                    .append(Component.text(" 有效期: ", NamedTextColor.YELLOW))
                    .append(Component.text(expiresIn + "\n", NamedTextColor.WHITE))
                    .append(Component.text(" 绑定页面: ", NamedTextColor.YELLOW))
                    .append(Component.text(baseUrl + "\n", NamedTextColor.GREEN))
                    .append(Component.text("═══════════════════════════════════════\n", NamedTextColor.RED))
                    .append(Component.text(" 操作步骤:\n", NamedTextColor.WHITE))
                    .append(Component.text(" 1. 打开上方绑定页面链接\n", NamedTextColor.GRAY))
                    .append(Component.text(" 2. 登录/注册并完成实名认证\n", NamedTextColor.GRAY))
                    .append(Component.text(" 3. 在绑定页面输入上方绑定码\n", NamedTextColor.GRAY))
                    .append(Component.text("═══════════════════════════════════════", NamedTextColor.RED));

            event.setResult(ResultedEvent.ComponentResult.denied(message));
            logger.info("未注册玩家 {} 被拒绝, 已提供绑定信息 (绑定码: {})", playerName, bindCode);
        } else {
            event.setResult(ResultedEvent.ComponentResult
                    .denied(Component.text("无法获取绑定信息, 请稍后重试").color(NamedTextColor.RED)));
        }
    }

    /**
     * 未成年玩家登录处理: 根据策略执行限制
     */
    private void handleMinorLogin(LoginEvent event, String uuid, String playerName, String ageRange) {
        String policy = plugin.getConfigString("Policy", "CNNPPA");

        // 先检查宵禁
        Component curfewKick = checkCurfew();
        if (curfewKick != null) {
            event.setResult(ResultedEvent.ComponentResult.denied(curfewKick));
            logger.info("未成年玩家 {} 处于宵禁时段, 拒绝登录", playerName);
            return;
        }

        switch (policy) {
            case "CNNPPA":
                handleCNNPPA(event, playerName);
                break;
            case "NoMinor":
                event.setResult(ResultedEvent.ComponentResult
                        .denied(Component.text("本服务器不对未成年人开放\n如需帮助请联系服务器管理员", NamedTextColor.RED)));
                logger.info("未成年玩家 {} 被 NoMinor 策略拒绝", playerName);
                break;
            case "TimeLimited":
                handleTimeLimited(event, uuid, playerName);
                break;
            default:
                logger.warn("未知的限制策略: {}, 默认使用 CNNPPA", policy);
                handleCNNPPA(event, playerName);
                break;
        }
    }

    // ==================== 策略实现 ====================

    /**
     * CNNPPA 策略: 仅周五/周六/周日及法定节假日 20:00-21:00 可游玩
     */
    private void handleCNNPPA(LoginEvent event, String playerName) {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek day = now.getDayOfWeek();
        int hour = now.getHour();

        // 检查是否为允许日 (周五/六/日)
        boolean isAllowedDay = (day == DayOfWeek.FRIDAY || day == DayOfWeek.SATURDAY
                || day == DayOfWeek.SUNDAY);

        // 检查是否为法定节假日
        if (!isAllowedDay) {
            List<String> holidays = plugin.getConfigStringList("CNNPPAHolidays");
            String todayStr = now.format(DateTimeFormatter.ofPattern("MM-dd"));
            for (String holiday : holidays) {
                String h = holiday.trim();
                if (h.length() > 5) h = h.substring(0, 5);
                if (h.equals(todayStr)) {
                    isAllowedDay = true;
                    break;
                }
            }
        }

        if (!isAllowedDay) {
            Component message = Component.text("根据《国家新闻出版署》防沉迷规定,\n", NamedTextColor.RED)
                    .append(Component.text("未成年人仅可在周五、周六、周日及法定节假日\n", NamedTextColor.YELLOW))
                    .append(Component.text("20:00-21:00 进行游戏。\n", NamedTextColor.YELLOW))
                    .append(Component.text("今日不在允许游玩日期范围内。", NamedTextColor.GRAY));
            event.setResult(ResultedEvent.ComponentResult.denied(message));
            logger.info("未成年玩家 {} 被 CNNPPA 策略拒绝 (非允许日)", playerName);
            return;
        }

        // 检查是否在允许时段 (20:00-21:00)
        if (hour < 20 || hour >= 21) {
            Component message = Component.text("根据《国家新闻出版署》防沉迷规定,\n", NamedTextColor.RED)
                    .append(Component.text("未成年人仅可在周五、周六、周日及法定节假日\n", NamedTextColor.YELLOW))
                    .append(Component.text("20:00-21:00 进行游戏。\n", NamedTextColor.YELLOW))
                    .append(Component.text("当前时间不在允许游玩时段内。", NamedTextColor.GRAY));
            event.setResult(ResultedEvent.ComponentResult.denied(message));
            logger.info("未成年玩家 {} 被 CNNPPA 策略拒绝 (非允许时段)", playerName);
            return;
        }

        // 允许登录, CNNPPA 限制为 1 小时
        logger.info("未成年玩家 {} 通过 CNNPPA 策略验证, 允许登录 (限时60分钟)", playerName);
    }

    /**
     * TimeLimited 策略: 自定义时长限制
     */
    private void handleTimeLimited(LoginEvent event, String uuid, String playerName) {
        int maxTime = plugin.getConfigInt("MaxTime", 90);
        int remaining = timeTracker.getRemainingSeconds(UUID.fromString(uuid));

        if (remaining <= 0) {
            Component message = Component.text("您今日的游戏时间已用完!\n", NamedTextColor.RED)
                    .append(Component.text("每日 0:00 将重置游戏时长, 请明天再来。", NamedTextColor.YELLOW));
            event.setResult(ResultedEvent.ComponentResult.denied(message));
            logger.info("未成年玩家 {} 今日时间已耗尽, 拒绝登录", playerName);
            return;
        }

        // 允许登录
        int minutes = remaining / 60;
        int seconds = remaining % 60;
        logger.info("未成年玩家 {} 通过 TimeLimited 策略验证, 剩余 {}分{}秒", playerName, minutes, seconds);
    }

    /**
     * 检查宵禁时段
     *
     * @return 如果在宵禁时段返回踢出消息, 否则返回 null
     */
    private Component checkCurfew() {
        if (!plugin.hasConfigValue("CurfewStart") || !plugin.hasConfigValue("CurfewEnd")) {
            return null; // 未配置宵禁
        }

        int curfewStart = plugin.getConfigInt("CurfewStart", 22);
        int curfewEnd = plugin.getConfigInt("CurfewEnd", 6);
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
            return Component.text("当前处于未成年人宵禁时段 (", NamedTextColor.RED)
                    .append(Component.text(String.format("%02d:00", curfewStart), NamedTextColor.YELLOW))
                    .append(Component.text(" - ", NamedTextColor.RED))
                    .append(Component.text(String.format("%02d:00", curfewEnd), NamedTextColor.YELLOW))
                    .append(Component.text(")\n", NamedTextColor.RED))
                    .append(Component.text("未成年人禁止在此时段内登录游戏。", NamedTextColor.YELLOW));
        }
        return null;
    }

    // ==================== 加入事件 (启动监控 & 欢迎消息 & 诚实奖励) ====================

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!timeTracker.isTrackedMinor(uuid)) {
            return; // 非未成年玩家, 不处理
        }

        // 发送自定义未成年玩家欢迎消息 (MinorMotd)
        if (plugin.hasConfigValue("MinorMotd")) {
            String motd = plugin.getConfigString("MinorMotd", "");
            if (!motd.isEmpty()) {
                Component motdComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(motd);
                player.sendMessage(motdComponent);
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
        String policy = plugin.getConfigString("Policy", "CNNPPA");

        if ("CNNPPA".equals(policy)) {
            // CNNPPA: 显示本次会话已游玩时间和剩余时间 (总限60分钟)
            int played = timeTracker.getPlayedSeconds(uuid);
            int remaining = Math.max(0, 3600 - played);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(Component.text("[防沉迷] 根据CNNPPA政策, 您本次可游玩60分钟。", NamedTextColor.YELLOW)
                    .append(Component.text("剩余: " + remMin + "分" + remSec + "秒", NamedTextColor.WHITE)));
        } else if ("TimeLimited".equals(policy)) {
            int remaining = timeTracker.getRemainingSeconds(uuid);
            int remMin = remaining / 60;
            int remSec = remaining % 60;
            player.sendMessage(Component.text("[防沉迷] 今日剩余游戏时间: "
                    + remMin + "分" + remSec + "秒", NamedTextColor.YELLOW));
        }
    }

    /**
     * 启动未成年玩家定期监控任务 (每秒执行)
     */
    private void startMonitorTask(Player player) {
        UUID uuid = player.getUniqueId();
        String policy = plugin.getConfigString("Policy", "CNNPPA");
        int remindTime = plugin.getConfigInt("RemindTime", 60);

        com.velocitypowered.api.scheduler.ScheduledTask task = proxy.getScheduler()
                .buildTask(plugin, () -> {
                    if (!player.isActive()) {
                        stopMonitorTask(uuid);
                        return;
                    }

                    // 检查宵禁 (中途进入宵禁时段也应踢出)
                    Component curfewMsg = checkCurfew();
                    if (curfewMsg != null) {
                        player.disconnect(curfewMsg);
                        stopMonitorTask(uuid);
                        return;
                    }

                    if ("CNNPPA".equals(policy)) {
                        // CNNPPA: 本次会话限60分钟
                        int played = timeTracker.getPlayedSeconds(uuid);

                        // 45分钟时提醒
                        if (played >= 2700 && played < 2760 && !timeTracker.isRemindSent(uuid)) {
                            player.sendMessage(Component.text("[防沉迷] 您已游玩45分钟, 请注意时间! 还剩15分钟。",
                                    NamedTextColor.RED));
                            timeTracker.markRemindSent(uuid);
                        }

                        // 55分钟时再次提醒
                        if (played >= 3300 && played < 3360) {
                            int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                            if (lastWarn != 55) {
                                player.sendMessage(Component.text("[防沉迷] 警告! 您仅剩5分钟游戏时间!",
                                        NamedTextColor.RED));
                                lastUrgentWarnMin.put(uuid, 55);
                            }
                        }

                        // 60分钟强制下线
                        if (played >= 3600) {
                            Component kickMsg = Component.text("根据《国家新闻出版署》防沉迷规定,\n", NamedTextColor.RED)
                                    .append(Component.text("您的本次游戏时间已达1小时上限。\n", NamedTextColor.YELLOW))
                                    .append(Component.text("请休息后再来, 每日20:00-21:00开放。", NamedTextColor.GRAY));
                            player.disconnect(kickMsg);
                            logger.info("未成年玩家 {} 达到CNNPPA 60分钟上限, 强制下线", player.getUsername());
                            stopMonitorTask(uuid);
                        }

                        // 检查是否仍在允许时段
                        int currentHour = LocalDateTime.now().getHour();
                        if (currentHour >= 21) {
                            Component kickMsg = Component.text("根据《国家新闻出版署》防沉迷规定,\n", NamedTextColor.RED)
                                    .append(Component.text("当前时间 (21:00后) 不在未成年人允许游玩时段。\n", NamedTextColor.YELLOW))
                                    .append(Component.text("允许时段: 周五/六/日及法定节假日 20:00-21:00", NamedTextColor.GRAY));
                            player.disconnect(kickMsg);
                            logger.info("未成年玩家 {} 超出CNNPPA允许时段, 强制下线", player.getUsername());
                            stopMonitorTask(uuid);
                        }

                    } else if ("TimeLimited".equals(policy)) {
                        int remaining = timeTracker.getRemainingSeconds(uuid);
                        int remainingMinutes = remaining / 60;

                        // 提醒时间到达时发送提醒
                        if (remaining <= remindTime * 60 && !timeTracker.isRemindSent(uuid)) {
                            player.sendMessage(Component.text("[防沉迷] 您今日剩余游戏时间还有 "
                                    + remainingMinutes + " 分钟, 请合理安排时间!", NamedTextColor.YELLOW));
                            timeTracker.markRemindSent(uuid);
                        }

                        // 剩余5分钟紧急提醒 (每分钟一次)
                        if (remaining <= 300 && remaining > 240) {
                            int warnMin = remaining / 60;
                            int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                            if (lastWarn != warnMin) {
                                player.sendMessage(Component.text("[防沉迷] 警告! 您今日仅剩" + warnMin + "分钟游戏时间!",
                                        NamedTextColor.RED));
                                lastUrgentWarnMin.put(uuid, warnMin);
                            }
                        }

                        // 剩余1分钟最后提醒 (每分钟一次)
                        if (remaining <= 60 && remaining > 0) {
                            int lastWarn = lastUrgentWarnMin.getOrDefault(uuid, -1);
                            if (lastWarn != 1) {
                                player.sendMessage(Component.text("[防沉迷] 最后1分钟! 请立即保存游戏进度!",
                                        NamedTextColor.RED));
                                lastUrgentWarnMin.put(uuid, 1);
                            }
                        }

                        // 时间耗尽, 强制下线
                        if (remaining <= 0) {
                            Component kickMsg = Component.text("您今日的游戏时间已用完!\n", NamedTextColor.RED)
                                    .append(Component.text("每日 0:00 将重置游戏时长。", NamedTextColor.YELLOW));
                            player.disconnect(kickMsg);
                            logger.info("未成年玩家 {} 今日时间耗尽, 强制下线", player.getUsername());
                            stopMonitorTask(uuid);
                        }
                    }
                })
                .repeat(1, TimeUnit.SECONDS)
                .schedule();

        monitorTasks.put(uuid, task);
        lastUrgentWarnMin.put(uuid, -1);
    }

    /**
     * 停止某玩家的监控任务
     */
    private void stopMonitorTask(UUID uuid) {
        com.velocitypowered.api.scheduler.ScheduledTask task = monitorTasks.remove(uuid);
        lastUrgentWarnMin.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 执行诚实奖励命令
     */
    private void executeHonestyReward(Player player) {
        if (!plugin.hasConfigValue("HonestyReward")) {
            return;
        }

        List<String> commands = plugin.getConfigStringList("HonestyReward");
        if (commands.isEmpty()) {
            return;
        }

        // 延迟1秒执行, 确保玩家完全进入世界
        proxy.getScheduler().buildTask(plugin, () -> {
            for (String cmd : commands) {
                String processed = cmd
                        .replace("{player}", player.getUsername())
                        .replace("{uuid}", player.getUniqueId().toString());
                proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), processed);
            }
            logger.info("已为玩家 {} 执行诚实奖励命令", player.getUsername());
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    // ==================== 退出事件 ====================

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 停止监控任务
        stopMonitorTask(uuid);

        // 保存剩余时间
        timeTracker.onPlayerQuit(uuid);
    }

    // ==================== 辅助方法 ====================

    /**
     * 缓存玩家未成年状态
     */
    private void cacheMinorStatus(String uuid, boolean isMinor, String ageRange) {
        try {
            UUID playerUuid = UUID.fromString(uuid);
            if (isMinor) {
                timeTracker.onPlayerJoin(playerUuid, true, ageRange,
                        plugin.getConfigInt("MaxTime", 90));
            } else {
                // 成年玩家也缓存状态 (用于 info 命令)
                timeTracker.onPlayerJoin(playerUuid, false, ageRange, 0);
            }
        } catch (Exception e) {
            logger.warn("缓存玩家状态失败: {}", e.getMessage());
        }
    }
}
