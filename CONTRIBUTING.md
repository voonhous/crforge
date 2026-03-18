# Contributing to crforge

Thanks for your interest in contributing! This guide covers the basics.

## Dev Setup

- **Java 17** is required
- Build: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew build`
- Run tests: `./gradlew :core:test :data:test`
- Run the debug visualizer: `./gradlew :desktop:run`

## Code Style

We use Google Java Format, enforced by [Spotless](https://github.com/diffplug/spotless).

Before committing, run:

```bash
./gradlew spotlessApply
```

CI will reject PRs that don't pass `spotlessCheck`.

## Testing

- Tests use JUnit 5 + AssertJ.
- Run: `./gradlew :core:test :data:test`
- For bug fixes, follow TDD: write a failing test first, then fix the bug and confirm the test passes.
- Tests should exercise the real code path, not just call the fix function in isolation.

## Python Bridge

The `gym-bridge` module exposes a Gymnasium-compatible environment for RL training.

To set up:

```bash
cd python
pip install -e ".[dev]"
```

Run bridge tests:

```bash
./gradlew :gym-bridge:test
```

## Pull Requests

1. Fork the repo and create a feature branch from `main`.
2. Make your changes, including tests where applicable.
3. Run `./gradlew spotlessApply` and `./gradlew build` to verify everything passes.
4. Open a PR -- the template will guide you through the description.

## Project Structure

See the [architecture docs](docs/) for module layout and design decisions.

## Questions?

Open a [discussion](../../discussions) or file an issue. We're happy to help.
