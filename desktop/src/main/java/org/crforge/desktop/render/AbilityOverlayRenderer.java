package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CIRCLE_SEGMENTS;
import static org.crforge.desktop.render.RenderConstants.COLOR_CHARGE_BAR;
import static org.crforge.desktop.render.RenderConstants.COLOR_CHARGE_READY;
import static org.crforge.desktop.render.RenderConstants.COLOR_DASH_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_HOOK_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_REFLECT_AURA;
import static org.crforge.desktop.render.RenderConstants.COLOR_VARIABLE_DAMAGE_DOT;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.Optional;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Renders ability state indicators for troops with abilities: charge bars, variable damage dots,
 * dash lines, hook lines, reflect auras, and tunnel lines.
 */
public class AbilityOverlayRenderer {

  private final RenderContext ctx;

  public AbilityOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Render ability state indicators for troops with abilities:
   *
   * <ul>
   *   <li>CHARGE: bar below entity showing charge progress
   *   <li>VARIABLE_DAMAGE: small dots showing current inferno stage
   *   <li>DASH: line toward dash target while dashing
   *   <li>HOOK: line to hooked target while hooking
   *   <li>REFLECT: aura ring at reflect radius
   *   <li>TUNNEL: line to tunnel target while tunneling
   * </ul>
   */
  public void renderAbilityIndicators(GameState state) {
    // Collect troops with abilities
    boolean hasAny = false;
    for (Entity entity : state.getAliveEntities()) {
      if (entity instanceof Troop troop && troop.getAbility() != null) {
        hasAny = true;
        break;
      }
    }
    if (!hasAny) {
      return;
    }

    // Filled shapes pass (charge bars, variable damage dots)
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      if (!(entity instanceof Troop troop) || troop.getAbility() == null) {
        continue;
      }

      AbilityComponent ability = troop.getAbility();
      float x = troop.getPosition().getX() * TILE_PIXELS;
      float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float visualRadius = troop.getVisualRadius() * TILE_PIXELS;

      switch (ability.getData().type()) {
        case CHARGE -> renderChargeBar(x, y, visualRadius, ability);
        case VARIABLE_DAMAGE -> renderVariableDamageDots(x, y, visualRadius, ability);
        default -> {}
      }
    }

    ctx.getShapeRenderer().end();

    // Line shapes pass (dash lines, hook lines, reflect aura)
    ctx.getShapeRenderer().begin(ShapeType.Line);

    for (Entity entity : state.getAliveEntities()) {
      if (!(entity instanceof Troop troop) || troop.getAbility() == null) {
        continue;
      }

      AbilityComponent ability = troop.getAbility();
      float x = troop.getPosition().getX() * TILE_PIXELS;
      float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      switch (ability.getData().type()) {
        case DASH -> {
          if (ability.isDashing()) {
            renderDashLine(x, y, ability);
          }
        }
        case HOOK -> {
          if (ability.isHooking()) {
            renderHookLine(x, y, ability, state);
          }
        }
        case REFLECT -> renderReflectAura(x, y, ability);
        case TUNNEL -> {
          if (ability.isTunneling()) {
            renderTunnelLine(x, y, ability);
          }
        }
        default -> {}
      }
    }

    ctx.getShapeRenderer().end();
  }

  // ---- Private helpers for ability indicators ----

  private void renderChargeBar(float x, float y, float visualRadius, AbilityComponent ability) {
    float barWidth = Math.max(visualRadius * 2, 16);
    float barHeight = 3;
    float barY = y - visualRadius - 6;

    // Background
    ctx.getShapeRenderer().setColor(0.2f, 0.2f, 0.2f, 0.7f);
    ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth, barHeight);

    // Fill
    if (ability.isCharged()) {
      ctx.getShapeRenderer().setColor(COLOR_CHARGE_READY);
      ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth, barHeight);
    } else {
      float chargeTime = ability.getChargeTime();
      float progress = chargeTime > 0 ? Math.min(1f, ability.getChargeTimer() / chargeTime) : 0f;
      ctx.getShapeRenderer().setColor(COLOR_CHARGE_BAR);
      ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth * progress, barHeight);
    }
  }

  private void renderVariableDamageDots(
      float x, float y, float visualRadius, AbilityComponent ability) {
    int totalStages = ((VariableDamageAbility) ability.getData()).stages().size();
    int currentStage = ability.getCurrentStage();

    float dotRadius = 2f;
    float dotSpacing = 6f;
    float startX = x + visualRadius + 4;

    for (int i = 0; i < totalStages; i++) {
      float dotX = startX + i * dotSpacing;
      float dotY = y;

      if (i <= currentStage) {
        // Filled dot for active stages
        ctx.getShapeRenderer().setColor(COLOR_VARIABLE_DAMAGE_DOT);
      } else {
        // Dim dot for inactive stages
        ctx.getShapeRenderer().setColor(0.4f, 0.2f, 0.2f, 0.5f);
      }
      ctx.getShapeRenderer().circle(dotX, dotY, dotRadius);
    }
  }

  private void renderDashLine(float x, float y, AbilityComponent ability) {
    float targetX = ability.getDashTargetX() * TILE_PIXELS;
    float targetY = ability.getDashTargetY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

    ctx.getShapeRenderer().setColor(COLOR_DASH_LINE);
    ctx.getShapeRenderer().line(x, y, targetX, targetY);
  }

  private void renderHookLine(float x, float y, AbilityComponent ability, GameState state) {
    long hookedId = ability.getHookedTargetId();
    Optional<Entity> hookedTarget = state.getEntityById(hookedId);

    if (hookedTarget.isPresent() && hookedTarget.get().isAlive()) {
      Entity target = hookedTarget.get();
      float targetX = target.getPosition().getX() * TILE_PIXELS;
      float targetY = target.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      ctx.getShapeRenderer().setColor(COLOR_HOOK_LINE);
      ctx.getShapeRenderer().line(x, y, targetX, targetY);
    }
  }

  private void renderReflectAura(float x, float y, AbilityComponent ability) {
    float reflectRadius = ((ReflectAbility) ability.getData()).reflectRadius() * TILE_PIXELS;
    if (reflectRadius > 0) {
      ctx.getShapeRenderer().setColor(COLOR_REFLECT_AURA);
      ctx.getShapeRenderer().circle(x, y, reflectRadius, CIRCLE_SEGMENTS);
    }
  }

  private void renderTunnelLine(float x, float y, AbilityComponent ability) {
    // Draw a dashed-style line from current position to tunnel target
    float targetX = ability.getTunnelTargetX() * TILE_PIXELS;
    float targetY = ability.getTunnelTargetY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

    // Line to waypoint first (if using), then to final target
    if (ability.isTunnelUsingWaypoint()) {
      float waypointX = ability.getTunnelWaypointX() * TILE_PIXELS;
      float waypointY = ability.getTunnelWaypointY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      ctx.getShapeRenderer().setColor(0.8f, 0.6f, 0.2f, 0.7f);
      ctx.getShapeRenderer().line(x, y, waypointX, waypointY);
      ctx.getShapeRenderer().line(waypointX, waypointY, targetX, targetY);
    } else {
      ctx.getShapeRenderer().setColor(0.8f, 0.6f, 0.2f, 0.7f);
      ctx.getShapeRenderer().line(x, y, targetX, targetY);
    }
  }
}
