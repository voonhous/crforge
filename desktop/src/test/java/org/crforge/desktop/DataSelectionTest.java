package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the visualizer picks its game tables: the data root, the lock's version, the versions in the
 * root, the root's checked-out commit, and the order the settings are taken in.
 */
class DataSelectionTest {

  private static final String LOCK_COMMIT = "0f84a50d38f0c91f4a149b5c94620474cd5c233f";
  private static final String OTHER_COMMIT = "e61b362ab94a1b059289969907551222a92da494";

  @TempDir Path workspace;

  /** A project folder holding a lock, beside a data root holding two version folders. */
  private Path project() throws IOException {
    Path project = Files.createDirectories(workspace.resolve("crforge"));
    Files.writeString(
        project.resolve(DataSelection.LOCK_FILE),
        "# The commit of the game data repository\n# and the version.\ncommit="
            + LOCK_COMMIT
            + "\nversion=14.593.1\n");
    Path root = Files.createDirectories(workspace.resolve(DataSelection.SIBLING_FOLDER));
    tables(root, GameVersions.DATA_14_593_1);
    tables(root, GameVersions.DATA_16_402_18);
    return project;
  }

  private static void tables(Path root, String version) throws IOException {
    Path folder = Files.createDirectories(root.resolve(version));
    Files.writeString(folder.resolve("globals.json"), "{\"version\": \"" + version + "\"}\n");
  }

  private static DataSelection.Settings settings(
      String argument, String property, String gameTables, Path project) {
    return new DataSelection.Settings(argument, property, null, null, gameTables, null, project);
  }

  @Test
  @DisplayName("the data root property wins over the variable, which wins over the sibling folder")
  void rootSettingsInOrder() throws IOException {
    Path project = project();

    assertThat(DataSelection.resolveRoot("/data/a", "/data/b", project))
        .contains(new DataSelection.DataRoot(Path.of("/data/a"), DataSelection.DATA_ROOT_PROPERTY));
    assertThat(DataSelection.resolveRoot(" ", "/data/b", project))
        .contains(
            new DataSelection.DataRoot(Path.of("/data/b"), DataSelection.DATA_ROOT_ENVIRONMENT));
    assertThat(DataSelection.resolveRoot(null, "", project))
        .contains(
            new DataSelection.DataRoot(
                workspace.resolve(DataSelection.SIBLING_FOLDER), DataSelection.SIBLING_SOURCE));
  }

  @Test
  @DisplayName("without a setting the sibling folder is the root only when it exists")
  void noSiblingNoRoot() throws IOException {
    Path project = Files.createDirectories(workspace.resolve("lonely"));

    assertThat(DataSelection.resolveRoot(null, null, project)).isEmpty();
    assertThat(DataSelection.resolveRoot(null, null, null)).isEmpty();
  }

  @Test
  @DisplayName("the lock's commit and version are read past its comments")
  void readsTheLock() throws IOException {
    Path project = project();

    assertThat(DataSelection.readLock(project))
        .contains(new DataSelection.Lock(LOCK_COMMIT, GameVersions.DATA_14_593_1));
    assertThat(DataSelection.readLock(workspace)).isEmpty();
    assertThat(DataSelection.readLock(null)).isEmpty();
  }

  @Test
  @DisplayName("the versions are the root's folders that hold tables, in version order")
  void listsTheVersions() throws IOException {
    Path root = Files.createDirectories(workspace.resolve("root"));
    tables(root, GameVersions.DATA_16_402_18);
    tables(root, "9.1.0");
    tables(root, GameVersions.DATA_14_593_1);
    tables(root.resolve("references"), GameVersions.DATA_14_593_1);
    Files.createDirectories(root.resolve("references").resolve("x"));
    Files.createDirectories(root.resolve("empty"));
    Files.createDirectories(root.resolve(".git"));
    Files.writeString(root.resolve(".git").resolve("x.json"), "{}");
    Files.writeString(root.resolve("README.md"), "readme");

    assertThat(DataSelection.versions(root))
        .containsExactly("9.1.0", GameVersions.DATA_14_593_1, GameVersions.DATA_16_402_18);
    assertThat(DataSelection.versions(workspace.resolve("missing"))).isEmpty();
  }

