# Contributing to ChessPlugin

Thanks for helping out! Keep it simple:

## Reporting bugs

Open an issue using the **Bug Report** template and include:
- Plugin version and server software (e.g. Paper 1.21)
- Steps to reproduce
- Any console errors

## Submitting changes

1. Fork the repository and create a branch: `git checkout -b your-feature`
2. Make your change and verify it builds: `mvn clean package`
3. Open a pull request against `main` using the PR template.

## Guidelines

- Keep the code style consistent with the surrounding code (see `.editorconfig`).
- Only commit what the PR is about; no unrelated changes.
- Never commit secrets or credentials.
- If a change affects players, add a line to `CHANGELOG.md`.

## Getting help

Ask questions by opening an issue. For security issues, see `SECURITY.md`.
