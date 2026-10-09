package org.crforge.desktop.render;

import static org.crforge.desktop.render.WorkspaceTheme.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;

/** Scene2D controls surrounding a separately projected arena; never steps or mutates a battle. */
public final class BattleWorkspace implements Disposable {
  /** The most height the list of a crawl's replays takes; a longer list scrolls. */
  private static final float ARCHIVE_HEIGHT = 190;

  private final Stage stage = new Stage(new ScreenViewport());
  private final WorkspaceTheme theme = new WorkspaceTheme(stage);
  private final Skin skin = theme.skin;
  private final Table root = new Table();
  private final Table arena = new Table();
  private final Table handRail = new Table();
  private final Viewport arenaViewport;
  private final ViewState view;
  private final BattleRenderer renderer;
  private final boolean replay;
  private final Consumer<WorkspaceAction> command;
  private final BiConsumer<Integer, Integer> selectCard;
  private final Label data;
  private final Label summary;
  private final Label state;
  private final UnitInspector inspector;
  private final Label diagnostics;
  private final Label events;
  private final Label notice;
  private final Label refusal;
  private final ScrollPane refusalPane;
  private final ScrollPane eventPane;
  private final TextButton latestEvents;
  private final ScrollPane sidebar;
  private final Cell<ScrollPane> sidebarCell;
  private final TextButton pause;
  private final TextButton step;
  private final TextButton inspect;
  private final TextButton deploy;
  private final TextButton dataButton;
  private final Table dataPanel;
  private final Label dataDetails;
  private final HandPanel topHand;
  private final HandPanel bottomHand;
  private final List<Toggle> toggles = new ArrayList<>();
  private final ProgressBar progress;
  private final Label progressText;
  private final Table versionControls = new Table();
  private SelectBox<String> versionSelector;
  private WorkspaceViewport bounds = new WorkspaceViewport(0, 0, 1, 1);
  private BattleFrame frame;
  private int selectedId = -1;
  private boolean inspecting;
  private String lastEvents = "";
  private String details = "";
  private String loadedFolder = "";
  private boolean revealFolder;
  private final TextButton folderButton;
  private List<String> metadata = List.of();
  private final Table header = new Table();
  private final Table archivePanel = new Table();
  private final Label archiveTitle;
  private final Label archiveStatus;
  private final Label archiveColumns;
  private final com.badlogic.gdx.scenes.scene2d.ui.List<String> archiveList;
  private final ScrollPane archivePane;
  private IntConsumer archivePick = index -> {};
  private boolean archiveButton;

  private record Toggle(TextButton button, BooleanSupplier value) {}

