package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkState;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GoblinsteinAbility;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * One run of Goblinstein's ability action on the area effect that follows the doctor.
 *
 * <p>Its first step connects: it asks the object the area effect follows for the first unit of its
 * group chain, and takes the one after that when the followed object is itself the first, so the
 * doctor connects to the monster made before it; an object in no chain connects to nothing. Every
 * later step waits for the followed object to cast its ability, which only the ability's request
 * leads to; the tether that starts once the cast ends is refused.
 *
 * <p>When the connected object leaves, the run forgets it and, the first time, makes the row's
 * death area at its point, for the area effect's side and at its level, the area effect its parent
 * and following nothing, and holds it as the connected object. When the area effect itself leaves,
 * before any notice of it, a death area it still holds ends: its countdown goes to 0, so it leaves
 * in the next round of the same cleanup.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the connection to the followed object's chain head, or the one"
            + " after it, the wait for the cast, the death area made once at the connected object's"
            + " point as it leaves, held in its place, and ended as the area effect leaves; held by"
            + " goblinstein_tower and goblinstein_doctor_first. Refused: the followed object"
            + " casting, after which the tether would start.")
final class GoblinsteinRun extends ActionInstance {

  /** The first step, which connects. */
  private static final int CONNECTING = 0;

  /** Every step after it, waiting for the cast. */
  private static final int WAITING = 1;

  private final BattleWorld world;
  private final GoblinsteinAbility ability;
  private final AreaEffectEntity owner;

  private int state = CONNECTING;

  /** The unit it is connected to, then the death area it made; null for none. */
  private BattleEntity connected;

  /** True once the death area is made: it is made at most once. */
  private boolean deathAreaMade;

  GoblinsteinRun(BattleWorld world, GoblinsteinAbility ability, AreaEffectEntity owner) {
    super(ability);
    this.world = world;
    this.ability = ability;
    this.owner = owner;
  }

  @Override
  protected void update(ActionHolder holder) {
    SpawnHost follow = owner.getFollow();
    checkState(follow != null, () -> ability.name() + " steps on an area effect that follows none");
    if (state == CONNECTING) {
      CharacterEntity head = follow instanceof CharacterEntity unit ? unit.chainHead() : null;
      connected = head == follow ? head.chainNext() : head;
      state = WAITING;
      world.goblinsteinConnected(owner, connected);
      return;
    }
    if (follow instanceof CharacterEntity unit
        && unit.getView().getState() == GridEntityState.CASTING) {
      throw new UnsupportedOperationException(
          unit.name()
              + " casts its ability under "
              + ability.name()
              + ", whose tether after the cast is not modelled");
    }
  }

  @Override
  protected void objectLeft(int leftId) {
    if (connected == null || connected.getId() != leftId) {
      return;
    }
    BattleEntity left = connected;
    connected = null;
    String row = ability.getColumns().deathAreaEffect();
    if (deathAreaMade || row == null) {
      return;
    }
    WorldEntity unit = (WorldEntity) left;
    int x = unit.getView().getX();
    int y = unit.getView().getY();
    AreaEffectEntity deathArea = world.goblinsteinDeathArea(owner, row, x, y);
    connected = deathArea;
    deathAreaMade = true;
    world.goblinsteinDeathAreaMade(owner, unit, deathArea, x, y);
  }

  @Override
  protected void ownerLeaving(ActionHolder holder) {
    if (deathAreaMade && connected instanceof AreaEffectEntity deathArea) {
      deathArea.end();
      connected = null;
      world.goblinsteinDeathAreaEnded(owner, deathArea);
    }
  }
}
