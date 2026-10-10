# Contributing to crforge

Thanks for your interest in contributing! This guide covers the basics.

## Dev Setup

- **Java 17** is required
- Build: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew build`
- Run tests: `./gradlew test`
- Run the debug visualizer: `./gradlew :desktop:run`

## Code Style

We use Google Java Format, enforced by [Spotless](https://github.com/diffplug/spotless).

Before committing, run:

```bash
./gradlew spotlessApply
```

CI will reject PRs that don't pass `spotlessCheck`.

`spotlessApply` also gives a new source file (Java or a Gradle Kotlin script) the header every source file starts with: the repository URL, the license and the request to ports (see [Porting or reimplementing crforge](README.md#porting-or-reimplementing-crforge)).

## Testing

- Tests use JUnit 5 + AssertJ.
- Run: `./gradlew test`
- For bug fixes, follow TDD: write a failing test first, then fix the bug and confirm the test passes.
- Tests should exercise the real code path, not just call the fix function in isolation.

## Pull Requests

1. Fork the repo and create a feature branch from `main`.
2. Make your changes, including tests where applicable.
3. Run `./gradlew spotlessApply` and `./gradlew build` to verify everything passes.
4. Open a PR -- the template will guide you through the description.

## Release Notes

The release notes list behaviour corrections per mechanic, so anyone who ported a mechanic can see when its rules changed.

## Project Structure

See [docs/architecture.md](docs/architecture.md) for the module layout, the package layout and the index of the docs.

## Questions?

Open a [discussion](../../discussions) or file an issue. We're happy to help.
