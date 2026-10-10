/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Renders fading circles for instantaneous AOE damage bursts (melee splash, projectile impact AOE,
 * death explosions, instant spells). The screen hands over the battle's area hits of each frame.
 */
public class AoeDamageRenderer {

  private static final float INDICATOR_DURATION = 0.5f;
  private static final float FILL_ALPHA_MAX = 0.35f;
  private static final float OUTLINE_ALPHA_MAX = 0.50f;

  private final RenderContext ctx;
  private final List<AoeIndicator> activeIndicators = new ArrayList<>();

  public AoeDamageRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Starts one indicator for an area hit.
   *
   * @param centerX the circle's centre along the width, in game units
   * @param centerY the circle's centre along the length, in game units
   * @param radius the circle's radius, in game units
   * @param blue whether the side whose hit it was is drawn blue, which colours it
   */
  public void add(float centerX, float centerY, float radius, boolean blue) {
    float worldX = unitsToPixels(centerX);
    float worldY = unitsToPixels(centerY) + BOTTOM_UI_HEIGHT;
    float worldRadius = unitsToPixels(radius);
    activeIndicators.add(new AoeIndicator(worldX, worldY, worldRadius, blue));
  }

  /**
   * Ages the indicators by the frame's time and drops the expired ones. Call once a frame, after
   * the frame's new indicators are added.
   */
  public void age() {
    float deltaTime = Gdx.graphics.getDeltaTime();

    // Age existing indicators and remove expired ones
    Iterator<AoeIndicator> it = activeIndicators.iterator();
    while (it.hasNext()) {
      AoeIndicator indicator = it.next();
      indicator.elapsed += deltaTime;
      if (indicator.elapsed >= INDICATOR_DURATION) {
        it.remove();
      }
    }
  }

  /** Render all active AOE indicators as fading team-colored circles. */
  public void render() {
    if (activeIndicators.isEmpty()) {
      return;
    }

    Gdx.gl.glEnable(GL20.GL_BLEND);
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

    // Filled circles
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    for (AoeIndicator indicator : activeIndicators) {
      float alpha = 1.0f - (indicator.elapsed / INDICATOR_DURATION);
      float fillAlpha = alpha * FILL_ALPHA_MAX;
      setSideColor(indicator.blue, fillAlpha);
      ctx.getShapeRenderer().circle(indicator.x, indicator.y, indicator.radius, CIRCLE_SEGMENTS);
    }
    ctx.getShapeRenderer().end();

    // Outlines
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (AoeIndicator indicator : activeIndicators) {
      float alpha = 1.0f - (indicator.elapsed / INDICATOR_DURATION);
      float outlineAlpha = alpha * OUTLINE_ALPHA_MAX;
      setSideColor(indicator.blue, outlineAlpha);
      ctx.getShapeRenderer().circle(indicator.x, indicator.y, indicator.radius, CIRCLE_SEGMENTS);
    }
    ctx.getShapeRenderer().end();

    Gdx.gl.glDisable(GL20.GL_BLEND);
  }

  private void setSideColor(boolean blue, float alpha) {
    if (blue) {
      ctx.getShapeRenderer().setColor(0.3f, 0.5f, 1.0f, alpha);
    } else {
      ctx.getShapeRenderer().setColor(1.0f, 0.3f, 0.3f, alpha);
    }
  }

  /** A fading AOE damage indicator. */
  private static class AoeIndicator {
    final float x;
    final float y;
    final float radius;
    final boolean blue;
    float elapsed;

    AoeIndicator(float x, float y, float radius, boolean blue) {
      this.x = x;
      this.y = y;
      this.radius = radius;
      this.blue = blue;
      this.elapsed = 0f;
    }
  }
}
