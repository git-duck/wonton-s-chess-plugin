# ChessPlugin

A fully interactive 8x8 Chess engine built natively for Minecraft servers (Paper/Spigot/Bukkit). Instead of using external maps or blocks, games are played entirely inside a custom, split-screen User Interface combining a virtual chest with the player's own inventory.

Current state is debugging some glitches we found.
## Authors & Credits

* **[wonton_stew](https://github.com)** — *Original Creator*
  source code mine :P (oh god that ":P" looks scary XD)
* **[MrFrenchGuy23](https://github.com/MrFrenchGuy23)** — 




## Core Features

* **Split-Screen GUI Board**: Renders board rows 0–3 inside a top virtual chest and maps rows 4–7 directly onto the player's active inventory screen.
* **Automated Data Protection**: Captures full snapshots of player items, armor, and off-hand objects upon starting, rolling back original configurations seamlessly when matches conclude or players leave.
* **Integrated Rules Engine**: Includes check, checkmate, stalemate verification, castling parameters, en passant logic, and auto-promotion directly to Queens.
* **Blitz Match Timers**: Integrates built-in 5-minute blitz clocks that update continuously in real-time right inside the command column.

---

## Commands & Permissions

Players require the `chess.use` permission node to access all primary game commands.

| Command | Description | Usage |

| `/chess <player>` | Challenges an active online user to a blitz match. | `/chess PlayerName` |
| `/chessaccept` | Accepts a pending invitation and starts the UI board. | `/chessaccept` |
| `/chessdeny` | Rejects a pending invitation and alerts the challenger. | `/chessdeny` |

### Configuration (`plugin.yml`)
```yaml
commands:
  chess:
    description: Send a chess challenge: /chess <player>
    usage: /chess <player>
    permission: chess.use
  chessaccept:
    description: Accept a chess challenge
    usage: /chessaccept
    permission: chess.use
  chessdeny:
    description: Deny a chess challenge
    usage: /chessdeny
    permission: chess.use

permissions:
  chess.use:
    description: Allows using the chess commands
    default: true
```

---

## Developer Installation

### Prerequisites
* Java 17 or higher
* Spigot/Paper API (Targeting 1.20+)
* Maven / Gradle

### Setup Instructions
1. Clone your project code directly into your development workspace:
   ```bash
   git clone https://github.com/ChessPlugin.git
   ```
2. Build the output file using your preferred build automation tool:
   ```bash
   mvn clean package
   ```
3. Drop the compiled `chessplugin-1.0.jar` target directly into your test environment's `plugins/` directory.
