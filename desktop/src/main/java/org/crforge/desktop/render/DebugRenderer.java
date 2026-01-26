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
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
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
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;

/**
 * Debug renderer using LibGDX ShapeRenderer for minimal visualization. Renders arena tiles,
 * entities as shapes, health bars, and projectiles.
 */
public class DebugRenderer {

  // Scale: 1 tile = TILE_PIXELS pixels
  public static final float TILE_PIXELS = 24f;

  // Colors for tile types
  private static final Color COLOR_BLUE_ZONE = new Color(0.2f, 0.3f, 0.6f, 1f);
  private static final Color COLOR_RED_ZONE = new Color(0.6f, 0.2f, 0.2f, 1f);
  private static final Color COLOR_RIVER = new Color(0.2f, 0.5f, 0.8f, 1f);
  private static final Color COLOR_BRIDGE = new Color(0.5f, 0.4f, 0.3f, 1f);
  private static final Color COLOR_GROUND = new Color(0.3f, 0.5f, 0.3f, 1f);
  private static final Color COLOR_BANNED = new Color(0.1f, 0.1f, 0.1f, 1f); // Dark gray for banned tiles
  private static final Color COLOR_GRID = new Color(0f, 0f, 0f, 0.2f);
  private static final Color COLOR_HOVER = new Color(1f, 1f, 1f, 0.3f); // Semi-transparent white

  // Colors for entities
  private static final Color COLOR_BLUE_ENTITY = new Color(0.3f, 0.5f, 1f, 1f);
  private static final Color COLOR_RED_ENTITY = new Color(1f, 0.3f, 0.3f, 1f);
  private static final Color COLOR_TOWER_BOUNDARY = new Color(0.5f, 0.5f, 0.5f, 1f); // Opaque grey
  private static final Color COLOR_PROJECTILE = new Color(1f, 1f, 0f, 1f);
  private static final Color COLOR_AIR_UNIT = new Color(0.7f, 0.9f, 1f, 0.8f);

  // Health bar colors
  private static final Color COLOR_HEALTH_BG = new Color(0.2f, 0.2f, 0.2f, 0.8f);
  private static final Color COLOR_HEALTH_GREEN = new Color(0.2f, 0.8f, 0.2f, 1f);
  private static final Color COLOR_HEALTH_YELLOW = new Color(0.9f, 0.9f, 0.2f, 1f);
  private static final Color COLOR_HEALTH_RED = new Color(0.9f, 0.2f, 0.2f, 1f);

  // UI colors
  private static final Color COLOR_ELIXIR = new Color(0.9f, 0.2f, 0.9f, 1f);
  private static final Color COLOR_ELIXIR_BG = new Color(0.3f, 0.1f, 0.3f, 0.8f);

  // Debug colors
  private static final Color COLOR_PATH = new Color(0f, 1f, 1f, 0.7f); // Cyan

  private final ShapeRenderer shapeRenderer;
  private final SpriteBatch spriteBatch;
  private final BitmapFont font;
  private final BitmapFont entityNameFont; // Smaller font for names
  private final GlyphLayout glpyhLayout = new GlyphLayout();

  private boolean drawPaths = false;

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

  public boolean isDrawPaths() {
    return drawPaths;
  }

  public void render(GameEngine engine, OrthographicCamera camera) {
    render(engine, camera, -1, -1);
  }

  public void render(GameEngine engine, OrthographicCamera camera, int hoverX, int hoverY) {
    GameState state = engine.getGameState();
    Arena arena = engine.getArena();
    Match match = engine.getMatch();

    shapeRenderer.setProjectionMatrix(camera.combined);
    spriteBatch.setProjectionMatrix(camera.combined);

    // 1. Render arena tiles (filled)
    renderArenaTiles(arena);

    // 2. Render grid lines
    renderGrid(arena);

    // 3. Render hover highlight
    if (hoverX >= 0 && hoverX < Arena.WIDTH && hoverY >= 0 && hoverY < Arena.HEIGHT) {
      renderHover(hoverX, hoverY);
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

    // 10. Render UI (elixir, timer)
    renderUI(engine, match, camera);
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
            y * TILE_PIXELS,
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

    // Vertical lines
    for (int x = 0; x <= Arena.WIDTH; x++) {
      shapeRenderer.line(x * TILE_PIXELS, 0, x * TILE_PIXELS, height);
    }

    // Horizontal lines
    for (int y = 0; y <= Arena.HEIGHT; y++) {
      shapeRenderer.line(0, y * TILE_PIXELS, width, y * TILE_PIXELS);
    }

    shapeRenderer.end();
  }

  private void renderHover(int x, int y) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapeRenderer.begin(ShapeType.Filled);
    shapeRenderer.setColor(COLOR_HOVER);
    shapeRenderer.rect(x * TILE_PIXELS, y * TILE_PIXELS, TILE_PIXELS, TILE_PIXELS);
    shapeRenderer.end();
  }

