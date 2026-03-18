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

  public void render(GameState state, boolean drawHpNumbers) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      Health health = entity.getHealth();
      float healthPercent = (float) health.getCurrent() / health.getMax();

      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      float barWidth = barWidth(entity, drawHpNumbers);
      float barY = y + radius + HEALTH_BAR_Y_OFFSET;

      float barLeft = x - barWidth / 2;

      float b = HEALTH_BAR_BORDER;
      float innerWidth = barWidth - b * 2;
      float innerHeight = HEALTH_BAR_HEIGHT - b * 2;

      // Health bar: border -> background -> fill
      ctx.getShapeRenderer().setColor(COLOR_CARD_BORDER);
      ctx.getShapeRenderer().rect(barLeft, barY, barWidth, HEALTH_BAR_HEIGHT);

      ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
      ctx.getShapeRenderer().rect(barLeft + b, barY + b, innerWidth, innerHeight);

      Color healthColor =
          healthPercent > HEALTH_THRESHOLD_HIGH
              ? COLOR_HEALTH_GREEN
              : healthPercent > HEALTH_THRESHOLD_LOW ? COLOR_HEALTH_YELLOW : COLOR_HEALTH_RED;
      ctx.getShapeRenderer().setColor(healthColor);
      ctx.getShapeRenderer().rect(barLeft + b, barY + b, innerWidth * healthPercent, innerHeight);

      // Shield bar (top, same width, only shown if unit was created with a shield)
      if (health.getShieldMax() > 0) {
        float shieldBarY = barY + HEALTH_BAR_HEIGHT + 1;
        float shieldPercent = (float) health.getShield() / health.getShieldMax();

        ctx.getShapeRenderer().setColor(COLOR_CARD_BORDER);
        ctx.getShapeRenderer().rect(barLeft, shieldBarY, barWidth, HEALTH_BAR_HEIGHT);

        ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
        ctx.getShapeRenderer().rect(barLeft + b, shieldBarY + b, innerWidth, innerHeight);

        ctx.getShapeRenderer().setColor(COLOR_SHIELD);
        ctx.getShapeRenderer()
            .rect(barLeft + b, shieldBarY + b, innerWidth * shieldPercent, innerHeight);
      }
    }

    ctx.getShapeRenderer().end();
  }

  /**
   * Compute the health bar width for an entity. When HP numbers are enabled, the bar widens to fit
   * the HP text at full font size.
   */
  float barWidth(Entity entity, boolean drawHpNumbers) {
    float radius = entity.getVisualRadius() * TILE_PIXELS;
    float base = Math.max(radius * 2, HEALTH_BAR_MIN_WIDTH);

    if (!drawHpNumbers) {
      return base;
    }

    // Measure HP text at full font size and widen bar if needed
    String hpText = entity.getHealth().getCurrent() + "/" + entity.getHealth().getMax();
    ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), hpText);
    float textWidth = ctx.getGlyphLayout().width + HEALTH_BAR_BORDER * 2 + 2;

    return Math.max(base, textWidth);
  }
}