  public BattleWorkspace(
      OrthographicCamera camera,
      BattleRenderer renderer,
      ViewState view,
      boolean replay,
      Consumer<WorkspaceAction> command,
      BiConsumer<Integer, Integer> selectCard) {
    this.renderer = renderer;
    this.view = view;
    this.replay = replay;
    this.command = command;
    this.selectCard = selectCard;
    inspecting = replay;
    arenaViewport = new ScreenViewport(camera);
    arenaViewport.setWorldSize(WorkspaceViewport.WORLD_WIDTH, WorkspaceViewport.WORLD_HEIGHT);
    camera.setToOrtho(false, WorkspaceViewport.WORLD_WIDTH, WorkspaceViewport.WORLD_HEIGHT);
    camera.position.set(
        WorkspaceViewport.WORLD_WIDTH / 2,
        RenderConstants.BOTTOM_UI_HEIGHT + WorkspaceViewport.WORLD_HEIGHT / 2,
        0);
    camera.update();

    root.setFillParent(true);
    root.pad(14);
    stage.addActor(root);
    Label brand = new Label("CRFORGE  /  " + (replay ? "REPLAY" : "BATTLE LAB"), skin, "title");
    brand.setColor(ACCENT);
    header.add(brand).left().expandX();
    data = label("");
    header.add(data).padRight(12);
    dataButton = button("Data details", this::toggleDataDetails);
    header.add(dataButton).height(32);
    root.add(header).growX().padBottom(10).row();

    Table toolbar = new Table();
    toolbar.defaults().height(34).padRight(4);
    pause = button("Pause [Space]", () -> command.accept(WorkspaceAction.PAUSE));
    step = button("Step [.]", () -> command.accept(WorkspaceAction.STEP));
    toolbar.add(pause).width(120);
    toolbar.add(step).width(86);
    toolbar.add(button("Restart [R]", () -> command.accept(WorkspaceAction.RESTART))).width(108);
    toolbar.add(button("-", () -> command.accept(WorkspaceAction.SLOWER))).width(28);
    state = label("");
    toolbar.add(state).minWidth(126);
    toolbar.add(button("+", () -> command.accept(WorkspaceAction.FASTER))).width(28);
    deploy =
        button(
            "Deploy",
            () -> {
              command.accept(WorkspaceAction.DEPLOY);
            });
    inspect =
        button(
            replay ? "Inspect" : "Inspect [I]",
            () -> {
              command.accept(WorkspaceAction.INSPECT);
            });
    if (!replay) toolbar.add(deploy).width(76);
    toolbar.add(inspect).width(104);
    if (replay) {
      toolbar.add(button("Flip [F]", () -> command.accept(WorkspaceAction.FLIP))).width(82);
    }
    toolbar.add().expandX();
    toolbar.add(button("Sidebar [T]", () -> command.accept(WorkspaceAction.SIDEBAR))).width(108);
    root.add(toolbar).growX().padBottom(8).row();

    dataPanel = new Table();
    dataPanel.background(skin.newDrawable("white", PANEL));
    dataPanel.pad(10);
    dataDetails = wrapped("");
    dataPanel.add(dataDetails).growX();
    Table dataActions = new Table();
    dataActions
        .add(button("Copy", () -> Gdx.app.getClipboard().setContents(details)))
        .growX()
        .height(28)
        .padBottom(4)
        .row();
    folderButton =
        button(
            "Show folder",
            () -> {
              revealFolder = !revealFolder;
              refreshDataDetails();
            });
    dataActions.add(folderButton).growX().height(28);
    dataPanel.add(dataActions).width(100).padLeft(10);
    dataPanel.setVisible(false);
    root.add(dataPanel).growX().height(0).row();

    // A crawl's replays, listed once showArchive gives them; a click opens one.
    archivePanel.background(skin.newDrawable("white", PANEL));
    archivePanel.pad(10);
    archivePanel.defaults().growX().left();
    archiveTitle = new Label("", skin, "heading");
    archiveTitle.setColor(ACCENT);
    archivePanel.add(archiveTitle).padBottom(4).row();
    archiveStatus = wrapped("");
    archiveStatus.setColor(MUTED);
    archivePanel.add(archiveStatus).padBottom(6).row();
    archiveColumns = new Label("", skin, "mono");
    archivePanel.add(archiveColumns).padBottom(2).row();
    archiveList = new com.badlogic.gdx.scenes.scene2d.ui.List<>(skin, "mono");
    archiveList.getSelection().setProgrammaticChangeEvents(false);
    archiveList.addListener(
        new ChangeListener() {
          @Override
          public void changed(ChangeEvent event, Actor actor) {
            int index = archiveList.getSelectedIndex();
            stage.setKeyboardFocus(null);
            if (index >= 0) archivePick.accept(index);
          }
        });
    archivePane = scroll(archiveList);
    archivePanel.add(archivePane).height(ARCHIVE_HEIGHT);
    archivePanel.setVisible(false);
    root.add(archivePanel).growX().height(0).padTop(4).row();

    summary = label("");
    summary.setName("session-summary");
    summary.setColor(MUTED);
    root.add(summary).left().padBottom(8).row();

    Table content = new Table();
    topHand = new HandPanel(theme, view, replay, selectCard);
    bottomHand = new HandPanel(theme, view, replay, selectCard);
    handRail.add(topHand.table).growX().padBottom(10).row();
    Table logHeader = new Table();
    Label logTitle = label("RECENT EVENTS");
    logTitle.setColor(MUTED);
    logHeader.add(logTitle).expandX().left();
    latestEvents =
        button(
            "Latest",
            () -> {
              followEvents();
            });
    logHeader.add(latestEvents).width(64).height(26).padRight(4);
    logHeader.add(button("Copy", () -> Gdx.app.getClipboard().setContents(lastEvents))).height(26);
    handRail.add(logHeader).growX().padBottom(6).row();
    events = wrapped("No events yet.");
    events.setAlignment(Align.topLeft);
    eventPane = scroll(events);
    eventPane.setName("event-log");
    handRail.add(eventPane).grow().minHeight(0).padBottom(10).row();
    handRail.add(bottomHand.table).growX().row();
    content.add(handRail).width(248).growY().padRight(12);
    arena.setTouchable(Touchable.disabled);
    arena.setName("battle-arena");
    refusal = wrapped("");
    refusal.setAlignment(Align.topLeft);
    refusalPane = scroll(refusal);
    refusalPane.setVisible(false);
    Stack arenaStack = new Stack(arena, refusalPane);
    content.add(arenaStack).grow().minSize(0);

    Table tools = new Table();
    tools.top().left().pad(14);
    tools.defaults().growX().left();
    tools.background(skin.newDrawable("white", PANEL));
    heading(tools, "UNIT INSPECTOR");
    inspector = new UnitInspector(skin);
    tools.add(inspector).padBottom(16).row();
    heading(tools, "OVERLAYS");
    Table presets = new Table();
    for (OverlayPreset preset : OverlayPreset.values()) {
      presets
          .add(button(preset.label(), () -> renderer.applyPreset(preset)))
          .growX()
          .height(32)
          .padRight(4);
    }
    tools.add(presets).padBottom(8).row();
    toggle(tools, "Tile grid", renderer::isDrawGrid, renderer::toggleDrawGrid);
    toggle(tools, "Status effects", renderer::isDrawStatuses, renderer::toggleDrawStatuses);
    toggle(tools, "All unit names", renderer::isDrawLabels, renderer::toggleDrawLabels);
    toggle(tools, "Target lines", renderer::isDrawTargets, renderer::toggleDrawTargets);
    toggle(
        tools, "Ranges [O]", renderer::isDrawRanges, () -> command.accept(WorkspaceAction.RANGES));
    toggle(
        tools,
        "Damage [D]",
        renderer::isDrawDamageNumbers,
        () -> command.accept(WorkspaceAction.DAMAGE));
    toggle(
        tools,
        "Area hits [A]",
        renderer::isDrawAoeDamage,
        () -> command.accept(WorkspaceAction.AREA_HITS));
    toggle(
        tools,
        "HP values [H]",
        renderer::isDrawHpNumbers,
        () -> command.accept(WorkspaceAction.HP));
    toggle(
        tools,
        "Headings [P]",
        renderer::isDrawPaths,
        () -> command.accept(WorkspaceAction.HEADINGS));
    toggle(
        tools,
        "Cell costs [G]",
        renderer::isDrawCellCosts,
        () -> command.accept(WorkspaceAction.CELL_COSTS));
    toggle(
        tools, "Routes [N]", renderer::isDrawRoutes, () -> command.accept(WorkspaceAction.ROUTES));
    tools.add().height(12).row();
    heading(tools, "SESSION");
    diagnostics = wrapped("");
    tools.add(diagnostics).padBottom(12).row();
    if (!replay) {
      tools.add(versionControls).row();
      Label resetNote = wrapped("Changing version starts a new battle.");
      resetNote.setColor(MUTED);
      tools.add(resetNote).padTop(6).row();
    }
    sidebar = scroll(tools);
    sidebarCell = content.add(sidebar).width(294).growY().padLeft(12);
    root.add(content).grow().minSize(0).row();

    progress = new ProgressBar(0, 1, 0.001f, false, skin);
    progress.setName("replay-progress");
    progressText = label("");
    if (replay) {
      Table timeline = new Table();
      timeline.add(progressText).padRight(12);
      timeline.add(progress).growX().height(8);
      root.add(timeline).growX().padTop(8).row();
    }
    notice = wrapped("");
    notice.setName("status-notice");
    notice.setColor(ACCENT);
    root.add(scroll(notice)).growX().minHeight(18).prefHeight(20).maxHeight(54).padTop(8).row();
  }