  private void renderEntities(GameState state) {
    shapeRenderer.begin(ShapeType.Filled);

    for (Entity entity : state.getAliveEntities()) {
      float x = entity.getPosition().getX() * TILE_PIXELS;
      float y = entity.getPosition().getY() * TILE_PIXELS;
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
      float y = entity.getPosition().getY() * TILE_PIXELS;
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
      float y = entity.getPosition().getY() * TILE_PIXELS;
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
      float y = projectile.getPosition().getY() * TILE_PIXELS;

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
      float y = entity.getPosition().getY() * TILE_PIXELS;
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
        float y1 = entity.getPosition().getY() * TILE_PIXELS;
        float x2 = target.getPosition().getX() * TILE_PIXELS;
        float y2 = target.getPosition().getY() * TILE_PIXELS;

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
        float y = troop.getPosition().getY() * TILE_PIXELS;
        float rot = troop.getPosition().getRotation();

        // Draw line in direction of movement
        float len = TILE_PIXELS * 1.5f;
        float x2 = x + (float)Math.cos(rot) * len;
        float y2 = y + (float)Math.sin(rot) * len;

        shapeRenderer.line(x, y, x2, y2);
      }
    }

    shapeRenderer.end();
  }

  private void renderUI(GameEngine engine, Match match, OrthographicCamera camera) {
    // Render UI in screen space
    float screenWidth = camera.viewportWidth;
    float screenHeight = camera.viewportHeight;

    // Game timer at top center
    float gameTime = engine.getGameTimeSeconds();
    int minutes = (int) (gameTime / 60);
    int seconds = (int) (gameTime % 60);
    String timeText = String.format("%d:%02d", minutes, seconds);
    if (engine.isOvertime()) {
      timeText = "OT " + timeText;
    }

    // Elixir bars at bottom
    if (match != null) {
      renderElixirBars(match, screenWidth);
    }

    // Timer and status text
    spriteBatch.begin();
    font.draw(spriteBatch, timeText, screenWidth / 2 - 20, screenHeight - 10);

    // Entity count
    String entityCount = "Entities: " + engine.getGameState().getAliveEntities().size();
    font.draw(spriteBatch, entityCount, 10, screenHeight - 10);

    // Game state
    if (engine.getGameState().isGameOver()) {
      Team winner = engine.getGameState().getWinner();
      String winText = winner != null ? winner + " WINS!" : "DRAW!";
      font.draw(spriteBatch, winText, screenWidth / 2 - 40, screenHeight / 2);
    }

    // Controls help
    if (drawPaths) {
      font.draw(spriteBatch, "Paths: ON", screenWidth - 80, screenHeight - 30);
    }

    spriteBatch.end();
  }

  private void renderElixirBars(Match match, float screenWidth) {
    shapeRenderer.begin(ShapeType.Filled);

    float barWidth = 100;
    float barHeight = 12;
    float padding = 10;

    // Blue player (left side)
    if (!match.getBluePlayers().isEmpty()) {
      Player blue = match.getBluePlayers().get(0);
      float elixir = blue.getElixir().getCurrent();

      // Background
      shapeRenderer.setColor(COLOR_ELIXIR_BG);
      shapeRenderer.rect(padding, padding, barWidth, barHeight);

      // Elixir fill
      shapeRenderer.setColor(COLOR_ELIXIR);
      shapeRenderer.rect(padding, padding, barWidth * (elixir / 10f), barHeight);
    }

    // Red player (right side)
    if (!match.getRedPlayers().isEmpty()) {
      Player red = match.getRedPlayers().get(0);
      float elixir = red.getElixir().getCurrent();

      float x = screenWidth - barWidth - padding;

      // Background
      shapeRenderer.setColor(COLOR_ELIXIR_BG);
      shapeRenderer.rect(x, padding, barWidth, barHeight);

      // Elixir fill
      shapeRenderer.setColor(COLOR_ELIXIR);
      shapeRenderer.rect(x, padding, barWidth * (elixir / 10f), barHeight);
    }

    shapeRenderer.end();

    // Elixir numbers
    spriteBatch.begin();
    if (!match.getBluePlayers().isEmpty()) {
      Player blue = match.getBluePlayers().get(0);
      font.draw(spriteBatch, String.valueOf(blue.getElixir().getFloor()), padding + barWidth + 5,
          padding + 12);
    }
    if (!match.getRedPlayers().isEmpty()) {
      Player red = match.getRedPlayers().get(0);
      font.draw(spriteBatch, String.valueOf(red.getElixir().getFloor()), screenWidth - padding - 15,
          padding + 12);
    }
    spriteBatch.end();
  }

  public void dispose() {
    shapeRenderer.dispose();
    spriteBatch.dispose();
    font.dispose();
    entityNameFont.dispose();
  }
}
