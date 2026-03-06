package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Match;
import org.crforge.core.player.Hand;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Debug renderer using LibGDX ShapeRenderer for minimal visualization. Renders arena tiles,
 * entities as shapes, health bars, and projectiles.
 */
public class DebugRenderer {

  private final RenderContext ctx;

  @Getter
  private boolean drawPaths = false;
  @Getter
  private boolean drawRanges = false;
  @Setter
  private int selectedHandIndex = -1;
  @Setter
  private Team selectedTeam = null;

  public DebugRenderer() {
    this.ctx = new RenderContext();
  }

  public void toggleDrawPaths() {
    drawPaths = !drawPaths;
  }

  public void toggleDrawRanges() {
    drawRanges = !drawRanges;
  }

  public void render(GameEngine engine, OrthographicCamera camera, int hoverX, int hoverY) {
    GameState state = engine.getGameState();
    Arena arena = engine.getArena();
    Match match = engine.getMatch();

    ctx.setProjection(camera);

    // 0. Render UI Backgrounds
    renderUIBackgrounds(camera);

    // 1. Render arena tiles (filled)
    renderArenaTiles(arena);

    // 2. Render grid lines
    renderGrid(arena);

    // 3. Render hover highlight
    if (hoverX >= 0 && hoverX < Arena.WIDTH && hoverY >= 0 && hoverY < Arena.HEIGHT) {
      Player player = getPlayer(match);
      Card selectedCard = getSelectedCard(player);
      renderHover(hoverX, hoverY, selectedCard, player, match);
    }

    // 4. Render entities
    renderEntities(state);

    // 5. Render projectiles
    renderProjectiles(state);

    // 6. Render health bars
    renderHealthBars(state);

    // 7. Render targeting lines (debug)
    renderTargetingLines(state);

    // 8. Render path lines (debug)
    if (drawPaths) {
      renderPathLines(state);
    }

    // 9. Render attack range circles (debug)
    if (drawRanges) {
      renderAttackRanges(state);
    }

    // 10. Render Entity Names
    renderEntityNames(state);

    // 11. Render UI (hands, elixir, timer)
    renderUI(engine, match, camera);
  }

  private Player getPlayer(Match match) {
    if (match == null || selectedTeam == null) {
      return null;
    }
    for (Player p : match.getAllPlayers()) {
      if (p.getTeam() == selectedTeam) {
        return p;
      }
    }
    return null;
  }

  private Card getSelectedCard(Player player) {
    if (player == null || selectedHandIndex == -1) {
      return null;
    }
    return player.getHand().getCard(selectedHandIndex);
  }

