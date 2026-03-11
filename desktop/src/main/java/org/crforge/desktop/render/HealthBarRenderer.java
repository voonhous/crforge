package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.component.Health;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;

/** Renders health bars (and shield bars) above entities. */
public class HealthBarRenderer {

  private final RenderContext ctx;

  public HealthBarRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  public void render(GameState state) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      Health health = entity.getHealth();
      float healthPercent = (float) health.getCurrent() / health.getMax();

      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      float barWidth = Math.max(radius * 2, HEALTH_BAR_MIN_WIDTH);
      float barY = y + radius + HEALTH_BAR_Y_OFFSET;

      float barLeft = x - barWidth / 2;

      // Health bar (bottom)
      ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
      ctx.getShapeRenderer().rect(barLeft, barY, barWidth, HEALTH_BAR_HEIGHT);

      Color healthColor =
          healthPercent > HEALTH_THRESHOLD_HIGH
              ? COLOR_HEALTH_GREEN
              : healthPercent > HEALTH_THRESHOLD_LOW ? COLOR_HEALTH_YELLOW : COLOR_HEALTH_RED;
      ctx.getShapeRenderer().setColor(healthColor);
      ctx.getShapeRenderer().rect(barLeft, barY, barWidth * healthPercent, HEALTH_BAR_HEIGHT);

      // Shield bar (top, same width, only shown if unit was created with a shield)
      if (health.getShieldMax() > 0) {
        float shieldBarY = barY + HEALTH_BAR_HEIGHT + 1;
        float shieldPercent = (float) health.getShield() / health.getShieldMax();

        ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
        ctx.getShapeRenderer().rect(barLeft, shieldBarY, barWidth, HEALTH_BAR_HEIGHT);

        ctx.getShapeRenderer().setColor(COLOR_SHIELD);
        ctx.getShapeRenderer()
            .rect(barLeft, shieldBarY, barWidth * shieldPercent, HEALTH_BAR_HEIGHT);
      }
    }

    ctx.getShapeRenderer().end();
  }
}
