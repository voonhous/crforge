/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.WorkspaceTheme.*;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
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
  private final Label[] costs = new Label[4];
  private final Label[] shortcuts = new Label[4];
  private final Label[] cardStates = new Label[4];
  private final ElixirMeter elixir;
  private final Label elixirValue;
  private int side;
  private boolean compact;

  private final ViewState view;
  private final boolean replay;

  HandPanel(
      WorkspaceTheme theme,
      ViewState view,
      boolean replay,
      BiConsumer<Integer, Integer> selectCard) {
    this.view = view;
    this.replay = replay;
    title = new Label("", theme.skin, "heading");
    elixir = new ElixirMeter(theme.skin.getDrawable("white"));
    elixirValue = theme.label("");
    next = theme.wrapped("");
    table.background(theme.skin.newDrawable("white", PANEL));
    table.pad(8);
    title.setWrap(true);
    table.add(title).colspan(2).growX().left().padBottom(6).row();
    Table meter = new Table();
    meter.add(elixir).growX().height(6).padRight(8);
    meter.add(elixirValue).minWidth(54);
    table.add(meter).colspan(2).growX().padBottom(10).row();
    for (int slot = 0; slot < 4; slot++) {
      final int index = slot;
      cards[slot] = theme.button("", () -> selectCard.accept(side, index));
      TextButton card = cards[slot];
      card.clearChildren();
      card.pad(7);
      card.getLabel().setWrap(true);
      card.getLabel().setAlignment(Align.left);
      costs[slot] = new Label("", theme.skin, "heading");
      costs[slot].setColor(Color.valueOf("d6adffff"));
      shortcuts[slot] = theme.label("");
      shortcuts[slot].setColor(MUTED);
      cardStates[slot] = theme.label("");
      cardStates[slot].setColor(MUTED);
      card.add(costs[slot]).left().expandX();
      card.add(shortcuts[slot]).right().row();
      card.add(card.getLabel()).colspan(2).growX().expandY().left().row();
      card.add(cardStates[slot]).colspan(2).left();
      if (replay) cards[slot].setTouchable(Touchable.disabled);
      table
          .add(cards[slot])
          .growX()
          .uniformX()
          .height(84)
          .padRight(slot % 2 == 0 ? 6 : 0)
          .padBottom(6);
      if (slot % 2 == 1) table.row();
    }
    next.setColor(MUTED);
    table.add(next).colspan(2).growX().minHeight(22).left();
  }

  void compact(boolean compact) {
    if (this.compact == compact) return;
    this.compact = compact;
    for (int i = 0; i < cards.length; i++) {
      table.getCell(cards[i]).height(compact ? 64 : 84);
      cards[i].getCell(cardStates[i]).height(compact ? 0 : 18);
      cardStates[i].setVisible(!compact);
      cards[i].invalidateHierarchy();
    }
    table.invalidateHierarchy();
  }

  void update(BattleFrame frame, int side, int selectedSide, int selectedSlot) {
    this.side = side;
    BattleFrame.SideView player =
        frame.sides().stream().filter(value -> value.side() == side).findFirst().orElse(null);
    title.setColor(view.getOrientation().blue(side) ? Color.SKY : Color.SALMON);
    title.setText(
        player == null
            ? "No match - no hand"
            : "SIDE "
                + side
                + " / "
                + view.getOrientation().sideName(side).toUpperCase(Locale.ROOT)
                + "   /   "
                + player.crowns()
                + " crowns");
    elixir.setValue(player == null ? 0 : player.elixir() / 10000f);
    elixirValue.setText(
        player == null ? "" : String.format(Locale.ROOT, "%.1f / 10", player.elixir() / 10000f));
    for (int slot = 0; slot < 4; slot++) {
      BattleFrame.CardView card = player == null ? null : player.hand().get(slot);
      TextButton button = cards[slot];
      button.setDisabled(card == null || card.unavailableReason() != null);
      button.setChecked(selectedSide == side && selectedSlot == slot);
      button.setText(card == null ? "-" : displayName(card.name()));
      costs[slot].setText(
          card == null
              ? ""
              : compact && card.pending()
                  ? "Queued"
                  : card.cost() + " elixir  " + BattleRenderer.levelText(card.level()));
      shortcuts[slot].setText(replay ? "" : "[" + (side * 4 + slot + 1) + "]");
      cardStates[slot].setText(
          card == null
              ? ""
              : card.pending()
                  ? "Queued"
                  : button.isChecked()
                      ? "Selected"
                      : button.isDisabled() ? "Unavailable" : "Ready");
      cardStates[slot].setColor(
          card != null && card.pending() ? Color.GOLD : button.isChecked() ? ACCENT : MUTED);
    }
    next.setText(
        player == null || player.next() == null
            ? ""
            : "Next: "
                + displayName(player.next().name())
                + " "
                + BattleRenderer.levelText(player.next().level()));
  }
}
