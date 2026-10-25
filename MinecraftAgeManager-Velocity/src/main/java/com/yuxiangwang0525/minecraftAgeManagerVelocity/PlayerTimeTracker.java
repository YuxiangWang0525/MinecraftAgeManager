package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未成年玩家游戏时长追踪器
 * 管理每日游戏时长、剩余时间计算
 * 使用 SQLite 持久化存储
 */
public class PlayerTimeTracker {

    private final SQLiteManager dbManager;

    /** 缓存的未成年玩家信息 (UUID -> MinorInfo), 在登录时填充, 用于命令查询和定期任务 */
    private final Map<UUID, MinorInfo> minorCache = new ConcurrentHashMap<>();

    /** 玩家本次会话加入时间 (用于计算已游玩时长) */
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    /** 本次会话开始时加载的剩余秒数 */
    private final Map<UUID, Integer> sessionRemaining = new ConcurrentHashMap<>();

    /** 已发送提醒的玩家集合 (防止重复提醒) */
    private final Map<UUID, Boolean> remindSent = new ConcurrentHashMap<>();

    public PlayerTimeTracker(SQLiteManager dbManager) {
        this.dbManager = dbManager;
    }

    // ==================== 会话管理 ====================

    /**
     * 玩家加入时初始化追踪
     *
     * @param uuid           玩家 UUID
     * @param isMinor        是否为未成年人
     * @param ageRange       年龄段 (如 "16+", "18+")
     * @param maxTimeMinutes 最大游戏时长 (分钟), 仅 TimeLimited 策略使用
     */
    public void onPlayerJoin(UUID uuid, boolean isMinor, String ageRange, int maxTimeMinutes) {
        if (isMinor) {
            minorCache.put(uuid, new MinorInfo(true, ageRange));
            sessionStart.put(uuid, System.currentTimeMillis());

            // 从 SQLite 加载剩余时间
            int maxTimeSeconds = maxTimeMinutes * 60;
            int remaining = dbManager.getRemainingSeconds(uuid.toString(), maxTimeSeconds);
            sessionRemaining.put(uuid, remaining);
            remindSent.put(uuid, false);
        } else {
            minorCache.put(uuid, new MinorInfo(false, ageRange));
        }
    }

    /**
     * 玩家退出时保存剩余时间
     */
    public void onPlayerQuit(UUID uuid) {
        if (isTrackedMinor(uuid)) {
            int remaining = getRemainingSeconds(uuid);
            dbManager.updateRemainingSeconds(uuid.toString(), Math.max(0, remaining));
        }

        minorCache.remove(uuid);
        sessionStart.remove(uuid);
        sessionRemaining.remove(uuid);
        remindSent.remove(uuid);
    }

    // ==================== 时间计算 ====================

    /**
     * 获取玩家当前剩余秒数 (实时计算, 不修改存储值)
     */
    public int getRemainingSeconds(UUID uuid) {
        Integer base = sessionRemaining.get(uuid);
        Long start = sessionStart.get(uuid);
        if (base == null || start == null) {
            return 0;
        }
        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;
        return (int) Math.max(0, base - elapsedSeconds);
    }

    /**
     * 获取玩家本次会话已游玩秒数
     */
    public int getPlayedSeconds(UUID uuid) {
        Long start = sessionStart.get(uuid);
        if (start == null) return 0;
        return (int) ((System.currentTimeMillis() - start) / 1000);
    }

    /**
     * 检查是否已发送提醒
     */
    public boolean isRemindSent(UUID uuid) {
        return remindSent.getOrDefault(uuid, false);
    }

    /**
     * 标记已发送提醒
     */
    public void markRemindSent(UUID uuid) {
        remindSent.put(uuid, true);
    }

    // ==================== 查询方法 ====================

    /**
     * 获取玩家缓存的未成年信息
     */
    public MinorInfo getMinorInfo(UUID uuid) {
        return minorCache.get(uuid);
    }

    /**
     * 检查玩家是否被追踪为未成年
     */
    public boolean isTrackedMinor(UUID uuid) {
        MinorInfo info = minorCache.get(uuid);
        return info != null && info.isMinor;
    }

    /**
     * 检查玩家是否有已保存的数据 (用于判断是否为新用户)
     */
    public boolean hasSavedData(UUID uuid) {
        return dbManager.hasData(uuid.toString());
    }

    /**
     * 获取玩家保存的年龄段 (从缓存)
     */
    public String getCachedAgeRange(UUID uuid) {
        MinorInfo info = minorCache.get(uuid);
        return info != null ? info.ageRange : null;
    }

    // ==================== 数据类 ====================

    /**
     * 缓存的未成年玩家信息
     */
    public static class MinorInfo {
        public final boolean isMinor;
        public final String ageRange;

        public MinorInfo(boolean isMinor, String ageRange) {
            this.isMinor = isMinor;
            this.ageRange = ageRange;
        }
    }
}
