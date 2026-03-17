package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CIRCLE_SEGMENTS;
import static org.crforge.desktop.render.RenderConstants.COLOR_BLUE_GHOST;
import static org.crforge.desktop.render.RenderConstants.COLOR_DEPLOY_TIMER;
import static org.crforge.desktop.render.RenderConstants.COLOR_RED_GHOST;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.DeploymentSystem.PendingDeployment;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Renders deployment-related overlays: radial countdown timers on deploying entities and ghost
 * silhouettes for pending deployments during sync delay and stagger phases.
 */
public class DeployOverlayRenderer {

  private static final float DEFAULT_GHOST_RADIUS = 0.8f;

  private final RenderContext ctx;

  public DeployOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Render radial (pie) countdown timers on deploying entities. The arc sweeps from full circle
   * down to nothing as deploy completes.
   */
  public void renderDeployTimers(GameState state) {
    boolean hasAny = false;
    for (Entity entity : state.getAliveEntities()) {
      if ((entity instanceof Troop troop && troop.isDeploying())
          || (entity instanceof Building building && building.isDeploying())) {
        hasAny = true;
        break;
      }
    }
    if (!hasAny) {
      return;
    }

    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_DEPLOY_TIMER);

    for (Entity entity : state.getAliveEntities()) {
      float deployTimer;
      float deployTime;

      if (entity instanceof Troop troop && troop.isDeploying()) {
        deployTimer = troop.getDeployTimer();
        deployTime = troop.getDeployTime();
      } else if (entity instanceof Building building && building.isDeploying()) {
        deployTimer = building.getDeployTimer();
        deployTime = building.getDeployTime();
      } else {
        continue;
      }

      float progress = deployTime > 0 ? deployTimer / deployTime : 0f;
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      // Arc starts at 90 degrees (top) and sweeps clockwise by progress * 360
      ctx.getShapeRenderer().arc(x, y, radius, 90, progress * 360, CIRCLE_SEGMENTS);
    }

    ctx.getShapeRenderer().end();
  }

  /**
   * Render ghost silhouettes for pending deployments. Shows per-unit ghost circles at formation
   * positions with radial countdown during sync delay, and only not-yet-spawned units during
   * stagger phase.
   */
  public void renderPendingDeployments(List<PendingDeployment> pendingDeployments) {
    if (pendingDeployments.isEmpty()) {
      return;
    }

    float defaultGhostRadius = TILE_PIXELS * DEFAULT_GHOST_RADIUS;

    // Pass 1: Filled ghost circles + radial countdown
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (PendingDeployment pending : pendingDeployments) {
      Color ghostColor = pending.getTeam() == Team.BLUE ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
      float centerX = pending.getX() * TILE_PIXELS;
      float centerY = pending.getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      // Compute radial countdown progress
      float progress;
      if (!pending.isSyncComplete()) {
        progress =
            DeploymentSystem.PLACEMENT_SYNC_DELAY > 0
                ? pending.getRemainingDelay() / DeploymentSystem.PLACEMENT_SYNC_DELAY
                : 0f;
      } else {
        progress = 0f;
      }

      List<float[]> ghostPositions = getGhostPositions(pending);

      for (float[] pos : ghostPositions) {
        float gx = centerX + pos[0] * TILE_PIXELS;
        float gy = centerY + pos[1] * TILE_PIXELS;
        float radius = pos[2] * TILE_PIXELS;

        // Ghost fill
        ctx.getShapeRenderer().setColor(ghostColor.r, ghostColor.g, ghostColor.b, 0.3f);
        ctx.getShapeRenderer().circle(gx, gy, radius, CIRCLE_SEGMENTS);

        // Radial countdown arc (only during sync delay)
        if (progress > 0) {
          ctx.getShapeRenderer().setColor(ghostColor.r, ghostColor.g, ghostColor.b, 0.5f);
          ctx.getShapeRenderer().arc(gx, gy, radius, 90, progress * 360, CIRCLE_SEGMENTS);
        }
      }
    }

    ctx.getShapeRenderer().end();

    // Pass 2: Outline rings
    ctx.getShapeRenderer().begin(ShapeType.Line);

    for (PendingDeployment pending : pendingDeployments) {
      Color ghostColor = pending.getTeam() == Team.BLUE ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
      float centerX = pending.getX() * TILE_PIXELS;
      float centerY = pending.getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      List<float[]> ghostPositions = getGhostPositions(pending);

      for (float[] pos : ghostPositions) {
        float gx = centerX + pos[0] * TILE_PIXELS;
        float gy = centerY + pos[1] * TILE_PIXELS;
        float radius = pos[2] * TILE_PIXELS;

        ctx.getShapeRenderer().setColor(ghostColor.r, ghostColor.g, ghostColor.b, 0.7f);
        ctx.getShapeRenderer().circle(gx, gy, radius, CIRCLE_SEGMENTS);
      }
    }

    ctx.getShapeRenderer().end();

    // Pass 3: Card name label at center position
    ctx.getSpriteBatch().begin();

    for (PendingDeployment pending : pendingDeployments) {
      float x = pending.getX() * TILE_PIXELS;
      float y = pending.getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      String name = pending.getCard().getName();
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), name);
      float textWidth = ctx.getGlyphLayout().width;
      ctx.getEntityNameFont()
          .draw(ctx.getSpriteBatch(), name, x - textWidth / 2, y + defaultGhostRadius + 10);
    }

    ctx.getSpriteBatch().end();
  }

  /**
   * Computes ghost positions for a pending deployment using the shared GhostFormation utility.
   * During sync delay, all units are shown. During stagger, only not-yet-spawned units are shown.
   */
  private List<float[]> getGhostPositions(PendingDeployment pending) {
    int startIdx = pending.isSyncComplete() ? pending.getNextUnitIndex() : 0;
    return GhostFormation.computePositions(
        pending.getCard(),
        pending.getTotalUnits(),
        startIdx,
        pending.getTeam(),
        DEFAULT_GHOST_RADIUS);
  }
}
