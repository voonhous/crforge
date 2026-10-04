package org.crforge.desktop.render;

import java.util.List;
import java.util.Locale;
import org.crforge.desktop.battle.BattleFrame;

/**
 * The text the battle renderer draws besides the arena for one frame, worked out without drawing:
 * the crowns and the result, named and ordered by the view's orientation, and the annotations the
 * view shows or hides.
 *
 * @param crowns the crowns line, the bottom (blue) side's first, or null outside a match
 * @param result the result headline once the battle has ended or stopped, else null
 * @param tickLine the tick and entity count line, or null with the annotations hidden
 * @param status the status column, bottom up; empty with the annotations hidden
 * @param messages the message column, oldest first; empty with the annotations hidden
 * @param halted whether the halted line is drawn: the session halted and the annotations shown
 * @param labels whether the characters' and area effects' name labels are drawn: always, since they
 *     name the units on the arena and are not annotations
 */
public record HudText(
    String crowns,
    String result,
    String tickLine,
    List<String> status,
    List<String> messages,
    boolean halted,
    boolean labels) {

  /**
   * The text of a frame.
   *
   * @param frame the frame, its messages already naming the sides by the view's orientation
   * @param view the screen's view settings
   * @param status the status column the annotations would show
   */
  public static HudText of(BattleFrame frame, ViewState view, List<String> status) {
    ViewOrientation orientation = view.getOrientation();
    boolean shown = view.isAnnotations();
    String crowns = null;
    if (!frame.sides().isEmpty()) {
      List<Integer> order = orientation.sidesBottomFirst();
      crowns =
          "crowns "
              + frame.sides().get(order.get(0)).crowns()
              + " - "
              + frame.sides().get(order.get(1)).crowns();
    }
    String result = null;
    if (frame.ended() || frame.over()) {
      result =
          frame.winner() < 0
              ? "DRAW!"
              : orientation.sideName(frame.winner()).toUpperCase(Locale.ROOT) + " WINS!";
    }
    return new HudText(
        crowns,
        result,
        shown ? "tick " + frame.tick() + "  entities " + frame.entities().size() : null,
        shown ? List.copyOf(status) : List.of(),
        shown ? List.copyOf(frame.messages()) : List.of(),
        shown && frame.halted() != null,
        true);
  }
}
