package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * SQLite 数据库管理器
 * 用于缓存玩家剩余游戏时间
 */
public class SQLiteManager {

    private final Path dataFolder;
    private final Logger logger;
    private Connection connection;

    public SQLiteManager(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    /**
     * 初始化数据库连接
     */
    public void connect() {
        try {
            if (!dataFolder.toFile().exists()) {
                dataFolder.toFile().mkdirs();
            }
            Path dbFile = dataFolder.resolve("times.db");
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            createTables();
            logger.info("SQLite 数据库连接成功");
        } catch (Exception e) {
            logger.error("无法连接 SQLite 数据库", e);
        }
    }

    /**
     * 关闭数据库连接
     */
    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("SQLite 数据库连接已关闭");
            }
        } catch (SQLException e) {
            logger.warn("关闭数据库连接时出错", e);
        }
    }

    /**
     * 创建数据表
     */
    private void createTables() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS player_times (
                uuid TEXT PRIMARY KEY,
                remaining_seconds INTEGER NOT NULL DEFAULT 0,
                last_date TEXT NOT NULL
            )
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            logger.error("无法创建数据表", e);
        }
    }

    /**
     * 获取玩家剩余游戏时间
     *
     * @param uuid           玩家 UUID
     * @param maxTimeSeconds 最大游戏时长 (秒), 用于新一天重置
     * @return 剩余秒数
     */
    public int getRemainingSeconds(String uuid, int maxTimeSeconds) {
        String sql = "SELECT remaining_seconds, last_date FROM player_times WHERE uuid = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int remaining = rs.getInt("remaining_seconds");
                String lastDate = rs.getString("last_date");
                String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

                // 如果不是今天的数据, 重置为最大时长
                if (!today.equals(lastDate)) {
                    remaining = maxTimeSeconds;
                    updateRemainingSeconds(uuid, remaining);
                }
                return remaining;
            } else {
                // 新玩家, 返回最大时长
                return maxTimeSeconds;
            }
        } catch (SQLException e) {
            logger.warn("查询玩家时间失败: {}", uuid, e);
            return maxTimeSeconds;
        }
    }

    /**
     * 更新玩家剩余游戏时间
     *
     * @param uuid             玩家 UUID
     * @param remainingSeconds 剩余秒数
     */
    public void updateRemainingSeconds(String uuid, int remainingSeconds) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String sql = """
            INSERT INTO player_times (uuid, remaining_seconds, last_date)
            VALUES (?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                remaining_seconds = excluded.remaining_seconds,
                last_date = excluded.last_date
            """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            pstmt.setInt(2, remainingSeconds);
            pstmt.setString(3, today);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("更新玩家时间失败: {}", uuid, e);
        }
    }

    /**
     * 检查玩家是否有数据记录
     *
     * @param uuid 玩家 UUID
     * @return 是否有数据
     */
    public boolean hasData(String uuid) {
        String sql = "SELECT 1 FROM player_times WHERE uuid = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.warn("检查玩家数据失败: {}", uuid, e);
            return false;
        }
    }

    /**
     * 删除玩家数据
     *
     * @param uuid 玩家 UUID
     */
    public void deletePlayer(String uuid) {
        String sql = "DELETE FROM player_times WHERE uuid = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("删除玩家数据失败: {}", uuid, e);
        }
    }
}
