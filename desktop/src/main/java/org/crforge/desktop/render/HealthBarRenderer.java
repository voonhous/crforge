package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.component.Health;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;

/**
 * Renders health bars (and shield bars) above entities.
 */
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

      // Background
      ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
      ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth, HEALTH_BAR_HEIGHT);

      // Health fill
      Color healthColor = healthPercent > HEALTH_THRESHOLD_HIGH ? COLOR_HEALTH_GREEN
          : healthPercent > HEALTH_THRESHOLD_LOW ? COLOR_HEALTH_YELLOW
              : COLOR_HEALTH_RED;
      ctx.getShapeRenderer().setColor(healthColor);
      ctx.getShapeRenderer().rect(
          x - barWidth / 2, barY, barWidth * healthPercent, HEALTH_BAR_HEIGHT);

      // Shield bar (golden segment above health bar)
      if (health.getShield() > 0) {
        float shieldPercent = (float) health.getShield() / health.getMax();
        float shieldBarY = barY + HEALTH_BAR_HEIGHT + 1;
        ctx.getShapeRenderer().setColor(COLOR_SHIELD);
        ctx.getShapeRenderer().rect(
            x - barWidth / 2, shieldBarY, barWidth * shieldPercent, HEALTH_BAR_HEIGHT - 1);
      }
    }

    ctx.getShapeRenderer().end();
  }
}
