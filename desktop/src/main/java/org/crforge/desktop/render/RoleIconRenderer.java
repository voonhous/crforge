/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.unitsToPixels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitRole;

/**
 * Draws each troop's and building's role icon on its body, so units read apart at a glance without
 * their names: a sword for a melee unit, an arrow for a ranged one, a bomb for one whose hit
 * destroys it; for one that does not attack, three small figures for a spawner, an elixir drop for
 * an elixir collector, else nothing. Two small badges on the body's lower rim add a burst for a
 * unit whose hits damage an area (right) and a house for one that attacks buildings only (left).
 *
 * <p>The icons are the project's own simple shapes, picked from each unit's {@link UnitRole}, so
 * every unit gets one without any art per card. Towers keep their silhouettes and get none.
 */
final class RoleIconRenderer {

  /** Bodies smaller than this radius, in pixels, are too small to hold an icon. */
  private static final float MIN_BODY_PIXELS = 6;

  /** The largest half size of a troop's icon, in pixels, so a big body's icon stays an icon. */
  private static final float MAX_ICON_HALF = 9;

  /** The largest half size of a building's icon: its square is larger and holds a larger one. */
  private static final float MAX_BUILDING_ICON_HALF = 14;

  /** A badge's radius, in pixels. */
  private static final float BADGE_RADIUS = 4;

  private static final float ICON_R = 0.07f;
  private static final float ICON_G = 0.13f;
  private static final float ICON_B = 0.19f;

  private final RenderContext ctx;

  RoleIconRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Draws the role icon and badges of every troop and building in the frame. */
  void render(BattleFrame frame, ViewOrientation view) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    for (EntityView entity : frame.entities()) {
      if (entity.kind() != EntityView.Kind.TROOP && entity.kind() != EntityView.Kind.BUILDING) {
        continue;
      }
      float radius = unitsToPixels(entity.radius());
      if (radius < MIN_BODY_PIXELS) {
        continue;
      }
      float x = view.px(entity.x());
      float y = view.py(entity.y());
      float alpha = entity.hidden() ? 0.3f : 1f;
      UnitRole role = entity.role();
      float cap =
          entity.kind() == EntityView.Kind.BUILDING ? MAX_BUILDING_ICON_HALF : MAX_ICON_HALF;
      float half = Math.min(radius * 0.7f, cap);
      shapes.setColor(ICON_R, ICON_G, ICON_B, alpha);
      switch (role.attack()) {
        case MELEE -> sword(shapes, x, y, half);
        case RANGED -> arrow(shapes, x, y, half);
        case KAMIKAZE -> bomb(shapes, x, y, half);
        case NONE -> {
          switch (role.work()) {
            case SPAWNER -> spawner(shapes, x, y, half);
            case COLLECTOR -> elixirDrop(shapes, x, y, half, alpha);
            case NONE -> {}
          }
        }
      }
      // The badges sit on the rim, below the middle, clear of the status sparks at its sides and
      // the status badges under it.
      float rim = radius * 0.72f;
      if (role.splash()) {
        badge(shapes, x + rim, y - rim, alpha);
        burst(shapes, x + rim, y - rim, BADGE_RADIUS - 1, alpha);
      }
      if (role.buildingsOnly()) {
        badge(shapes, x - rim, y - rim, alpha);
        house(shapes, x - rim, y - rim, BADGE_RADIUS - 1, alpha);
      }
    }
    shapes.end();
  }

  /** A sword pointing up and to the right: blade, tip, cross guard and pommel. */
  private static void sword(ShapeRenderer shapes, float x, float y, float half) {
    float d = half * 0.7071f;
    float width = Math.max(1.5f, half * 0.22f);
    // The blade runs from the guard, a third of the way from the bottom left, to below the tip.
    float guardX = x - d * 0.45f;
    float guardY = y - d * 0.45f;
    float tipX = x + d;
    float tipY = y + d;
    shapes.rectLine(guardX, guardY, tipX - width * 0.6f, tipY - width * 0.6f, width);
    shapes.triangle(
        tipX,
        tipY,
        tipX - width * 1.4f,
        tipY - width * 0.2f,
        tipX - width * 0.2f,
        tipY - width * 1.4f);
    // The cross guard, across the blade.
    float guard = half * 0.38f;
    shapes.rectLine(
        guardX - guard * 0.7071f,
        guardY + guard * 0.7071f,
        guardX + guard * 0.7071f,
        guardY - guard * 0.7071f,
        width);
    // The grip and the pommel.
    shapes.rectLine(guardX, guardY, x - d, y - d, width * 0.8f);
    shapes.circle(x - d, y - d, width * 0.75f, 8);
  }

  /** An arrow pointing up and to the right: shaft, head and fletching. */
  private static void arrow(ShapeRenderer shapes, float x, float y, float half) {
    float d = half * 0.7071f;
    float width = Math.max(1.2f, half * 0.16f);
    float head = half * 0.5f;
    float tipX = x + d;
    float tipY = y + d;
    // The head's base sits a head's length back along the shaft.
    float baseX = tipX - head * 0.7071f;
    float baseY = tipY - head * 0.7071f;
    shapes.rectLine(x - d, y - d, baseX, baseY, width);
    shapes.triangle(
        tipX,
        tipY,
        baseX - head * 0.45f,
        baseY + head * 0.45f,
        baseX + head * 0.45f,
        baseY - head * 0.45f);
    // Two feathers at the tail, swept back from the shaft.
    float tailX = x - d * 0.75f;
    float tailY = y - d * 0.75f;
    float feather = half * 0.35f;
    shapes.rectLine(tailX, tailY, tailX - feather, tailY, width);
    shapes.rectLine(tailX, tailY, tailX, tailY - feather, width);
  }

  /** A round bomb with its fuse up and to the right. */
  private static void bomb(ShapeRenderer shapes, float x, float y, float half) {
    float body = half * 0.68f;
    float cx = x - half * 0.15f;
    float cy = y - half * 0.15f;
    shapes.circle(cx, cy, body, 16);
    float width = Math.max(1.2f, half * 0.16f);
    float fuseX = cx + body * 0.7071f;
    float fuseY = cy + body * 0.7071f;
    shapes.rectLine(fuseX, fuseY, fuseX + half * 0.35f, fuseY + half * 0.35f, width);
    // The spark at the fuse's end, in the warm colour of the projectiles.
    float alpha = shapes.getColor().a;
    shapes.setColor(1f, 0.78f, 0.3f, alpha);
    shapes.circle(fuseX + half * 0.42f, fuseY + half * 0.42f, Math.max(1.2f, half * 0.14f), 8);
    shapes.setColor(ICON_R, ICON_G, ICON_B, alpha);
  }

  /** Three small figures, a head over a body each, two behind and one in front: a spawner. */
  private static void spawner(ShapeRenderer shapes, float x, float y, float half) {
    float size = half * 0.42f;
    figure(shapes, x - half * 0.48f, y + half * 0.12f, size);
    figure(shapes, x + half * 0.48f, y + half * 0.12f, size);
    figure(shapes, x, y - half * 0.28f, size * 1.15f);
  }

  /** One small figure about a point: a round head over a rounded body. */
  private static void figure(ShapeRenderer shapes, float x, float y, float size) {
    shapes.circle(x, y + size * 0.55f, size * 0.42f, 10);
    shapes.arc(x, y - size * 0.75f, size * 0.8f, 0, 180, 10);
  }

  /** An elixir drop in the elixir colour: a round bottom under a pointed top. */
  private static void elixirDrop(ShapeRenderer shapes, float x, float y, float half, float alpha) {
    float r = half * 0.55f;
    float cy = y - half * 0.25f;
    Color elixir = RenderConstants.COLOR_ELIXIR;
    shapes.setColor(ICON_R, ICON_G, ICON_B, alpha);
    shapes.circle(x, cy, r + 1.2f, 16);
    shapes.triangle(x - r - 1.2f, cy + 0.2f, x + r + 1.2f, cy + 0.2f, x, y + half + 1.2f);
    shapes.setColor(elixir.r, elixir.g, elixir.b, alpha);
    shapes.circle(x, cy, r, 16);
    shapes.triangle(x - r, cy, x + r, cy, x, y + half);
    shapes.setColor(ICON_R, ICON_G, ICON_B, alpha);
  }

  /** A badge's dark disc with a light rim. */
  private static void badge(ShapeRenderer shapes, float x, float y, float alpha) {
    shapes.setColor(0.92f, 0.94f, 0.9f, alpha);
    shapes.circle(x, y, BADGE_RADIUS + 1, 12);
    shapes.setColor(ICON_R, ICON_G, ICON_B, alpha);
    shapes.circle(x, y, BADGE_RADIUS, 12);
  }

  /** A four-ray burst: hits that damage an area. */
  private static void burst(ShapeRenderer shapes, float x, float y, float r, float alpha) {
    shapes.setColor(1f, 0.78f, 0.3f, alpha);
    shapes.rectLine(x - r, y, x + r, y, 1.2f);
    shapes.rectLine(x, y - r, x, y + r, 1.2f);
    float d = r * 0.6f;
    shapes.rectLine(x - d, y - d, x + d, y + d, 1f);
    shapes.rectLine(x - d, y + d, x + d, y - d, 1f);
  }

  /** A small house: a unit that attacks buildings only. */
  private static void house(ShapeRenderer shapes, float x, float y, float r, float alpha) {
    shapes.setColor(0.92f, 0.94f, 0.9f, alpha);
    shapes.rect(x - r * 0.7f, y - r * 0.85f, r * 1.4f, r);
    shapes.triangle(x - r, y + 0.1f, x + r, y + 0.1f, x, y + r);
  }
}
