/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.env.Fields.Field;
import org.crforge.env.Fields.Tag;
import org.crforge.env.Fields.V4Source;

/**
 * The field export's plan and its scratch: where the export puts each value of the v4 observations
 * ({@link Fields#v4Source}), the public fields, the seat fields of the header and the seat entity
 * columns, worked out once from the registry, and how a block is built and copied out. The export
 * copies the values the v4 packer wrote, so its seat fields are the observation by construction and
 * the packer is the one place their rules live.
 *
 * <p>A block is mostly its entity table, field after field, each {@link Fields#ENTITY_CAPACITY}
 * rows long, and a battle fills few of its rows. The export therefore zeroes and copies only the
 * leading rows of each table field that are in use, in the scratch it builds in and in the block it
 * writes, keeping every other row 0.
 */
final class FieldExport {

  private FieldExport() {
    // Static plan
  }

  private static final int CAP = Fields.ENTITY_CAPACITY;

  /** Each public field's v4 header index and its place in the block. */
  private static final int[] PUBLIC_FROM;

  private static final int[] PUBLIC_TO;

  /**
   * Each element of a header seat field: its v4 header index, its place in the block at seat 0, and
   * the integers a seat takes (its place at seat s is {@code to + s * stride}).
   */
  private static final int[] HEADER_FROM;

  private static final int[] HEADER_TO;
  private static final int[] HEADER_STRIDE;

  /**
   * Each element of a seat entity column: its index in a v4 entity row, its place in the block at
   * seat 0 and row 0, and the integers a row takes (its place at seat s and row r is {@code to + (s
   * * CAP + r) * stride}).
   */
  private static final int[] ENTITY_FROM;

  private static final int[] ENTITY_TO;
  private static final int[] ENTITY_STRIDE;

  /**
   * The entity table's columns, a seat field's once per seat: where each starts in the block and
   * the integers a row takes. {@code entity.battle_id} is left out: it is written in full every
   * time, -1 on the rows holding no entity.
   */
  private static final int[] TABLE_START;

  private static final int[] TABLE_STRIDE;

  /** The runs of the block outside the entity table, each written in full every time. */
  private static final int[] RUN_START;

  private static final int[] RUN_LENGTH;

  /** Where {@code entity.battle_id} starts. */
  private static final int BATTLE_ID = Fields.ENTITY_BATTLE_ID.offset();

  static {
    List<int[]> pub = new ArrayList<>();
    List<int[]> header = new ArrayList<>();
    List<int[]> entity = new ArrayList<>();
    List<int[]> table = new ArrayList<>();
    List<int[]> runs = new ArrayList<>();
    for (Field field : Fields.fields()) {
      if (field.aliasOf() != null) {
        continue;
      }
      // A table field: a seat field with the rows as its second axis, a hidden one as its first.
      int axis = field.tag() == Tag.SEAT ? 1 : 0;
      boolean inTable = field.shape().size() > axis && field.shape().get(axis) == CAP;
      if (!inTable) {
        int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        if (last != null && last[0] + last[1] == field.offset()) {
          last[1] += field.size();
        } else {
          runs.add(new int[] {field.offset(), field.size()});
        }
      } else if (field != Fields.ENTITY_BATTLE_ID) {
        int seats = axis == 1 ? 2 : 1;
        int stride = field.size() / (seats * CAP);
        for (int seat = 0; seat < seats; seat++) {
          table.add(new int[] {field.offset() + seat * CAP * stride, stride});
        }
      }
      V4Source source = Fields.v4Source(field.name());
      if (source == null) {
        continue;
      }
      List<Integer> indices = source.indices();
      if (field.tag() == Tag.PUBLIC) {
        check(field, indices.size() == 1 && field.size() == 1);
        pub.add(new int[] {indices.get(0), field.offset(), 0});
      } else if (!source.entity()) {
        int stride = field.size() / 2;
        check(field, field.tag() == Tag.SEAT && indices.size() == stride);
        for (int e = 0; e < stride; e++) {
          header.add(new int[] {indices.get(e), field.offset() + e, stride});
        }
      } else {
        int stride = field.size() / (2 * CAP);
        check(field, field.tag() == Tag.SEAT && inTable && indices.size() == stride);
        for (int e = 0; e < stride; e++) {
          entity.add(new int[] {indices.get(e), field.offset() + e, stride});
        }
      }
    }
    PUBLIC_FROM = column(pub, 0);
    PUBLIC_TO = column(pub, 1);
    HEADER_FROM = column(header, 0);
    HEADER_TO = column(header, 1);
    HEADER_STRIDE = column(header, 2);
    ENTITY_FROM = column(entity, 0);
    ENTITY_TO = column(entity, 1);
    ENTITY_STRIDE = column(entity, 2);
    TABLE_START = column(table, 0);
    TABLE_STRIDE = column(table, 1);
    RUN_START = column(runs, 0);
    RUN_LENGTH = column(runs, 1);
  }

