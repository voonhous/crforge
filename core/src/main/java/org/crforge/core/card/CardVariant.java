package org.crforge.core.card;

/**
 * Represents one variant of a variant card (e.g. MergeMaiden's Mounted or Normal form). Each
 * variant has a mana trigger threshold that determines which form deploys based on the player's
 * current elixir at play time.
 *
 * @param name display name of this variant (e.g. "MergeMaiden_Mounted")
 * @param manaTrigger minimum elixir threshold to select this variant (checked in order)
 * @param cost elixir cost when this variant is selected
 * @param unitStats resolved TroopStats for the unit this variant spawns
 */
public record CardVariant(String name, int manaTrigger, int cost, TroopStats unitStats) {}