  @Test
  @DisplayName(
      "the root's commit is read from a branch ref, packed refs, a detached head or a link")
  void readsTheHeadCommit() throws IOException {
    Path loose = Files.createDirectories(workspace.resolve("loose"));
    Path looseGit = Files.createDirectories(loose.resolve(".git/refs/heads"));
    Files.writeString(loose.resolve(".git/HEAD"), "ref: refs/heads/main\n");
    Files.writeString(looseGit.resolve("main"), OTHER_COMMIT + "\n");
    assertThat(DataSelection.headCommit(loose)).contains(OTHER_COMMIT);

    Path packed = Files.createDirectories(workspace.resolve("packed/.git"));
    Files.writeString(packed.resolve("HEAD"), "ref: refs/heads/main\n");
    Files.writeString(
        packed.resolve("packed-refs"),
        "# pack-refs with: peeled fully-peeled sorted\n"
            + OTHER_COMMIT
            + " refs/remotes/origin/main\n"
            + LOCK_COMMIT
            + " refs/heads/main\n");
    assertThat(DataSelection.headCommit(packed.getParent())).contains(LOCK_COMMIT);

    Path detached = Files.createDirectories(workspace.resolve("detached/.git"));
    Files.writeString(detached.resolve("HEAD"), LOCK_COMMIT + "\n");
    assertThat(DataSelection.headCommit(detached.getParent())).contains(LOCK_COMMIT);

    // A linked worktree: .git is a file naming its git folder, whose commondir holds the refs.
    Path linked = Files.createDirectories(workspace.resolve("linked"));
    Path worktreeGit = Files.createDirectories(loose.resolve(".git/worktrees/linked"));
    Files.writeString(linked.resolve(".git"), "gitdir: " + worktreeGit + "\n");
    Files.writeString(worktreeGit.resolve("HEAD"), "ref: refs/heads/main\n");
    Files.writeString(worktreeGit.resolve("commondir"), "../..\n");
    assertThat(DataSelection.headCommit(linked)).contains(OTHER_COMMIT);

    assertThat(DataSelection.headCommit(workspace)).isEmpty();
  }

  @Test
  @DisplayName("an explicit data version wins: the argument, then the property, in the data root")
  void anExplicitVersionWins() throws IOException {
    Path project = project();
    Path root = workspace.resolve(DataSelection.SIBLING_FOLDER);

    DataSelection.Choice byArgument =
        DataSelection.choose(
            settings(
                GameVersions.DATA_16_402_18, GameVersions.DATA_14_593_1, "/tables/x", project));
    assertThat(byArgument.problem()).isNull();
    assertThat(byArgument.tables().folder()).isEqualTo(root.resolve(GameVersions.DATA_16_402_18));
    assertThat(byArgument.tables().source()).isEqualTo("--data-version 16.402.18 in the data root");
    assertThat(byArgument.root().folder()).isEqualTo(root);

    DataSelection.Choice byProperty =
        DataSelection.choose(settings(null, GameVersions.DATA_16_402_18, "/tables/x", project));
    assertThat(byProperty.tables().folder()).isEqualTo(root.resolve(GameVersions.DATA_16_402_18));
    assertThat(byProperty.tables().source())
        .isEqualTo("crforge.dataVersion=16.402.18 in the data root");
  }

  @Test
  @DisplayName("without an explicit version a configured tables folder is used, as before")
  void theTablesFolderNext() throws IOException {
    Path project = project();

    DataSelection.Choice choice = DataSelection.choose(settings(null, " ", "/tables/x", project));

    assertThat(choice.problem()).isNull();
    assertThat(choice.tables().folder()).isEqualTo(Path.of("/tables/x"));
    assertThat(choice.tables().source()).isEqualTo(GameTables.PROPERTY);
    // The root is still known, so the screen can switch between its versions.
    assertThat(choice.root().folder()).isEqualTo(workspace.resolve(DataSelection.SIBLING_FOLDER));

    DataSelection.Choice byVariable =
        DataSelection.choose(
            new DataSelection.Settings(null, null, null, null, null, "/tables/y", project));
    assertThat(byVariable.tables().folder()).isEqualTo(Path.of("/tables/y"));
    assertThat(byVariable.tables().source()).isEqualTo(GameTables.ENVIRONMENT);
  }

  @Test
  @DisplayName("with nothing else set the lock's version in the data root is used")
  void theLockVersionLast() throws IOException {
    Path project = project();

    DataSelection.Choice choice = DataSelection.choose(settings(null, null, null, project));

    assertThat(choice.problem()).isNull();
    assertThat(choice.tables().folder())
        .isEqualTo(
            workspace.resolve(DataSelection.SIBLING_FOLDER).resolve(GameVersions.DATA_14_593_1));
    assertThat(choice.tables().source())
        .isEqualTo("version=14.593.1 of crforge-data.lock in the data root");
    assertThat(choice.lock())
        .isEqualTo(new DataSelection.Lock(LOCK_COMMIT, GameVersions.DATA_14_593_1));
  }