  private void followEvents() {
    eventPane.setScrollPercentY(1);
    latestEvents.setText("Latest");
  }

  /**
   * Lists a crawl's replays above the arena, and opens the list. A click on a row hands its index
   * to {@code pick}; the row stays marked only once {@link #selectArchiveRow} marks it.
   *
   * @param title the list's title
   * @param columns the column titles, aligned with the rows
   * @param rows one row a replay
   * @param selected the row of the replay open, or -1
   * @param pick what opens a row's replay
   */
  public void showArchive(
      String title, String columns, List<String> rows, int selected, IntConsumer pick) {
    archivePick = pick;
    archiveTitle.setText(title);
    archiveColumns.setText(columns);
    archiveList.setItems(rows.toArray(String[]::new));
    // A short list takes only its rows' height, a long one scrolls.
    archivePanel
        .getCell(archivePane)
        .height(Math.min(ARCHIVE_HEIGHT, archiveList.getPrefHeight() + 2));
    if (!archiveButton) {
      archiveButton = true;
      header.add(button("Replays [L]", this::toggleArchive)).height(32).padLeft(4);
    }
    archivePanel.setVisible(true);
    selectArchiveRow(selected);
    root.invalidateHierarchy();
  }

  /** Marks the row of the replay open, scrolled into view, without opening it again. */
  public void selectArchiveRow(int index) {
    archiveList.setSelectedIndex(index);
    if (index < 0) return;
    archivePane.layout();
    float rowHeight = archiveList.getItemHeight();
    archivePane.scrollTo(
        0, archiveList.getHeight() - (index + 1) * rowHeight, archiveList.getWidth(), rowHeight);
  }

