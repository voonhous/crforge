package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Match;
import org.crforge.core.player.Hand;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;

/**
 * Renders the HUD: UI backgrounds, game timer, entity count, win/draw text,
 * debug overlay status, player HUDs (cards + elixir bar).
 */
public class HudRenderer {

  private final RenderContext ctx;

  public HudRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Render the top and bottom UI background panels. */
  public void renderBackgrounds(OrthographicCamera camera) {
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

  /**
   * Render all HUD elements: timer, entity count, player hands/elixir, debug status.
   */
  public void render(GameEngine engine, Match match, OrthographicCamera camera,
                     int selectedHandIndex, Team selectedTeam,
                     boolean drawPaths, boolean drawRanges, boolean drawDamageNumbers,
                     boolean drawAoeDamage) {
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
        renderPlayerHUD(blue, false, 0, screenWidth, selectedHandIndex, selectedTeam);
      }
      if (!match.getRedPlayers().isEmpty()) {
        Player red = match.getRedPlayers().get(0);
        renderPlayerHUD(red, true, screenHeight - TOP_UI_HEIGHT, screenWidth,
            selectedHandIndex, selectedTeam);
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
    if (drawDamageNumbers) {
      ctx.getFont().draw(ctx.getSpriteBatch(), "Damage: ON",
          screenWidth - 110, screenHeight / 2 + 20 + (overlayLine++ * 16));
    }
    if (drawAoeDamage) {
      ctx.getFont().draw(ctx.getSpriteBatch(), "AOE: ON",
          screenWidth - 110, screenHeight / 2 + 20 + (overlayLine++ * 16));
    }

    ctx.getSpriteBatch().end();
  }

  private void renderPlayerHUD(Player player, boolean isTop, float yPos, float width,
                                int selectedHandIndex, Team selectedTeam) {
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
      int level = card != null ? player.getLevelConfig().getLevelFor(card) : 1;
      renderCard(card, cx, cy, CARD_WIDTH, CARD_HEIGHT, isSelected, level);
    }

    // 3. Draw Next Card
    Card nextCard = hand.getNextCard();
    float nextX = CardLayout.nextCardX(width);
    int nextLevel = nextCard != null ? player.getLevelConfig().getLevelFor(nextCard) : 1;

    renderCard(nextCard, nextX, cy + 10, CARD_WIDTH * 0.8f, CARD_HEIGHT * 0.8f, false, nextLevel);

    // Label for Next
    ctx.getSpriteBatch().begin();
    ctx.getFont().draw(ctx.getSpriteBatch(), "Next", nextX, cy + CARD_HEIGHT);
    ctx.getSpriteBatch().end();
  }

  private void renderCard(Card card, float x, float y, float w, float h, boolean selected,
                          int level) {
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

    // Level indicator (bottom-left corner)
    ctx.getFont().setColor(Color.LIGHT_GRAY);
    String lvlText = "Lv" + level;
    ctx.getFont().draw(ctx.getSpriteBatch(), lvlText, x + 3, y + 12);

    ctx.getFont().getData().setScale(1.0f);
    ctx.getSpriteBatch().end();
  }
}
