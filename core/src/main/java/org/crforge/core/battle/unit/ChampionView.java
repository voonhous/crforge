package org.crforge.core.battle.unit;

/**
 * What a champion slot and an ability command read of one character of a champion row.
 *
 * @param name the character's name
 * @param row its row's name
 * @param deployIndex the deploy count of the play that made it, or -1
 * @param state its state
 * @param pending whether a requested ability waits on it
 * @param warning the visits left before its ability's effect fires, below zero once it has
 * @param tags its tags the slot reads: the one that disables the ability and the one that pauses
 *     the cooldown
 * @param cloned whether it is a clone
 */
public record ChampionView(
    String name,
    String row,
    int deployIndex,
    int state,
    boolean pending,
    int warning,
    long tags,
    boolean cloned) {}
