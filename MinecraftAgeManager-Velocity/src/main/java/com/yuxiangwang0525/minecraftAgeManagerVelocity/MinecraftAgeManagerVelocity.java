package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MinecraftAgeManager - MCAP 未成年人防沉迷管理系统 (Velocity 版本)
 *
 * 功能:
 * 1. 未注册玩家拒绝登录, 展示绑定提示和绑定码
 * 2. 成年玩家直接放行
 * 3. 未成年玩家执行配置的限制策略 (CNNPPA / NoMinor / TimeLimited)
 * 4. MCAP 服务不可达时谢绝新用户登录
 * 5. SQLite 缓存玩家剩余游戏时间
 * 6. LuckyPerms 集成: 自动管理 Minor 权限组
 */
@Plugin(id = "minecraftagemanager", name = "MinecraftAgeManager", version = "0.0.1",
        description = "MCAP 未成年人防沉迷管理系统 - Velocity 版本",
        url = "yuxiangwang0525.com", authors = {"RiverFrozer"})
public final class MinecraftAgeManagerVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private MCAPApiClient apiClient;
    private PlayerTimeTracker timeTracker;
    private SQLiteManager dbManager;
    private LuckyPermsIntegration luckyPermsIntegration;
    private Map<String, Object> config;
    private final Yaml yaml;
    private File configFile;

    @Inject
    public MinecraftAgeManagerVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.yaml = new Yaml();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 加载配置
        loadConfig();

        // 初始化 SQLite 数据库
        dbManager = new SQLiteManager(dataDirectory, logger);
        dbManager.connect();

        // 初始化 LuckyPerms 集成
        String minorGroupName = getConfigString("LuckyPerms.MinorGroup", "Minor");
        luckyPermsIntegration = new LuckyPermsIntegration(logger, minorGroupName);
        luckyPermsIntegration.initialize();

        // 初始化 API 客户端
        String mcapAddress = getConfigString("MCAP", "http://localhost:3000");
        String apiKey = getConfigString("APIKEY", "");
        apiClient = new MCAPApiClient(mcapAddress, apiKey, logger);

        // 初始化时间追踪器
        timeTracker = new PlayerTimeTracker(dbManager);

        // 注册事件监听器
        proxy.getEventManager().register(this, new PlayerLoginListener(this, proxy, apiClient, timeTracker, luckyPermsIntegration));

        // 注册命令
        CommandManager commandManager = proxy.getCommandManager();
        new AgeManagerCommand(this, timeTracker, apiClient).register(commandManager);

        // 启动时检查 MCAP 服务连通性
        proxy.getScheduler().buildTask(this, () -> {
            if (apiClient.ping()) {
                logger.info("MCAP 后端服务连接成功: {}", mcapAddress);
            } else {
                // 检测是否为默认配置
                boolean isDefaultAddress = "http://localhost:3000".equals(mcapAddress)
                        || "https://localhost:3000".equals(mcapAddress)
                        || "localhost:3000".equals(mcapAddress);

                if (isDefaultAddress) {
                    logger.error("════════════════════════════════════════════════════════");
                    logger.error("  无法连接到 MCAP 后端服务: {}", mcapAddress);
                    logger.error("  检测到您使用的是默认配置, 请按以下步骤操作:");
                    logger.error("────────────────────────────────────────────────────────");
                    logger.error("  1. 打开配置文件:");
                    logger.error("     plugins/minecraftagemanager/config.yml");
                    logger.error("  2. 修改 MCAP 地址为您的实际服务地址:");
                    logger.error("     MCAP: \"https://your-mcap-server.com\"");
                    logger.error("  3. 填写您的 API Key:");
                    logger.error("     APIKEY: \"mcap_xxxxxxxx\"");
                    logger.error("  4. 保存文件后执行重载命令:");
                    logger.error("     /agemanager reload");
                    logger.error("════════════════════════════════════════════════════════");
                } else {
                    logger.warn("无法连接到 MCAP 后端服务: {}", mcapAddress);
                    logger.warn("新用户将被拒绝登录, 请检查:");
                    logger.warn("  - MCAP 服务是否正在运行");
                    logger.warn("  - 地址配置是否正确");
                    logger.warn("  - APIKEY 是否有效");
                    logger.warn("修改配置后请执行: /agemanager reload");
                }
            }
        }).schedule();

        logger.info("MinecraftAgeManager 已启用! 限制策略: {}", getConfigString("Policy", "CNNPPA"));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // 关闭数据库连接
        if (dbManager != null) {
            dbManager.disconnect();
        }
        logger.info("MinecraftAgeManager 已禁用");
    }

    // ==================== 配置管理 ====================

    /**
     * 加载配置文件
     */
    @SuppressWarnings("unchecked")
    private void loadConfig() {
        configFile = dataDirectory.resolve("config.yml").toFile();

        // 首次运行时从 jar 中释放默认配置
        if (!configFile.exists()) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    } else {
                        // 如果 jar 中没有配置文件, 创建空文件
                        configFile.createNewFile();
                    }
                }
            } catch (IOException e) {
                logger.error("无法创建默认配置文件", e);
            }
        }

        // 读取配置
        try (InputStream in = new FileInputStream(configFile)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                config = (Map<String, Object>) loaded;
            } else {
                config = new HashMap<>();
            }
        } catch (Exception e) {
            logger.error("无法加载配置文件 config.yml", e);
            config = new HashMap<>();
        }

        // 确保默认值存在
        initConfigDefaults();
    }

    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        loadConfig();
    }

    /**
     * 设置所有配置项的默认值
     */
    private void initConfigDefaults() {
        config.putIfAbsent("MCAP", "http://localhost:3000");
        config.putIfAbsent("APIKEY", "mcap_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        config.putIfAbsent("Policy", "CNNPPA");
        config.putIfAbsent("RemindTime", 60);
        config.putIfAbsent("MaxTime", 90);

        // LuckyPerms 默认配置
        Map<String, Object> luckyPermsConfig = new HashMap<>();
        luckyPermsConfig.put("Enabled", true);
        luckyPermsConfig.put("MinorGroup", "Minor");
        config.putIfAbsent("LuckyPerms", luckyPermsConfig);
    }

    // ==================== 配置访问方法 ====================

    public String getConfigString(String key, String defaultValue) {
        // 支持嵌套键 (如 "LuckyPerms.MinorGroup")
        String[] parts = key.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return defaultValue;
            }
        }
        if (current instanceof String) {
            return (String) current;
        }
        return defaultValue;
    }

    public int getConfigInt(String key, int defaultValue) {
        String[] parts = key.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return defaultValue;
            }
        }
        if (current instanceof Number) {
            return ((Number) current).intValue();
        }
        return defaultValue;
    }

    public boolean getConfigBoolean(String key, boolean defaultValue) {
        String[] parts = key.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return defaultValue;
            }
        }
        if (current instanceof Boolean) {
            return (Boolean) current;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> getConfigStringList(String key) {
        Object value = config.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    public boolean hasConfigValue(String key) {
        String[] parts = key.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
                if (current == null) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    public Logger getLogger() {
        return logger;
    }

    public LuckyPermsIntegration getLuckyPermsIntegration() {
        return luckyPermsIntegration;
    }

    public MCAPApiClient getApiClient() {
        return apiClient;
    }

    /**
     * 重新初始化 API 客户端并检查连接状态
     * 用于 reload 命令后更新连接
     *
     * @return 连接是否成功
     */
    public boolean reinitializeApiClient() {
        String mcapAddress = getConfigString("MCAP", "http://localhost:3000");
        String apiKey = getConfigString("APIKEY", "");
        apiClient = new MCAPApiClient(mcapAddress, apiKey, logger);
        return apiClient.ping();
    }

    /**
     * 检查 MCAP 地址是否为默认值
     */
    public boolean isDefaultMcapAddress() {
        String mcapAddress = getConfigString("MCAP", "http://localhost:3000");
        return "http://localhost:3000".equals(mcapAddress)
                || "https://localhost:3000".equals(mcapAddress)
                || "localhost:3000".equals(mcapAddress);
    }
}
