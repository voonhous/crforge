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
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.desktop.battle.BattleFrame;

/**
 * One side's compact hand and its king's two champion slots, each slot's ability a button;
 * availability comes from the session snapshot.
 */
final class HandPanel {
  /** The height of a champion slot's button: two lines, the cost and state, then the champion. */
  private static final int ABILITY_HEIGHT = 46;

  /** The height of a champion slot's button in a small window: one line. */
  private static final int ABILITY_HEIGHT_COMPACT = 26;

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
  private final TextButton[] abilities = new TextButton[2];
  private final Label[] abilityCosts = new Label[2];
  private final Label[] abilityStates = new Label[2];

  /** Whether the champion slots' row is shown: hidden while neither slot follows a champion. */
  private boolean abilitiesShown = true;

  private final ElixirMeter elixir;
  private final Table meter;
  private final Label elixirValue;
  private int side;
  private boolean compact;

  private final ViewState view;
  private final boolean replay;

  HandPanel(
      WorkspaceTheme theme,
      ViewState view,
      boolean replay,
      BiConsumer<Integer, Integer> selectCard,
      BiConsumer<Integer, Integer> useAbility) {
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
    meter = new Table();
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
    for (int slot = 0; slot < abilities.length; slot++) {
      final int index = slot;
      abilities[slot] = theme.button("", () -> useAbility.accept(side, index));
      TextButton ability = abilities[slot];
      ability.clearChildren();
      ability.pad(3, 7, 3, 7);
      // The cost, the champion's card and the button's state (arrangeAbilities); the keys are
      // named in the notice under the arena.
      ability.getLabel().setWrap(false);
      ability.getLabel().setEllipsis(true);
      ability.getLabel().setAlignment(Align.left);
      abilityCosts[slot] = theme.label("");
      abilityCosts[slot].setColor(Color.valueOf("d6adffff"));
      abilityStates[slot] = theme.label("");
      abilityStates[slot].setColor(MUTED);
      if (replay) ability.setTouchable(Touchable.disabled);
      // No width of their own: the name shortens to the width the cards give the column.
      table
          .add(ability)
          .prefWidth(0)
          .minWidth(0)
          .growX()
          .uniformX()
          .height(ABILITY_HEIGHT)
          .padRight(slot % 2 == 0 ? 6 : 0)
          .padBottom(6);
    }
    arrangeAbilities();
    table.row();
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
    // The champion slots' row fits a small window by the header's gaps.
    table.getCell(title).padBottom(compact ? 3 : 6);
    table.getCell(meter).padBottom(compact ? 5 : 10);
    arrangeAbilities();
    sizeAbilities();
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
    boolean anyAbility = false;
    for (int slot = 0; slot < abilities.length; slot++) {
      BattleFrame.AbilityView ability =
          player == null || player.abilities() == null ? null : player.abilities().get(slot);
      anyAbility |= ability != null;
      TextButton button = abilities[slot];
      button.setDisabled(ability == null || ability.unavailableReason() != null);
      if (ability == null) {
        abilityCosts[slot].setText("");
        abilityStates[slot].setText("");
        button.setText("No champion");
        continue;
      }
      abilityCosts[slot].setText(
          ability.cost() + (ability.charges() >= 0 ? " x" + ability.charges() : ""));
      abilityStates[slot].setText(abilityState(ability, compact));
      abilityStates[slot].setColor(
          ability.pending() ? Color.GOLD : ability.unavailableReason() == null ? ACCENT : MUTED);
      button.setText(displayName(ability.champion()));
    }
    if (anyAbility != abilitiesShown) {
      abilitiesShown = anyAbility;
      sizeAbilities();
    }
    next.setText(
        player == null || player.next() == null
            ? ""
            : "Next: "
                + displayName(player.next().name())
                + " "
                + BattleRenderer.levelText(player.next().level()));
  }

  /**
   * Lays out each champion slot's button: in a small window one line, the cost, the champion and a
   * short state; else the cost and the state over the champion.
   */
  private void arrangeAbilities() {
    for (int slot = 0; slot < abilities.length; slot++) {
      TextButton ability = abilities[slot];
      ability.clearChildren();
      if (compact) {
        ability.add(abilityCosts[slot]).left().padRight(5);
        ability.add(ability.getLabel()).minWidth(0).growX().left();
        ability.add(abilityStates[slot]).right().padLeft(5);
      } else {
        ability.add(abilityCosts[slot]).left();
        ability.add(abilityStates[slot]).right().expandX().row();
        ability.add(ability.getLabel()).colspan(2).minWidth(0).growX().left();
      }
    }
  }

  /** Sizes the champion slots' row: hidden while neither slot follows a champion. */
  private void sizeAbilities() {
    for (TextButton ability : abilities) {
      ability.setVisible(abilitiesShown);
      table
          .getCell(ability)
          .height(abilitiesShown ? (compact ? ABILITY_HEIGHT_COMPACT : ABILITY_HEIGHT) : 0)
          .padBottom(abilitiesShown ? 6 : 0);
      ability.invalidateHierarchy();
    }
    table.invalidateHierarchy();
  }

  /**
   * The state a champion slot's button shows: queued while a command given for it waits to run,
   * else the button state the game works out for the slot, the cooldown left in seconds; in a small
   * window only a short one.
   */
  private static String abilityState(BattleFrame.AbilityView ability, boolean compact) {
    if (ability.pending()) return "Queued";
    if (compact) {
      // One line leaves room for a short state only: the disabled button tells the rest.
      if (ability.unavailableReason() == null) return "Ready";
      return ability.state() == ChampionController.ON_COOLDOWN
          ? String.format(Locale.ROOT, "%.1fs", ability.cooldownMs() / 1000f)
          : "";
    }
    return switch (ability.state()) {
      case ChampionController.READY ->
          ability.unavailableReason() == null ? "Ready" : "Unavailable";
      case ChampionController.ABSENT -> "Not on field";
      case ChampionController.DEPLOYING -> "Deploying";
      case ChampionController.ON_COOLDOWN ->
          String.format(Locale.ROOT, "%.1f s", ability.cooldownMs() / 1000f);
      case ChampionController.NOT_ENOUGH_ELIXIR -> "Elixir";
      case ChampionController.CASTING -> "Casting";
      case ChampionController.PENDING -> "Pending";
      case ChampionController.ALL_CHARGES_CONSUMED -> "Used";
      case ChampionController.DISABLED -> "Disabled";
      default -> ability.unavailableReason() == null ? "Ready" : "Unavailable";
    };
  }
}
