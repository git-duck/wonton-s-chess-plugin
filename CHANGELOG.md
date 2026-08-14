# Changelog

All notable changes to ChessPlugin are documented here. This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- PR template, security policy, CODEOWNERS, and contributing guide
- `.editorconfig` and expanded `.gitignore`

### Changed
- `.gitattributes` normalizes line endings; `.gitattributes`/`.editorconfig` enforce LF
- Plugin metadata (description, authors, website) added and `api-version` bumped to 1.21
- Reproducible Maven builds via `project.build.outputTimestamp`
- CI workflow hardened with concurrency groups and read-only permissions
- Fixed malformed command table in the README

## [1.0.0] - 2026-08-13

First tagged release. Builds the current `main` as `chessplugin-1.0.0.jar`.

### Added
- Interactive 8x8 chess GUI played inside a virtual chest plus the player's inventory
- `/chess`, `/chessaccept`, `/chessdeny`, `/chessrating` for player-versus-player blitz games
- `/chessai` with difficulty levels (easy / casual / medium / hard / extreme)
- `/chessspectate` to watch ongoing games
- `/chesspuzzle` with a rotating daily mate-in-one/two/three puzzle
- `/chessmatch` matchmaking queues (bullet / blitz / rapid)
- `/chesstourney` tournament mode
- `/chessreplay` to review recent games
- `/chessleaderboard` and Elo ratings with time controls (1, 3, 5, 10 + increment)
- Pawn promotion picker, castling, en passant, check / checkmate / stalemate detection
- Blitz timers shown inside the command column
- Vault currency bets (Vault is optional, with a fallback BalanceService bridge)

### Fixed
- AI no longer stalls: search runs off the main thread, `aiThinking` is always reset, and a random legal move is used as a fallback so a game can never wait forever on the AI
- AI search budget is now shared fairly between root moves instead of the first move consuming the whole node limit
- AI games now award the correct side the win when the human disconnects
- Abandoned tournament matches now advance the player who stayed online
- Joining a matchmaking queue now leaves any other queue first
- Re-solving the same daily puzzle no longer double-counts the solve total
- Inventory is protected during games: board clicks are cancelled before the game-state check, and drop / pickup / creative-inventory actions are blocked while a board is open
- Source files restored to UTF-8 so the plugin builds cleanly and diffs are readable
