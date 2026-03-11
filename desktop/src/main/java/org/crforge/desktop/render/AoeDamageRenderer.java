package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.crforge.core.engine.AoeDamageEvent;
import org.crforge.core.engine.GameState;
import org.crforge.core.player.Team;

/**
 * Renders fading circles for instantaneous AOE damage bursts (melee splash, projectile impact AOE,
 * death explosions, instant spells). Events are recorded by CombatSystem.applySpellDamage() and
 * consumed here each frame.
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
   * Consume AOE damage events from the game state and age existing indicators. Must be called every
   * frame (even when rendering is toggled off) to keep indicators current.
   */
  public void update(GameState state) {
    float deltaTime = Gdx.graphics.getDeltaTime();

    // Consume new events
    for (AoeDamageEvent event : state.getAoeDamageEvents()) {
      float worldX = event.centerX() * TILE_PIXELS;
      float worldY = event.centerY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float worldRadius = event.radius() * TILE_PIXELS;
      activeIndicators.add(new AoeIndicator(worldX, worldY, worldRadius, event.sourceTeam()));
    }

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
      setTeamColor(indicator.sourceTeam, fillAlpha);
      ctx.getShapeRenderer().circle(indicator.x, indicator.y, indicator.radius, CIRCLE_SEGMENTS);
    }
    ctx.getShapeRenderer().end();

    // Outlines
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (AoeIndicator indicator : activeIndicators) {
      float alpha = 1.0f - (indicator.elapsed / INDICATOR_DURATION);
      float outlineAlpha = alpha * OUTLINE_ALPHA_MAX;
      setTeamColor(indicator.sourceTeam, outlineAlpha);
      ctx.getShapeRenderer().circle(indicator.x, indicator.y, indicator.radius, CIRCLE_SEGMENTS);
    }
    ctx.getShapeRenderer().end();

    Gdx.gl.glDisable(GL20.GL_BLEND);
  }

  private void setTeamColor(Team team, float alpha) {
    if (team == Team.BLUE) {
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
    final Team sourceTeam;
    float elapsed;

    AoeIndicator(float x, float y, float radius, Team sourceTeam) {
      this.x = x;
      this.y = y;
      this.radius = radius;
      this.sourceTeam = sourceTeam;
      this.elapsed = 0f;
    }
  }
}
