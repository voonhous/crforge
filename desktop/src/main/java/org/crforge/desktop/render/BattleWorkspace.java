package org.crforge.desktop.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.util.ArrayList;
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
  private static final Color BACKGROUND = Color.valueOf("10151fff");
  private static final Color PANEL = Color.valueOf("192230ff");
  private static final Color MUTED = Color.valueOf("a1b1c6ff");
  private static final Color ACCENT = Color.valueOf("76d7cbff");
  private final Skin skin = new Skin();
  private final Stage stage = new Stage(new ScreenViewport());
  private final Table root = new Table();
  private final Table arena = new Table();
  private final Table handRail = new Table();
  private final Viewport arenaViewport;
  private final ViewState view;
  private final BattleRenderer renderer;
  private final boolean replay;
  private final IntConsumer command;
  private final BiConsumer<Integer, Integer> selectCard;
  private final Label data;
  private final Label summary;
  private final Label state;
  private final Label inspector;
  private final Label diagnostics;
  private final Label events;
  private final Label notice;
  private final Label refusal;
  private final ScrollPane refusalPane;
  private final ScrollPane eventPane;
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

  private record Toggle(TextButton button, BooleanSupplier value) {}

  public BattleWorkspace(
      OrthographicCamera camera,
      BattleRenderer renderer,
      ViewState view,
      boolean replay,
      IntConsumer command,
      BiConsumer<Integer, Integer> selectCard) {
    this.renderer = renderer;
    this.view = view;
    this.replay = replay;
    this.command = command;
    this.selectCard = selectCard;
    inspecting = replay;
    createSkin();
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
    Table header = new Table();
    Label brand = label("CRFORGE  /  " + (replay ? "REPLAY" : "BATTLE LAB"));
    brand.setColor(ACCENT);
    header.add(brand).left().expandX();
    data = label("");
    header.add(data).padRight(12);
    dataButton = button("Data details", this::toggleDataDetails);
    header.add(dataButton).height(32);
    root.add(header).growX().padBottom(10).row();

    Table toolbar = new Table();
    toolbar.defaults().height(34).padRight(4);
    pause = button("Pause [Space]", () -> command.accept(Input.Keys.SPACE));
    step = button("Step [.]", () -> command.accept(Input.Keys.PERIOD));
    toolbar.add(pause).width(120);
    toolbar.add(step).width(86);
    toolbar.add(button("Restart [R]", () -> command.accept(Input.Keys.R))).width(108);
    toolbar.add(button("-", () -> command.accept(Input.Keys.MINUS))).width(28);
    state = label("");
    toolbar.add(state).minWidth(126);
    toolbar.add(button("+", () -> command.accept(Input.Keys.PLUS))).width(28);
    deploy =
        button(
            "Deploy",
            () -> {
              if (inspecting && !replay) command.accept(Input.Keys.I);
            });
    inspect =
        button(
            replay ? "Inspect" : "Inspect [I]",
            () -> {
              if (!inspecting) command.accept(Input.Keys.I);
            });
    if (!replay) toolbar.add(deploy).width(76);
    toolbar.add(inspect).width(104);
    if (replay) {
      toolbar.add(button("Flip [F]", () -> command.accept(Input.Keys.F))).width(82);
    }
    toolbar.add().expandX();
    toolbar.add(button("Sidebar [T]", () -> command.accept(Input.Keys.T))).width(108);
    root.add(toolbar).growX().padBottom(8).row();

    dataPanel = new Table();
    dataPanel.background(skin.newDrawable("white", PANEL));
    dataPanel.pad(10);
    dataDetails = wrapped("");
    dataPanel.add(dataDetails).growX();
    dataPanel
        .add(button("Copy", () -> Gdx.app.getClipboard().setContents(details)))
        .width(72)
        .height(32)
        .padLeft(10);
    dataPanel.setVisible(false);
    root.add(dataPanel).growX().height(0).row();

    summary = label("");
    summary.setColor(MUTED);
    root.add(summary).left().padBottom(8).row();

    Table content = new Table();
    topHand = new HandPanel();
    bottomHand = new HandPanel();
    handRail.add(topHand.table).growX().padBottom(10).row();
    Table logHeader = new Table();
    Label logTitle = label("RECENT EVENTS");
    logTitle.setColor(MUTED);
    logHeader.add(logTitle).expandX().left();
    logHeader.add(button("Copy", () -> Gdx.app.getClipboard().setContents(lastEvents))).height(26);
    handRail.add(logHeader).growX().padBottom(6).row();
    events = wrapped("No events yet.");
    events.setAlignment(Align.topLeft);
    eventPane = scroll(events);
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
    inspector = wrapped("Choose Inspect, then click a unit.");
    tools.add(inspector).padBottom(16).row();
    heading(tools, "OVERLAYS");
    Table presets = new Table();
    for (String preset : List.of("Clean", "Combat", "Pathing")) {
      presets
          .add(button(preset, () -> renderer.applyPreset(preset)))
          .growX()
          .height(32)
          .padRight(4);
    }
    tools.add(presets).padBottom(8).row();
    toggle(tools, "Unit names", renderer::isDrawLabels, renderer::toggleDrawLabels);
    toggle(tools, "Target lines", renderer::isDrawTargets, renderer::toggleDrawTargets);
    toggle(tools, "Ranges [O]", renderer::isDrawRanges, () -> command.accept(Input.Keys.O));
    toggle(tools, "Damage [D]", renderer::isDrawDamageNumbers, () -> command.accept(Input.Keys.D));
    toggle(tools, "Area hits [A]", renderer::isDrawAoeDamage, () -> command.accept(Input.Keys.A));
    toggle(tools, "HP values [H]", renderer::isDrawHpNumbers, () -> command.accept(Input.Keys.H));
    toggle(tools, "Headings [P]", renderer::isDrawPaths, () -> command.accept(Input.Keys.P));
    toggle(tools, "Cell costs [G]", renderer::isDrawCellCosts, () -> command.accept(Input.Keys.G));
    toggle(tools, "Routes [N]", renderer::isDrawRoutes, () -> command.accept(Input.Keys.N));
    tools.add().height(12).row();
    heading(tools, "SESSION");
    diagnostics = wrapped("");
    tools.add(diagnostics).padBottom(12).row();
    if (!replay) {
      tools
          .add(button("Next golden scenario [S]", () -> command.accept(Input.Keys.S)))
          .height(32)
          .padBottom(6)
          .row();
      tools
          .add(button("Export trajectories [E]", () -> command.accept(Input.Keys.E)))
          .height(32)
          .padBottom(6)
          .row();
      tools.add(versionControls).row();
      Label resetNote = wrapped("Changing version starts a new battle.");
      resetNote.setColor(MUTED);
      tools.add(resetNote).padTop(6).row();
    }
    sidebar = scroll(tools);
    sidebarCell = content.add(sidebar).width(294).growY().padLeft(12);
    root.add(content).grow().minSize(0).row();

    progress = new ProgressBar(0, 1, 0.001f, false, skin);
    progressText = label("");
    if (replay) {
      Table timeline = new Table();
      timeline.add(progressText).padRight(12);
      timeline.add(progress).growX().height(8);
      root.add(timeline).growX().padTop(8).row();
    }
    notice = wrapped("");
    notice.setColor(ACCENT);
    root.add(scroll(notice)).growX().minHeight(18).prefHeight(20).maxHeight(54).padTop(8).row();
  }

  private void createSkin() {
    FreeTypeFontGenerator generator =
        new FreeTypeFontGenerator(Gdx.files.classpath("fonts/RobotoMono-Regular.ttf"));
    FreeTypeFontGenerator.FreeTypeFontParameter parameter =
        new FreeTypeFontGenerator.FreeTypeFontParameter();
    parameter.size = 14;
    skin.add("default-font", generator.generateFont(parameter), BitmapFont.class);
    generator.dispose();
    Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    pixel.setColor(Color.WHITE);
    pixel.fill();
    Texture texture = new Texture(pixel);
    pixel.dispose();
    skin.add("pixel", texture);
    skin.add("white", new TextureRegionDrawable(new TextureRegion(texture)), Drawable.class);
    skin.add(
        "default", new Label.LabelStyle(skin.getFont("default-font"), Color.valueOf("e8edf5ff")));
    TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
    button.font = skin.getFont("default-font");
    button.fontColor = Color.valueOf("e8edf5ff");
    button.disabledFontColor = Color.valueOf("78899fff");
    button.up = skin.newDrawable("white", Color.valueOf("263447ff"));
    button.over = skin.newDrawable("white", Color.valueOf("344860ff"));
    button.down = skin.newDrawable("white", Color.valueOf("326e70ff"));
    button.checked = skin.newDrawable("white", Color.valueOf("28575fff"));
    button.disabled = skin.newDrawable("white", Color.valueOf("1c2634ff"));
    skin.add("default", button);
    ScrollPane.ScrollPaneStyle scroll = new ScrollPane.ScrollPaneStyle();
    scroll.vScrollKnob = skin.newDrawable("white", Color.valueOf("42526aff"));
    scroll.vScrollKnob.setMinWidth(5);
    skin.add("default", scroll);
    com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle list =
        new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle();
    list.font = skin.getFont("default-font");
    list.fontColorSelected = Color.WHITE;
    list.fontColorUnselected = MUTED;
    list.selection = skin.newDrawable("white", Color.valueOf("28575fff"));
    list.background = skin.newDrawable("white", PANEL);
    SelectBox.SelectBoxStyle select = new SelectBox.SelectBoxStyle();
    select.font = list.font;
    select.fontColor = Color.WHITE;
    select.background = skin.newDrawable("white", Color.valueOf("263447ff"));
    select.listStyle = list;
    select.scrollStyle = scroll;
    skin.add("default", select);
    ProgressBar.ProgressBarStyle bar = new ProgressBar.ProgressBarStyle();
    bar.background = skin.newDrawable("white", PANEL);
    bar.background.setMinHeight(5);
    bar.knobBefore = skin.newDrawable("white", ACCENT);
    bar.knobBefore.setMinHeight(5);
    skin.add("default-horizontal", bar);
  }

  private void toggleDataDetails() {
    dataPanel.setVisible(!dataPanel.isVisible());
    root.invalidateHierarchy();
  }

  private Label label(String text) {
    return new Label(text, skin);
  }

  private Label wrapped(String text) {
    Label label = label(text);
    label.setWrap(true);
    return label;
  }

  private TextButton button(String text, Runnable action) {
    TextButton button = new TextButton(text, skin);
    button.pad(0, 6, 0, 6);
    button.setProgrammaticChangeEvents(false);
    button.addListener(
        new ChangeListener() {
          @Override
          public void changed(ChangeEvent event, Actor actor) {
            action.run();
            button.setChecked(false);
            stage.setKeyboardFocus(null);
          }
        });
    return button;
  }

  private ScrollPane scroll(Actor actor) {
    ScrollPane pane = new ScrollPane(actor, skin);
    pane.setScrollingDisabled(true, false);
    pane.setFadeScrollBars(false);
    return pane;
  }

  private void heading(Table table, String text) {
    Label title = label(text);
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
      Label hint = wrapped("No data root configured. See Data details for the loaded folder.");
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
    data.setText("Data: " + version);
    String updated =
        "Loaded: "
            + version
            + "   |   Development target: "
            + target
            + "\nSource: "
            + source
            + "\nFolder: "
            + folder
            + "\nSHA: "
            + sha;
    if (!updated.equals(details)) {
      details = updated;
      dataDetails.setText(details);
    }
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
    refusalPane.setVisible(false);
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
        (frame.scenario() == null ? (replay ? "Replay" : "Ladder") : frame.scenario())
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
    EntityView selected =
        frame.entities().stream()
            .filter(entity -> entity.id() == selectedId)
            .findFirst()
            .orElse(null);
    if (selected == null) {
      inspector.setText(
          selectedId < 0
              ? "Choose Inspect, then click a unit.\n\nPosition and ranges use game units."
              : "Unit #" + selectedId + " is no longer on the arena.");
    } else {
      inspector.setText(
          selected.name()
              + " #"
              + selected.id()
              + "\nSide "
              + selected.side()
              + " / "
              + view.getOrientation().sideName(selected.side())
              + "\nHP "
              + selected.hitPoints()
              + " / "
              + selected.maxHitPoints()
              + "\nShield "
              + selected.shield()
              + " / "
              + selected.maxShield()
              + "\nPosition "
              + selected.x()
              + ", "
              + selected.y()
              + "\nState "
              + RouteOverlayRenderer.stateName(selected.state())
              + "\nRange "
              + selected.minimumRange()
              + " - "
              + selected.range()
              + "\nSight "
              + selected.sightRange()
              + "\nTarget "
              + (selected.hasTarget() ? selected.targetX() + ", " + selected.targetY() : "none")
              + (selected.deploying() ? "\nDeploying" : "")
              + (selected.hidden() ? "\nHidden" : ""));
    }
    String result = HudText.of(frame, view, List.of()).result();
    notice.setText(
        frame.halted() != null
            ? "HALTED: " + frame.halted()
            : finished
                ? (result == null ? "Finished" : result)
                    + (stopReason == null ? "" : " - " + stopReason)
                : frame.sides().isEmpty()
                    ? "SCENARIO: pause or step to examine the reference trajectory."
                    : inspecting
                        ? "INSPECT: click a unit to pin its details."
                        : "DEPLOY: select a card [1-8], then click the arena. Right click cancels.");
    notice.setColor(frame.halted() != null ? Color.SALMON : ACCENT);
    String text = String.join("\n", frame.messages());
    if (!text.equals(lastEvents)) {
      lastEvents = text;
      events.setText(text.isBlank() ? "No events yet." : text);
      eventPane.layout();
      eventPane.setScrollPercentY(1);
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
    notice.setText("Replay not played. Drop another replay JSON file to open it.");
    lastEvents = String.join("\n", reasons);
    events.setText(lastEvents);
  }

  private final class HandPanel {
    private final Table table = new Table();
    private final Label title = label("");
    private final TextButton[] cards = new TextButton[4];
    private final Label next = wrapped("");
    private int side;

    private HandPanel() {
      table.background(skin.newDrawable("white", PANEL));
      table.pad(8);
      title.setWrap(true);
      table.add(title).colspan(2).growX().left().padBottom(6).row();
      for (int slot = 0; slot < 4; slot++) {
        final int index = slot;
        cards[slot] =
            button(
                "",
                () -> {
                  setInspecting(false);
                  selectCard.accept(side, index);
                });
        cards[slot].getLabel().setWrap(true);
        if (replay) cards[slot].setTouchable(Touchable.disabled);
        table
            .add(cards[slot])
            .growX()
            .uniformX()
            .height(58)
            .padRight(slot % 2 == 0 ? 6 : 0)
            .padBottom(6);
        if (slot % 2 == 1) table.row();
      }
      next.setColor(MUTED);
      table.add(next).colspan(2).growX().minHeight(22).left();
    }

    private void update(BattleFrame frame, int side, int selectedSide, int selectedSlot) {
      this.side = side;
      BattleFrame.SideView player =
          frame.sides().stream().filter(value -> value.side() == side).findFirst().orElse(null);
      title.setColor(view.getOrientation().blue(side) ? Color.SKY : Color.SALMON);
      title.setText(
          player == null
              ? "Scenario - no hand"
              : "SIDE "
                  + side
                  + " / "
                  + view.getOrientation().sideName(side).toUpperCase(Locale.ROOT)
                  + "\nElixir "
                  + String.format(Locale.ROOT, "%.1f", player.elixir() / 10000f)
                  + " / 10  Crowns "
                  + player.crowns());
      int reserved =
          player == null
              ? 0
              : player.hand().stream()
                  .filter(value -> value != null && value.pending())
                  .mapToInt(BattleFrame.CardView::cost)
                  .sum();
      for (int slot = 0; slot < 4; slot++) {
        BattleFrame.CardView card = player == null ? null : player.hand().get(slot);
        TextButton button = cards[slot];
        boolean unavailable =
            card == null || card.pending() || card.cost() > player.wholeElixir() - reserved;
        button.setDisabled(unavailable || frame.halted() != null || frame.ended() || frame.over());
        button.setChecked(selectedSide == side && selectedSlot == slot);
        button.setText(
            card == null
                ? "-"
                : card.name().replaceAll("(?<=[a-z])(?=[A-Z])", " ")
                    + "\n"
                    + (card.pending() ? "Queued" : card.cost() + " elixir")
                    + (replay ? "" : " [" + (side * 4 + slot + 1) + "]"));
      }
      next.setText(
          player == null || player.next() == null
              ? ""
              : "Next: " + player.next().name().replaceAll("(?<=[a-z])(?=[A-Z])", " "));
    }
  }

  @Override
  public void dispose() {
    stage.dispose();
    skin.dispose();
  }
}
