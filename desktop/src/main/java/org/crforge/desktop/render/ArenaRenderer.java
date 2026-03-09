package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.match.Match;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

import java.util.List;

/**
 * Renders the arena: tiles, grid lines, hover highlights, and ghost card previews.
 */
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
        ctx.getShapeRenderer().rect(
            x * TILE_PIXELS,
            y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
            TILE_PIXELS,
            TILE_PIXELS
        );
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
      ctx.getShapeRenderer().line(
          x * TILE_PIXELS, startY,
          x * TILE_PIXELS, startY + height
      );
    }

    for (int y = 0; y <= Arena.HEIGHT; y++) {
      ctx.getShapeRenderer().line(
          0, startY + y * TILE_PIXELS,
          width, startY + y * TILE_PIXELS
      );
    }

    ctx.getShapeRenderer().end();
  }

  /**
   * Render hover highlight and ghost card preview at the hovered tile.
   *
   * @param selectedHandIndex the currently selected hand slot (-1 if none)
   */
  public void renderHover(int x, int y, Card selectedCard, Player player,
                           Match match, int selectedHandIndex) {
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
      ctx.getShapeRenderer().rect(
          x * TILE_PIXELS,
          y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
          TILE_PIXELS,
          TILE_PIXELS
      );
    } else if (selectedCard != null) {
      renderGhostCard(x, y, selectedCard, player.getTeam());

      // Subtle white overlay to indicate the tile is being targeted
      ctx.getShapeRenderer().setColor(0.5f, 0.5f, 0.5f, 0.2f);
      ctx.getShapeRenderer().rect(
          x * TILE_PIXELS,
          y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
          TILE_PIXELS,
          TILE_PIXELS
      );
    }

    ctx.getShapeRenderer().end();
  }

  private void renderGhostCard(int hoverX, int hoverY, Card card, Team team) {
    Color ghostColor = team == Team.BLUE ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
    ctx.getShapeRenderer().setColor(ghostColor);

    float centerX = (hoverX + 0.5f) * TILE_PIXELS;
    float centerY = (hoverY + 0.5f) * TILE_PIXELS + BOTTOM_UI_HEIGHT;

    if (card.getType() == CardType.SPELL) {
      // Area-effect spells (Poison, Freeze, Zap) use areaEffect radius;
      // projectile-based spells (Fireball, Arrows) use projectile radius
      float radius;
      if (card.getAreaEffect() != null) {
        radius = card.getAreaEffect().getRadius() * TILE_PIXELS;
      } else if (card.getProjectile() != null) {
        radius = card.getProjectile().getRadius() * TILE_PIXELS;
      } else {
        radius = TILE_PIXELS; // fallback: 1 tile
      }
      ctx.getShapeRenderer().setColor(COLOR_SPELL_RADIUS);
      ctx.getShapeRenderer().circle(centerX, centerY, radius, CIRCLE_SEGMENTS);
    } else {
      TroopStats unitStats = card.getUnitStats();
      if (unitStats != null) {
        List<float[]> formationOffsets = card.getFormationOffsets();
        int primaryCount = card.getUnitCount();
        int totalUnits = card.getTotalDeployCount();
        float summonRadius = card.getSummonRadius();

        for (int idx = 0; idx < totalUnits; idx++) {
          boolean isSecondary = idx >= primaryCount;
          TroopStats stats = isSecondary ? card.getSecondaryUnitStats() : unitStats;
          if (stats == null) continue;
          float visRadius = stats.getVisualRadius() * TILE_PIXELS;

          float offsetX = 0f;
          float offsetY = 0f;

          if (formationOffsets != null && idx < formationOffsets.size()) {
            float[] offset = formationOffsets.get(idx);
            offsetX = offset[0];
            offsetY = offset[1];
          } else if (totalUnits > 1 && summonRadius > 0) {
            org.crforge.core.util.Vector2 offset =
                org.crforge.core.util.FormationLayout.calculateDeployOffset(
                    idx, totalUnits, summonRadius, stats.getCollisionRadius());
            offsetX = offset.getX();
            offsetY = offset.getY();
          }

          if (team == Team.RED) {
            offsetX = -offsetX;
            offsetY = -offsetY;
          }

          float ghostX = centerX + (offsetX * TILE_PIXELS);
          float ghostY = centerY + (offsetY * TILE_PIXELS);

          ctx.getShapeRenderer().setColor(ghostColor);
          ctx.getShapeRenderer().circle(ghostX, ghostY, visRadius);
        }
      }
    }
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
