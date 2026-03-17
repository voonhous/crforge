package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CIRCLE_SEGMENTS;
import static org.crforge.desktop.render.RenderConstants.COLOR_AREA_EFFECT;
import static org.crforge.desktop.render.RenderConstants.COLOR_LASER_BALL;
import static org.crforge.desktop.render.RenderConstants.COLOR_RED_ENTITY;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import java.util.Optional;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.ScaledDamageTier;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;

/**
 * Renders area effect overlays: colored zone circles with alpha fading, and laser ball (DarkMagic)
 * beam lines, pulse rings, tier labels, and scan progress text.
 */
public class AreaEffectOverlayRenderer {

  private final RenderContext ctx;

  public AreaEffectOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

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
      float lifeFraction =
          stats.getLifeDuration() > 0
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
   * Render laser ball (DarkMagic/Void) overlays: beam lines to locked targets, pulse ring on scan
   * boundaries, tier label, and scan progress text.
   */
  public void renderLaserBallOverlays(GameState state) {
    var areaEffects = state.getEntitiesOfType(AreaEffect.class);
    if (areaEffects.isEmpty()) {
      return;
    }

    // Pass 1 (Line): beam lines to targets + pulse ring
    Gdx.gl.glEnable(GL20.GL_BLEND);
    // Filled pass: target marker circles on units hit by laser
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    for (AreaEffect effect : areaEffects) {
      if (effect.isDead() || effect.getScaledDamageTiers().isEmpty()) {
        continue;
      }
      if (effect.isLaserActive()) {
        // Blink: show markers only in the first 30% of each scan cycle (flash on hit)
        float scanInterval = effect.getStats().getScanInterval();
        float progress = scanInterval > 0 ? effect.getLaserScanAccumulator() / scanInterval : 0f;
        if (progress < 0.3f) {
          float blinkAlpha = 0.8f * (1f - progress / 0.3f);
          ctx.getShapeRenderer()
              .setColor(COLOR_LASER_BALL.r, COLOR_LASER_BALL.g, COLOR_LASER_BALL.b, blinkAlpha);
          for (long targetId : effect.getLaserTargetIds()) {
            Optional<Entity> targetOpt = state.getEntityById(targetId);
            if (targetOpt.isPresent()) {
              Entity target = targetOpt.get();
              float tx = target.getPosition().getX() * TILE_PIXELS;
              float ty = target.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
              ctx.getShapeRenderer().circle(tx, ty, 4f, CIRCLE_SEGMENTS);
            }
          }
        }
      }
    }
    ctx.getShapeRenderer().end();

    // Line pass: pulse ring for laser effects
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (AreaEffect effect : areaEffects) {
      if (effect.isDead() || effect.getScaledDamageTiers().isEmpty()) {
        continue;
      }
      if (effect.isLaserActive()) {
        float x = effect.getPosition().getX() * TILE_PIXELS;
        float y = effect.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        float radius = effect.getStats().getRadius() * TILE_PIXELS;

        // Pulse ring: expands and fades between scans
        float scanInterval = effect.getStats().getScanInterval();
        if (scanInterval > 0) {
          float progress = effect.getLaserScanAccumulator() / scanInterval;
          float pulseRadius = radius * (1f + 0.3f * progress);
          float pulseAlpha = 0.6f * (1f - progress);
          ctx.getShapeRenderer()
              .setColor(COLOR_LASER_BALL.r, COLOR_LASER_BALL.g, COLOR_LASER_BALL.b, pulseAlpha);
          ctx.getShapeRenderer().circle(x, y, pulseRadius, CIRCLE_SEGMENTS);
        }
      }
    }
    ctx.getShapeRenderer().end();

    // Pass 2 (SpriteBatch): tier label + scan progress text
    ctx.getSpriteBatch().begin();

    for (AreaEffect effect : areaEffects) {
      if (effect.isDead() || effect.getScaledDamageTiers().isEmpty()) {
        continue;
      }

      float x = effect.getPosition().getX() * TILE_PIXELS;
      float y = effect.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = effect.getStats().getRadius() * TILE_PIXELS;

      // Scan progress text above the AOE circle: "N/M"
      String scanText = effect.getLaserScanCount() + "/" + effect.getTotalLaserScans();
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), scanText);
      float scanTextWidth = ctx.getGlyphLayout().width;
      ctx.getEntityNameFont()
          .draw(ctx.getSpriteBatch(), scanText, x - scanTextWidth / 2, y + radius + 14);

      // Tier label below the AOE circle
      String tierLabel;
      if (!effect.isLaserActive()) {
        tierLabel = "...";
      } else {
        tierLabel = determineTierLabel(effect);
      }
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), tierLabel);
      float tierTextWidth = ctx.getGlyphLayout().width;
      ctx.getEntityNameFont()
          .draw(ctx.getSpriteBatch(), tierLabel, x - tierTextWidth / 2, y - radius - 3);
    }

    ctx.getSpriteBatch().end();
  }

  /**
   * Determines the current tier label ("T1", "T2", "T3", etc.) for a laser ball effect based on
   * target count and scaled damage tiers. Uses the same tier selection logic as
   * AreaEffectSystem.performLaserScan().
   */
  private String determineTierLabel(AreaEffect effect) {
    List<ScaledDamageTier> tiers = effect.getScaledDamageTiers();
    int targetCount = effect.getLaserTargetIds().size();

    for (int i = 0; i < tiers.size(); i++) {
      ScaledDamageTier tier = tiers.get(i);
      if (tier.maxTargets() > 0 && targetCount <= tier.maxTargets()) {
        return "T" + (i + 1);
      }
      if (tier.maxTargets() == 0) {
        return "T" + (i + 1);
      }
    }
    return "T" + tiers.size();
  }

  /** Determine the color for an area effect based on its buff type. */
  private Color getAreaEffectColor(AreaEffectStats stats) {
    // Laser ball effects (DarkMagic) get a distinct purple color
    if (!stats.getDamageTiers().isEmpty()) {
      return COLOR_LASER_BALL;
    }
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
