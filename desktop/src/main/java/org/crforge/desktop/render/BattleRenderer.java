package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.utils.Align;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.EntityView;

/**
 * Draws a battle core session from the frames {@link org.crforge.desktop.battle.BattleAdapter}
 * reads: the arena from the battle's own tile map, every entity kind (troops, buildings, towers,
 * projectiles, area effects) in its side's colour with health and shield bars, the overlays the
 * screen toggles, and the HUD.
 *
 * <p>It shares the original renderer's resources, colours and layout, and draws the overlays that
 * exist for both engines through the same renderers: the routing cell costs, the routes, the golden
 * trajectory, the damage numbers and the area damage indicators.
 *
 * <p>Every arena position, team colour and side name goes through the screen's {@link
 * ViewOrientation}, so a flipped view draws the whole arena and every overlay turned by 180
 * degrees, the side at the bottom in blue with the bottom HUD panel. The screen's {@link ViewState}
 * also says whether the text annotations are drawn (see {@link HudText}).
 */
public class BattleRenderer {

  /** Vertical spacing between two lines of the status and message columns, in pixels. */
  private static final float LINE_HEIGHT = 14f;

  /** Length in pixels of a heading line. */
  private static final float HEADING_PIXELS = TILE_PIXELS * 1.5f;

  private final RenderContext ctx;
  private final HudRenderer backgrounds;
  private final CellCostOverlayRenderer cellCosts;
  private final RouteOverlayRenderer routes;
  private final GoldenTrajectoryRenderer golden;
  private final DamageNumberRenderer damageNumbers;
  private final AoeDamageRenderer areaHits;

  @Getter private boolean drawPaths = false;
  @Getter private boolean drawRanges = false;
  @Getter private boolean drawDamageNumbers = false;
  @Getter private boolean drawAoeDamage = true;
  @Getter private boolean drawHpNumbers = false;
  @Getter private boolean drawCellCosts = false;
  @Getter private boolean drawRoutes = false;
  @Getter private boolean drawLabels = true;
  @Getter private boolean drawTargets = true;
  private int inspectedEntity = -1;

  public void toggleDrawLabels() {
    drawLabels = !drawLabels;
  }

  public void toggleDrawTargets() {
    drawTargets = !drawTargets;
  }

  public void setInspectedEntity(int id) {
    inspectedEntity = id;
  }

  /** View presets affect presentation only. Individual toggles remain available. */
  public void applyPreset(OverlayPreset preset) {
    drawLabels = preset != OverlayPreset.CLEAN;
    drawTargets = preset == OverlayPreset.COMBAT;
    drawRanges = preset == OverlayPreset.COMBAT;
    drawDamageNumbers = preset == OverlayPreset.COMBAT;
    drawAoeDamage = preset == OverlayPreset.COMBAT;
    drawHpNumbers = preset == OverlayPreset.COMBAT;
    drawPaths = preset == OverlayPreset.PATHING;
    drawRoutes = preset == OverlayPreset.PATHING;
    drawCellCosts = preset == OverlayPreset.PATHING;
  }

  /** Which way up the frame being drawn has the arena, from the screen's view settings. */
  private ViewOrientation view = ViewOrientation.STANDARD;

  public BattleRenderer() {
    this.ctx = new RenderContext(12);
    this.backgrounds = new HudRenderer(ctx);
    this.cellCosts = new CellCostOverlayRenderer(ctx);
    this.routes = new RouteOverlayRenderer(ctx);
    this.golden = new GoldenTrajectoryRenderer(ctx);
    this.damageNumbers = new DamageNumberRenderer(ctx);
    this.areaHits = new AoeDamageRenderer(ctx);
  }

  public void toggleDrawPaths() {
    drawPaths = !drawPaths;
  }

  public void toggleDrawRanges() {
    drawRanges = !drawRanges;
  }

  public void toggleDrawDamageNumbers() {
    drawDamageNumbers = !drawDamageNumbers;
  }

  public void toggleDrawAoeDamage() {
    drawAoeDamage = !drawAoeDamage;
  }

  public void toggleDrawHpNumbers() {
    drawHpNumbers = !drawHpNumbers;
  }

  public void toggleDrawCellCosts() {
    drawCellCosts = !drawCellCosts;
  }

  public void toggleDrawRoutes() {
    drawRoutes = !drawRoutes;
  }

  public String hoveredCellStatus(BattleWorld world, int column, int row) {
    return drawCellCosts ? cellCosts.hoverStatus(world.getGrid(), column, row) : "";
  }

