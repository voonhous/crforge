package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Renders entity bodies: filled circles, tower boundaries, outlines, collision circles, status
 * effect rings, and invulnerability visuals.
 */
public class EntityRenderer {

  private final RenderContext ctx;

  public EntityRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Render all alive entities as filled shapes with outlines and effect rings. */
  public void render(GameState state) {
    // Pass 1: Filled shapes (enable blending so alpha-based visuals are consistent)
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float visualRadius = entity.getVisualRadius() * TILE_PIXELS;

      // Tower boundary rectangle
      if (entity.getEntityType() == EntityType.TOWER) {
        ctx.getShapeRenderer().setColor(COLOR_TOWER_BOUNDARY);
        ctx.getShapeRenderer()
            .rect(x - visualRadius, y - visualRadius, visualRadius * 2, visualRadius * 2);
      }

      // Entity body
      Color baseColor = getEntityColor(entity);
      // Pre-compute hiding state for buildings with hiding ability
      AbilityComponent.HidingState hidingState = null;
      if (entity instanceof Building building && building.getAbility() != null) {
        hidingState = building.getAbility().getHidingState();
      }

      if (entity instanceof Troop troop && troop.isInvisible()) {
        // Invisible (stealth) entities render as very faint
        ctx.getShapeRenderer().setColor(baseColor.r, baseColor.g, baseColor.b, 0.15f);
      } else if (hidingState == AbilityComponent.HidingState.HIDDEN) {
        // Hidden buildings (Tesla underground) render as faint earthy silhouette
        ctx.getShapeRenderer().setColor(COLOR_HIDDEN_BUILDING);
      } else if (hidingState == AbilityComponent.HidingState.REVEALING) {
        // Revealing buildings render semi-transparent
        ctx.getShapeRenderer().setColor(baseColor.r, baseColor.g, baseColor.b, 0.5f);
      } else if (entity.isInvulnerable()) {
        // Invulnerable entities render with reduced alpha
        ctx.getShapeRenderer().setColor(baseColor.r, baseColor.g, baseColor.b, 0.5f);
      } else {
        ctx.getShapeRenderer().setColor(baseColor);
      }
      ctx.getShapeRenderer().circle(x, y, visualRadius);

      // Air unit glow ring
      if (entity.getMovementType() == MovementType.AIR) {
        ctx.getShapeRenderer().setColor(COLOR_AIR_UNIT);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 2);
      }
    }

    ctx.getShapeRenderer().end();

    // Pass 2: Line outlines, collision circles, and status effect rings
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float visualRadius = entity.getVisualRadius() * TILE_PIXELS;

      // Visual outline (darker version of entity color)
      Color baseColor = getEntityColor(entity);
      ctx.getShapeRenderer()
          .setColor(baseColor.r * 0.5f, baseColor.g * 0.5f, baseColor.b * 0.5f, 1f);
      ctx.getShapeRenderer().circle(x, y, visualRadius);

      // Collision circle (yellow overlay)
      float collisionRadius = entity.getCollisionRadius() * TILE_PIXELS;
      ctx.getShapeRenderer().setColor(COLOR_COLLISION_CIRCLE);
      ctx.getShapeRenderer().circle(x, y, collisionRadius);

      // Status effect ring (outside visual radius)
      Color effectColor = StatusEffectRenderer.getStatusEffectColor(entity);
      if (effectColor != null) {
        ctx.getShapeRenderer().setColor(effectColor);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 3);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 5);
      }
    }

    ctx.getShapeRenderer().end();
  }

  /** Returns the fill color for an entity based on type and team. */
  static Color getEntityColor(Entity entity) {
    if (entity.getEntityType() == EntityType.TOWER) {
      Tower tower = (Tower) entity;
      if (tower.isCrownTower()) {
        return entity.getTeam() == Team.BLUE ? COLOR_BLUE_CROWN_TOWER : COLOR_RED_CROWN_TOWER;
      }
      return entity.getTeam() == Team.BLUE ? COLOR_BLUE_PRINCESS_TOWER : COLOR_RED_PRINCESS_TOWER;
    }
    return entity.getTeam() == Team.BLUE ? COLOR_BLUE_ENTITY : COLOR_RED_ENTITY;
  }
}
