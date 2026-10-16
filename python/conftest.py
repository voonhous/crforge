"""The test options: the env module's installed distribution (required) and the game tables.

    uv run pytest --install=../env/build/install/env --tables=<tables folder of a data version>

Without ``--tables`` the Ladder tests are skipped, and the run says so; the synthetic tests need
no tables. ``--install`` has no default: without it the run stops, naming it.
"""

from __future__ import annotations

from pathlib import Path

import pytest


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--install",
        default=None,
        help="the env module's installed distribution, made by ./gradlew :env:installDist "
        "(env/build/install/env)",
    )
    parser.addoption(
        "--tables",
        default=None,
        help="the game tables folder of a data version, as the build makes it; without it the "
        "Ladder tests are skipped",
    )


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "ladder: needs the game tables (--tables)")
    if config.getoption("--install") is None and not config.getoption("help"):
        raise pytest.UsageError(
            "--install is required: the env module's installed distribution, made by "
            "./gradlew :env:installDist (env/build/install/env)"
        )


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    if config.getoption("--tables") is not None:
        return
    skip = pytest.mark.skip(reason="no --tables given: the Ladder tests need the game tables")
    for item in items:
        if "ladder" in item.keywords:
            item.add_marker(skip)


def pytest_report_header(config: pytest.Config) -> list[str]:
    tables = config.getoption("--tables")
    return [
        f"crforge env install: {config.getoption('--install')}",
        f"game tables: {tables}" if tables else "game tables: none given, Ladder tests skipped",
    ]


@pytest.fixture(scope="session")
def install(request: pytest.FixtureRequest) -> Path:
    return Path(request.config.getoption("--install"))


@pytest.fixture(scope="session")
def tables(request: pytest.FixtureRequest) -> Path:
    folder = Path(request.config.getoption("--tables"))
    if not folder.is_dir():
        raise pytest.UsageError(f"--tables: no folder at {folder}")
    return folder
