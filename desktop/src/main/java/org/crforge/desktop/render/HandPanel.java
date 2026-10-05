package org.crforge.desktop.render;

import static org.crforge.desktop.render.WorkspaceTheme.*;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import org.crforge.desktop.battle.BattleFrame;

/** One side's compact hand; availability comes from the session snapshot. */
final class HandPanel {
  private final Map<String, String> names = new HashMap<>();

  private String displayName(String name) {
    return names.computeIfAbsent(name, value -> value.replaceAll("(?<=[a-z])(?=[A-Z])", " "));
  }

  final Table table = new Table();
  private final Label title;
  private final TextButton[] cards = new TextButton[4];
  private final Label next;
  private int side;

  private final ViewState view;
  private final boolean replay;

  HandPanel(
      WorkspaceTheme theme,
      ViewState view,
      boolean replay,
      BiConsumer<Integer, Integer> selectCard) {
    this.view = view;
    this.replay = replay;
    title = theme.label("");
    next = theme.wrapped("");
    table.background(theme.skin.newDrawable("white", PANEL));
    table.pad(8);
    title.setWrap(true);
    table.add(title).colspan(2).growX().left().padBottom(6).row();
    for (int slot = 0; slot < 4; slot++) {
      final int index = slot;
      cards[slot] = theme.button("", () -> selectCard.accept(side, index));
      cards[slot].getLabel().setWrap(true);
      if (replay) cards[slot].setTouchable(Touchable.disabled);
      table
          .add(cards[slot])
          .growX()
          .uniformX()
          .height(58)
          .padRight(slot % 2 == 0 ? 6 : 0)
          .padBottom(6);
      if (slot % 2 == 1) table.row();
    }
    next.setColor(MUTED);
    table.add(next).colspan(2).growX().minHeight(22).left();
  }

  void update(BattleFrame frame, int side, int selectedSide, int selectedSlot) {
    this.side = side;
    BattleFrame.SideView player =
        frame.sides().stream().filter(value -> value.side() == side).findFirst().orElse(null);
    title.setColor(view.getOrientation().blue(side) ? Color.SKY : Color.SALMON);
    title.setText(
        player == null
            ? "Scenario - no hand"
            : "SIDE "
                + side
                + " / "
                + view.getOrientation().sideName(side).toUpperCase(Locale.ROOT)
                + "\nElixir "
                + String.format(Locale.ROOT, "%.1f", player.elixir() / 10000f)
                + " / 10  Crowns "
                + player.crowns());
    for (int slot = 0; slot < 4; slot++) {
      BattleFrame.CardView card = player == null ? null : player.hand().get(slot);
      TextButton button = cards[slot];
      button.setDisabled(card == null || card.unavailableReason() != null);
      button.setChecked(selectedSide == side && selectedSlot == slot);
      button.setText(
          card == null
              ? "-"
              : displayName(card.name())
                  + "\n"
                  + (card.pending() ? "Queued" : card.cost() + " elixir")
                  + (replay ? "" : " [" + (side * 4 + slot + 1) + "]"));
    }
    next.setText(
        player == null || player.next() == null
            ? ""
            : "Next: " + displayName(player.next().name()));
  }
}
