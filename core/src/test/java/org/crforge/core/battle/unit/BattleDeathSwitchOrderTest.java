package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The death slot switches the dying unit's movement component off only after its buffs have ended:
 * a Goblin Curse's goblin, made as the curse ends on a dying unit and visited at once, still finds
 * the dying unit moving, and steers around it by that unit's own avoidance blend.
 *
 * <p>The scene: the top side's Skeletons are played in front of its right princess tower and the
 * bottom side's Goblin Curse is cast on them as they deploy; its damage kills each Skeleton as it
 * walks off, and the curse makes a goblin for the bottom side where it stood.
 */
class BattleDeathSwitchOrderTest {

  /** The Skeletons' level. */
  private static final int LEVEL = 1;

  /** The curse's level, whose damage kills a Skeleton of {@link #LEVEL} in two ticks. */
  private static final int CURSE_LEVEL = 6;

  /** The tick by which every Skeleton has died. */
  private static final int LAST_TICK = 200;

  /** One curse death spawn, as the dying unit stood when its goblin was made. */
  private record Spawn(WorldEntity dying, boolean movementOn, CharacterEntity goblin) {}

  private static List<Spawn> curseSpawns() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<Spawn> spawns = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffDeathSpawn(
                  int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> made) {
                for (CharacterEntity goblin : made) {
                  spawns.add(new Spawn(dying, dying.getView().isMovementActive(), goblin));
                }
              }
            });
    match.play(20, GameData.card("Skeletons"), LEVEL, 1, 14500, 23000, "Skeletons");
    match.play(24, GameData.card("GoblinCurse"), CURSE_LEVEL, 0, 14500, 23000, "GoblinCurse");
    while (match.getBattle().getTick() < LAST_TICK) {
      match.getBattle().step();
    }
    assertThat(spawns).as("each Skeleton died cursed and left a goblin").hasSize(3);
    return spawns;
  }

  @Test
  @DisplayName("a cursed unit's goblin is made while the dying unit's movement is still on")
  void theGoblinIsMadeBeforeTheMovementSwitch() {
    for (Spawn spawn : curseSpawns()) {
      assertThat(spawn.movementOn())
          .as("%s moving as its goblin is made", spawn.dying().name())
          .isTrue();
      assertThat(spawn.dying().getView().isMovementActive())
          .as("%s's movement off after its death slot", spawn.dying().name())
          .isFalse();
    }
  }
}
