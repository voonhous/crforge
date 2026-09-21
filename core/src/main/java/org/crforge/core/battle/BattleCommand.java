package org.crforge.core.battle;

/**
 * Something a player or the match does to the battle from outside the entity tick: a deployment, an
 * ability press, a scripted spawn.
 *
 * <p>A command is stamped with the battle tick it is due on. It never runs in the middle of an
 * entity tick: the battle executes due commands at the tail of the step, after every entity has
 * been visited, so what a command creates first takes part in the following tick.
 */
public interface BattleCommand {

  /** The battle tick this command is due on. */
  int tick();

  /** Applies the command. */
  void execute(Battle battle);
}
