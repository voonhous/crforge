package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.match.Match;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/** Renders the arena: tiles, grid lines, hover highlights, and ghost card previews. */
public class ArenaRenderer {

  private final RenderContext ctx;

  public ArenaRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Render filled arena tiles. */
  public void renderTiles(Arena arena) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (int x = 0; x < Arena.WIDTH; x++) {
      for (int y = 0; y < Arena.HEIGHT; y++) {
        TileType type = arena.getTile(x, y).type();
        Color color = getTileColor(type);
        ctx.getShapeRenderer().setColor(color);
        ctx.getShapeRenderer()
            .rect(x * TILE_PIXELS, y * TILE_PIXELS + BOTTOM_UI_HEIGHT, TILE_PIXELS, TILE_PIXELS);
      }
    }

    ctx.getShapeRenderer().end();
  }

  /** Render grid overlay lines. */
  public void renderGrid(Arena arena) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    ctx.getShapeRenderer().setColor(COLOR_GRID);

    float width = Arena.WIDTH * TILE_PIXELS;
    float height = Arena.HEIGHT * TILE_PIXELS;
    float startY = BOTTOM_UI_HEIGHT;

    for (int x = 0; x <= Arena.WIDTH; x++) {
      ctx.getShapeRenderer().line(x * TILE_PIXELS, startY, x * TILE_PIXELS, startY + height);
    }

    for (int y = 0; y <= Arena.HEIGHT; y++) {
      ctx.getShapeRenderer().line(0, startY + y * TILE_PIXELS, width, startY + y * TILE_PIXELS);
    }

    ctx.getShapeRenderer().end();
  }

  /**
   * Render hover highlight and ghost card preview at the hovered tile.
   *
   * @param selectedHandIndex the currently selected hand slot (-1 if none)
   */
  public void renderHover(
      int x, int y, Card selectedCard, Player player, Match match, int selectedHandIndex) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    // Validate placement
    boolean isValid = true;
    if (player != null && selectedCard != null) {
      PlayerActionDTO action = PlayerActionDTO.play(selectedHandIndex, x + 0.5f, y + 0.5f);
      isValid = match.validateAction(player, action);
    }

    if (!isValid) {
      ctx.getShapeRenderer().setColor(COLOR_HOVER_INVALID);
      ctx.getShapeRenderer()
          .rect(x * TILE_PIXELS, y * TILE_PIXELS + BOTTOM_UI_HEIGHT, TILE_PIXELS, TILE_PIXELS);
    } else if (selectedCard != null) {
      renderGhostCard(x, y, selectedCard, player.getTeam());

      // Subtle white overlay to indicate the tile is being targeted
      ctx.getShapeRenderer().setColor(0.5f, 0.5f, 0.5f, 0.2f);
      ctx.getShapeRenderer()
          .rect(x * TILE_PIXELS, y * TILE_PIXELS + BOTTOM_UI_HEIGHT, TILE_PIXELS, TILE_PIXELS);
    }

    ctx.getShapeRenderer().end();
  }

  private void renderGhostCard(int hoverX, int hoverY, Card card, Team team) {
    Color ghostColor = team == Team.BLUE ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
    ctx.getShapeRenderer().setColor(ghostColor);

    float centerX = (hoverX + 0.5f) * TILE_PIXELS;
    float centerY = (hoverY + 0.5f) * TILE_PIXELS + BOTTOM_UI_HEIGHT;

    if (card.getType() == CardType.SPELL) {
      if (card.isSpellAsDeploy()
          && card.getProjectile() != null
          && card.getProjectile().getSpawnProjectile() != null) {
        // Rolling projectile spells (e.g. The Log): draw a rectangular path preview
        ProjectileStats rolling = card.getProjectile().getSpawnProjectile();
        float halfWidth = rolling.getProjectileRadius() * TILE_PIXELS;
        float length = rolling.getProjectileRange() * TILE_PIXELS;
        float rectX = centerX - halfWidth;
        float rectY = (team == Team.BLUE) ? centerY : centerY - length;

        ctx.getShapeRenderer().setColor(COLOR_SPELL_RADIUS);
        ctx.getShapeRenderer().rect(rectX, rectY, halfWidth * 2, length);
      } else {
        // Area-effect spells use areaEffect radius; spell-level radius (e.g. Arrows 3.5 tiles)
        // takes priority over projectile AOE radius; projectile radius is the final fallback
        float radius;
        if (card.getAreaEffect() != null) {
          radius = card.getAreaEffect().getRadius() * TILE_PIXELS;
        } else if (card.getSpellRadius() > 0) {
          radius = card.getSpellRadius() * TILE_PIXELS;
        } else if (card.getProjectile() != null) {
          radius = card.getProjectile().getRadius() * TILE_PIXELS;
        } else {
          radius = TILE_PIXELS; // fallback: 1 tile
        }
        ctx.getShapeRenderer().setColor(COLOR_SPELL_RADIUS);
        ctx.getShapeRenderer().circle(centerX, centerY, radius, CIRCLE_SEGMENTS);
      }
    } else if (card.getUnitStats() != null) {
      int totalUnits = card.getTotalDeployCount();
      List<float[]> positions =
          GhostFormation.computePositions(
              card, totalUnits, 0, team, card.getUnitStats().getVisualRadius());

      for (float[] pos : positions) {
        float ghostX = centerX + pos[0] * TILE_PIXELS;
        float ghostY = centerY + pos[1] * TILE_PIXELS;
        float visRadius = pos[2] * TILE_PIXELS;

        ctx.getShapeRenderer().setColor(ghostColor);
        ctx.getShapeRenderer().circle(ghostX, ghostY, visRadius);
      }
    }
  }

  /**
   * Render attack range and minimum range circles at the hover position for the selected card. This
   * helps with strategic placement -- especially for buildings like Mortar that have a blind spot.
   * Spells are skipped since they already show their AOE radius via the ghost card preview.
   */
  public void renderHoverRanges(int x, int y, Card selectedCard) {
    if (selectedCard == null || selectedCard.getType() == CardType.SPELL) {
      return;
    }

    TroopStats unitStats = selectedCard.getUnitStats();
    if (unitStats == null || unitStats.getRange() <= 0) {
      return;
    }

    float centerX = (x + 0.5f) * TILE_PIXELS;
    float centerY = (y + 0.5f) * TILE_PIXELS + BOTTOM_UI_HEIGHT;
    float collisionRadius = unitStats.getCollisionRadius();

    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);

    // Outer attack range circle
    float attackRange = (unitStats.getRange() + collisionRadius) * TILE_PIXELS;
    ctx.getShapeRenderer().setColor(COLOR_HOVER_ATTACK_RANGE);
    ctx.getShapeRenderer().circle(centerX, centerY, attackRange, CIRCLE_SEGMENTS);

    // Inner minimum range circle (blind spot)
    if (unitStats.getMinimumRange() > 0) {
      float minRange = (unitStats.getMinimumRange() + collisionRadius) * TILE_PIXELS;
      ctx.getShapeRenderer().setColor(COLOR_MINIMUM_RANGE);
      ctx.getShapeRenderer().circle(centerX, centerY, minRange, CIRCLE_SEGMENTS);
    }

    ctx.getShapeRenderer().end();
  }

  private Color getTileColor(TileType type) {
    return switch (type) {
      case BLUE_ZONE -> COLOR_BLUE_ZONE;
      case RED_ZONE -> COLOR_RED_ZONE;
      case RIVER -> COLOR_RIVER;
      case BRIDGE -> COLOR_BRIDGE;
      case GROUND -> COLOR_GROUND;
      case BANNED -> COLOR_BANNED;
      case TOWER -> COLOR_TOWER_TILE;
    };
  }
}
