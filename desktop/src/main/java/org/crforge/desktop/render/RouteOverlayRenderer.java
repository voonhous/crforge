/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_NODE;
import static org.crforge.desktop.render.RenderConstants.COLOR_ROUTE_REFERENCE;
import static org.crforge.desktop.render.RenderConstants.unitsToPixels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Draws what a ground troop driven by the routing grid is currently doing: the cells still left on
 * its route, the position it is holding as its reference, and a one-line label with its state, how
 * many route cells are left and how far it may move this tick.
 *
 * <p>A route is stored goal first, so its <b>last</b> node is the next cell the troop walks to. The
 * polyline is therefore drawn from the troop to the last node and then backwards through the list,
 * ending at the goal. Each node is marked at its cell centre.
 *
 * <p>Every character of the battle carries its grid state, and the screen hands each troop over.
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

  /**
   * One grid-driven unit as the overlay draws it.
   *
   * @param x the unit's position along the width, in game units
   * @param y the unit's position along the length, in game units
   * @param radius the radius its label is dropped below, in game units
   * @param unit its grid state: route, reference and entity state
   * @param speed how far it may move this tick, in game units, for the label
   */
  public record Routed(int x, int y, float radius, GridUnitState unit, int speed) {}

  /** Draws each unit's route, reference marker and label the way up the view has the arena. */
  public void render(List<Routed> units, ViewOrientation view) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (Routed routed : units) {
      drawRoute(routed, view);
      drawReference(routed.unit(), view);
    }
    ctx.getShapeRenderer().end();

    ctx.getSpriteBatch().begin();
    ctx.getEntityNameFont().setColor(COLOR_ROUTE_LINE);
    for (Routed routed : units) {
      drawLabel(routed, view);
    }
    ctx.getEntityNameFont().setColor(Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /** The polyline from the troop through every remaining route cell, ending at the goal. */
  private void drawRoute(Routed routed, ViewOrientation view) {
    Route route = routed.unit().movement().getRoute();
    if (route.isEmpty()) {
      return;
    }
    int width = TileMap.standard1v1().width();
    float x = view.px(routed.x());
    float y = view.py(routed.y());

    ctx.getShapeRenderer().setColor(COLOR_ROUTE_LINE);
    for (int i = route.size() - 1; i >= 0; i--) {
      int node = route.get(i);
      float nodeX = view.px(cellCentre(node % width));
      float nodeY = view.py(cellCentre(node / width));
      ctx.getShapeRenderer().line(x, y, nodeX, nodeY);
      x = nodeX;
      y = nodeY;
    }

    ctx.getShapeRenderer().setColor(COLOR_ROUTE_NODE);
    for (int i = route.size() - 1; i >= 0; i--) {
      int node = route.get(i);
      ctx.getShapeRenderer()
          .circle(
              view.px(cellCentre(node % width)), view.py(cellCentre(node / width)), NODE_RADIUS);
    }
  }

  /** A ring on the position the troop is currently holding as its reference. */
  private void drawReference(GridUnitState unit, ViewOrientation view) {
    TargetView reference = unit.targeting().getReference();
    if (reference == null) {
      return;
    }
    ctx.getShapeRenderer().setColor(COLOR_ROUTE_REFERENCE);
    ctx.getShapeRenderer().circle(view.px(reference.x()), view.py(reference.y()), REFERENCE_RADIUS);
  }

  /** The state name, the number of route cells left and this tick's movement budget. */
  private void drawLabel(Routed routed, ViewOrientation view) {
    GridUnitState unit = routed.unit();
    String label =
        stateName(unit.entity().getState())
            + " n="
            + unit.movement().getRoute().size()
            + " v="
            + routed.speed();
    float x = view.px(routed.x());
    float y = view.py(routed.y());
    float radius = unitsToPixels(routed.radius());
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

  /** The name of an entity state, for the label and the visualizer's other state readouts. */
  public static String stateName(int state) {
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