  /** The line under the list's title: what is open, or why a pick did not open. */
  public void archiveStatus(String text) {
    archiveStatus.setText(text);
  }

  /** Opens or closes the list of a crawl's replays, when there is one. */
  public void toggleArchive() {
    if (archiveList.getItems().isEmpty()) return;
    archivePanel.setVisible(!archivePanel.isVisible());
    root.invalidateHierarchy();
  }

  private void toggleDataDetails() {
    dataPanel.setVisible(!dataPanel.isVisible());
    root.invalidateHierarchy();
  }

  private Label label(String text) {
    return theme.label(text);
  }

  private Label wrapped(String text) {
    return theme.wrapped(text);
  }

  private TextButton button(String text, Runnable action) {
    return theme.button(text, action);
  }

  private ScrollPane scroll(Actor actor) {
    return theme.scroll(actor);
  }

  private void heading(Table table, String text) {
    Label title = new Label(text, skin, "heading");
    title.setColor(ACCENT);
    table.add(title).padBottom(10).row();
  }

  private void toggle(Table table, String text, BooleanSupplier value, Runnable change) {
    TextButton button = button(text, change);
    button.getLabel().setAlignment(Align.left);
    button.padLeft(10);
    table.add(button).height(28).padBottom(4).row();
    toggles.add(new Toggle(button, value));
  }

  public Stage input() {
    return stage;
  }

  public void configureVersions(List<String> versions, String current, Consumer<String> load) {
    versionControls.clearChildren();
    if (versions.isEmpty()) {
      Label hint = wrapped("No other data version built. See Data details for the loaded folder.");
      hint.setColor(MUTED);
      versionControls.add(hint).growX();
      return;
    }
    versionSelector = new SelectBox<>(skin);
    versionSelector.setItems(versions.toArray(String[]::new));
    versionSelector.setSelected(current);
    versionControls.add(label("Data version")).colspan(2).left().padBottom(6).row();
    versionControls.add(versionSelector).growX().height(32).padRight(6);
    versionControls
        .add(button("Load + restart", () -> load.accept(versionSelector.getSelected())))
        .height(32);
  }

  public void versionChanged(String version) {
    if (versionSelector != null) versionSelector.setSelected(version);
  }

  public void resize(int width, int height) {
    stage.getViewport().update(width, height, true);
    root.invalidateHierarchy();
    layout();
  }