  /**
   * What the screen hands the renderer besides the frame.
   *
   * @param world the battle's world, for the routing grid
   * @param newAreaHits the area hits of the steps run since the last frame
   * @param hoverTileX the tile column under the mouse, or -1
   * @param hoverTileY the tile row under the mouse, or -1
   * @param hoverCellX the routing cell column under the mouse, or -1
   * @param hoverCellY the routing cell row under the mouse, or -1
   * @param selectedSide the side whose card is selected, or -1
   * @param selectedSlot the selected hand slot, or -1
   * @param selectedCard the battle's row of the selected card, or null
   * @param preview where the selected card would be placed at the hovered tile, or null
   * @param goldenOverlay the golden scenario's trajectory, or {@link GoldenOverlay#none()}
   * @param scenarioLines the golden scenario's status lines
   * @param notes lines about controls this screen does not offer
   * @param view the screen's view settings: the orientation and whether annotations are shown
   */
  public record Inputs(
      BattleWorld world,
      List<AreaHitLog.AreaHit> newAreaHits,
      int hoverTileX,
      int hoverTileY,
      int hoverCellX,
      int hoverCellY,
      int selectedSide,
      int selectedSlot,
      DeployCard selectedCard,
      CardPlacement.Result preview,
      GoldenOverlay goldenOverlay,
      List<String> scenarioLines,
      List<String> notes,
      ViewState view) {}

  /** Renders one frame. */
  public void render(BattleFrame frame, OrthographicCamera camera, Inputs inputs) {
    render(frame, camera, inputs, true);
  }

  /** The workspace draws its own controls outside the arena's viewport. */
  public void render(
      BattleFrame frame, OrthographicCamera camera, Inputs inputs, boolean legacyHud) {
    ctx.setProjection(camera);
    TileMap tileMap = inputs.world().getTileMap();
    view = inputs.view().getOrientation();
    HudText hud = HudText.of(frame, inputs.view(), statusLines(inputs));

    if (legacyHud) backgrounds.renderBackgrounds(camera);
    renderArena(tileMap);
    if (drawCellCosts) {
      cellCosts.render(inputs.world().getGrid(), inputs.hoverCellX(), inputs.hoverCellY(), view);
    }
    renderAreaEffects(frame);
    renderBodies(frame);
    renderHover(inputs);
    renderProjectiles(frame);
    renderHealthBars(frame);
    if (drawTargets) renderTargetLines(frame);
    if (drawPaths) {
      renderHeadings(frame);
    }
    golden.render(inputs.goldenOverlay(), view);
    if (drawRoutes) {
      routes.render(routed(frame), view);
    }
    if (drawRanges) {
      renderRanges(frame);
    }
    if (drawLabels && hud.labels()) {
      renderLabels(frame);
    }

    // Kept current every frame, drawn only when toggled on. Both renderers take positions in game
    // units as drawn, so the view's mirror is applied as each sample and hit is handed over.
    damageNumbers.update(healthSamples(frame));
    if (drawDamageNumbers) {
      damageNumbers.render();
    }
    for (AreaHitLog.AreaHit hit : inputs.newAreaHits()) {
      areaHits.add(view.x(hit.x()), view.y(hit.y()), hit.radius(), view.team(hit.side()));
    }
    areaHits.age();
    if (drawAoeDamage) {
      areaHits.render();
    }
    if (drawHpNumbers) {
      renderHpNumbers(frame);
    }
    renderInspection(frame);
    if (legacyHud) renderHud(frame, camera, inputs, hud);
  }

