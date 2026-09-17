package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_NODE;
import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_REFERENCE;
import static org.crforge.desktop.render.RenderConstants.unitsToPixels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.component.GridUnitState;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.move.SpeedBudget;
import org.crforge.core.pathfinding.move.SpeedGlobals;
import org.crforge.core.pathfinding.move.SpeedInputs;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * Draws what a ground troop driven by the routing grid is currently doing: the cells still left on
 * its route, the position it is holding as its reference, and a one-line label with its state, how
 * many route cells are left and how far it may move this tick.
 *
 * <p>A route is stored goal first, so its <b>last</b> node is the next cell the troop walks to. The
 * polyline is therefore drawn from the troop to the last node and then backwards through the list,
 * ending at the goal. Each node is marked at its cell centre.
 *
 * <p>Troops that are not driven by the routing grid - air units, jumping, tunnelling and attached
 * ones, and everything in a match under the waypoint rules - carry no grid state and are skipped.
 */
public class RouteOverlayRenderer {

  /** Radius in pixels of the dot marking one route cell. */
  private static final float NODE_RADIUS = 2.5f;

  /** Radius in pixels of the ring marking the troop's reference position. */
  private static final float REFERENCE_RADIUS = 6f;

  /** How far below the troop the label sits, in pixels. */
  private static final float LABEL_DROP = 10f;

  private final RenderContext ctx;

  public RouteOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Draws the route, the reference marker and the label of every grid-driven troop. */
  public void render(GameEngine engine) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      GridUnitState unit = gridState(entity);
      if (unit == null) {
        continue;
      }
      drawRoute(entity, unit);
      drawReference(unit);
    }
    ctx.getShapeRenderer().end();

    ctx.getSpriteBatch().begin();
    ctx.getEntityNameFont().setColor(COLOR_ROUTE_LINE);
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      GridUnitState unit = gridState(entity);
      if (unit == null) {
        continue;
      }
      drawLabel(entity, unit);
    }
    ctx.getEntityNameFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /** The grid state of an alive ground troop, or null when the entity is not driven by the grid. */
  private static GridUnitState gridState(Entity entity) {
    if (!(entity instanceof Troop troop) || !troop.isAlive()) {
      return null;
    }
    return troop.getGridUnitState();
  }

  /** The polyline from the troop through every remaining route cell, ending at the goal. */
  private void drawRoute(Entity entity, GridUnitState unit) {
    Route route = unit.getMovement().getRoute();
    if (route.isEmpty()) {
      return;
    }
    int width = TileMap.standard1v1().width();
    float x = unitsToPixels(entity.getPosition().getX());
    float y = unitsToPixels(entity.getPosition().getY()) + BOTTOM_UI_HEIGHT;

    ctx.getShapeRenderer().setColor(COLOR_ROUTE_LINE);
    for (int i = route.size() - 1; i >= 0; i--) {
      int node = route.get(i);
      float nodeX = unitsToPixels(cellCentre(node % width));
      float nodeY = unitsToPixels(cellCentre(node / width)) + BOTTOM_UI_HEIGHT;
      ctx.getShapeRenderer().line(x, y, nodeX, nodeY);
      x = nodeX;
      y = nodeY;
    }

    ctx.getShapeRenderer().setColor(COLOR_ROUTE_NODE);
    for (int i = route.size() - 1; i >= 0; i--) {
      int node = route.get(i);
      ctx.getShapeRenderer()
          .circle(
              unitsToPixels(cellCentre(node % width)),
              unitsToPixels(cellCentre(node / width)) + BOTTOM_UI_HEIGHT,
              NODE_RADIUS);
    }
  }

  /** A ring on the position the troop is currently holding as its reference. */
  private void drawReference(GridUnitState unit) {
    TargetView reference = unit.getTargeting().getReference();
    if (reference == null) {
      return;
    }
    ctx.getShapeRenderer().setColor(COLOR_ROUTE_REFERENCE);
    ctx.getShapeRenderer()
        .circle(
            unitsToPixels(reference.x()),
            unitsToPixels(reference.y()) + BOTTOM_UI_HEIGHT,
            REFERENCE_RADIUS);
  }

  /** The state name, the number of route cells left and this tick's movement budget. */
  private void drawLabel(Entity entity, GridUnitState unit) {
    String label =
        stateName(unit.getEntity().getState())
            + " n="
            + unit.getMovement().getRoute().size()
            + " v="
            + speedBudget(unit);
    float x = unitsToPixels(entity.getPosition().getX());
    float y = unitsToPixels(entity.getPosition().getY()) + BOTTOM_UI_HEIGHT;
    float radius = unitsToPixels(entity.getVisualRadius());
    ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), label);
    ctx.getEntityNameFont()
        .draw(
            ctx.getSpriteBatch(),
            label,
            x - ctx.getGlyphLayout().width / 2,
            y - radius - LABEL_DROP);
  }

  /** Centre of a cell along one axis, in game units. */
  private static int cellCentre(int cellIndex) {
    return cellIndex * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
  }

  /**
   * How far the troop may move this tick, in game units.
   *
   * <p>The inputs are gathered the same way the movement pass gathers them, so the label shows the
   * budget the pass is about to spend rather than an approximation of it. Status effects do not
   * feed the grid speed budget yet, so the modifier list is empty here as it is there.
   */
  private static int speedBudget(GridUnitState unit) {
    GridEntity gridEntity = unit.getEntity();
    TargetingState targeting = unit.getTargeting();
    SpeedInputs inputs =
        new SpeedInputs(
            gridEntity.getFlags(),
            gridEntity.getState(),
            true,
            targeting.getDashWindupMs(),
            targeting.getAttackBlockTimerMs(),
            targeting.isSpecialLoadPending() ? 1 : 0,
            gridEntity.getBlockCountdownMs(),
            new int[0],
            true,
            unit.getMovement().getChargeProgress());
    return SpeedBudget.speedBudget(inputs, unit.getSpeedConfig(), SpeedGlobals.standard());
  }

  /** The name of an entity state, for the label. */
  private static String stateName(int state) {
    return switch (state) {
      case GridEntityState.STANDING -> "STANDING";
      case GridEntityState.MOVING -> "MOVING";
      case GridEntityState.ATTACKING -> "ATTACKING";
      case GridEntityState.DASHING -> "DASHING";
      case GridEntityState.DEPLOYING -> "DEPLOYING";
      case GridEntityState.JUMPING -> "JUMPING";
      case GridEntityState.SPAWN_PATHFIND -> "SPAWN_PATHFIND";
      case GridEntityState.INGAME_PATHFIND -> "INGAME_PATHFIND";
      case GridEntityState.CLONE_SETUP -> "CLONE_SETUP";
      case GridEntityState.MORPHING -> "MORPHING";
      case GridEntityState.CASTING -> "CASTING";
      case GridEntityState.WAITING_TO_DEPLOY -> "WAITING_TO_DEPLOY";
      case GridEntityState.FOLLOWING_REMOVED -> "FOLLOWING_REMOVED";
      case GridEntityState.FOLLOWING_REMOVED_BUILDING -> "FOLLOWING_REMOVED_BUILDING";
      case GridEntityState.COMPONENTS_DISABLED -> "COMPONENTS_DISABLED";
      case GridEntityState.ROUTE_FOLLOWING_ALTERNATE -> "ROUTE_FOLLOWING_ALTERNATE";
      case GridEntityState.ABILITY_FOLLOW_UP -> "ABILITY_FOLLOW_UP";
      default -> "STATE_" + state;
    };
  }
}