  @Test
  @DisplayName("an explicit version with no data root is refused, naming the data root settings")
  void anExplicitVersionNeedsARoot() throws IOException {
    Path project = Files.createDirectories(workspace.resolve("lonely"));

    DataSelection.Choice choice =
        DataSelection.choose(settings(GameVersions.DATA_16_402_18, null, null, project));

    assertThat(choice.tables()).isNull();
    assertThat(choice.problem())
        .contains(GameVersions.DATA_16_402_18)
        .contains(DataSelection.DATA_ROOT_PROPERTY)
        .contains(DataSelection.DATA_ROOT_ENVIRONMENT);
  }

  @Test
  @DisplayName("with no root and no tables folder the message names all four settings")
  void nothingToChoose() throws IOException {
    Path project = Files.createDirectories(workspace.resolve("lonely"));

    for (DataSelection.Choice choice :
        List.of(
            DataSelection.choose(settings(null, null, null, project)),
            DataSelection.choose(settings(null, null, null, null)))) {
      assertThat(choice.tables()).isNull();
      assertThat(choice.problem())
          .contains(DataSelection.DATA_ROOT_PROPERTY)
          .contains(DataSelection.DATA_ROOT_ENVIRONMENT)
          .contains(GameTables.PROPERTY)
          .contains(GameTables.ENVIRONMENT);
    }
  }

  @Test
  @DisplayName("the argument is read in both spellings, and is absent without a value")
  void readsTheArgument() {
    assertThat(
            DataSelection.versionArgument(
                new String[] {"--data-version", GameVersions.DATA_16_402_18}))
        .isEqualTo(GameVersions.DATA_16_402_18);
    assertThat(DataSelection.versionArgument(new String[] {"--data-version=16.402.18"}))
        .isEqualTo(GameVersions.DATA_16_402_18);
    assertThat(DataSelection.versionArgument(new String[] {"--data-version"})).isNull();
    assertThat(DataSelection.versionArgument(new String[] {"--replay", "battle.json"})).isNull();
  }

  @Test
  @DisplayName("the project folder is the property's, else the nearest folder up holding the lock")
  void findsTheProjectFolder() throws IOException {
    Path project = project();
    Path inside = Files.createDirectories(project.resolve("desktop/build"));

    assertThat(DataSelection.findProjectDir("/elsewhere", inside)).isEqualTo(Path.of("/elsewhere"));
    assertThat(DataSelection.findProjectDir(null, inside)).isEqualTo(project);
    assertThat(DataSelection.findProjectDir("", workspace)).isNull();
  }

  @Test
  @DisplayName("the root lines name the root, its commit against the lock's, and its versions")
  void describesTheRoot() throws IOException {
    Path project = project();
    Path root = workspace.resolve(DataSelection.SIBLING_FOLDER);
    Path git = Files.createDirectories(root.resolve(".git"));
    Files.writeString(git.resolve("HEAD"), OTHER_COMMIT + "\n");

    List<String> differs =
        DataSelection.describeRoot(DataSelection.choose(settings(null, null, null, project)));
    assertThat(differs)
        .containsExactly(
            "data root: "
                + root.toAbsolutePath().normalize()
                + " (from "
                + DataSelection.SIBLING_SOURCE
                + ")",
            "data root commit: "
                + OTHER_COMMIT
                + " (differs from the lock's "
                + LOCK_COMMIT
                + "; informational only)",
            "data versions: 14.593.1, 16.402.18 (V switches)");

    Files.writeString(git.resolve("HEAD"), LOCK_COMMIT + "\n");
    assertThat(
            DataSelection.describeRoot(DataSelection.choose(settings(null, null, null, project))))
        .contains("data root commit: " + LOCK_COMMIT + " (the lock's commit)");

    Files.delete(git.resolve("HEAD"));
    assertThat(
            DataSelection.describeRoot(DataSelection.choose(settings(null, null, null, project))))
        .contains("data root commit: unknown (no git checkout read)");

    // A project with no data root beside it, and a tables folder named outright: no root lines.
    Path lonely = Files.createDirectories(workspace.resolve("elsewhere/lonely"));
    assertThat(
            DataSelection.describeRoot(
                DataSelection.choose(settings(null, null, "/tables/x", lonely))))
        .isEmpty();
  }
}
