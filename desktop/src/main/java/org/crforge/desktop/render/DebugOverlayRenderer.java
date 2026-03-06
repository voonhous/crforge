package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.Optional;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.unit.Troop;

/**
 * Renders debug overlays: targeting lines, path direction indicators,
 * attack range circles, entity name labels, area effect zones,
 * ability state indicators, and spawner timers.
 */
public class DebugOverlayRenderer {

  private final RenderContext ctx;

  public DebugOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Draw thin red lines from each entity to its current target. */
  public void renderTargetingLines(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);

    for (Entity entity : state.getAliveEntities()) {
      Combat combat = entity.getCombat();
      if (combat == null || !combat.hasTarget()) {
        continue;
      }

      Entity target = combat.getCurrentTarget();
      if (target != null && target.isAlive()) {
        float x1 = entity.getPosition().getX() * TILE_PIXELS;
        float y1 = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        float x2 = target.getPosition().getX() * TILE_PIXELS;
        float y2 = target.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

        ctx.getShapeRenderer().setColor(1f, 0f, 0f, 0.3f);
        ctx.getShapeRenderer().line(x1, y1, x2, y2);
      }
    }

    ctx.getShapeRenderer().end();
  }

  /** Draw movement direction lines for all troops. */
  public void renderPathLines(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    ctx.getShapeRenderer().setColor(COLOR_PATH);

    for (Entity entity : state.getAliveEntities()) {
      if (entity instanceof Troop troop && troop.isAlive()) {
        float x = troop.getPosition().getX() * TILE_PIXELS;
        float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        float rot = troop.getPosition().getRotation();

        float len = TILE_PIXELS * 1.5f;
        float x2 = x + (float) Math.cos(rot) * len;
        float y2 = y + (float) Math.sin(rot) * len;

        ctx.getShapeRenderer().line(x, y, x2, y2);
      }
    }

    ctx.getShapeRenderer().end();
  }

  /** Draw attack range circles for all entities with combat. */
  public void renderAttackRanges(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    ctx.getShapeRenderer().setColor(COLOR_ATTACK_RANGE);

    for (Entity entity : state.getAliveEntities()) {
      Combat combat = entity.getCombat();
      if (combat == null) {
        continue;
      }
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float attackRadius = (combat.getRange() + entity.getCollisionRadius()) * TILE_PIXELS;
      ctx.getShapeRenderer().circle(x, y, attackRadius, CIRCLE_SEGMENTS);
    }

    ctx.getShapeRenderer().end();
  }

  /** Draw entity names above health bars. */
  public void renderEntityNames(GameState state) {
    ctx.getSpriteBatch().begin();
    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      // Push name above both health bar and shield bar (if present)
      float barsHeight = HEALTH_BAR_Y_OFFSET + HEALTH_BAR_HEIGHT;
      if (entity.getHealth().getShieldMax() > 0) {
        barsHeight += 1 + HEALTH_BAR_HEIGHT;
      }
      float textY = y + radius + barsHeight + 10;

      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), entity.getName());
      float textWidth = ctx.getGlyphLayout().width;

      ctx.getEntityNameFont().draw(
          ctx.getSpriteBatch(), entity.getName(), x - textWidth / 2, textY);
    }
    ctx.getSpriteBatch().end();
  }

  // ---- New Feature Visualizations ----

  /**
   * Render area effect zones as colored circles with alpha fading based on remaining lifetime.
   * Color is determined by the effect's buff type.
   */
  public void renderAreaEffects(GameState state) {
    var areaEffects = state.getEntitiesOfType(AreaEffect.class);
    if (areaEffects.isEmpty()) {
      return;
    }

    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (AreaEffect effect : areaEffects) {
      if (effect.isDead()) {
        continue;
      }

      AreaEffectStats stats = effect.getStats();
      float x = effect.getPosition().getX() * TILE_PIXELS;
      float y = effect.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = stats.getRadius() * TILE_PIXELS;

      // Alpha fades based on remaining lifetime
      float lifeFraction = stats.getLifeDuration() > 0
          ? effect.getRemainingLifetime() / stats.getLifeDuration()
          : 1f;
      float alpha = Math.max(0.1f, lifeFraction * 0.4f);

      Color baseColor = getAreaEffectColor(stats);
      ctx.getShapeRenderer().setColor(baseColor.r, baseColor.g, baseColor.b, alpha);
      ctx.getShapeRenderer().circle(x, y, radius, CIRCLE_SEGMENTS);
    }

    ctx.getShapeRenderer().end();

    // Outline ring
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (AreaEffect effect : areaEffects) {
      if (effect.isDead()) {
        continue;
      }

      AreaEffectStats stats = effect.getStats();
      float x = effect.getPosition().getX() * TILE_PIXELS;
      float y = effect.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = stats.getRadius() * TILE_PIXELS;

      Color baseColor = getAreaEffectColor(stats);
      ctx.getShapeRenderer().setColor(baseColor.r, baseColor.g, baseColor.b, 0.6f);
      ctx.getShapeRenderer().circle(x, y, radius, CIRCLE_SEGMENTS);
    }
    ctx.getShapeRenderer().end();
  }

  /**
   * Render ability state indicators for troops with abilities:
   * - CHARGE: bar below entity showing charge progress
   * - VARIABLE_DAMAGE: small dots showing current inferno stage
   * - DASH: line toward dash target while dashing
   * - HOOK: line to hooked target while hooking
   * - REFLECT: aura ring at reflect radius
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
      AbilityType type = ability.getData().getType();
      float x = troop.getPosition().getX() * TILE_PIXELS;
      float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float visualRadius = troop.getVisualRadius() * TILE_PIXELS;

      switch (type) {
        case CHARGE -> renderChargeBar(x, y, visualRadius, ability);
        case VARIABLE_DAMAGE -> renderVariableDamageDots(x, y, visualRadius, ability);
        default -> { }
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
      AbilityType type = ability.getData().getType();
      float x = troop.getPosition().getX() * TILE_PIXELS;
      float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float visualRadius = troop.getVisualRadius() * TILE_PIXELS;

      switch (type) {
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
        default -> { }
      }
    }

    ctx.getShapeRenderer().end();
  }

  /**
   * Render spawner timer countdowns above entities with active live spawns.
   */
  public void renderSpawnerTimers(GameState state) {
    boolean hasAny = false;
    for (Entity entity : state.getAliveEntities()) {
      if (entity.getSpawner() != null && entity.getSpawner().hasLiveSpawn()) {
        hasAny = true;
        break;
      }
    }
    if (!hasAny) {
      return;
    }

    ctx.getSpriteBatch().begin();
    for (Entity entity : state.getAliveEntities()) {
      SpawnerComponent spawner = entity.getSpawner();
      if (spawner == null || !spawner.hasLiveSpawn()) {
        continue;
      }

      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      // Draw countdown text below the entity
      String timerText = String.format("%.1f", spawner.getCurrentTimer());
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), timerText);
      float textWidth = ctx.getGlyphLayout().width;

      ctx.getEntityNameFont().draw(
          ctx.getSpriteBatch(), timerText, x - textWidth / 2, y - radius - 3);
    }
    ctx.getSpriteBatch().end();
  }

  // ---- Private helpers for ability indicators ----

  private void renderChargeBar(float x, float y, float visualRadius,
                                AbilityComponent ability) {
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
      float progress = chargeTime > 0
          ? Math.min(1f, ability.getChargeTimer() / chargeTime)
          : 0f;
      ctx.getShapeRenderer().setColor(COLOR_CHARGE_BAR);
      ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth * progress, barHeight);
    }
  }

  private void renderVariableDamageDots(float x, float y, float visualRadius,
                                         AbilityComponent ability) {
    int totalStages = ability.getData().getStages().size();
    int currentStage = ability.getCurrentStage();

    float dotRadius = 2f;
    float dotSpacing = 6f;
    float startX = x + visualRadius + 4;

    for (int i = 0; i < totalStages; i++) {
      float dotX = startX + i * dotSpacing;
      float dotY = y;

      if (i < currentStage) {
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
    float reflectRadius = ability.getData().getReflectRadius() * TILE_PIXELS;
    if (reflectRadius > 0) {
      ctx.getShapeRenderer().setColor(COLOR_REFLECT_AURA);
      ctx.getShapeRenderer().circle(x, y, reflectRadius, CIRCLE_SEGMENTS);
    }
  }

  /** Determine the color for an area effect based on its buff type. */
  private Color getAreaEffectColor(AreaEffectStats stats) {
    if (stats.getBuff() != null) {
      StatusEffectType effectType = StatusEffectType.fromBuffName(stats.getBuff());
      if (effectType != null) {
        Color effectColor = StatusEffectRenderer.getEffectColor(effectType);
        if (effectColor != null) {
          return effectColor;
        }
      }
    }
    // Fallback: if the effect deals damage, use a reddish color; otherwise default
    if (stats.getDamage() > 0) {
      return COLOR_RED_ENTITY;
    }
    return COLOR_AREA_EFFECT;
  }
}
