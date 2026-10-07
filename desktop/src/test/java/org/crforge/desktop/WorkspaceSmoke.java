package org.crforge.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.StatusSmokeFixture;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.battle.UnitStatus;
import org.crforge.desktop.replay.SmokeReplayFixture;
import org.crforge.desktop.screen.DebugGameScreen;
import org.crforge.desktop.screen.ReplayGameScreen;

public class WorkspaceSmoke extends CRForgeGame {
  private final BattleSession session;
  private final DataVersions versions;
  private int frames;
  private int tick;
  private BattleSession statusSession;
  private CharacterEntity statusClone;
  private List<UnitStatus> pausedStatuses;

  WorkspaceSmoke(DataVersions versions, BattleSession session) {
    super(versions, session);
    this.session = session;
    this.versions = versions;
  }

  public static void main(String[] args) {
    DataSelection.Choice choice = DataSelection.choose(DataSelection.Settings.ofProcess(args));
    GameTables tables = GameTables.load(choice.tables().folder());
    DataVersions versions = DesktopLauncher.dataVersions(choice, tables);
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setWindowedMode(1120, 1040);
    config.setTitle("CRForge UI smoke check");
    config.setForegroundFPS(30);
    config.setInitialVisible(false);
    if (!tables.version().equals(GameVersions.DATA_14_593_1))
      throw new IllegalArgumentException("UI smoke fixtures require tables 14.593.1");
    new Lwjgl3Application(new WorkspaceSmoke(versions, versions.ladder()), config);
  }

