package com.yuxiangwang0525.minecraftAgeManagerVelocity;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LuckyPerms 集成管理器
 * 用于将未成年玩家加入 Minor 权限组, 成年后自动移除
 */
public class LuckyPermsIntegration {

    private final Logger logger;
    private final String minorGroupName;
    private LuckPerms luckPermsApi;
    private boolean available;

    public LuckyPermsIntegration(Logger logger, String minorGroupName) {
        this.logger = logger;
        this.minorGroupName = minorGroupName;
        this.available = false;
    }

    /**
     * 初始化 LuckyPerms API
     *
     * @return 是否成功连接
     */
    public boolean initialize() {
        try {
            luckPermsApi = LuckPermsProvider.get();
            available = true;
            logger.info("LuckyPerms 集成已启用, 未成年玩家将被加入 '{}' 权限组", minorGroupName);
            return true;
        } catch (Exception e) {
            available = false;
            logger.info("LuckyPerms 未检测到, 跳过权限组集成");
            return false;
        }
    }

    /**
     * 检查 LuckyPerms 是否可用
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 将玩家加入 Minor 权限组
     *
     * @param uuid 玩家 UUID
     * @return 操作是否成功
     */
    public CompletableFuture<Boolean> addToMinorGroup(UUID uuid) {
        if (!available) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = luckPermsApi.getUserManager().loadUser(uuid).join();
                if (user == null) {
                    logger.warn("无法加载玩家数据: {}", uuid);
                    return false;
                }

                // 检查是否已在组中
                boolean inGroup = user.getNodes().stream()
                        .anyMatch(node -> node.getKey().equals("group." + minorGroupName));
                if (inGroup) {
                    return true; // 已在组中
                }

                // 添加到组
                Node node = Node.builder("group." + minorGroupName).build();
                user.data().add(node);
                luckPermsApi.getUserManager().saveUser(user);
                logger.info("已将玩家 {} 加入 '{}' 权限组", uuid, minorGroupName);
                return true;
            } catch (Exception e) {
                logger.warn("添加玩家到 Minor 组失败: {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * 将玩家从 Minor 权限组移除
     *
     * @param uuid 玩家 UUID
     * @return 操作是否成功
     */
    public CompletableFuture<Boolean> removeFromMinorGroup(UUID uuid) {
        if (!available) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = luckPermsApi.getUserManager().loadUser(uuid).join();
                if (user == null) {
                    logger.warn("无法加载玩家数据: {}", uuid);
                    return false;
                }

                // 检查是否在组中
                boolean inGroup = user.getNodes().stream()
                        .anyMatch(node -> node.getKey().equals("group." + minorGroupName));

                if (!inGroup) {
                    return true; // 不在组中, 无需移除
                }

                // 从组中移除
                Node node = Node.builder("group." + minorGroupName).build();
                user.data().remove(node);
                luckPermsApi.getUserManager().saveUser(user);
                logger.info("已将玩家 {} 从 '{}' 权限组移除 (已成年)", uuid, minorGroupName);
                return true;
            } catch (Exception e) {
                logger.warn("从 Minor 组移除玩家失败: {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * 检查玩家是否在 Minor 权限组中
     *
     * @param uuid 玩家 UUID
     * @return 是否在组中
     */
    public CompletableFuture<Boolean> isInMinorGroup(UUID uuid) {
        if (!available) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = luckPermsApi.getUserManager().loadUser(uuid).join();
                if (user == null) {
                    return false;
                }
                return user.getNodes().stream()
                        .anyMatch(node -> node.getKey().equals("group." + minorGroupName));
            } catch (Exception e) {
                logger.warn("检查玩家权限组失败: {}", uuid, e);
                return false;
            }
        });
    }
}
