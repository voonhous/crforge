package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CIRCLE_SEGMENTS;
import static org.crforge.desktop.render.RenderConstants.COLOR_AGGRO_RANGE;
import static org.crforge.desktop.render.RenderConstants.COLOR_ATTACK_RANGE;
import static org.crforge.desktop.render.RenderConstants.COLOR_HP_TEXT;
import static org.crforge.desktop.render.RenderConstants.COLOR_MINIMUM_RANGE;
import static org.crforge.desktop.render.RenderConstants.COLOR_PATH;
import static org.crforge.desktop.render.RenderConstants.HEALTH_BAR_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.HEALTH_BAR_Y_OFFSET;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.ability.HidingAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/**
 * Renders lightweight debug overlays: targeting lines, path direction indicators, attack range
 * circles, entity name labels, and spawner timers.
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

      // Draw minimum range (blind spot) circle if present
      if (combat.getMinimumRange() > 0) {
        ctx.getShapeRenderer().setColor(COLOR_MINIMUM_RANGE);
        float minRadius = (combat.getMinimumRange() + entity.getCollisionRadius()) * TILE_PIXELS;
        ctx.getShapeRenderer().circle(x, y, minRadius, CIRCLE_SEGMENTS);
        ctx.getShapeRenderer().setColor(COLOR_ATTACK_RANGE);
      }
    }

    // Draw aggro detection range for aggro-gated spawners (e.g. GoblinHut_Rework)
    ctx.getShapeRenderer().setColor(COLOR_AGGRO_RANGE);
    for (Entity entity : state.getAliveEntities()) {
      SpawnerComponent spawner = entity.getSpawner();
      if (spawner == null || !spawner.isSpawnOnAggro()) {
        continue;
      }
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float aggroRadius = spawner.getAggroDetectionRange() * TILE_PIXELS;
      ctx.getShapeRenderer().circle(x, y, aggroRadius, CIRCLE_SEGMENTS);
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

      // Prefix level like the game does (e.g. "Lvl 11 Witch"), only when scaled
      String label =
          entity.getLevel() > 1
              ? "Lvl " + entity.getLevel() + " " + entity.getName()
              : entity.getName();

      // Append hiding state for buildings with hiding ability (e.g. "Tesla [HIDDEN]")
      if (entity instanceof Building b
          && b.getAbility() != null
          && b.getAbility().getData() instanceof HidingAbility) {
        label += " [" + b.getAbility().getHidingState() + "]";
      }

      // Append clone indicator for cloned troops
      if (entity instanceof Troop troop && troop.isClone()) {
        label += " [CLONE]";
      }

      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), label);
      float textWidth = ctx.getGlyphLayout().width;

      ctx.getEntityNameFont().draw(ctx.getSpriteBatch(), label, x - textWidth / 2, textY);
    }
    ctx.getSpriteBatch().end();
  }

  /** Draw current/max HP text centered on the existing health bar for each alive entity. */
  public void renderHpNumbers(GameState state) {
    ctx.getSpriteBatch().begin();
    ctx.getEntityNameFont().setColor(COLOR_HP_TEXT);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      float barY = y + radius + HEALTH_BAR_Y_OFFSET;
      float barCenterY = barY + HEALTH_BAR_HEIGHT / 2;

      String hpText = entity.getHealth().getCurrent() + "/" + entity.getHealth().getMax();
      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), hpText);
      float tw = ctx.getGlyphLayout().width;
      float th = ctx.getGlyphLayout().height;

      ctx.getEntityNameFont().draw(ctx.getSpriteBatch(), hpText, x - tw / 2, barCenterY + th / 2);
    }

    ctx.getEntityNameFont().setColor(com.badlogic.gdx.graphics.Color.WHITE);
    ctx.getSpriteBatch().end();
  }

  /** Render spawner timer countdowns above entities with active live spawns. */
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

      ctx.getEntityNameFont()
          .draw(ctx.getSpriteBatch(), timerText, x - textWidth / 2, y - radius - 3);
    }
    ctx.getSpriteBatch().end();
  }
}
