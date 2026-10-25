# MinecraftAgeManager

Minecraft 未成年人防沉迷合规插件，支持 **Spigot** 和 **Velocity** 双平台。

[English Documentation](README.md)

内部代号:[Metamo Reborn](https://projectsekai.fandom.com/wiki/Metamo_Re:born)
> ## 🚧 开发进行中 🚧
> MCAM是实验性项目，暂时不推荐用于生产环境。


## 功能特性

- **实名认证**：集成 MCAP 后端进行玩家身份验证
- **多种限制策略**：
  - **CNNPPA**：国家新闻出版署政策 - 未成年人仅可在周五/周六/周日及法定节假日 20:00-21:00 游玩
  - **NoMinor**：完全禁止未成年人登录
  - **TimeLimited**：自定义每日游戏时长限制
- **SQLite 持久化**：本地缓存剩余游戏时间（不缓存年龄状态）
- **LuckyPerms 集成**：自动管理 `Minor` 权限组
- **宵禁支持**：可配置宵禁时段，禁止未成年人在特定时段登录
- **未注册玩家处理**：显示绑定码和链接，引导完成实名认证
- **API 故障回退**：MCAP 不可达时，已知玩家可继续游戏，新玩家被拒绝

## 环境要求

- Java 17+
- MCAP 后端服务（用于实名认证）
- （可选）LuckyPerms 插件（用于权限组管理）

## 安装步骤

1. 下载对应平台的 JAR 文件：
   - Spigot: `minecraftagemanager-0.0.1.jar`
   - Velocity: `minecraftagemanager-velocity-0.0.1.jar`

2. 将 JAR 文件放入服务器的 `plugins` 目录

3. 启动服务器，生成配置文件

4. 编辑 `plugins/MinecraftAgeManager/config.yml`：
   ```yaml
   # MCAP 后端服务地址（必填）
   MCAP: "https://your-mcap-server.com"
   
   # MCAP API Key（必填）
   APIKEY: "mcap_xxxxxxxx"
   
   # 限制策略: CNNPPA / NoMinor / TimeLimited
   Policy: "CNNPPA"
   ```

5. 重载插件：
   ```
   /agemanager reload
   ```

## 配置说明

```yaml
# MCAP 后端服务地址（必填）
MCAP: "http://localhost:3000"

# MCAP API Key（必填，格式: mcap_xxxxxxxx）
APIKEY: "mcap_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# 未成年人限制策略（必填）
# CNNPPA      - 国家新闻出版署政策: 仅周五/周六/周日及法定节假日 20:00-21:00 可游玩
# NoMinor     - 禁止所有未成年人登录
# TimeLimited - 自定义时长限制
Policy: "CNNPPA"

# TimeLimited 策略专用配置
RemindTime: 60  # 提醒时间（分钟）
MaxTime: 90     # 每日最大游戏时长（分钟）

# 可选：未成年人宵禁（24小时制）
# CurfewStart: 22
# CurfewEnd: 6

# LuckyPerms 集成（可选）
LuckyPerms:
  Enabled: true
  MinorGroup: "Minor"  # 未成年玩家权限组名称

# 可选：自定义未成年玩家欢迎提示语
# MinorMotd: "&e[防沉迷] 您为未成年玩家, 请合理安排游戏时间!"

# 可选：诚实奖励命令列表
# HonestyReward:
#   - "give {player} diamond 1"

# CNNPPA 法定节假日列表（MM-dd 格式）
CNNPPAHolidays:
  - "01-01"  # 元旦
  - "05-01"  # 劳动节
  - "10-01"  # 国庆节
  # ... 根据需要添加更多
```

## 命令列表

| 命令 | 说明 | 权限 |
|------|------|------|
| `/agemanager timeleft` | 查询剩余游戏时长（仅未成年玩家可用） | `agemanager.use` |
| `/agemanager info` | 查看玩家信息与服务器策略 | `agemanager.use` |
| `/agemanager reload` | 重载配置并检查 MCAP 连接 | `agemanager.admin` |

## 权限节点

| 权限 | 说明 | 默认值 |
|------|------|--------|
| `agemanager.use` | 允许使用年龄管理命令 | `true`（所有玩家） |
| `agemanager.admin` | 允许重载插件配置 | `op` |

## 从源码构建

```bash
# 构建 Spigot 版本
cd MinecraftAgeManager-Spigot
mvn clean package

# 构建 Velocity 版本
cd MinecraftAgeManager-Velocity
mvn clean package
```

## 系统架构

```
┌──────────────┐     API Key      ┌──────────────┐
│  Minecraft   │ ◄──────────────► │  MCAP 后端    │
│   服务器      │   HTTP/JSON     │              │
└──────┬───────┘                  └──────┬───────┘
       │                                 │
       │  SQLite (时间缓存)               │  用户实名认证
       ▼                                 ▼
┌──────────────┐                 ┌───────────────┐
│   times.db   │                 │  MCAP 前端     │
└──────────────┘                 └───────────────┘
```

## 政策依据

本插件依据《国家新闻出版署关于进一步严格管理 切实防止未成年人沉迷网络游戏的通知》（国新出发〔2021〕14号）设计：

> 所有网络游戏企业仅可在周五、周六、周日和法定节假日每日20时至21时向未成年人提供1小时网络游戏服务，其他时间均不得以任何形式向未成年人提供网络游戏服务。

## 常见问题

### Q: 插件启动时提示无法连接到 MCAP 后端？
A: 请检查 `config.yml` 中的 `MCAP` 地址和 `APIKEY` 配置是否正确。如果是首次使用，请将默认的 `localhost:3000` 修改为您的实际 MCAP 服务地址。

### Q: 如何禁用 LuckyPerms 集成？
A: 在 `config.yml` 中设置 `LuckyPerms.Enabled: false`，或者不安装 LuckyPerms 插件，集成会自动禁用。

### Q: 玩家剩余时间数据存储在哪里？
A: 存储在插件目录下的 `times.db` SQLite 数据库中。每日 0:00 自动重置为最大时长。

### Q: 如何手动更新玩家的剩余时间？
A: 删除 `times.db` 中对应玩家的记录，或删除整个数据库文件（会重置所有玩家数据）。

## 许可证

本插件基于 GNU General Public License v3.0 许可证发布

## 致谢

- 作者：RiverFrozer
- MCAP API：实名认证接口
- JetBrains: 提供了IntelliJ IDEA的免费许可证，万分感谢。
- LuckyPerms API：权限管理集成
- Qoder CN: AI辅助编程支持