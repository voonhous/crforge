package org.crforge.desktop.render;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * A screen's view settings that are not overlays: which way up the arena is drawn ({@code F}) and
 * whether the text annotations are shown ({@code T}). Like the overlays, they change only what is
 * drawn, never the battle.
 *
 * <p>The annotations are the text beside the arena that is not the battle's HUD: the status column
 * (the tick line, the toggles on, the replay's or scenario's status and the controls legend) and
 * the message column with its halted line. Hiding them leaves the battle itself (bodies, towers,
 * health and shield bars, and the name labels of the characters and area effects), the hands,
 * elixir, clock, crowns and result, and every overlay with a key of its own as they are.
 */
public final class ViewState {

  /** The status column's note for the annotations key. */
  public static final String ANNOTATIONS_NOTE = "T hides this text";

  /** Which way up the arena is drawn. */
  @Getter private ViewOrientation orientation;

  /** Whether {@code F} may flip the orientation on this screen. */
  @Getter private final boolean flippable;

  /** Whether the text annotations are drawn. */
  @Getter private boolean annotations = true;

  private ViewState(ViewOrientation orientation, boolean flippable) {
    this.orientation = orientation;
    this.flippable = flippable;
  }

  /**
   * The replay viewer's settings: flipped, side 1 at the bottom in blue, and {@code F} flips it
   * back and forth. Annotations shown.
   */
  public static ViewState replay() {
    return new ViewState(ViewOrientation.FLIPPED, true);
  }

  /**
   * The Ladder screen's settings: standing, side 0 at the bottom in blue, and {@code F} does not
   * flip it, since its clicks and keys play for a side by the panel and arena as drawn standing.
   * Annotations shown.
   */
  public static ViewState ladder() {
    return new ViewState(ViewOrientation.STANDARD, false);
  }

  /**
   * Flips the arena, when this screen allows it.
   *
   * @return true when the orientation changed
   */
  public boolean flip() {
    if (!flippable) {
      return false;
    }
    orientation = orientation.toggled();
    return true;
  }

  /** Shows the text annotations when hidden, hides them when shown. */
  public void toggleAnnotations() {
    annotations = !annotations;
  }

  /** The status column's lines for these settings: the orientation where it can flip. */
  public List<String> statusLines() {
    List<String> lines = new ArrayList<>();
    if (flippable) {
      lines.add(orientation.statusLine());
    }
    lines.add(ANNOTATIONS_NOTE);
    return lines;
  }
}
