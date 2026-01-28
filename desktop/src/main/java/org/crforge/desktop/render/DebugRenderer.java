package org.crforge.desktop.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
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

  // Scale: 1 tile = TILE_PIXELS pixels
  public static final float TILE_PIXELS = 24f;

  // UI Dimensions
  public static final float TOP_UI_HEIGHT = 140f;
  public static final float BOTTOM_UI_HEIGHT = 140f;

  // Colors for tile types
  private static final Color COLOR_BLUE_ZONE = new Color(0.2f, 0.3f, 0.6f, 1f);
  private static final Color COLOR_RED_ZONE = new Color(0.6f, 0.2f, 0.2f, 1f);
  private static final Color COLOR_RIVER = new Color(0.2f, 0.5f, 0.8f, 1f);
  private static final Color COLOR_BRIDGE = new Color(0.5f, 0.4f, 0.3f, 1f);
  private static final Color COLOR_GROUND = new Color(0.3f, 0.5f, 0.3f, 1f);
  private static final Color COLOR_BANNED = new Color(0.1f, 0.1f, 0.1f,
      1f); // Dark gray for banned tiles
  private static final Color COLOR_GRID = new Color(0f, 0f, 0f, 0.2f);
  private static final Color COLOR_HOVER_INVALID = new Color(1f, 0.3f, 0.3f,
      0.3f); // Reddish for invalid

  // Colors for entities
  private static final Color COLOR_BLUE_ENTITY = new Color(0.3f, 0.5f, 1f, 1f);
  private static final Color COLOR_RED_ENTITY = new Color(1f, 0.3f, 0.3f, 1f);
  private static final Color COLOR_TOWER_BOUNDARY = new Color(0.5f, 0.5f, 0.5f, 1f); // Opaque grey
  private static final Color COLOR_PROJECTILE = new Color(1f, 1f, 0f, 1f);
  private static final Color COLOR_AIR_UNIT = new Color(0.7f, 0.9f, 1f, 0.8f);

  // Ghost colors for placement
  private static final Color COLOR_BLUE_GHOST = new Color(0.3f, 0.5f, 1f, 0.5f);
  private static final Color COLOR_RED_GHOST = new Color(1f, 0.3f, 0.3f, 0.5f);
  private static final Color COLOR_SPELL_RADIUS = new Color(1f, 1f, 1f, 0.3f);

  // Health bar colors
  private static final Color COLOR_HEALTH_BG = new Color(0.2f, 0.2f, 0.2f, 0.8f);
  private static final Color COLOR_HEALTH_GREEN = new Color(0.2f, 0.8f, 0.2f, 1f);
  private static final Color COLOR_HEALTH_YELLOW = new Color(0.9f, 0.9f, 0.2f, 1f);
  private static final Color COLOR_HEALTH_RED = new Color(0.9f, 0.2f, 0.2f, 1f);

  // UI colors
  private static final Color COLOR_UI_BG = new Color(0.15f, 0.15f, 0.15f, 1f);
  private static final Color COLOR_CARD_BG = new Color(0.3f, 0.3f, 0.3f, 1f);
  private static final Color COLOR_CARD_BORDER = new Color(0.1f, 0.1f, 0.1f, 1f);
  private static final Color COLOR_CARD_SELECTED = new Color(1f, 1f, 0f, 1f);
  private static final Color COLOR_ELIXIR = new Color(0.9f, 0.2f, 0.9f, 1f);
  private static final Color COLOR_ELIXIR_BG = new Color(0.3f, 0.1f, 0.3f, 0.8f);

  // Debug colors
  private static final Color COLOR_PATH = new Color(0f, 1f, 1f, 0.7f); // Cyan

  private final ShapeRenderer shapeRenderer;
  private final SpriteBatch spriteBatch;
  private final BitmapFont font;
  private final BitmapFont entityNameFont; // Smaller font for names
  private final GlyphLayout glpyhLayout = new GlyphLayout();

  @Getter
  private boolean drawPaths = false;
  @Setter
  private int selectedHandIndex = -1;
  @Setter
  private Team selectedTeam = null;

  public DebugRenderer() {
    this.shapeRenderer = new ShapeRenderer();
    this.spriteBatch = new SpriteBatch();
    this.font = new BitmapFont();
    this.font.setColor(Color.WHITE);

    this.entityNameFont = new BitmapFont();
    this.entityNameFont.setColor(Color.WHITE);
    this.entityNameFont.getData().setScale(0.7f); // Scale down slightly
  }

  public void toggleDrawPaths() {
    drawPaths = !drawPaths;
  }

  public void render(GameEngine engine, OrthographicCamera camera, int hoverX, int hoverY) {
    GameState state = engine.getGameState();
    Arena arena = engine.getArena();
    Match match = engine.getMatch();

    shapeRenderer.setProjectionMatrix(camera.combined);
    spriteBatch.setProjectionMatrix(camera.combined);

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

    // 9. Render Entity Names (New)
    renderEntityNames(state);

    // 10. Render UI (hands, elixir, timer)
    renderUI(engine, match, camera);
  }

  private Player getPlayer(Match match) {
    if (match == null || selectedTeam == null) {
      return null;
    }
    // Find player by team. Assuming 1v1 or primary player for now.
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
    shapeRenderer.begin(ShapeType.Filled);
    shapeRenderer.setColor(COLOR_UI_BG);

    // Top UI
    shapeRenderer.rect(
        0,
        camera.viewportHeight - TOP_UI_HEIGHT,
        camera.viewportWidth,
        TOP_UI_HEIGHT
    );

    // Bottom UI
    shapeRenderer.rect(
        0,
        0,
        camera.viewportWidth,
        BOTTOM_UI_HEIGHT
    );

    shapeRenderer.end();
  }

  private void renderArenaTiles(Arena arena) {
    shapeRenderer.begin(ShapeType.Filled);

    for (int x = 0; x < Arena.WIDTH; x++) {
      for (int y = 0; y < Arena.HEIGHT; y++) {
        TileType type = arena.getTile(x, y).type();
        Color color = getTileColor(type);
        shapeRenderer.setColor(color);
        shapeRenderer.rect(
            x * TILE_PIXELS,
            y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
            TILE_PIXELS,
            TILE_PIXELS
        );
      }
    }

    shapeRenderer.end();
  }

  private Color getTileColor(TileType type) {
    return switch (type) {
      case BLUE_ZONE -> COLOR_BLUE_ZONE;
      case RED_ZONE -> COLOR_RED_ZONE;
      case RIVER -> COLOR_RIVER;
      case BRIDGE -> COLOR_BRIDGE;
      case GROUND -> COLOR_GROUND;
      case BANNED -> COLOR_BANNED;
    };
  }

  private void renderGrid(Arena arena) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapeRenderer.begin(ShapeType.Line);
    shapeRenderer.setColor(COLOR_GRID);

    float width = Arena.WIDTH * TILE_PIXELS;
    float height = Arena.HEIGHT * TILE_PIXELS;
    float startY = BOTTOM_UI_HEIGHT;

    // Vertical lines
    for (int x = 0; x <= Arena.WIDTH; x++) {
      shapeRenderer.line(
          x * TILE_PIXELS,
          startY,
          x * TILE_PIXELS,
          startY + height
      );
    }

    // Horizontal lines
    for (int y = 0; y <= Arena.HEIGHT; y++) {
      shapeRenderer.line(
          0,
          startY + y * TILE_PIXELS,
          width,
          startY + y * TILE_PIXELS
      );
    }

    shapeRenderer.end();
  }

  private void renderHover(int x, int y, Card selectedCard, Player player, Match match) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapeRenderer.begin(ShapeType.Filled);

    // Validate placement
    boolean isValid = true;
    if (player != null && selectedCard != null) {
      PlayerActionDTO action = PlayerActionDTO.play(selectedHandIndex, x + 0.5f, y + 0.5f);
      isValid = match.validateAction(player, action);
    }

    if (!isValid) {
      shapeRenderer.setColor(COLOR_HOVER_INVALID);
      shapeRenderer.rect(
          x * TILE_PIXELS,
          y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
          TILE_PIXELS,
          TILE_PIXELS
      );
    } else if (selectedCard != null) {
      renderGhostCard(x, y, selectedCard, player.getTeam());

      // Also render the tile grid for better placement visualization
      // Use a subtle white overlay to indicate the tile is being targeted
      shapeRenderer.setColor(0.5f, 0.5f, 0.5f, 0.2f);
      shapeRenderer.rect(
          x * TILE_PIXELS,
          y * TILE_PIXELS + BOTTOM_UI_HEIGHT,
          TILE_PIXELS,
          TILE_PIXELS
      );
    }

    shapeRenderer.end();
  }

  private void renderGhostCard(int hoverX, int hoverY, Card card, Team team) {
    Color ghostColor = team == Team.BLUE ? COLOR_BLUE_GHOST : COLOR_RED_GHOST;
    shapeRenderer.setColor(ghostColor);

    float centerX = (hoverX + 0.5f) * TILE_PIXELS;
    float centerY = (hoverY + 0.5f) * TILE_PIXELS + BOTTOM_UI_HEIGHT;

    if (card.getType() == CardType.SPELL) {
      float radius = card.getProjectile().getRadius() * TILE_PIXELS;
      shapeRenderer.setColor(COLOR_SPELL_RADIUS);
      shapeRenderer.circle(centerX, centerY, radius, 32);
    } else {
      // Render troops/buildings
      if (card.getTroops() != null) {
        for (TroopStats stats : card.getTroops()) {
          // Calculate ghost position relative to deployment center
          float offsetX = stats.getOffsetX();
          float offsetY = stats.getOffsetY();

          if (team == Team.RED) {
            offsetX = -offsetX;
            offsetY = -offsetY;
          }

          float ghostX = centerX + (offsetX * TILE_PIXELS);
          float ghostY = centerY + (offsetY * TILE_PIXELS);
          float radius = (stats.getSize() * TILE_PIXELS) / 2f;

          // Ensure ghost color is set for each circle
          shapeRenderer.setColor(ghostColor);
          shapeRenderer.circle(ghostX, ghostY, radius);
        }
      }
    }
  }

  private void renderEntities(GameState state) {
    shapeRenderer.begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getSize() * TILE_PIXELS / 2f;

      // Special indicator for Tower (Optional: Inner box to signify building)
      // Draw this FIRST so it appears underneath the circular body
      if (entity.getEntityType() == EntityType.TOWER) {
        shapeRenderer.setColor(COLOR_TOWER_BOUNDARY);
        // radius * 2 is the full diameter/size of the entity
        shapeRenderer.rect(x - radius, y - radius, radius * 2, radius * 2);
      }

      // Get base color by team
      Color baseColor = getEntityColor(entity);
      shapeRenderer.setColor(baseColor);

      // Render entity as circle
      shapeRenderer.circle(x, y, radius);

      // Air units get a ring
      if (entity.getMovementType() == MovementType.AIR) {
        shapeRenderer.setColor(COLOR_AIR_UNIT);
        shapeRenderer.circle(x, y, radius + 2);
      }
    }

    shapeRenderer.end();

    // Draw outlines
    shapeRenderer.begin(ShapeType.Line);
    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getSize() * TILE_PIXELS / 2f;

      // Darker outline
      Color baseColor = getEntityColor(entity);
      shapeRenderer.setColor(baseColor.r * 0.5f, baseColor.g * 0.5f, baseColor.b * 0.5f, 1f);

      // Always draw circle outline (physics body)
      shapeRenderer.circle(x, y, radius);
    }
    shapeRenderer.end();
  }

  private void renderEntityNames(GameState state) {
    spriteBatch.begin();
    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getSize() * TILE_PIXELS / 2f;

      // Draw name slightly above the health bar
      float textY = y + radius + 15;

      // Use width from glyph layout to center name
      glpyhLayout.setText(entityNameFont, entity.getName());
      float textWidth = glpyhLayout.width;

      entityNameFont.draw(spriteBatch, entity.getName(), x - textWidth / 2, textY);
    }
    spriteBatch.end();
  }

  private Color getEntityColor(Entity entity) {
    if (entity.getEntityType() == EntityType.TOWER) {
      // Towers are gray with team tint
      Tower tower = (Tower) entity;
      if (tower.isCrownTower()) {
        return entity.getTeam() == Team.BLUE
            ? new Color(0.4f, 0.5f, 0.9f, 1f)
            : new Color(0.9f, 0.4f, 0.4f, 1f);
      }
      return entity.getTeam() == Team.BLUE
          ? new Color(0.5f, 0.6f, 0.8f, 1f)
          : new Color(0.8f, 0.5f, 0.5f, 1f);
    }
    return entity.getTeam() == Team.BLUE ? COLOR_BLUE_ENTITY : COLOR_RED_ENTITY;
  }

  private void renderProjectiles(GameState state) {
    shapeRenderer.begin(ShapeType.Filled);
    shapeRenderer.setColor(COLOR_PROJECTILE);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive()) {
        continue;
      }

      float x = projectile.getPosition().getX() * TILE_PIXELS;
      float y = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      // Small circle for projectile
      shapeRenderer.circle(x, y, 4);
    }

    shapeRenderer.end();
  }

  private void renderHealthBars(GameState state) {
    shapeRenderer.begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      Health health = entity.getHealth();
      float healthPercent = (float) health.getCurrent() / health.getMax();

      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float radius = entity.getSize() * TILE_PIXELS / 2f;

      float barWidth = Math.max(radius * 2, 20);
      float barHeight = 4;
      float barY = y + radius + 4;

      // Background
      shapeRenderer.setColor(COLOR_HEALTH_BG);
      shapeRenderer.rect(x - barWidth / 2, barY, barWidth, barHeight);

      // Health fill
      Color healthColor = healthPercent > 0.6f ? COLOR_HEALTH_GREEN
          : healthPercent > 0.3f ? COLOR_HEALTH_YELLOW
              : COLOR_HEALTH_RED;
      shapeRenderer.setColor(healthColor);
      shapeRenderer.rect(x - barWidth / 2, barY, barWidth * healthPercent, barHeight);
    }

    shapeRenderer.end();
  }

  private void renderTargetingLines(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapeRenderer.begin(ShapeType.Line);

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

        // Thin red line for targeting
        shapeRenderer.setColor(1f, 0f, 0f, 0.3f);
        shapeRenderer.line(x1, y1, x2, y2);
      }
    }

    shapeRenderer.end();
  }

  private void renderPathLines(GameState state) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapeRenderer.begin(ShapeType.Line);
    shapeRenderer.setColor(COLOR_PATH);

    for (Entity entity : state.getAliveEntities()) {
      // Only render paths for troops that are moving
      if (entity instanceof Troop troop && troop.isAlive()) {
        float x = troop.getPosition().getX() * TILE_PIXELS;
        float y = troop.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        float rot = troop.getPosition().getRotation();

        // Draw line in direction of movement
        float len = TILE_PIXELS * 1.5f;
        float x2 = x + (float) Math.cos(rot) * len;
        float y2 = y + (float) Math.sin(rot) * len;

        shapeRenderer.line(x, y, x2, y2);
      }
    }

    shapeRenderer.end();
  }

  private void renderUI(GameEngine engine, Match match, OrthographicCamera camera) {
    // Render UI in screen space
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
    spriteBatch.begin();
    font.getData().setScale(1.2f);
    glpyhLayout.setText(font, timeText);
    font.draw(spriteBatch, timeText, (screenWidth - glpyhLayout.width) / 2, screenHeight - 10);

    font.getData().setScale(1.0f);
    String entityCount = "Entities: " + engine.getGameState().getAliveEntities().size();
    font.draw(spriteBatch, entityCount, 10, screenHeight / 2 + 20);

    if (engine.getGameState().isGameOver()) {
      Team winner = engine.getGameState().getWinner();
      String winText = winner != null ? winner + " WINS!" : "DRAW!";
      font.getData().setScale(2.0f);
      glpyhLayout.setText(font, winText);
      font.draw(spriteBatch, winText, (screenWidth - glpyhLayout.width) / 2, screenHeight / 2);
      font.getData().setScale(1.0f);
    }

    // Controls help
    if (drawPaths) {
      font.draw(spriteBatch, "Paths: ON", screenWidth - 100, screenHeight / 2 + 20);
    }

    spriteBatch.end();
  }

  private void renderPlayerHUD(Player player, boolean isTop, float yPos, float width) {
    float cardWidth = 60;
    float cardHeight = 80;
    float spacing = 10;
    float handWidth = (cardWidth * 4) + (spacing * 3);
    float startX = (width - handWidth) / 2;
    float cardY = isTop ? yPos + 30 : yPos + 30; // Adjust for margins

    // 1. Draw Elixir Bar
    float elixir = player.getElixir().getCurrent();
    float maxElixir = 10f;
    float barWidth = 250;
    float barHeight = 15;
    float barX = (width - barWidth) / 2;
    float barY = isTop ? yPos + 10 : yPos + 115; // Bottom for top player, top for bottom player

    shapeRenderer.begin(ShapeType.Filled);
    // Bar Background
    shapeRenderer.setColor(COLOR_ELIXIR_BG);
    shapeRenderer.rect(barX, barY, barWidth, barHeight);
    // Elixir Fill
    shapeRenderer.setColor(COLOR_ELIXIR);
    shapeRenderer.rect(barX, barY, barWidth * (elixir / maxElixir), barHeight);
    shapeRenderer.end();

    // Elixir Text
    spriteBatch.begin();
    String elixirText = String.format("%d / 10", player.getElixir().getFloor());
    glpyhLayout.setText(font, elixirText);

    // Draw text centered within the bar
    float textX = barX + (barWidth - glpyhLayout.width) / 2;
    float textY = barY + (barHeight + glpyhLayout.height) / 2;

    font.draw(spriteBatch, elixirText, textX, textY);
    spriteBatch.end();

    // 2. Draw Hand
    Hand hand = player.getHand();
    for (int i = 0; i < 4; i++) {
      Card card = hand.getCard(i);
      float cardX = startX + i * (cardWidth + spacing);
      boolean isSelected = i == selectedHandIndex && player.getTeam() == selectedTeam;
      renderCard(card, cardX, cardY, cardWidth, cardHeight, isSelected);
    }

    // 3. Draw Next Card
    Card nextCard = hand.getNextCard();
    // Position to the RIGHT of the hand
    float nextX = startX + handWidth + spacing * 2;

    renderCard(nextCard, nextX, cardY + 10, cardWidth * 0.8f, cardHeight * 0.8f, false);

    // Label for Next
    spriteBatch.begin();
    font.draw(spriteBatch, "Next", nextX, cardY + cardHeight);
    spriteBatch.end();
  }

  private void renderCard(Card card, float x, float y, float w, float h, boolean selected) {
    if (card == null) {
      return;
    }

    shapeRenderer.begin(ShapeType.Filled);
    // Background
    shapeRenderer.setColor(COLOR_CARD_BG);
    shapeRenderer.rect(x, y, w, h);

    // Selection Highlight
    if (selected) {
      shapeRenderer.setColor(COLOR_CARD_SELECTED);
      shapeRenderer.rect(x, y, w, 4); // Bottom highlight
      shapeRenderer.rect(x, y + h - 4, w, 4); // Top
      shapeRenderer.rect(x, y, 4, h); // Left
      shapeRenderer.rect(x + w - 4, y, 4, h); // Right
    } else {
      shapeRenderer.setColor(COLOR_CARD_BORDER);
      shapeRenderer.rect(x, y, w, h);
      shapeRenderer.setColor(COLOR_CARD_BG);
      shapeRenderer.rect(x + 2, y + 2, w - 4, h - 4);
    }
    shapeRenderer.end();

    // Text (Name and Cost)
    spriteBatch.begin();

    // Cost (Top Left)
    font.setColor(COLOR_ELIXIR);
    font.draw(spriteBatch, String.valueOf(card.getCost()), x + 5, y + h - 5);

    // Name (Center, Wrapped)
    font.setColor(Color.WHITE);
    font.getData().setScale(0.7f);
    // Simple wrapping logic or truncation
    String name = card.getName();
    if (name.length() > 8) {
      name = name.substring(0, 8) + "..";
    }

    glpyhLayout.setText(font, name);
    font.draw(spriteBatch, name, x + (w - glpyhLayout.width) / 2, y + h / 2);

    font.getData().setScale(1.0f); // Reset
    spriteBatch.end();
  }

  public void dispose() {
    shapeRenderer.dispose();
    spriteBatch.dispose();
    font.dispose();
    entityNameFont.dispose();
  }
}
