# ChessPlugin

[![Build](https://img.shields.io/github/actions/workflow/status/git-duck/wonton-s-chess-plugin/main.yml)](https://github.com/git-duck/wonton-s-chess-plugin/actions/workflows/main.yml)
[![Release](https://img.shields.io/github/v/release/git-duck/wonton-s-chess-plugin)](https://github.com/git-duck/wonton-s-chess-plugin/releases)
[![Downloads](https://img.shields.io/github/downloads/git-duck/wonton-s-chess-plugin/total)](https://github.com/git-duck/wonton-s-chess-plugin/releases)

Project is currently inactive, there will likely be no more updates on this repository. I am remaking the plugin with new stuff.
## Authors & Credits

* **[wonton_stew](https://github.com/git-duck)** — *Original Creator*
 
* **[MrFrenchGuy23](https://github.com/MrFrenchGuy23)** — *Gameplay & AI fixes, built automation*

sorry frenchie


## Core Features

* **Split-Screen GUI Board**: Renders board rows 0–3 inside a top virtual chest and maps rows 4–7 directly onto the player's active inventory screen.
* **Automated Data Protection**: Captures full snapshots of player items, armor, and off-hand objects upon starting, rolling back original configurations seamlessly when matches conclude or players leave.
* **Integrated Rules Engine**: Includes check, checkmate, stalemate verification, castling parameters, en passant logic, and auto-promotion directly to Queens.
* **Blitz Match Timers**: Integrates built-in 5-minute blitz clocks that update continuously in real-time right inside the command column.

---

## Commands & Permissions

Players require the `chess.use` permission node to access all primary game commands.

| Command | Description | Usage |
| --- | --- | --- |
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
* Java 21 or higher
* Spigot/Paper API (Targeting 1.20+)
* Maven / Gradle

### Setup Instructions
1. Clone your project code directly into your development workspace:
   ```bash
   git clone https://github.com/git-duck/wonton-s-chess-plugin.git
   ```
2. Build the output file using your preferred build automation tool:
   ```bash
   mvn clean package
   ```
3. Drop the compiled `chessplugin-1.0.0.jar` target directly into your test environment's `plugins/` directory.