  private static void check(Field field, boolean holds) {
    if (!holds) {
      throw new IllegalStateException("the field " + field.name() + " has no v4 place to copy");
    }
  }

  private static int[] column(List<int[]> rows, int at) {
    int[] values = new int[rows.size()];
    for (int k = 0; k < values.length; k++) {
      values[k] = rows.get(k)[at];
    }
    return values;
  }

  /**
   * The arrays an export is built in, one set per thread: an environment's export is built and
   * copied out within one call on its own thread, so the environments a thread steps share them,
   * and an environment holds none (a fork copies none).
   */
  static final class Scratch {

    /**
     * The block under construction, {@link Fields#INTS} integers: between two exports every row of
     * the entity table from {@link #rows} on is 0.
     */
    final int[] block = new int[Fields.INTS];

    /** Both seats' observations as last written, {@link Schema#SEAT_INTS} each, for a fork's. */
    final int[] seats = new int[2 * Schema.SEAT_INTS];

    /** The leading rows of the entity table the last export wrote. */
    private int rows;

    /** The leading rows the export being built writes, so far. */
    private int written;

    /** Zeroes the rows the last export wrote, for a new one. */
    private void begin() {
      if (rows > 0) {
        for (int k = 0; k < TABLE_START.length; k++) {
          int from = TABLE_START[k];
          Arrays.fill(block, from, from + rows * TABLE_STRIDE[k], 0);
        }
      }
      rows = 0;
      written = 0;
    }

    /**
     * Copies a seat's v4 observation into the block's seat fields, and seat 0's into the public
     * fields: the header's values, and each v4 entity row's into the export row it was written
     * from. The rows no v4 row names are left 0.
     *
     * @param seat the seat, 0 or 1
     * @param source the array holding the seat's observation
     * @param base where the observation starts in it
     * @param exportRows the export row each v4 entity row was written from, by v4 row; a row past
     *     the export's capacity is left out
     */
    void scatter(int seat, int[] source, int base, int[] exportRows) {
      if (seat == 0) {
        for (int k = 0; k < PUBLIC_FROM.length; k++) {
          block[PUBLIC_TO[k]] = source[base + PUBLIC_FROM[k]];
        }
      }
      for (int k = 0; k < HEADER_FROM.length; k++) {
        block[HEADER_TO[k] + seat * HEADER_STRIDE[k]] = source[base + HEADER_FROM[k]];
      }
      int listed = source[base + Schema.H_ENTITY_COUNT];
      for (int v = 0; v < listed; v++) {
        int row = exportRows[v];
        if (row >= CAP) {
          continue;
        }
        written = Math.max(written, row + 1);
        int at = base + Schema.HEADER_INTS + v * Schema.ENTITY_INTS;
        int place = seat * CAP + row;
        for (int k = 0; k < ENTITY_FROM.length; k++) {
          block[ENTITY_TO[k] + place * ENTITY_STRIDE[k]] = source[at + ENTITY_FROM[k]];
        }
      }
    }

    /**
     * Copies the block out and readies the scratch for the next export.
     *
     * @param target the environment's block
     * @param rows the rows of the entity table holding an entity, the leading ones
     * @param targetRows the leading rows of the target that may not be 0 before the copy
     * @return the leading rows of the target that may not be 0 after it
     */
    int end(IntBuffer target, int rows, int targetRows) {
      written = Math.max(written, rows);
      Arrays.fill(block, BATTLE_ID + rows, BATTLE_ID + CAP, -1);
      int used = Math.max(written, targetRows);
      if (2 * used > CAP) {
        target.put(0, block, 0, Fields.INTS);
      } else {
        for (int k = 0; k < RUN_START.length; k++) {
          target.put(RUN_START[k], block, RUN_START[k], RUN_LENGTH[k]);
        }
        target.put(BATTLE_ID, block, BATTLE_ID, CAP);
        for (int k = 0; k < TABLE_START.length; k++) {
          int from = TABLE_START[k];
          target.put(from, block, from, used * TABLE_STRIDE[k]);
        }
      }
      this.rows = written;
      return written;
    }
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  /** The calling thread's scratch, readied for a new export. */
  static Scratch begin() {
    Scratch scratch = SCRATCH.get();
    scratch.begin();
    return scratch;
  }
}
