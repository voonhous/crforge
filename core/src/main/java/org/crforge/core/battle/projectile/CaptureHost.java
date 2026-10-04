package org.crforge.core.battle.projectile;

import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;

/**
 * What a capture's run asks of the object it runs on: a projectile, as the evolved Snowball's
 * rolling snowball, or a character, as the evolved Goblin Cage. See {@link CaptureRun}.
 */
public interface CaptureHost {

  /** The object the capture runs on. */
  BattleEntity owner();

  /** The owner's id, which its lock requests and claims are made under. */
  int id();

  /** The owner's row name, for the messages of what is refused. */
  String name();

  /** Where the owner stands along the width. */
  int x();

  /** Where the owner stands along the length. */
  int y();

  /** The owner's battle. */
  BattleWorld world();

  /** The owner's action holder, the cause of what the run schedules on a captured object. */
  ActionHolder actionHolder();

  /**
   * True when the run grants its claims and makes new ones this step: always for a projectile; for
   * a character only while its targeting component is on, which a deploy or a stun switches off.
   */
  boolean claims();

  /** The object query of the capture around the owner's point, the filter asked for its side. */
  List<WorldEntity> query(int radius, GameObjectFilter filter);

  /** Whether a claimed unit still passes the capture's filter, asked for the owner's side. */
  boolean passes(WorldEntity unit, GameObjectFilter filter);

  /** The capture buff on a unit it captured, the owner its parent and source. */
  void buff(WorldEntity unit, String buff, int timeMs);

  /** Schedules an action, built on the owner, on the owner with a captured unit as its cause. */
  void scheduleOnOwner(WorldEntity cause, String action);

  /** The owner's one-step tag word gains HAS_CAPTURE, as each completed drag raises it. */
  void hasCapture();

  /** One step of the hit timer, as the owner's buffs scale its hit speed. */
  int hitStep(int stepMs);

  /**
   * One hit of the capture on a captured unit: its damage per hit at the owner's level, through the
   * damage entry with the owner as the attacker, passing a hidden unit.
   */
  void hit(WorldEntity unit, int damagePerHit);
}