  private void layout() {
    boolean compactHands = stage.getHeight() < 900;
    topHand.compact(compactHands);
    bottomHand.compact(compactHands);
    boolean hasHands = frame != null && !frame.sides().isEmpty();
    topHand.table.setVisible(hasHands);
    bottomHand.table.setVisible(hasHands);
    handRail
        .getCell(topHand.table)
        .height(hasHands ? topHand.table.getPrefHeight() : 0)
        .padBottom(hasHands ? 10 : 0);
    handRail.getCell(bottomHand.table).height(hasHands ? bottomHand.table.getPrefHeight() : 0);
    sidebar.setVisible(view.isAnnotations());
    sidebarCell.width(view.isAnnotations() ? 294 : 0).padLeft(view.isAnnotations() ? 12 : 0);
    root.getCell(dataPanel).height(dataPanel.isVisible() ? dataPanel.getPrefHeight() : 0);
    root.getCell(archivePanel)
        .height(archivePanel.isVisible() ? archivePanel.getPrefHeight() : 0)
        .padTop(archivePanel.isVisible() ? 4 : 0);
    root.validate();
    Vector2 origin = arena.localToStageCoordinates(new Vector2());
    bounds =
        WorkspaceViewport.fit(
            Math.round(origin.x),
            Math.round(origin.y),
            Math.max(1, Math.round(arena.getWidth())),
            Math.max(1, Math.round(arena.getHeight())));
    arenaViewport.setScreenBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
  }

  public boolean onArena(int screenX, int screenY) {
    return bounds.contains(screenX, screenY, Gdx.graphics.getHeight());
  }

  public void unproject(Vector3 position) {
    arenaViewport.unproject(position);
  }

  public void beginArena() {
    layout();
    arenaViewport.apply(false);
  }

  public void draw(float delta) {
    stage.getViewport().apply();
    stage.act(Math.min(delta, 0.1f));
    stage.draw();
  }

  public static Color background() {
    return BACKGROUND;
  }

  public void setInspecting(boolean value) {
    inspecting = replay || value;
    if (!inspecting) {
      selectedId = -1;
      renderer.setInspectedEntity(-1);
    }
  }

  public boolean isInspecting() {
    return inspecting;
  }

