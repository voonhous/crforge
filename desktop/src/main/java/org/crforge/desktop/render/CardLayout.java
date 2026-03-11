package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

/**
 * Static utility for card hand layout calculations. Centralizes the position math shared between
 * the renderer (drawing cards) and the screen (hit detection).
 */
public final class CardLayout {

  private CardLayout() {}

  /** Total pixel width of the hand (4 cards + 3 gaps). */
  public static float handWidth() {
    return (CARD_WIDTH * HAND_SIZE) + (CARD_SPACING * (HAND_SIZE - 1));
  }

  /** X coordinate of the first card in the hand, centered on screen. */
  public static float handStartX(float screenWidth) {
    return (screenWidth - handWidth()) / 2;
  }

  /** X coordinate of a card at the given hand index. */
  public static float cardX(float screenWidth, int index) {
    return handStartX(screenWidth) + index * (CARD_WIDTH + CARD_SPACING);
  }

  /** Y coordinate of the card row. */
  public static float cardY(boolean isTop, float screenHeight) {
    float baseY = isTop ? (screenHeight - TOP_UI_HEIGHT) : 0;
    return baseY + 30;
  }

  /** X coordinate of the "next card" preview (right of the hand). */
  public static float nextCardX(float screenWidth) {
    return handStartX(screenWidth) + handWidth() + CARD_SPACING * 2;
  }

  /**
   * Hit-test: returns the card index (0-3) if the world coordinates fall inside a card, or -1 if
   * nothing was hit.
   */
  public static int hitTest(
      float worldX, float worldY, boolean isTop, float screenWidth, float screenHeight) {
    float cy = cardY(isTop, screenHeight);
    if (worldY < cy || worldY > cy + CARD_HEIGHT) {
      return -1;
    }
    float startX = handStartX(screenWidth);
    for (int i = 0; i < HAND_SIZE; i++) {
      float cx = startX + i * (CARD_WIDTH + CARD_SPACING);
      if (worldX >= cx && worldX <= cx + CARD_WIDTH) {
        return i;
      }
    }
    return -1;
  }
}
