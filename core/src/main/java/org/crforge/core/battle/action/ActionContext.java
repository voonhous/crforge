package org.crforge.core.battle.action;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The context a chain of actions shares: two boards of integer values, each value under a key, the
 * main board and the scratch board. A group whose row sets ContextMode makes one, or hands on the
 * one it was scheduled with, and the actions it schedules carry it on: through their queue entry,
 * their start and their run, to the actions they schedule in turn. Actions write values into it and
 * an expression reads them back by key, {@code as_int(#key, default)}.
 *
 * <p>A key is the 32-bit FNV-1a hash of its name, the same for a row's key column and for an
 * expression's {@code #name}; a key of hash 0 stands for no key. A read looks in the main board
 * first and then in the scratch board.
 *
 * <p>The game keeps contexts in a pool, counts who holds each, and empties both boards as the last
 * holder lets one go; a context made afresh for each group that creates one, and kept for as long
 * as anything refers to it, is the same to every read.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the two boards, the key as the FNV-1a hash of its name, the read in the main"
            + " board then the scratch board. A context emptied when its last holder lets it go"
            + " behaves as one made afresh, so the pool is left out. Not modelled: values of any"
            + " other type than an integer, which no row of the battle writes.")
public final class ActionContext {

  /** The FNV-1a offset basis. */
  private static final int FNV_OFFSET = 0x811c9dc5;

  /** The FNV-1a prime. */
  private static final int FNV_PRIME = 0x01000193;

  private final Map<Integer, Integer> main = new HashMap<>();
  private final Map<Integer, Integer> scratch = new HashMap<>();

  /**
   * The key of a name: its 32-bit FNV-1a hash over its bytes.
   *
   * @param name the key's name, without the {@code #} an expression writes before it
   */
  public static int key(String name) {
    int hash = FNV_OFFSET;
    for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
      hash = (hash ^ (b & 0xff)) * FNV_PRIME;
    }
    return hash;
  }

  /**
   * Writes a value under a key, replacing what the key held.
   *
   * @param toScratch true for the scratch board, false for the main board
   * @param key the key
   * @param value the value
   */
  public void write(boolean toScratch, int key, int value) {
    (toScratch ? scratch : main).put(key, value);
  }

  /**
   * The value under a key in one board alone, or null.
   *
   * @param fromScratch true for the scratch board, false for the main board
   * @param key the key
   */
  public Integer readBoard(boolean fromScratch, int key) {
    return (fromScratch ? scratch : main).get(key);
  }

  /**
   * The value under a key: the main board's, else the scratch board's, else null.
   *
   * @param key the key
   */
  public Integer read(int key) {
    Integer value = main.get(key);
    return value != null ? value : scratch.get(key);
  }
}
