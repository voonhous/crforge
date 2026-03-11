package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CIRCLE_SEGMENTS;
import static org.crforge.desktop.render.RenderConstants.COLOR_AREA_EFFECT;
import static org.crforge.desktop.render.RenderConstants.COLOR_ATTACK_RANGE;
import static org.crforge.desktop.render.RenderConstants.COLOR_BLUE_GHOST;
import static org.crforge.desktop.render.RenderConstants.COLOR_CHARGE_BAR;
import static org.crforge.desktop.render.RenderConstants.COLOR_CHARGE_READY;
import static org.crforge.desktop.render.RenderConstants.COLOR_DASH_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_DEPLOY_TIMER;
import static org.crforge.desktop.render.RenderConstants.COLOR_HOOK_LINE;
import static org.crforge.desktop.render.RenderConstants.COLOR_PATH;
import static org.crforge.desktop.render.RenderConstants.COLOR_RED_ENTITY;
import static org.crforge.desktop.render.RenderConstants.COLOR_RED_GHOST;
import static org.crforge.desktop.render.RenderConstants.COLOR_REFLECT_AURA;
import static org.crforge.desktop.render.RenderConstants.COLOR_VARIABLE_DAMAGE_DOT;
import static org.crforge.desktop.render.RenderConstants.HEALTH_BAR_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.HEALTH_BAR_Y_OFFSET;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import java.util.Optional;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.DeploymentSystem.PendingDeployment;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/**
 * Renders debug overlays: targeting lines, path direction indicators, attack range circles, entity
 * name labels, area effect zones, ability state indicators, and spawner timers.
 */
public class DebugOverlayRenderer {

  private final RenderContext ctx;

  public DebugOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Draw thin red lines from each entity to its current target.
   */
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

  /**
   * Draw movement direction lines for all troops.
   */
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

  /**
   * Draw attack range circles for all entities with combat.
   */
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

  /**
   * Draw entity names above health bars.
   */
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

    float defaultGhostRadius = TILE_PIXELS * 0.8f;

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
        progress = DeploymentSystem.PLACEMENT_SYNC_DELAY > 0
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
      ctx.getEntityNameFont().draw(
          ctx.getSpriteBatch(), name, x - textWidth / 2, y + defaultGhostRadius + 10);
    }

    ctx.getSpriteBatch().end();
  }

  /**
   * Computes ghost positions for a pending deployment. Returns a list of [offsetX, offsetY, radius]
   * arrays in tile units relative to the deployment center. During sync delay, all units are shown.
   * During stagger, only not-yet-spawned units are shown.
   */
  private List<float[]> getGhostPositions(PendingDeployment pending) {
    float defaultRadius = 0.8f;
    var card = pending.getCard();

    // Single-unit or non-troop cards: single ghost at center
    if (pending.getTotalUnits() <= 1 || card.getUnitStats() == null) {
      return List.of(new float[]{0f, 0f, defaultRadius});
    }

    List<float[]> positions = new java.util.ArrayList<>();
    int primaryCount = card.getUnitCount();
    int totalUnits = pending.getTotalUnits();
    TroopStats primaryStats = card.getUnitStats();
    TroopStats secondaryStats = card.getSecondaryUnitStats();
    List<float[]> formationOffsets = card.getFormationOffsets();
    float summonRadius = card.getSummonRadius();

    // During stagger, skip already-spawned units
    int startIdx = pending.isSyncComplete() ? pending.getNextUnitIndex() : 0;

    for (int idx = startIdx; idx < totalUnits; idx++) {
      boolean isSecondary = idx >= primaryCount;
      TroopStats stats = isSecondary ? secondaryStats : primaryStats;
      if (stats == null) continue;

      float visRadius = stats.getVisualRadius() > 0 ? stats.getVisualRadius() : defaultRadius;
      float offsetX = 0f;
      float offsetY = 0f;

      if (formationOffsets != null && idx < formationOffsets.size()) {
        float[] offset = formationOffsets.get(idx);
        offsetX = offset[0];
        offsetY = offset[1];
      } else if (totalUnits > 1 && summonRadius > 0) {
        Vector2 offset = FormationLayout.calculateDeployOffset(
            idx, totalUnits, summonRadius, stats.getCollisionRadius());
        offsetX = offset.getX();
        offsetY = offset.getY();
      }

      if (pending.getTeam() == Team.RED) {
        offsetX = -offsetX;
        offsetY = -offsetY;
      }

      positions.add(new float[]{offsetX, offsetY, visRadius});
    }

    return positions;
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
   * Render ability state indicators for troops with abilities: - CHARGE: bar below entity showing
   * charge progress - VARIABLE_DAMAGE: small dots showing current inferno stage - DASH: line toward
   * dash target while dashing - HOOK: line to hooked target while hooking - REFLECT: aura ring at
   * reflect radius
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
        default -> {
        }
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
      float visualRadius = troop.getVisualRadius() * TILE_PIXELS;

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
        default -> {
        }
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
    int totalStages = ((VariableDamageAbility) ability.getData()).stages().size();
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
    float reflectRadius = ((ReflectAbility) ability.getData()).reflectRadius() * TILE_PIXELS;
    if (reflectRadius > 0) {
      ctx.getShapeRenderer().setColor(COLOR_REFLECT_AURA);
      ctx.getShapeRenderer().circle(x, y, reflectRadius, CIRCLE_SEGMENTS);
    }
  }

  /**
   * Determine the color for an area effect based on its buff type.
   */
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