  @Override
  public void render() {
    try {
      super.render();
      frames++;
      if (frames == 2) {
        key(Input.Keys.SPACE);
        tick = session.tick();
      }
      if (frames == 5) {
        require(session.tick() == tick, "pause");
        capture("workspace");
        Actor arena = stage().getRoot().findActor("battle-arena");
        var bounds =
            org.crforge.desktop.render.WorkspaceViewport.fit(
                0, 0, (int) arena.getWidth(), (int) arena.getHeight());
        require(bounds.height() >= 830, "arena grows to fill the content height");
        require(
            stage().getRoot().findActor("replay-progress") == null,
            "no replay progress in live play");
        click("Step [.]", true);
        require(session.tick() == tick + 1, "step button advances once");
      }
      if (frames == 6) click("Tile grid", true);
      if (frames == 7) {
        require(find(stage().getRoot(), "Tile grid", true).isChecked(), "grid toggle is active");
        capture("grid");
        click("Tile grid", true);
        require(session.tick() == tick + 1, "step stays paused");
        click("Data details", true);
      }
      if (frames == 8) {
        require(
            !labels(stage().getRoot()).contains("Folder: "),
            "metadata hides local folder by default");
        click("Show folder", true);
        require(
            labels(stage().getRoot()).contains("Folder: "),
            "folder is available on explicit request");
        String previousClipboard = Gdx.app.getClipboard().getContents();
        click("Copy", true);
        String copied = Gdx.app.getClipboard().getContents();
        require(
            !copied.contains(versions.currentFolder().toAbsolutePath().toString()),
            "copy omits local folder even when revealed");
        Gdx.app.getClipboard().setContents(previousClipboard == null ? "" : previousClipboard);
        click("Hide folder", true);
      }
      if (frames == 10) {
        capture("data-details");
        click("Data details", true);
        key(Input.Keys.T);
      }
      if (frames == 13) {
        capture("sidebar-hidden");
        key(Input.Keys.T);
        Gdx.graphics.setWindowedMode(1000, 760);
      }
      if (frames == 18) {
        capture("minimum-size");
        click("Data details", true);
      }
      if (frames == 19) {
        capture("minimum-size-data");
        click("Data details", true);
        Gdx.graphics.setWindowedMode(1120, 1040);
      }
      if (frames == 20) {
        click("Inspect [I]", true);
        key(Input.Keys.NUM_5); // Opening Mega Knight costs more than the available elixir.
      }
      if (frames == 21) {
        require(
            labels(stage().getRoot()).contains("INSPECT: click"),
            "unavailable keyboard card preserves inspection");
        click("Mega", false);
      }
      if (frames == 22) {
        require(
            labels(stage().getRoot()).contains("INSPECT: click"),
            "Inspect selects rather than toggles mode");
        click("Deploy", true);
      }
      if (frames == 23) {
        require(labels(stage().getRoot()).contains("DEPLOY: select"), "Deploy button changes mode");
        click("Witch", false);
        click("Data details", true);
        require(session.getBattle().getPlays().isEmpty(), "toolbar click cannot deploy");
        click("Data details", true);
        clickArena(0.3f, 0.25f);
        require(session.isPending(0, 0), "arena click queues selected card");
      }
      if (frames == 26) {
        capture("queued-card");
        for (int i = 0; i < 25; i++) key(Input.Keys.PERIOD);
      }
      if (frames == 29) {
        require(!session.getBattle().getPlays().isEmpty(), "queued card executes");
        key(Input.Keys.I);
        var entity =
            BattleAdapter.frame(session).entities().stream()
                .filter(value -> value.kind() == org.crforge.desktop.battle.EntityView.Kind.TROOP)
                .findFirst()
                .orElseThrow();
        clickArena(entity.x() / 18000f, entity.y() / 32000f);
      }
      if (frames == 30) {
        capture("inspector");
        for (int i = 0; i < 10; i++)
          session.note("Event " + i + ": " + "wrapping detail ".repeat(24));
      }
      if (frames == 31) {
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane log =
            stage().getRoot().findActor("event-log");
        require(log.getMaxY() > 0, "event log overflows");
        log.setScrollY(0);
        log.updateVisualScroll();
        session.note("A new event while reading history");
      }
      if (frames == 32) {
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane log =
            stage().getRoot().findActor("event-log");
        require(log.getScrollY() == 0, "new events preserve scroll position");
        click("+ Latest", true);
        require(log.getScrollPercentY() == 1, "Latest resumes following events");
        require(labels(stage().getRoot()).contains("Witch #"), "inspection uses arena projection");
        session.halt("Smoke check: unsupported behavior");
        key(Input.Keys.T);
      }
      if (frames == 35) {
        String labels = labels(stage().getRoot());
        require(labels.contains("HALTED: Smoke check"), "halt reason persists");
        require(labels.contains("Data: " + versions.current().version()), "loaded data persists");
        capture("halted");
        var previous = getScreen();
        setScreen(new ReplayGameScreen(SmokeReplayFixture.create(false), versions));
        previous.dispose();
        key(Input.Keys.PERIOD);
      }
      if (frames == 38) {
        require(
            stage().getRoot().findActor("replay-progress") != null,
            "replay progress remains visible");
        require(find(stage().getRoot(), "Deploy", true) == null, "no Deploy mode in replay");
        capture("replay");
        click("Flip [F]", true);
      }
      if (frames == 41) {
        capture("replay-flipped");
        require(
            labels(stage().getRoot()).contains("SIDE 0 / BLUE"),
            "replay flip updates side identity");
        Gdx.graphics.setWindowedMode(1000, 760);
      }
      if (frames == 46) {
        capture("replay-minimum-size");
        for (int i = 0; i < 405; i++) key(Input.Keys.PERIOD);
      }
      if (frames == 49) {
        require(labels(stage().getRoot()).contains("FINISHED"), "replay end state");
        capture("replay-finished");
        var previous = getScreen();
        setScreen(new ReplayGameScreen(SmokeReplayFixture.create(true), versions));
        previous.dispose();
      }
      if (frames == 53) {
        require(labels(stage().getRoot()).contains("REFUSED"), "refusal state");
        capture("replay-refused");
        statusSession = versions.ladder();
        statusClone = StatusSmokeFixture.populate(statusSession);
        var previous = getScreen();
        setScreen(new DebugGameScreen(versions, statusSession));
        previous.dispose();
        key(Input.Keys.SPACE);
        key(Input.Keys.I);
        Gdx.graphics.setWindowedMode(1120, 1040);
      }
      if (frames == 56) {
        clickArena(statusClone.getView().getX() / 18000f, statusClone.getView().getY() / 32000f);
      }
      if (frames == 58) {
        require(
            labels(stage().getRoot()).contains("Frozen - 5.0s"), "inspector shows freeze duration");
        require(
            labels(stage().getRoot()).contains("Stunned - 3.0s"), "inspector shows stun duration");
        require(labels(stage().getRoot()).contains("From MiniPekka"), "inspector shows source");
        pausedStatuses = BattleAdapter.entity(statusClone).statuses();
        capture("statuses");
      }
      if (frames == 61) {
        require(
            pausedStatuses.equals(BattleAdapter.entity(statusClone).statuses()),
            "paused status timers remain unchanged");
        capture("statuses-paused");
        for (int i = 0; i < 20; i++) key(Input.Keys.PERIOD);
      }
      if (frames == 63) {
        require(
            labels(stage().getRoot()).contains("Frozen - 4.0s"),
            "status duration follows simulation steps");
        capture("statuses-stepped");
        click("Status effects", true);
      }
      if (frames == 65) {
        require(
            !find(stage().getRoot(), "Status effects", true).isChecked(),
            "status overlay toggles off");
        require(
            labels(stage().getRoot()).contains("Frozen - 4.0s"),
            "inspector survives overlay toggle");
        capture("statuses-hidden");
        click("Status effects", true);
        Gdx.graphics.setWindowedMode(1000, 760);
      }
      if (frames == 69) {
        capture("statuses-minimum-size");
        for (int i = 0; i < 81; i++) key(Input.Keys.PERIOD);
      }
      if (frames == 72) {
        var snapshot = BattleAdapter.entity(statusClone);
        require(!snapshot.hasStatus(UnitStatus.Kind.FROZEN), "freeze expires");
        require(!snapshot.hasStatus(UnitStatus.Kind.STUNNED), "stun expires");
        require(snapshot.hasStatus(UnitStatus.Kind.CLONE), "clone identity persists");
        capture("statuses-expired");
        var previous = getScreen();
        setScreen(new ReplayGameScreen(SmokeReplayFixture.statuses(), versions));
        previous.dispose();
        Gdx.graphics.setWindowedMode(1120, 1040);
        for (int i = 0; i < 262; i++) key(Input.Keys.PERIOD);
      }
      if (frames == 75) {
        clickArena(14500 / 18000f, (32000 - 25500) / 32000f);
      }
      if (frames == 77) {
        require(labels(stage().getRoot()).contains("Frozen -"), "replay reads real Freeze spell");
        require(labels(stage().getRoot()).contains("Stunned -"), "replay reads real Zap spell");
        capture("statuses-replay-flipped");
        click("Flip [F]", true);
      }
      if (frames == 80) {
        require(
            labels(stage().getRoot()).contains("Frozen -"),
            "selected statuses survive replay flip");
        capture("statuses-replay-standard");
        System.out.println(
            "UI_SMOKE_OK: workspace, replay, status badges/inspector/pause/step/toggle/expiry/flip");
        Gdx.app.exit();
      }
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  static Stage stage() {
    return (Stage) ((InputMultiplexer) Gdx.input.getInputProcessor()).getProcessors().get(0);
  }

  static int count(Group group, Class<?> type) {
    int total = 0;
    for (Actor actor : group.getChildren()) {
      if (type.isInstance(actor)) total++;
      if (actor instanceof Group child) total += count(child, type);
    }
    return total;
  }

  static String labels(Group group) {
    String result = "";
    for (Actor actor : group.getChildren()) {
      if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.Label label)
        result += label.getText() + "\n";
      if (actor instanceof Group child) result += labels(child);
    }
    return result;
  }

  static void clickArena(float x, float y) {
    Actor arena = stage().getRoot().findActor("battle-arena");
    Vector2 origin = arena.localToStageCoordinates(new Vector2());
    var bounds =
        org.crforge.desktop.render.WorkspaceViewport.fit(
            (int) origin.x, (int) origin.y, (int) arena.getWidth(), (int) arena.getHeight());
    int screenX = (int) (bounds.x() + bounds.width() * x);
    int screenY = Gdx.graphics.getHeight() - (int) (bounds.y() + bounds.height() * y);
    Gdx.input.getInputProcessor().touchDown(screenX, screenY, 0, Input.Buttons.LEFT);
    Gdx.input.getInputProcessor().touchUp(screenX, screenY, 0, Input.Buttons.LEFT);
  }

  static void key(int key) {
    Gdx.input.getInputProcessor().keyDown(key);
  }

  static void click(String text, boolean exact) {
    Stage stage = (Stage) ((InputMultiplexer) Gdx.input.getInputProcessor()).getProcessors().get(0);
    TextButton button = find(stage.getRoot(), text, exact);
    if (button == null) throw new AssertionError("Missing button: " + text);
    Vector2 pos =
        button.localToStageCoordinates(new Vector2(button.getWidth() / 2, button.getHeight() / 2));
    stage.stageToScreenCoordinates(pos);
    Gdx.input.getInputProcessor().touchDown((int) pos.x, (int) pos.y, 0, Input.Buttons.LEFT);
    Gdx.input.getInputProcessor().touchUp((int) pos.x, (int) pos.y, 0, Input.Buttons.LEFT);
  }

  static TextButton find(Group group, String text, boolean exact) {
    for (Actor actor : group.getChildren()) {
      if (actor instanceof TextButton button
          && (exact
              ? button.getText().toString().equals(text)
              : button.getText().toString().contains(text))) return button;
      if (actor instanceof Group child) {
        TextButton found = find(child, text, exact);
        if (found != null) return found;
      }
    }
    return null;
  }

  static void require(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }

  static void capture(String name) {
    Actor arena = stage().getRoot().findActor("battle-arena");
    var bounds =
        org.crforge.desktop.render.WorkspaceViewport.fit(
            0, 0, (int) arena.getWidth(), (int) arena.getHeight());
    Vector2 arenaOrigin = arena.localToStageCoordinates(new Vector2());
    Actor summary = stage().getRoot().findActor("session-summary");
    Actor notice = stage().getRoot().findActor("status-notice");
    float summaryY = summary.localToStageCoordinates(new Vector2()).y;
    float noticeTop = notice.localToStageCoordinates(new Vector2(0, notice.getHeight())).y;
    require(arenaOrigin.y + arena.getHeight() <= summaryY, "arena stays below summary: " + name);
    require(arenaOrigin.y >= noticeTop, "arena stays above notice: " + name);
    System.out.println("ARENA " + name + ": " + bounds.width() + "x" + bounds.height());
    TextButton witch = find(stage().getRoot(), "Witch", false);
    if (witch != null) {
      Vector2 pos = witch.localToStageCoordinates(new Vector2());
      System.out.println(
          "HAND " + name + ": " + pos + " size=" + witch.getWidth() + "," + witch.getHeight());
      require(
          pos.y >= 0 && pos.y + witch.getHeight() <= Gdx.graphics.getHeight(),
          "hand stays visible: " + name);
    }
    Pixmap image =
        Pixmap.createFromFrameBuffer(
            0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    PixmapIO.writePNG(
        Gdx.files.absolute(
            System.getProperty("crforge.uiSmokeOutput", "build/ui-smoke") + "/" + name + ".png"),
        image,
        -1,
        true);
    image.dispose();
  }
}