  private void renderUIBackgrounds(OrthographicCamera camera) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_UI_BG);

    // Top UI
    ctx.getShapeRenderer().rect(
        0,
        camera.viewportHeight - TOP_UI_HEIGHT,
        camera.viewportWidth,
        TOP_UI_HEIGHT
    );

    // Bottom UI
    ctx.getShapeRenderer().rect(
        0,
        0,
        camera.viewportWidth,
        BOTTOM_UI_HEIGHT
    );

    ctx.getShapeRenderer().end();
  }

  private void renderArenaTiles(Arena arena) {
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

  private void renderGrid(Arena arena) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    ctx.getShapeRenderer().setColor(COLOR_GRID);

    float width = Arena.WIDTH * TILE_PIXELS;
    float height = Arena.HEIGHT * TILE_PIXELS;
    float startY = BOTTOM_UI_HEIGHT;

    // Vertical lines
    for (int x = 0; x <= Arena.WIDTH; x++) {
      ctx.getShapeRenderer().line(
          x * TILE_PIXELS,
          startY,
          x * TILE_PIXELS,
          startY + height
      );
    }

    // Horizontal lines
    for (int y = 0; y <= Arena.HEIGHT; y++) {
      ctx.getShapeRenderer().line(
          0,
          startY + y * TILE_PIXELS,
          width,
          startY + y * TILE_PIXELS
      );
    }

    ctx.getShapeRenderer().end();
  }

  private void renderHover(int x, int y, Card selectedCard, Player player, Match match) {
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
      float radius = card.getProjectile().getRadius() * TILE_PIXELS;
      ctx.getShapeRenderer().setColor(COLOR_SPELL_RADIUS);
      ctx.getShapeRenderer().circle(centerX, centerY, radius, CIRCLE_SEGMENTS);
    } else {
      // Render troops/buildings
      if (card.getTroops() != null) {
        for (TroopStats stats : card.getTroops()) {
          float offsetX = stats.getOffsetX();
          float offsetY = stats.getOffsetY();

          if (team == Team.RED) {
            offsetX = -offsetX;
            offsetY = -offsetY;
          }

          float ghostX = centerX + (offsetX * TILE_PIXELS);
          float ghostY = centerY + (offsetY * TILE_PIXELS);
          float radius = stats.getVisualRadius() * TILE_PIXELS;

          ctx.getShapeRenderer().setColor(ghostColor);
          ctx.getShapeRenderer().circle(ghostX, ghostY, radius);
        }
      }
    }
  }

  private void renderEntities(GameState state) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      float visualRadius = entity.getVisualRadius() * TILE_PIXELS;

      if (entity.getEntityType() == EntityType.TOWER) {
        ctx.getShapeRenderer().setColor(COLOR_TOWER_BOUNDARY);
        ctx.getShapeRenderer().rect(
            x - visualRadius, y - visualRadius, visualRadius * 2, visualRadius * 2);
      }

      Color baseColor = getEntityColor(entity);
      ctx.getShapeRenderer().setColor(baseColor);
      ctx.getShapeRenderer().circle(x, y, visualRadius);

      if (entity.getMovementType() == MovementType.AIR) {
        ctx.getShapeRenderer().setColor(COLOR_AIR_UNIT);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 2);
      }
    }

    ctx.getShapeRenderer().end();

    // Draw outlines, collision circles, and status effect rings
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);
    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      // 1. Visual Outline
      float visualRadius = entity.getVisualRadius() * TILE_PIXELS;
      Color baseColor = getEntityColor(entity);
      ctx.getShapeRenderer().setColor(
          baseColor.r * 0.5f, baseColor.g * 0.5f, baseColor.b * 0.5f, 1f);
      ctx.getShapeRenderer().circle(x, y, visualRadius);

      // 2. Collision Circle (Yellow overlay)
      float collisionRadius = entity.getCollisionRadius() * TILE_PIXELS;
      ctx.getShapeRenderer().setColor(COLOR_COLLISION_CIRCLE);
      ctx.getShapeRenderer().circle(x, y, collisionRadius);

      // 3. Status effect ring (outside visual radius)
      Color effectColor = getStatusEffectColor(entity);
      if (effectColor != null) {
        ctx.getShapeRenderer().setColor(effectColor);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 3);
        ctx.getShapeRenderer().circle(x, y, visualRadius + 5);
      }
    }
    ctx.getShapeRenderer().end();
  }

  private Color getStatusEffectColor(Entity entity) {
    // Priority: FREEZE > STUN > SLOW > RAGE
    boolean hasFreezeOrStun = false;
    boolean hasSlow = false;
    boolean hasRage = false;
    for (AppliedEffect effect : entity.getAppliedEffects()) {
      switch (effect.getType()) {
        case FREEZE -> { return COLOR_EFFECT_FREEZE; }
        case STUN -> hasFreezeOrStun = true;
        case SLOW -> hasSlow = true;
        case RAGE -> hasRage = true;
        default -> { }
      }
    }
    if (hasFreezeOrStun) return COLOR_EFFECT_STUN;
    if (hasSlow) return COLOR_EFFECT_SLOW;
    if (hasRage) return COLOR_EFFECT_RAGE;
    return null;
  }

  private void renderAttackRanges(GameState state) {
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

  private void renderEntityNames(GameState state) {
    ctx.getSpriteBatch().begin();
    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      // Draw name slightly above the health bar
      float textY = y + radius + 15;

      ctx.getGlyphLayout().setText(ctx.getEntityNameFont(), entity.getName());
      float textWidth = ctx.getGlyphLayout().width;

      ctx.getEntityNameFont().draw(
          ctx.getSpriteBatch(), entity.getName(), x - textWidth / 2, textY);
    }
    ctx.getSpriteBatch().end();
  }

  private Color getEntityColor(Entity entity) {
    if (entity.getEntityType() == EntityType.TOWER) {
      Tower tower = (Tower) entity;
      if (tower.isCrownTower()) {
        return entity.getTeam() == Team.BLUE
            ? COLOR_BLUE_CROWN_TOWER
            : COLOR_RED_CROWN_TOWER;
      }
      return entity.getTeam() == Team.BLUE
          ? COLOR_BLUE_PRINCESS_TOWER
          : COLOR_RED_PRINCESS_TOWER;
    }
    return entity.getTeam() == Team.BLUE ? COLOR_BLUE_ENTITY : COLOR_RED_ENTITY;
  }

  private void renderProjectiles(GameState state) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_PROJECTILE);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive()) {
        continue;
      }

      float x = projectile.getPosition().getX() * TILE_PIXELS;
      float y = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      ctx.getShapeRenderer().circle(x, y, PROJECTILE_RADIUS);
    }

    ctx.getShapeRenderer().end();
  }

  private void renderHealthBars(GameState state) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      Health health = entity.getHealth();
      float healthPercent = (float) health.getCurrent() / health.getMax();

      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getVisualRadius() * TILE_PIXELS;

      float barWidth = Math.max(radius * 2, HEALTH_BAR_MIN_WIDTH);
      float barY = y + radius + HEALTH_BAR_Y_OFFSET;

      // Background
      ctx.getShapeRenderer().setColor(COLOR_HEALTH_BG);
      ctx.getShapeRenderer().rect(x - barWidth / 2, barY, barWidth, HEALTH_BAR_HEIGHT);

      // Health fill
      Color healthColor = healthPercent > HEALTH_THRESHOLD_HIGH ? COLOR_HEALTH_GREEN
          : healthPercent > HEALTH_THRESHOLD_LOW ? COLOR_HEALTH_YELLOW
              : COLOR_HEALTH_RED;
      ctx.getShapeRenderer().setColor(healthColor);
      ctx.getShapeRenderer().rect(
          x - barWidth / 2, barY, barWidth * healthPercent, HEALTH_BAR_HEIGHT);
    }

    ctx.getShapeRenderer().end();
  }

  private void renderTargetingLines(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);

    for (Entity entity : state.getAliveEntities()) {
      Entity target = null;
      Combat combat = entity.getCombat();
      if (combat != null && combat.hasTarget()) {
        target = combat.getCurrentTarget();
      }

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

  private void renderPathLines(GameState state) {
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

  private void renderUI(GameEngine engine, Match match, OrthographicCamera camera) {
    float screenWidth = camera.viewportWidth;
    float screenHeight = camera.viewportHeight;

    // Game timer
    float gameTime = engine.getGameTimeSeconds();
    int minutes = (int) (gameTime / 60);
    int seconds = (int) (gameTime % 60);
    String timeText = String.format("%d:%02d", minutes, seconds);
    if (engine.isOvertime()) {
      timeText = "OT " + timeText;
    }

    // Render Hands and Elixir
    if (match != null) {
      if (!match.getBluePlayers().isEmpty()) {
        Player blue = match.getBluePlayers().get(0);
        renderPlayerHUD(blue, false, 0, screenWidth);
      }
      if (!match.getRedPlayers().isEmpty()) {
        Player red = match.getRedPlayers().get(0);
        renderPlayerHUD(red, true, screenHeight - TOP_UI_HEIGHT, screenWidth);
      }
    }

    // Screen Center Text (Timer, State)
    ctx.getSpriteBatch().begin();
    ctx.getFont().getData().setScale(1.2f);
    ctx.getGlyphLayout().setText(ctx.getFont(), timeText);
    ctx.getFont().draw(ctx.getSpriteBatch(), timeText,
        (screenWidth - ctx.getGlyphLayout().width) / 2, screenHeight - 10);

    ctx.getFont().getData().setScale(1.0f);
    String entityCount = "Entities: " + engine.getGameState().getAliveEntities().size();
    ctx.getFont().draw(ctx.getSpriteBatch(), entityCount, 10, screenHeight / 2 + 20);

    if (engine.getGameState().isGameOver()) {
      Team winner = engine.getGameState().getWinner();
      String winText = winner != null ? winner + " WINS!" : "DRAW!";
      ctx.getFont().getData().setScale(2.0f);
      ctx.getGlyphLayout().setText(ctx.getFont(), winText);
      ctx.getFont().draw(ctx.getSpriteBatch(), winText,
          (screenWidth - ctx.getGlyphLayout().width) / 2, screenHeight / 2);
      ctx.getFont().getData().setScale(1.0f);
    }

    // Debug overlay status
    int overlayLine = 0;
    if (drawPaths) {
      ctx.getFont().draw(ctx.getSpriteBatch(), "Paths: ON",
          screenWidth - 110, screenHeight / 2 + 20 + (overlayLine++ * 16));
    }
    if (drawRanges) {
      ctx.getFont().draw(ctx.getSpriteBatch(), "Ranges: ON",
          screenWidth - 110, screenHeight / 2 + 20 + (overlayLine++ * 16));
    }

    ctx.getSpriteBatch().end();
  }

  private void renderPlayerHUD(Player player, boolean isTop, float yPos, float width) {
    float screenHeight = yPos + (isTop ? TOP_UI_HEIGHT : BOTTOM_UI_HEIGHT);
    float cy = CardLayout.cardY(isTop, screenHeight);

    // 1. Draw Elixir Bar
    float elixir = player.getElixir().getCurrent();
    float barX = (width - ELIXIR_BAR_WIDTH) / 2;
    float barY = isTop ? yPos + 10 : yPos + 115;

    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_ELIXIR_BG);
    ctx.getShapeRenderer().rect(barX, barY, ELIXIR_BAR_WIDTH, ELIXIR_BAR_HEIGHT);
    ctx.getShapeRenderer().setColor(COLOR_ELIXIR);
    ctx.getShapeRenderer().rect(
        barX, barY, ELIXIR_BAR_WIDTH * (elixir / MAX_ELIXIR), ELIXIR_BAR_HEIGHT);
    ctx.getShapeRenderer().end();

    // Elixir Text
    ctx.getSpriteBatch().begin();
    String elixirText = String.format("%d / 10", player.getElixir().getFloor());
    ctx.getGlyphLayout().setText(ctx.getFont(), elixirText);

    float textX = barX + (ELIXIR_BAR_WIDTH - ctx.getGlyphLayout().width) / 2;
    float textY = barY + (ELIXIR_BAR_HEIGHT + ctx.getGlyphLayout().height) / 2;

    ctx.getFont().draw(ctx.getSpriteBatch(), elixirText, textX, textY);
    ctx.getSpriteBatch().end();

    // 2. Draw Hand
    Hand hand = player.getHand();
    for (int i = 0; i < HAND_SIZE; i++) {
      Card card = hand.getCard(i);
      float cx = CardLayout.cardX(width, i);
      boolean isSelected = i == selectedHandIndex && player.getTeam() == selectedTeam;
      renderCard(card, cx, cy, CARD_WIDTH, CARD_HEIGHT, isSelected);
    }

    // 3. Draw Next Card
    Card nextCard = hand.getNextCard();
    float nextX = CardLayout.nextCardX(width);

    renderCard(nextCard, nextX, cy + 10, CARD_WIDTH * 0.8f, CARD_HEIGHT * 0.8f, false);

    // Label for Next
    ctx.getSpriteBatch().begin();
    ctx.getFont().draw(ctx.getSpriteBatch(), "Next", nextX, cy + CARD_HEIGHT);
    ctx.getSpriteBatch().end();
  }

  private void renderCard(Card card, float x, float y, float w, float h, boolean selected) {
    if (card == null) {
      return;
    }

    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_CARD_BG);
    ctx.getShapeRenderer().rect(x, y, w, h);

    if (selected) {
      ctx.getShapeRenderer().setColor(COLOR_CARD_SELECTED);
      ctx.getShapeRenderer().rect(x, y, w, 4);
      ctx.getShapeRenderer().rect(x, y + h - 4, w, 4);
      ctx.getShapeRenderer().rect(x, y, 4, h);
      ctx.getShapeRenderer().rect(x + w - 4, y, 4, h);
    } else {
      ctx.getShapeRenderer().setColor(COLOR_CARD_BORDER);
      ctx.getShapeRenderer().rect(x, y, w, h);
      ctx.getShapeRenderer().setColor(COLOR_CARD_BG);
      ctx.getShapeRenderer().rect(x + 2, y + 2, w - 4, h - 4);
    }
    ctx.getShapeRenderer().end();

    // Text (Name and Cost)
    ctx.getSpriteBatch().begin();

    ctx.getFont().setColor(COLOR_ELIXIR);
    ctx.getFont().draw(ctx.getSpriteBatch(), String.valueOf(card.getCost()), x + 5, y + h - 5);

    ctx.getFont().setColor(Color.WHITE);
    ctx.getFont().getData().setScale(ENTITY_NAME_FONT_SCALE);
    String name = card.getName();
    if (name.length() > 8) {
      name = name.substring(0, 8) + "..";
    }

    ctx.getGlyphLayout().setText(ctx.getFont(), name);
    ctx.getFont().draw(ctx.getSpriteBatch(), name,
        x + (w - ctx.getGlyphLayout().width) / 2, y + h / 2);

    ctx.getFont().getData().setScale(1.0f);
    ctx.getSpriteBatch().end();
  }

  public void dispose() {
    ctx.dispose();
  }
}
