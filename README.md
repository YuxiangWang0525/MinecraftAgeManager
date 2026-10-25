# MinecraftAgeManager(MCAM)

A Minecraft anti-addiction compliance plugin for managing minor players' gameplay time, supporting both **Spigot** and **Velocity** platforms.

[中文文档](README_CN.md)

Internal Codename: [Metamo Reborn](https://projectsekai.fandom.com/wiki/Metamo_Re:born)

> ## 🚧 Work in progress 🚧  
> Minecraft Age Manager is currently under active development and is not yet ready for operation in a production environment.

## Features

- **Real-name Authentication**: Integrates with MCAP backend for player identity verification
- **Multiple Restriction Policies**:
  - **CNNPPA**: China's National Press and Publication Administration policy - minors can only play on Fri/Sat/Sun and legal holidays from 20:00-21:00
  - **NoMinor**: Completely block minor players from joining
  - **TimeLimited**: Custom daily time limit with configurable max duration
- **SQLite Persistence**: Caches remaining gameplay time locally (does NOT cache age status)
- **LuckyPerms Integration**: Automatically manages a `Minor` permission group for minors
- **Curfew Support**: Optional curfew time range to block minor logins
- **Unregistered Player Handling**: Shows binding code and link for real-name authentication
- **API Fallback**: When MCAP is unreachable, known players can still join but new players are blocked

## Requirements

- Java 17+
- MCAP backend service for real-name authentication
- (Optional) LuckyPerms for permission group management

## Installation

1. Download the appropriate JAR for your platform:
   - `minecraftagemanager-0.0.1.jar` for Spigot
   - `minecraftagemanager-velocity-0.0.1.jar` for Velocity

2. Place the JAR in your server's `plugins` directory

3. Start the server to generate the configuration file

4. Edit `plugins/MinecraftAgeManager/config.yml`:
   ```yaml
   # MCAP backend address (required)
   MCAP: "https://your-mcap-server.com"
   
   # MCAP API Key (required)
   APIKEY: "mcap_xxxxxxxx"
   
   # Restriction policy: CNNPPA / NoMinor / TimeLimited
   Policy: "CNNPPA"
   ```

5. Reload the plugin:
   ```
   /agemanager reload
   ```

## Configuration

```yaml
# MCAP backend service address (required)
MCAP: "http://localhost:3000"

# MCAP API Key (required, format: mcap_xxxxxxxx)
APIKEY: "mcap_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Restriction policy (required)
# CNNPPA      - National policy: Fri/Sat/Sun & holidays 20:00-21:00 only
# NoMinor     - Block all minor players
# TimeLimited - Custom daily time limit
Policy: "CNNPPA"

# TimeLimited policy settings
RemindTime: 60  # Reminder time (minutes before time runs out)
MaxTime: 90     # Maximum daily playtime (minutes)

# Optional curfew (24-hour format)
# CurfewStart: 22
# CurfewEnd: 6

# LuckyPerms integration (optional)
LuckyPerms:
  Enabled: true
  MinorGroup: "Minor"  # Group name for minor players

# Optional: Custom welcome message for minors
# MinorMotd: "&e[Anti-Addiction] You are a minor player, please arrange your game time reasonably!"

# Optional: Reward commands for verified minor players
# HonestyReward:
#   - "give {player} diamond 1"

# CNNPPA legal holidays (MM-dd format)
CNNPPAHolidays:
  - "01-01"  # New Year
  - "05-01"  # Labor Day
  - "10-01"  # National Day
  # ... add more as needed
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/agemanager timeleft` | Check remaining gameplay time (minors only) | `agemanager.use` |
| `/agemanager info` | View player info and server policy | `agemanager.use` |
| `/agemanager reload` | Reload configuration and check MCAP connection | `agemanager.admin` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `agemanager.use` | Allows using age management commands | `true` (everyone) |
| `agemanager.admin` | Allows reloading plugin configuration | `op` |

## Build from Source

```bash
# Build Spigot version
cd MinecraftAgeManager-Spigot
mvn clean package

# Build Velocity version
cd MinecraftAgeManager-Velocity
mvn clean package
```

## Architecture

```
┌──────────────┐     API Key      ┌──────────────┐
│  Minecraft   │ ◄──────────────► │  MCAP Backend │
│   Server     │   HTTP/JSON     │              │
└──────┬───────┘                  └──────┬───────┘
       │                                 │
       │  SQLite (time cache)            │  User authentication
       ▼                                 ▼
┌──────────────┐                 ┌───────────────┐
│   times.db   │                 │  MCAP Frontend │
└──────────────┘                 └───────────────┘
```

## License

This project is licensed under the GNU General Public License v3.0

## Credits

- Author: RiverFrozer
- MCAP API integration for real-name verification
- LuckyPerms API for permission management
- JetBrains for providing the IntelliJ IDEA free license.
- Qoder CN for AI Assisted