  public void inspectAt(float x, float y) {
    if (frame == null) return;
    selectedId = -1;
    float best = Float.MAX_VALUE;
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter()) continue;
      float dx = view.getOrientation().px(entity.x()) - x;
      float dy = view.getOrientation().py(entity.y()) - y;
      float distance = dx * dx + dy * dy;
      float radius = Math.max(10, RenderConstants.unitsToPixels(entity.radius()));
      if (distance <= radius * radius && distance < best) {
        best = distance;
        selectedId = entity.id();
      }
    }
    renderer.setInspectedEntity(selectedId);
  }

  public void reset() {
    selectedId = -1;
    renderer.setInspectedEntity(-1);
    frame = null;
  }

  /** Metadata is always the tables actually loaded, independent of hidden diagnostics. */
  public void setData(String version, String source, String folder, String sha, String target) {
    setData(version, source, folder, sha, target, null);
  }

  /**
   * Metadata of the tables actually loaded, with a replay's line: whether its capture block named
   * the data or the data version is assumed.
   *
   * @param replay the replay's line, or null outside a replay
   */
  public void setData(
      String version, String source, String folder, String sha, String target, String replay) {
    List<String> updatedMetadata = Arrays.asList(version, source, folder, sha, target, replay);
    if (updatedMetadata.equals(metadata)) return;
    metadata = updatedMetadata;
    loadedFolder = folder;
    data.setText("Data: " + version);
    String updated =
        "Loaded: "
            + version
            + "   |   Development target: "
            + target
            + "\nSource: "
            + source
            + "\nSHA: "
            + sha
            + (replay == null ? "" : "\n" + replay);
    if (!updated.equals(details)) {
      details = updated;
      refreshDataDetails();
    }
  }

  private void refreshDataDetails() {
    dataDetails.setText(details + (revealFolder ? "\nFolder: " + loadedFolder : ""));
    folderButton.setText(revealFolder ? "Hide folder" : "Show folder");
    root.invalidateHierarchy();
  }

  public void update(
      BattleFrame frame,
      boolean paused,
      float speed,
      boolean finished,
      String stopReason,
      List<String> status,
      int selectedSide,
      int selectedSlot,
      int endTick) {
    this.frame = frame;
    if (refusalPane.isVisible()) {
      // A replay opened after a refused one: what showRefused turned off comes back.
      refusalPane.setVisible(false);
      for (Toggle toggle : toggles) toggle.button().setDisabled(false);
      if (replay) progress.getParent().setVisible(true);
    }
    topHand.update(
        frame, view.getOrientation().sidesBottomFirst().get(1), selectedSide, selectedSlot);
    bottomHand.update(frame, view.getOrientation().bottomSide(), selectedSide, selectedSlot);
    String phase =
        frame.halted() != null ? "HALTED" : finished ? "FINISHED" : paused ? "PAUSED" : "RUNNING";
    state.setText(String.format(Locale.ROOT, "%s  %sx", phase, speed));
    state.setColor(frame.halted() != null ? Color.SALMON : paused ? Color.GOLD : ACCENT);
    pause.setText(paused ? "Play [Space]" : "Pause [Space]");
    pause.setDisabled(finished);
    step.setDisabled(finished);
    pause.setChecked(false);
    step.setChecked(false);
    inspect.setChecked(inspecting);
    inspect.setDisabled(replay);
    deploy.setChecked(!inspecting);
    deploy.setDisabled(replay || finished || frame.sides().isEmpty());
    String time =
        String.format(Locale.ROOT, "%d:%02d", frame.timeMs() / 60000, frame.timeMs() / 1000 % 60);
    summary.setText(
        (replay ? "Replay" : "Ladder")
            + "  |  "
            + time
            + (frame.overtime() ? " OT" : "")
            + "  |  Tick "
            + frame.tick()
            + "  |  "
            + frame.entities().size()
            + " entities"
            + (frame.elixirRate() > 0 ? "  |  Elixir x" + frame.elixirRate() : ""));
    diagnostics.setText(
        status.isEmpty() ? "Battle core\nStandard Ladder / level 11" : String.join("\n", status));
    for (Toggle toggle : toggles) toggle.button().setChecked(toggle.value().getAsBoolean());
    inspector.update(frame, selectedId, view.getOrientation());
    String result = HudText.of(frame, view, List.of()).result();
    notice.setText(
        frame.halted() != null
            ? "HALTED: " + frame.halted()
            : finished
                ? (result == null ? "Finished" : result)
                    + (stopReason == null ? "" : " - " + stopReason)
                : frame.sides().isEmpty()
                    ? "NO MATCH: R starts a Ladder battle."
                    : inspecting
                        ? "INSPECT: click a unit to pin its details."
                        : "DEPLOY: select a card [1-8], then click the arena. Right click cancels.");
    notice.setColor(frame.halted() != null ? Color.SALMON : ACCENT);
    String text = String.join("\n", frame.messages());
    if (!text.equals(lastEvents)) {
      boolean following =
          eventPane.getMaxY() == 0 || eventPane.getScrollY() >= eventPane.getMaxY() - 2;
      lastEvents = text;
      events.setText(text.isBlank() ? "No events yet." : text);
      eventPane.layout();
      if (following) followEvents();
      else latestEvents.setText("+ Latest");
    }
    if (replay) {
      progress.setValue(endTick > 0 ? Math.min(1f, frame.tick() / (float) endTick) : 0);
      progressText.setText(
          "Tick " + frame.tick() + (endTick >= 0 ? " / " + endTick : " / unknown"));
    }
  }

  public void showRefused(List<String> reasons) {
    refusalPane.setVisible(true);
    refusal.setText(String.join("\n\n", reasons));
    state.setText("REFUSED");
    state.setColor(Color.SALMON);
    pause.setDisabled(true);
    step.setDisabled(true);
    inspect.setDisabled(true);
    inspector.setText("No battle loaded.\n\nSee the replay refusal reasons beside this panel.");
    for (Toggle toggle : toggles) toggle.button().setDisabled(true);
    progress.getParent().setVisible(false);
    notice.setText(
        archiveList.getItems().isEmpty()
            ? "Replay not played. Drop another replay JSON file to open it."
            : "Replay not played. Pick another from the replays list [L], or drop another file.");
    lastEvents = String.join("\n", reasons);
    events.setText(lastEvents);
  }

  @Override
  public void dispose() {
    stage.dispose();
    theme.dispose();
  }
}
