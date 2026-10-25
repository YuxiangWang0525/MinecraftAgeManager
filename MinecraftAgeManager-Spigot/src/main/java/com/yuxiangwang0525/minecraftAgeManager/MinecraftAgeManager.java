package com.yuxiangwang0525.minecraftAgeManager;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * MinecraftAgeManager - MCAP 未成年人防沉迷管理系统
 *
 * 功能:
 * 1. 未注册玩家拒绝登录, 展示绑定提示和绑定码
 * 2. 成年玩家直接放行
 * 3. 未成年玩家执行配置的限制策略 (CNNPPA / NoMinor / TimeLimited)
 * 4. MCAP 服务不可达时谢绝新用户登录
 * 5. SQLite 缓存玩家剩余游戏时间
 * 6. LuckyPerms 集成: 自动管理 Minor 权限组
 */
public final class MinecraftAgeManager extends JavaPlugin {

    private MCAPApiClient apiClient;
    private PlayerTimeTracker timeTracker;
    private SQLiteManager dbManager;
    private LuckyPermsIntegration luckyPermsIntegration;

    @Override
    public void onEnable() {
        // 保存默认配置 (首次运行时从 jar 中释放 config.yml)
        saveDefaultConfig();

        // 确保所有配置项存在默认值
        initConfigDefaults();

        // 初始化 SQLite 数据库
        dbManager = new SQLiteManager(getDataFolder(), getLogger());
        dbManager.connect();

        // 初始化 LuckyPerms 集成
        String minorGroupName = getConfig().getString("LuckyPerms.MinorGroup", "Minor");
        luckyPermsIntegration = new LuckyPermsIntegration(getLogger(), minorGroupName);
        luckyPermsIntegration.initialize();

        // 读取配置并初始化 API 客户端
        String mcapAddress = getConfig().getString("MCAP", "http://localhost:3000");
        String apiKey = getConfig().getString("APIKEY", "");
        apiClient = new MCAPApiClient(mcapAddress, apiKey, getLogger());

        // 初始化时间追踪器
        timeTracker = new PlayerTimeTracker(this, dbManager);

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(
                new PlayerLoginListener(this, apiClient, timeTracker, luckyPermsIntegration), this);

        // 注册命令处理器
        Objects.requireNonNull(getCommand("agemanager"))
                .setExecutor(new AgeManagerCommand(this, timeTracker, apiClient));

        // 启动时检查 MCAP 服务连通性
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            if (apiClient.ping()) {
                getLogger().info("MCAP 后端服务连接成功: " + mcapAddress);
            } else {
                // 检测是否为默认配置
                boolean isDefaultAddress = "http://localhost:3000".equals(mcapAddress)
                        || "https://localhost:3000".equals(mcapAddress)
                        || "localhost:3000".equals(mcapAddress);

                if (isDefaultAddress) {
                    getLogger().severe("════════════════════════════════════════════════════════");
                    getLogger().severe("  无法连接到 MCAP 后端服务: " + mcapAddress);
                    getLogger().severe("  检测到您使用的是默认配置, 请按以下步骤操作:");
                    getLogger().severe("────────────────────────────────────────────────────────");
                    getLogger().severe("  1. 打开配置文件:");
                    getLogger().severe("     plugins/MinecraftAgeManager/config.yml");
                    getLogger().severe("  2. 修改 MCAP 地址为您的实际服务地址:");
                    getLogger().severe("     MCAP: \"https://your-mcap-server.com\"");
                    getLogger().severe("  3. 填写您的 API Key:");
                    getLogger().severe("     APIKEY: \"mcap_xxxxxxxx\"");
                    getLogger().severe("  4. 保存文件后执行重载命令:");
                    getLogger().severe("     /agemanager reload");
                    getLogger().severe("════════════════════════════════════════════════════════");
                } else {
                    getLogger().warning("无法连接到 MCAP 后端服务: " + mcapAddress);
                    getLogger().warning("新用户将被拒绝登录, 请检查:");
                    getLogger().warning("  - MCAP 服务是否正在运行");
                    getLogger().warning("  - 地址配置是否正确");
                    getLogger().warning("  - APIKEY 是否有效");
                    getLogger().warning("修改配置后请执行: /agemanager reload");
                }
            }
        });

        getLogger().info("MinecraftAgeManager 已启用! 限制策略: "
                + getConfig().getString("Policy", "CNNPPA"));
    }

    @Override
    public void onDisable() {
        // 取消所有调度任务
        getServer().getScheduler().cancelTasks(this);

        // 关闭数据库连接
        if (dbManager != null) {
            dbManager.disconnect();
        }

        getLogger().info("MinecraftAgeManager 已禁用");
    }

    /**
     * 获取 LuckyPerms 集成实例
     */
    public LuckyPermsIntegration getLuckyPermsIntegration() {
        return luckyPermsIntegration;
    }

    /**
     * 重新初始化 API 客户端并检查连接状态
     * 用于 reload 命令后更新连接
     *
     * @return 连接是否成功
     */
    public boolean reinitializeApiClient() {
        String mcapAddress = getConfig().getString("MCAP", "http://localhost:3000");
        String apiKey = getConfig().getString("APIKEY", "");
        apiClient = new MCAPApiClient(mcapAddress, apiKey, getLogger());
        return apiClient.ping();
    }

    /**
     * 获取当前 API 客户端
     */
    public MCAPApiClient getApiClient() {
        return apiClient;
    }

    /**
     * 检查 MCAP 地址是否为默认值
     */
    public boolean isDefaultMcapAddress() {
        String mcapAddress = getConfig().getString("MCAP", "http://localhost:3000");
        return "http://localhost:3000".equals(mcapAddress)
                || "https://localhost:3000".equals(mcapAddress)
                || "localhost:3000".equals(mcapAddress);
    }

    /**
     * 设置所有配置项的默认值, 避免 get 时返回 null
     */
    private void initConfigDefaults() {
        getConfig().options().copyDefaults(true);
        getConfig().addDefault("MCAP", "http://localhost:3000");
        getConfig().addDefault("APIKEY", "mcap_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        getConfig().addDefault("Policy", "CNNPPA");
        getConfig().addDefault("RemindTime", 60);
        getConfig().addDefault("MaxTime", 90);
        getConfig().addDefault("LuckyPerms.MinorGroup", "Minor");
        getConfig().addDefault("LuckyPerms.Enabled", true);
        saveConfig();
    }
}