  private void renderInspection(BattleFrame frame) {
    if (inspectedEntity < 0) return;
    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Line);
    shapes.setColor(Color.WHITE);
    for (EntityView entity : frame.entities()) {
      if (entity.id() != inspectedEntity) continue;
      shapes.circle(
          px(entity.x()), py(entity.y()), Math.max(10, unitsToPixels(entity.radius())) + 3);
      if (entity.hasTarget()) {
        shapes.line(px(entity.x()), py(entity.y()), px(entity.targetX()), py(entity.targetY()));
      }
    }
    shapes.end();
  }

  /**
   * The arena's cells from the battle's tile map, a tile's checkerboard and the tile grid, each
   * cell where the view draws it.
   */
  private void renderArena(TileMap tileMap) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    for (int row = 0; row < tileMap.height(); row++) {
      boolean riverRow = riverRow(tileMap, row);
      for (int col = 0; col < tileMap.width(); col++) {
        Color color = cellColor(tileMap, col, row, riverRow, view);
        boolean water = (tileMap.bits(col, row) & TileMap.WATER_BIT) != 0;
        float shade = !water && ((col / 2) + (row / 2)) % 2 == 0 ? CHECKER_DARKEN : 1f;
        shade *= 0.65f;
        shapes.setColor(color.r * shade, color.g * shade, color.b * shade, 1f);
        shapes.rect(
            view.left(col * TileMap.CELL_UNITS, TileMap.CELL_UNITS),
            view.bottom(row * TileMap.CELL_UNITS, TileMap.CELL_UNITS),
            CELL_PIXELS,
            CELL_PIXELS);
      }
    }
    shapes.end();

    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Line);
    shapes.setColor(COLOR_GRID);
    float width = unitsToPixels(tileMap.widthUnits());
    float height = unitsToPixels(tileMap.heightUnits());
    for (int x = 0; x <= tileMap.width() / 2; x++) {
      shapes.line(x * TILE_PIXELS, BOTTOM_UI_HEIGHT, x * TILE_PIXELS, BOTTOM_UI_HEIGHT + height);
    }
    for (int y = 0; y <= tileMap.height() / 2; y++) {
      shapes.line(0, BOTTOM_UI_HEIGHT + y * TILE_PIXELS, width, BOTTOM_UI_HEIGHT + y * TILE_PIXELS);
    }
    shapes.end();
  }

  /** Whether a row of cells crosses the river: some cell of it is water. */
  private static boolean riverRow(TileMap tileMap, int row) {
    for (int col = 0; col < tileMap.width(); col++) {
      if ((tileMap.bits(col, row) & TileMap.WATER_BIT) != 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * A cell's colour: water, blocked, a bridge across the river, or its side's half, in the colour
   * the view draws that side in.
   */
  static Color cellColor(
      TileMap tileMap, int col, int row, boolean riverRow, ViewOrientation view) {
    int bits = tileMap.bits(col, row);
    if ((bits & TileMap.WATER_BIT) != 0) {
      return COLOR_RIVER;
    }
    if ((bits & TileMap.BLOCKED_BIT) != 0) {
      return COLOR_BANNED;
    }
    if (riverRow) {
      return COLOR_BRIDGE;
    }
    int half = row < tileMap.height() / 2 ? 0 : 1;
    return view.blue(half) ? COLOR_BLUE_ZONE : COLOR_RED_ZONE;
  }

  /** Area effects: a translucent disc in the side's colour with an outline, under the bodies. */
  private void renderAreaEffects(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    for (EntityView area : frame.entities()) {
      if (area.kind() == EntityView.Kind.AREA_EFFECT && area.radius() > 0) {
        Color side = sideColor(area.side());
        shapes.setColor(side.r, side.g, side.b, 0.18f);
        shapes.circle(px(area.x()), py(area.y()), unitsToPixels(area.radius()), CIRCLE_SEGMENTS);
      }
    }
    shapes.end();
    shapes.begin(ShapeType.Line);
    for (EntityView area : frame.entities()) {
      if (area.kind() == EntityView.Kind.AREA_EFFECT) {
        shapes.setColor(COLOR_AREA_EFFECT);
        float radius = area.radius() > 0 ? unitsToPixels(area.radius()) : 4f;
        shapes.circle(px(area.x()), py(area.y()), radius, CIRCLE_SEGMENTS);
      }
    }
    shapes.end();
  }

  /** Troops, buildings and towers: a filled body in the side's colour, then its outlines. */
  private void renderBodies(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter()) {
        continue;
      }
      float x = px(entity.x());
      float y = py(entity.y());
      float radius = unitsToPixels(entity.radius());
      if (entity.kind() == EntityView.Kind.TOWER) {
        shapes.setColor(COLOR_TOWER_BOUNDARY);
        shapes.rect(x - radius, y - radius, radius * 2, radius * 2);
      }
      Color color = bodyColor(entity);
      float alpha = entity.hidden() ? 0.25f : entity.deploying() ? 0.5f : 1f;
      shapes.setColor(color.r, color.g, color.b, alpha);
      if (entity.kind() == EntityView.Kind.BUILDING) {
        shapes.rect(x - radius, y - radius, radius * 2, radius * 2);
      } else {
        shapes.circle(x, y, radius);
      }
      if (entity.air()) {
        shapes.setColor(COLOR_AIR_UNIT);
        shapes.circle(x, y, radius + 2);
        shapes.setColor(color.r, color.g, color.b, alpha);
        shapes.circle(x, y, radius);
      }
    }
    shapes.end();
    shapes.begin(ShapeType.Line);
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter()) {
        continue;
      }
      Color color = bodyColor(entity);
      shapes.setColor(color.r * 0.5f, color.g * 0.5f, color.b * 0.5f, 1f);
      shapes.circle(px(entity.x()), py(entity.y()), unitsToPixels(entity.radius()));
    }
    shapes.end();
  }

  /**
   * The hovered tile and the selected card's placement there, as the battle would place it now: a
   * red tile where it finds no tile, else the placed point, each unit's ghost and the unit's attack
   * range, or a spell's circle.
   */
  private void renderHover(Inputs inputs) {
    int tileX = inputs.hoverTileX();
    int tileY = inputs.hoverTileY();
    if (tileX < 0 || tileY < 0 || tileX >= view.tilesWide() || tileY >= view.tilesLong()) {
      return;
    }
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    CardPlacement.Result preview = inputs.preview();
    float tileLeft = view.left(tileX * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
    float tileBottom = view.bottom(tileY * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
    if (preview != null && !preview.placed()) {
      shapes.setColor(COLOR_HOVER_INVALID);
      shapes.rect(tileLeft, tileBottom, TILE_PIXELS, TILE_PIXELS);
      shapes.end();
      return;
    }
    shapes.setColor(0.5f, 0.5f, 0.5f, 0.2f);
    shapes.rect(tileLeft, tileBottom, TILE_PIXELS, TILE_PIXELS);
    DeployCard card = inputs.selectedCard();
    if (preview != null && card != null) {
      Color ghost = view.blue(inputs.selectedSide()) ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
      if (preview.units().isEmpty()) {
        shapes.setColor(COLOR_SPELL_RADIUS);
        float radius = spellRadius(inputs.world(), card);
        shapes.circle(px(preview.x()), py(preview.y()), unitsToPixels(radius), CIRCLE_SEGMENTS);
      } else {
        shapes.setColor(ghost);
        for (CardPlacement.Unit unit : preview.units()) {
          shapes.circle(px(unit.x()), py(unit.y()), unitsToPixels(unit.unit().collisionRadius()));
        }
      }
    }
    shapes.end();
    if (preview != null && card != null && !preview.units().isEmpty()) {
      CardPlacement.Unit first = preview.units().get(0);
      int range = first.unit().range();
      if (range > 0) {
        shapes.begin(ShapeType.Line);
        shapes.setColor(COLOR_HOVER_ATTACK_RANGE);
        shapes.circle(
            px(preview.x()),
            py(preview.y()),
            unitsToPixels(range + first.unit().collisionRadius()),
            CIRCLE_SEGMENTS);
        if (first.unit().minimumRange() > 0) {
          shapes.setColor(COLOR_MINIMUM_RANGE);
          shapes.circle(
              px(preview.x()),
              py(preview.y()),
              unitsToPixels(first.unit().minimumRange() + first.unit().collisionRadius()),
              CIRCLE_SEGMENTS);
        }
        shapes.end();
      }
    }
  }

  /**
   * The circle a spell's preview shows: its area effect's radius, else the card's own radius, else
   * its projectile's area radius, else one tile.
   */
  static float spellRadius(BattleWorld world, DeployCard card) {
    if (card.areaEffect() != null && world.getRecords().areaEffect(card.areaEffect()) != null) {
      int radius = world.getRecords().areaEffect(card.areaEffect()).radius();
      if (radius > 0) {
        return radius;
      }
    }
    if (card.radius() > 0) {
      return card.radius();
    }
    if (card.projectile() != null && world.getRecords().projectile(card.projectile()) != null) {
      int radius = world.getRecords().projectile(card.projectile()).radius();
      if (radius > 0) {
        return radius;
      }
    }
    return GameUnits.UNITS_PER_TILE;
  }

  /** Projectiles: a dot, and the landing circle of one with an area. */
  private void renderProjectiles(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    for (EntityView projectile : frame.entities()) {
      if (projectile.kind() != EntityView.Kind.PROJECTILE) {
        continue;
      }
      if (projectile.radius() > 0) {
        shapes.setColor(
            view.blue(projectile.side()) ? COLOR_BLUE_LANDING_ZONE : COLOR_RED_LANDING_ZONE);
        shapes.circle(
            px(projectile.aimX()),
            py(projectile.aimY()),
            unitsToPixels(projectile.radius()),
            CIRCLE_SEGMENTS);
      }
      shapes.setColor(COLOR_PROJECTILE);
      shapes.circle(px(projectile.x()), py(projectile.y()), PROJECTILE_RADIUS);
    }
    shapes.end();
  }

  /** A health bar over every character with hit points, a shield bar over it while it has one. */
  private void renderHealthBars(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter() || !entity.hasHitPoints()) {
        continue;
      }
      float width = barWidth(entity);
      float left = px(entity.x()) - width / 2;
      float barY = py(entity.y()) + unitsToPixels(entity.radius()) + HEALTH_BAR_Y_OFFSET;
      float share = entity.healthShare();
      Color fill =
          share > HEALTH_THRESHOLD_HIGH
              ? COLOR_HEALTH_GREEN
              : share > HEALTH_THRESHOLD_LOW ? COLOR_HEALTH_YELLOW : COLOR_HEALTH_RED;
      bar(shapes, left, barY, width, share, fill);
      if (entity.maxShield() > 0) {
        float shieldShare = (float) entity.shield() / entity.maxShield();
        bar(shapes, left, barY + HEALTH_BAR_HEIGHT + 1, width, shieldShare, COLOR_SHIELD);
      }
    }
    shapes.end();
  }

  private static void bar(
      ShapeRenderer shapes, float left, float y, float width, float share, Color fill) {
    float b = HEALTH_BAR_BORDER;
    shapes.setColor(COLOR_CARD_BORDER);
    shapes.rect(left, y, width, HEALTH_BAR_HEIGHT);
    shapes.setColor(COLOR_HEALTH_BG);
    shapes.rect(left + b, y + b, width - b * 2, HEALTH_BAR_HEIGHT - b * 2);
    shapes.setColor(fill);
    shapes.rect(left + b, y + b, (width - b * 2) * share, HEALTH_BAR_HEIGHT - b * 2);
  }

  /** A bar's width: the body's, at least the minimum, widened to fit the HP numbers when shown. */
  private float barWidth(EntityView entity) {
    float base = Math.max(unitsToPixels(entity.radius()) * 2, HEALTH_BAR_MIN_WIDTH);
    if (!drawHpNumbers) {
      return base;
    }
    ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), hpText(entity));
    return Math.max(base, ctx.getGlyphLayout().width + HEALTH_BAR_BORDER * 2 + 2);
  }

  private static String hpText(EntityView entity) {
    return entity.hitPoints() + "/" + entity.maxHitPoints();
  }

  /** A faint line from every character to the reference it holds. */
  private void renderTargetLines(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Line);
    for (EntityView entity : frame.entities()) {
      if (entity.isCharacter() && entity.hasTarget()) {
        shapes.setColor(1f, 0f, 0f, 0.3f);
        shapes.line(px(entity.x()), py(entity.y()), px(entity.targetX()), py(entity.targetY()));
      }
    }
    shapes.end();
  }

  /** A short line along each moving character's direction of travel. */
  private void renderHeadings(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Line);
    shapes.setColor(COLOR_PATH);
    for (EntityView entity : frame.entities()) {
      if (entity.kind() != EntityView.Kind.TROOP) {
        continue;
      }
      double length = Math.hypot(entity.headingX(), entity.headingY());
      if (length == 0) {
        continue;
      }
      float x = px(entity.x());
      float y = py(entity.y());
      shapes.line(
          x,
          y,
          x + view.dx((float) (entity.headingX() / length)) * HEADING_PIXELS,
          y + view.dy((float) (entity.headingY() / length)) * HEADING_PIXELS);
    }
    shapes.end();
  }

  /** The characters the route overlay draws: every troop with its grid state. */
  private static List<RouteOverlayRenderer.Routed> routed(BattleFrame frame) {
    List<RouteOverlayRenderer.Routed> units = new ArrayList<>();
    for (EntityView entity : frame.entities()) {
      if (entity.kind() == EntityView.Kind.TROOP && entity.grid() != null) {
        units.add(
            new RouteOverlayRenderer.Routed(
                entity.x(), entity.y(), entity.radius(), entity.grid(), entity.speed()));
      }
    }
    return units;
  }

  /**
   * Each character's attack range (its range plus its own collision radius; a target's radius adds
   * to it), its minimum range and its sight range.
   */
  private void renderRanges(BattleFrame frame) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Line);
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter() || entity.range() <= 0) {
        continue;
      }
      float x = px(entity.x());
      float y = py(entity.y());
      shapes.setColor(COLOR_ATTACK_RANGE);
      shapes.circle(x, y, unitsToPixels(entity.range() + entity.radius()), CIRCLE_SEGMENTS);
      if (entity.minimumRange() > 0) {
        shapes.setColor(COLOR_MINIMUM_RANGE);
        shapes.circle(
            x, y, unitsToPixels(entity.minimumRange() + entity.radius()), CIRCLE_SEGMENTS);
      }
      if (entity.sightRange() > entity.range()) {
        shapes.setColor(COLOR_AGGRO_RANGE);
        shapes.circle(x, y, unitsToPixels(entity.sightRange()), CIRCLE_SEGMENTS);
      }
    }
    shapes.end();
  }

  /** Each character's row name above its bars, and an area effect's name and life left. */
  private void renderLabels(BattleFrame frame) {
    ctx.getSpriteBatch().begin();
    for (EntityView entity : frame.entities()) {
      String label;
      float y;
      if (entity.isCharacter()) {
        label = entity.name();
        if (entity.state() == GridEntityState.WAITING_TO_DEPLOY) {
          label += " [WAIT]";
        } else if (entity.deploying()) {
          label += " [DEPLOY]";
        } else if (entity.hidden()) {
          label += " [HIDDEN]";
        }
        float bars = HEALTH_BAR_Y_OFFSET + HEALTH_BAR_HEIGHT;
        if (entity.maxShield() > 0) {
          bars += 1 + HEALTH_BAR_HEIGHT;
        }
        y = py(entity.y()) + unitsToPixels(entity.radius()) + bars + 10;
      } else if (entity.kind() == EntityView.Kind.AREA_EFFECT) {
        label =
            entity.name()
                + " "
                + String.format(Locale.ROOT, "%.1fs", Math.max(entity.lifeMs(), 0) / 1000f);
        y = py(entity.y()) - unitsToPixels(entity.radius()) - 2;
      } else {
        continue;
      }
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), label);
      ctx.getEntityNameFont()
          .draw(ctx.getSpriteBatch(), label, px(entity.x()) - ctx.getGlyphLayout().width / 2, y);
    }
    ctx.getSpriteBatch().end();
  }

  /** Every character's hit points, at its position as drawn, for the damage numbers. */
  private List<DamageNumberRenderer.HealthSample> healthSamples(BattleFrame frame) {
    List<DamageNumberRenderer.HealthSample> samples = new ArrayList<>();
    for (EntityView entity : frame.entities()) {
      if (entity.isCharacter() && entity.hasHitPoints()) {
        samples.add(
            new DamageNumberRenderer.HealthSample(
                entity.id(),
                view.x(entity.x()),
                view.y(entity.y()),
                entity.hitPoints(),
                entity.shield()));
      }
    }
    return samples;
  }

  /** The hit points and maximum centred on each health bar. */
  private void renderHpNumbers(BattleFrame frame) {
    ctx.getSpriteBatch().begin();
    ctx.getEntityNameFont().setColor(COLOR_HP_TEXT);
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter() || !entity.hasHitPoints()) {
        continue;
      }
      float barY = py(entity.y()) + unitsToPixels(entity.radius()) + HEALTH_BAR_Y_OFFSET;
      String text = hpText(entity);
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), text);
      ctx.getEntityNameFont()
          .draw(
              ctx.getSpriteBatch(),
              text,
              px(entity.x()) - ctx.getGlyphLayout().width / 2,
              barY + HEALTH_BAR_HEIGHT / 2 + ctx.getGlyphLayout().height / 2);
    }
    ctx.getEntityNameFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /**
   * The clock, both hands and elixir bars, the crowns and the result, and the annotations the view
   * shows: the tick line, the status column and the messages.
   */
  private void renderHud(BattleFrame frame, OrthographicCamera camera, Inputs inputs, HudText hud) {
    float width = camera.viewportWidth;
    float height = camera.viewportHeight;
    for (BattleFrame.SideView side : frame.sides()) {
      renderSide(side, width, height, inputs);
    }

    ctx.getSpriteBatch().begin();
    int seconds = frame.timeMs() / 1000;
    String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    if (frame.overtime()) {
      time = "OT " + time;
    }
    ctx.getGlyphLayout().setText(ctx.getTimerFont(), time);
    float timerX = (width - ctx.getGlyphLayout().width) / 2;
    float timerWidth = ctx.getGlyphLayout().width;
    ctx.getTimerFont().draw(ctx.getSpriteBatch(), time, timerX, height - 10);
    if (frame.elixirRate() > 0) {
      ctx.getTimerFont().setColor(COLOR_ELIXIR);
      ctx.getTimerFont()
          .draw(
              ctx.getSpriteBatch(), "x" + frame.elixirRate(), timerX + timerWidth + 8, height - 10);
      ctx.getTimerFont().setColor(Color.WHITE);
    }
    if (hud.crowns() != null) {
      ctx.getFont().draw(ctx.getSpriteBatch(), hud.crowns(), 10, height - 12);
    }

    float middle = BOTTOM_UI_HEIGHT + unitsToPixels(inputs.world().getTileMap().heightUnits()) / 2;
    if (hud.tickLine() != null) {
      ctx.getFont().draw(ctx.getSpriteBatch(), hud.tickLine(), 10, middle + 20);
    }

    if (hud.result() != null) {
      String result = hud.result();
      ctx.getGlyphLayout().setText(ctx.getTitleFont(), result);
      ctx.getTitleFont()
          .draw(ctx.getSpriteBatch(), result, (width - ctx.getGlyphLayout().width) / 2, middle);
    }

    // The status column, right aligned and bottom up, as the original screen has it.
    List<String> status = hud.status();
    for (int line = 0; line < status.size(); line++) {
      String text = status.get(line);
      ctx.getGlyphLayout().setText(ctx.getFont(), text);
      ctx.getFont()
          .draw(
              ctx.getSpriteBatch(),
              text,
              width - 10 - ctx.getGlyphLayout().width,
              middle + 20 + line * LINE_HEIGHT);
    }

    // The session's messages, newest at the bottom, just above the bottom panel.
    List<String> messages = hud.messages();
    ctx.getEntityNameFont().setColor(Color.LIGHT_GRAY);
    for (int line = 0; line < messages.size(); line++) {
      ctx.getEntityNameFont()
          .draw(
              ctx.getSpriteBatch(),
              messages.get(line),
              6,
              BOTTOM_UI_HEIGHT + 8 + (messages.size() - 1 - line) * (LINE_HEIGHT - 2));
    }
    if (hud.halted()) {
      ctx.getEntityNameFont().setColor(COLOR_HEALTH_RED);
      ctx.getEntityNameFont().draw(ctx.getSpriteBatch(), "HALTED: R resets", 6, middle - 30);
    }
    ctx.getEntityNameFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /** The status column: the overlays that are on, the hovered cell, the scenario and the notes. */
  private List<String> statusLines(Inputs inputs) {
    List<String> lines = new ArrayList<>();
    if (drawPaths) {
      lines.add("Paths: ON");
    }
    if (drawRanges) {
      lines.add("Ranges: ON");
    }
    if (drawDamageNumbers) {
      lines.add("Damage: ON");
    }
    if (drawAoeDamage) {
      lines.add("AOE: ON");
    }
    if (drawHpNumbers) {
      lines.add("HP: ON");
    }
    if (drawRoutes) {
      lines.add("Routes: ON");
    }
    if (drawCellCosts) {
      lines.add("Cells: ON");
      lines.add(
          cellCosts.hoverStatus(
              inputs.world().getGrid(), inputs.hoverCellX(), inputs.hoverCellY()));
    }
    lines.addAll(inputs.scenarioLines());
    lines.add("engine: battle core");
    lines.addAll(inputs.view().statusLines());
    lines.addAll(inputs.notes());
    return lines;
  }

  /**
   * One side's elixir bar, hand and next card, in its panel: the top one for the view's top side.
   */
  private void renderSide(BattleFrame.SideView side, float width, float height, Inputs inputs) {
    boolean top = view.atTop(side.side());
    float panelBottom = top ? height - TOP_UI_HEIGHT : 0;
    float cardY = CardLayout.cardY(top, panelBottom + (top ? TOP_UI_HEIGHT : BOTTOM_UI_HEIGHT));
    float barX = (width - ELIXIR_BAR_WIDTH) / 2;
    float barY = top ? panelBottom + 10 : panelBottom + 115;
    float elixir = side.elixir() / 10000f;

    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    shapes.setColor(COLOR_ELIXIR_BG);
    shapes.rect(barX, barY, ELIXIR_BAR_WIDTH, ELIXIR_BAR_HEIGHT);
    shapes.setColor(COLOR_ELIXIR);
    shapes.rect(
        barX, barY, ELIXIR_BAR_WIDTH * Math.min(elixir / MAX_ELIXIR, 1f), ELIXIR_BAR_HEIGHT);
    shapes.end();

    ctx.getSpriteBatch().begin();
    String text = String.format(Locale.ROOT, "%d / 10", side.wholeElixir());
    ctx.getGlyphLayout().setText(ctx.getFont(), text);
    ctx.getFont()
        .draw(
            ctx.getSpriteBatch(),
            text,
            barX + (ELIXIR_BAR_WIDTH - ctx.getGlyphLayout().width) / 2,
            barY + (ELIXIR_BAR_HEIGHT + ctx.getGlyphLayout().height) / 2);
    ctx.getSpriteBatch().end();

    for (int slot = 0; slot < HAND_SIZE; slot++) {
      boolean selected = inputs.selectedSide() == side.side() && inputs.selectedSlot() == slot;
      renderCard(
          side.hand().get(slot),
          CardLayout.cardX(width, slot),
          cardY,
          CARD_WIDTH,
          CARD_HEIGHT,
          selected);
    }
    float nextX = CardLayout.nextCardX(width);
    renderCard(side.next(), nextX, cardY + 10, CARD_WIDTH * 0.8f, CARD_HEIGHT * 0.8f, false);
    ctx.getSpriteBatch().begin();
    ctx.getFont().draw(ctx.getSpriteBatch(), "Next", nextX, cardY + CARD_HEIGHT);
    ctx.getSpriteBatch().end();
  }

  private void renderCard(
      BattleFrame.CardView card, float x, float y, float w, float h, boolean selected) {
    if (card == null) {
      return;
    }
    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    shapes.setColor(COLOR_CARD_BG);
    shapes.rect(x, y, w, h);
    if (selected) {
      shapes.setColor(COLOR_CARD_SELECTED);
      shapes.rect(x, y, w, 4);
      shapes.rect(x, y + h - 4, w, 4);
      shapes.rect(x, y, 4, h);
      shapes.rect(x + w - 4, y, 4, h);
    } else {
      shapes.setColor(COLOR_CARD_BORDER);
      shapes.rect(x, y, w, h);
      shapes.setColor(card.pending() ? COLOR_HEALTH_BG : COLOR_CARD_BG);
      shapes.rect(x + 2, y + 2, w - 4, h - 4);
    }
    shapes.end();

    ctx.getSpriteBatch().begin();
    ctx.getFont().setColor(COLOR_ELIXIR);
    ctx.getFont().draw(ctx.getSpriteBatch(), String.valueOf(card.cost()), x + 5, y + h - 5);
    ctx.getFont().setColor(Color.WHITE);
    String name = card.name().length() > 8 ? card.name().substring(0, 8) + ".." : card.name();
    ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), name);
    ctx.getEntityNameFont()
        .draw(ctx.getSpriteBatch(), name, x + (w - ctx.getGlyphLayout().width) / 2, y + h / 2);
    ctx.getEntityNameFont().setColor(Color.LIGHT_GRAY);
    String level = card.pending() ? "played" : "Lv" + BattleSession.LEVEL;
    ctx.getEntityNameFont().draw(ctx.getSpriteBatch(), level, x + 3, y + 12);
    ctx.getEntityNameFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /** A body's fill: the side's tower colours for towers, else the side's entity colour. */
  private Color bodyColor(EntityView entity) {
    return bodyColor(entity, view);
  }

  /**
   * A body's fill in a view: the tower colours of the side's team for towers, else the team's
   * entity colour.
   */
  static Color bodyColor(EntityView entity, ViewOrientation view) {
    boolean blue = view.blue(entity.side());
    if (entity.kind() == EntityView.Kind.TOWER) {
      if (entity.king()) {
        return blue ? COLOR_BLUE_CROWN_TOWER : COLOR_RED_CROWN_TOWER;
      }
      return blue ? COLOR_BLUE_PRINCESS_TOWER : COLOR_RED_PRINCESS_TOWER;
    }
    return sideColor(entity.side(), view);
  }

  private Color sideColor(int side) {
    return sideColor(side, view);
  }

  /** A side's colour in a view: blue for the side at the bottom, red for the side at the top. */
  static Color sideColor(int side, ViewOrientation view) {
    return view.blue(side) ? COLOR_BLUE_ENTITY : COLOR_RED_ENTITY;
  }

  /** The window's pixel column of a position along the width, as the view draws it. */
  private float px(int x) {
    return view.px(x);
  }

  /** The window's pixel row of a position along the length, as the view draws it. */
  private float py(int y) {
    return view.py(y);
  }

  /**
   * Draws lines of text from the top left with no battle, each wrapped to the window's width: the
   * screen a replay shows when it is not played, with the reasons.
   */
  public void renderLines(OrthographicCamera camera, List<String> lines) {
    ctx.setProjection(camera);
    float width = camera.viewportWidth - 20;
    float y = camera.viewportHeight - 12;
    ctx.getSpriteBatch().begin();
    for (int line = 0; line < lines.size(); line++) {
      // The first line is the heading.
      ctx.getFont().setColor(line == 0 ? COLOR_HEALTH_RED : Color.WHITE);
      ctx.getGlyphLayout()
          .setText(
              ctx.getFont(), lines.get(line), ctx.getFont().getColor(), width, Align.left, true);
      ctx.getFont().draw(ctx.getSpriteBatch(), ctx.getGlyphLayout(), 10, y);
      y -= Math.max(LINE_HEIGHT, ctx.getGlyphLayout().height + 4);
    }
    ctx.getFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  public void dispose() {
    ctx.dispose();
  }
}
