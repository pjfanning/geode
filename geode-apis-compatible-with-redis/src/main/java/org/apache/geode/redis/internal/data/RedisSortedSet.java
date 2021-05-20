/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.apache.geode.redis.internal.data;

import static it.unimi.dsi.fastutil.bytes.ByteArrays.HASH_STRATEGY;
import static org.apache.geode.internal.JvmSizeUtils.memoryOverhead;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_NOT_A_VALID_FLOAT;
import static org.apache.geode.redis.internal.data.RedisDataType.REDIS_SORTED_SET;
import static org.apache.geode.redis.internal.netty.Coder.bytesToDouble;
import static org.apache.geode.redis.internal.netty.Coder.doubleToBytes;
import static org.apache.geode.redis.internal.netty.Coder.stripTrailingZeroFromDouble;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.bGREATEST_MEMBER_NAME;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.bLEAST_MEMBER_NAME;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.Region;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.size.Sizeable;
import org.apache.geode.redis.internal.RedisConstants;
import org.apache.geode.redis.internal.collections.OrderStatisticsTree;
import org.apache.geode.redis.internal.collections.SizeableObject2ObjectOpenCustomHashMapWithCursor;
import org.apache.geode.redis.internal.delta.AddsDeltaInfo;
import org.apache.geode.redis.internal.delta.DeltaInfo;
import org.apache.geode.redis.internal.delta.RemsDeltaInfo;
import org.apache.geode.redis.internal.executor.sortedset.AbstractSortedSetRangeOptions;
import org.apache.geode.redis.internal.executor.sortedset.SortedSetLexRangeOptions;
import org.apache.geode.redis.internal.executor.sortedset.SortedSetScoreRangeOptions;
import org.apache.geode.redis.internal.executor.sortedset.ZAddOptions;

public class RedisSortedSet extends AbstractRedisData {
  protected static final int REDIS_SORTED_SET_OVERHEAD = memoryOverhead(RedisSortedSet.class);

  private MemberMap members;
  private final ScoreSet scoreSet = new ScoreSet();

  @Override
  public int getSizeInBytes() {
    return REDIS_SORTED_SET_OVERHEAD + members.getSizeInBytes() + scoreSet.getSizeInBytes();
  }

  RedisSortedSet(List<byte[]> members) {
    this.members = new MemberMap(members.size() / 2);

    Iterator<byte[]> iterator = members.iterator();

    while (iterator.hasNext()) {
      byte[] score = iterator.next();
      byte[] member = iterator.next();
      memberAdd(member, score);
    }
  }

  protected int getSortedSetSize() {
    return scoreSet.size();
  }

  // for serialization
  public RedisSortedSet() {}

  @Override
  protected void applyDelta(DeltaInfo deltaInfo) {
    if (deltaInfo instanceof AddsDeltaInfo) {
      AddsDeltaInfo addsDeltaInfo = (AddsDeltaInfo) deltaInfo;
      membersAddAll(addsDeltaInfo);
    } else {
      RemsDeltaInfo remsDeltaInfo = (RemsDeltaInfo) deltaInfo;
      membersRemoveAll(remsDeltaInfo);
    }
  }

  /**
   * Since GII (getInitialImage) can come in and call toData while other threads are modifying this
   * object, the striped executor will not protect toData. So any methods that modify "members"
   * needs to be thread safe with toData.
   */

  @Override
  public synchronized void toData(DataOutput out, SerializationContext context) throws IOException {
    super.toData(out, context);
    InternalDataSerializer.writePrimitiveInt(members.size(), out);
    for (Map.Entry<byte[], OrderedSetEntry> entry : members.entrySet()) {
      byte[] member = entry.getKey();
      byte[] score = entry.getValue().getScoreBytes();
      InternalDataSerializer.writeByteArray(member, out);
      InternalDataSerializer.writeByteArray(score, out);
    }
  }

  @Override
  public void fromData(DataInput in, DeserializationContext context)
      throws IOException, ClassNotFoundException {
    super.fromData(in, context);
    int size = InternalDataSerializer.readPrimitiveInt(in);
    members = new MemberMap(size);
    for (int i = 0; i < size; i++) {
      byte[] member = InternalDataSerializer.readByteArray(in);
      byte[] score = InternalDataSerializer.readByteArray(in);
      OrderedSetEntry newEntry = new OrderedSetEntry(member, score);
      members.put(member, newEntry);
      scoreSet.add(newEntry);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RedisSortedSet)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    RedisSortedSet other = (RedisSortedSet) o;
    if (members.size() != other.members.size()) {
      return false;
    }

    for (Map.Entry<byte[], OrderedSetEntry> entry : members.entrySet()) {
      OrderedSetEntry otherEntry = other.members.get(entry.getKey());
      if (otherEntry == null) {
        return false;
      }
      if (Double.compare(otherEntry.getScore(), entry.getValue().getScore()) != 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int getDSFID() {
    return REDIS_SORTED_SET_ID;
  }

  protected synchronized byte[] memberAdd(byte[] memberToAdd, byte[] scoreToAdd) {
    OrderedSetEntry existingEntry = members.get(memberToAdd);
    if (existingEntry == null) {
      OrderedSetEntry newEntry = new OrderedSetEntry(memberToAdd, scoreToAdd);
      members.put(memberToAdd, newEntry);
      scoreSet.add(newEntry);
      return null;
    } else {
      scoreSet.remove(existingEntry);
      byte[] oldScore = existingEntry.scoreBytes;
      existingEntry.updateScore(stripTrailingZeroFromDouble(scoreToAdd));
      members.put(memberToAdd, existingEntry);
      scoreSet.add(existingEntry);
      return oldScore;
    }
  }

  synchronized byte[] memberRemove(byte[] member) {
    byte[] oldValue = null;
    OrderedSetEntry orderedSetEntry = members.remove(member);
    if (orderedSetEntry != null) {
      scoreSet.remove(orderedSetEntry);
      oldValue = orderedSetEntry.getScoreBytes();
    }

    return oldValue;
  }

  private synchronized void membersAddAll(AddsDeltaInfo addsDeltaInfo) {
    Iterator<byte[]> iterator = addsDeltaInfo.getAdds().iterator();
    while (iterator.hasNext()) {
      byte[] member = iterator.next();
      byte[] score = iterator.next();
      memberAdd(member, score);
    }
  }

  private synchronized void membersRemoveAll(RemsDeltaInfo remsDeltaInfo) {
    for (byte[] member : remsDeltaInfo.getRemoves()) {
      memberRemove(member);
    }
  }

  /**
   * @param region the region this instance is stored in
   * @param key the name of the set to add to
   * @param membersToAdd members to add to this set; NOTE this list may by modified by this call
   * @return the number of members actually added OR incremented value if INCR option specified
   */
  Object zadd(Region<RedisKey, RedisData> region, RedisKey key, List<byte[]> membersToAdd,
      ZAddOptions options) {
    if (options.isINCR()) {
      return zaddIncr(region, key, membersToAdd, options);
    }
    AddsDeltaInfo deltaInfo = null;
    Iterator<byte[]> iterator = membersToAdd.iterator();
    int initialSize = scoreSet.size();
    int changesCount = 0;
    while (iterator.hasNext()) {
      byte[] score = iterator.next();
      byte[] member = iterator.next();
      if (options.isNX() && members.containsKey(member)) {
        continue;
      }
      if (options.isXX() && !members.containsKey(member)) {
        continue;
      }
      byte[] oldScore = memberAdd(member, score);
      if (options.isCH() && oldScore != null
          && !Arrays.equals(oldScore, stripTrailingZeroFromDouble(score))) {
        changesCount++;
      }

      if (deltaInfo == null) {
        deltaInfo = new AddsDeltaInfo(new ArrayList<>());
      }
      deltaInfo.add(member);
      deltaInfo.add(score);
    }

    storeChanges(region, key, deltaInfo);
    return scoreSet.size() - initialSize + changesCount;
  }

  long zcard() {
    return members.size();
  }

  long zcount(SortedSetScoreRangeOptions rangeOptions) {
    int minIndex = getIndexByScore(rangeOptions.getStartRange(),
        rangeOptions.isStartExclusive(), true);

    int maxIndex = getIndexByScore(rangeOptions.getEndRange(),
        rangeOptions.isEndExclusive(), false);

    return maxIndex - minIndex;
  }

  byte[] zincrby(Region<RedisKey, RedisData> region, RedisKey key, byte[] increment,
      byte[] member) {
    OrderedSetEntry orderedSetEntry = members.get(member);
    double incr = processByteArrayAsDouble(increment);

    if (orderedSetEntry != null) {
      double byteScore = orderedSetEntry.getScore();
      incr += byteScore;

      if (Double.isNaN(incr)) {
        throw new NumberFormatException(RedisConstants.ERROR_OPERATION_PRODUCED_NAN);
      }
    }

    byte[] byteIncr = doubleToBytes(incr);
    memberAdd(member, byteIncr);

    AddsDeltaInfo deltaInfo = new AddsDeltaInfo(new ArrayList<>());
    deltaInfo.add(member);
    deltaInfo.add(byteIncr);

    storeChanges(region, key, deltaInfo);

    return byteIncr;
  }

  List<byte[]> zrange(int min, int max, boolean withScores) {
    return getRange(min, max, withScores, false);
  }

  List<byte[]> zrangebylex(SortedSetLexRangeOptions rangeOptions) {
    // Assume that all members have the same score. Behaviour is unspecified otherwise.
    double score = scoreSet.get(0).score;

    int minIndex =
        getIndexByLex(score, rangeOptions.getStartRange(), rangeOptions.isStartExclusive(), true);
    if (minIndex >= scoreSet.size()) {
      return Collections.emptyList();
    }

    int maxIndex =
        getIndexByLex(score, rangeOptions.getEndRange(), rangeOptions.isEndExclusive(), false);
    if (minIndex == maxIndex) {
      return Collections.emptyList();
    }

    return addLimitToRange(rangeOptions, false, false, minIndex, maxIndex);
  }

  List<byte[]> zrangebyscore(SortedSetScoreRangeOptions rangeOptions, boolean withScores) {
    int minIndex =
        getIndexByScore(rangeOptions.getStartRange(), rangeOptions.isStartExclusive(), true);
    if (minIndex >= scoreSet.size()) {
      return Collections.emptyList();
    }

    int maxIndex =
        getIndexByScore(rangeOptions.getEndRange(), rangeOptions.isEndExclusive(), false);
    if (minIndex == maxIndex) {
      return Collections.emptyList();
    }

    // Okay, if we make it this far there's a potential range of things to return.
    return addLimitToRange(rangeOptions, withScores, false, minIndex, maxIndex);
  }

  long zrank(byte[] member) {
    OrderedSetEntry orderedSetEntry = members.get(member);
    if (orderedSetEntry == null) {
      return -1;
    }
    return scoreSet.indexOf(orderedSetEntry);
  }

  long zrem(Region<RedisKey, RedisData> region, RedisKey key, List<byte[]> membersToRemove) {
    int membersRemoved = 0;
    RemsDeltaInfo deltaInfo = null;
    for (byte[] memberToRemove : membersToRemove) {
      if (memberRemove(memberToRemove) != null) {
        if (deltaInfo == null) {
          deltaInfo = new RemsDeltaInfo();
        }
        deltaInfo.add(memberToRemove);
        membersRemoved++;
      }
    }
    storeChanges(region, key, deltaInfo);
    return membersRemoved;
  }

  List<byte[]> zrevrange(int min, int max, boolean withScores) {
    return getRange(min, max, withScores, true);
  }

  List<byte[]> zrevrangebyscore(SortedSetScoreRangeOptions rangeOptions, boolean withScores) {
    int maxIndex =
        getIndexByScore(rangeOptions.getStartRange(), rangeOptions.isStartExclusive(), false);

    int minIndex = getIndexByScore(rangeOptions.getEndRange(), rangeOptions.isEndExclusive(), true);

    if (minIndex > getSortedSetSize()) {
      return Collections.emptyList();
    }

    if (minIndex == maxIndex) {
      return Collections.emptyList();
    }

    // Okay, if we make it this far there's a potential range of things to return.
    return addLimitToRange(rangeOptions, withScores, true, minIndex, maxIndex);
  }

  long zrevrank(byte[] member) {
    OrderedSetEntry orderedSetEntry = members.get(member);
    if (orderedSetEntry == null) {
      return -1;
    }
    return scoreSet.size() - scoreSet.indexOf(orderedSetEntry) - 1;
  }

  byte[] zscore(byte[] member) {
    OrderedSetEntry orderedSetEntry = members.get(member);
    if (orderedSetEntry != null) {
      return orderedSetEntry.getScoreBytes();
    }
    return null;
  }

  List<byte[]> zpopmax(Region<RedisKey, RedisData> region, RedisKey key, int count) {
    Iterator<AbstractOrderedSetEntry> scoresIterator =
        scoreSet.getIndexRange(scoreSet.size() - 1, count, true);

    if (!scoresIterator.hasNext()) {
      return Collections.emptyList();
    }

    List<byte[]> result = new ArrayList<>();
    RemsDeltaInfo deltaInfo = new RemsDeltaInfo();
    while (scoresIterator.hasNext()) {
      AbstractOrderedSetEntry entry = scoresIterator.next();
      scoresIterator.remove();
      members.remove(entry.member);

      result.add(entry.member);
      result.add(entry.scoreBytes);
      deltaInfo.add(entry.member);
    }

    storeChanges(region, key, deltaInfo);

    return result;
  }

  private byte[] zaddIncr(Region<RedisKey, RedisData> region, RedisKey key,
      List<byte[]> membersToAdd, ZAddOptions options) {
    // for zadd incr option, only one incrementing element pair is allowed to get here.
    byte[] increment = membersToAdd.get(0);
    byte[] member = membersToAdd.get(1);
    if (options.isNX() && members.containsKey(member)) {
      return null;
    }
    if (options.isXX() && !members.containsKey(member)) {
      return null;
    }
    return zincrby(region, key, increment, member);
  }

  private int getIndexByScore(Double startRange, boolean isExclusive, boolean isMinimum) {
    AbstractOrderedSetEntry entry =
        new ScoreDummyOrderedSetEntry(startRange, isExclusive, isMinimum);
    return scoreSet.indexOf(entry);
  }

  private int getIndexByLex(double score, byte[] rangeValue, boolean isExclusive,
      boolean isMinimum) {
    AbstractOrderedSetEntry minEntry =
        new MemberDummyOrderedSetEntry(rangeValue, score, isExclusive, isMinimum);
    return scoreSet.indexOf(minEntry);
  }

  private List<byte[]> getRange(int min, int max, boolean withScores, boolean isReverse) {
    List<byte[]> result = new ArrayList<>();
    int start;
    int rangeSize;
    if (isReverse) {
      // scoreSet.size() - 1 is the maximum index of elements in the sorted set
      start = scoreSet.size() - 1 - getBoundedStartIndex(min, scoreSet.size());
      int end = scoreSet.size() - 1 - getBoundedEndIndex(max, scoreSet.size());
      // Add one to rangeSize because the range is inclusive, so even if start == end, we return one
      // element
      rangeSize = start - end + 1;
    } else {
      start = getBoundedStartIndex(min, scoreSet.size());
      int end = getBoundedEndIndex(max, scoreSet.size());
      // Add one to rangeSize because the range is inclusive, so even if start == end, we return one
      // element
      rangeSize = end - start + 1;
    }
    if (rangeSize <= 0 || start == scoreSet.size()) {
      return result;
    }

    Iterator<AbstractOrderedSetEntry> entryIterator =
        scoreSet.getIndexRange(start, rangeSize, isReverse);
    while (entryIterator.hasNext()) {
      AbstractOrderedSetEntry entry = entryIterator.next();
      result.add(entry.member);
      if (withScores) {
        result.add(entry.scoreBytes);
      }
    }
    return result;
  }

  private List<byte[]> addLimitToRange(AbstractSortedSetRangeOptions<?> rangeOptions,
      boolean withScores, boolean isReverse,
      int minIndex, int maxIndex) {
    int count = Integer.MAX_VALUE;
    if (rangeOptions.hasLimit()) {
      count = rangeOptions.getCount();
      if (isReverse) {
        maxIndex -= rangeOptions.getOffset();
        if (maxIndex < 0) {
          return Collections.emptyList();
        }
      } else {
        minIndex += rangeOptions.getOffset();
        if (minIndex > getSortedSetSize() || minIndex > maxIndex) {
          return Collections.emptyList();
        }
      }
    }

    int maxElements = Math.min(count, maxIndex - minIndex);

    int startIndex = isReverse ? maxIndex - 1 : minIndex;
    Iterator<AbstractOrderedSetEntry> entryIterator =
        scoreSet.getIndexRange(startIndex, maxElements, isReverse);

    if (withScores) {
      maxElements *= 2;
    }

    List<byte[]> result = new ArrayList<>(maxElements);
    while (entryIterator.hasNext()) {
      AbstractOrderedSetEntry entry = entryIterator.next();

      result.add(entry.member);
      if (withScores) {
        result.add(entry.scoreBytes);
      }
    }
    return result;
  }

  private int getBoundedStartIndex(int index, int size) {
    if (index >= 0) {
      return Math.min(index, size);
    } else {
      return Math.max(index + size, 0);
    }
  }

  private int getBoundedEndIndex(int index, int size) {
    if (index >= 0) {
      return Math.min(index, size);
    } else {
      return Math.max(index + size, -1);
    }
  }

  @Override
  public RedisDataType getType() {
    return REDIS_SORTED_SET;
  }

  @Override
  protected boolean removeFromRegion() {
    return members.isEmpty();
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), members);
  }

  @Override
  public String toString() {
    return "RedisSortedSet{" + super.toString() + ", " + "size=" + members.size() + '}';
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return null;
  }

  public static double processByteArrayAsDouble(byte[] value) {
    try {
      double doubleValue = bytesToDouble(value);
      if (Double.isNaN(doubleValue)) {
        throw new NumberFormatException(ERROR_NOT_A_VALID_FLOAT);
      }
      return doubleValue;
    } catch (NumberFormatException nfe) {
      throw new NumberFormatException(ERROR_NOT_A_VALID_FLOAT);
    }
  }

  // Comparison to allow the use of bLEAST_MEMBER_NAME and bGREATEST_MEMBER_NAME to always be less
  // than or greater than respectively any other byte array representing a member name. The use of
  // "==" is important as it allows us to differentiate between the constant byte arrays defined
  // in StringBytesGlossary and user-supplied member names which may be equal in content but have
  // a different memory address.
  public static int checkDummyMemberNames(byte[] array1, byte[] array2) {
    if (array2 == bLEAST_MEMBER_NAME || array1 == bGREATEST_MEMBER_NAME) {
      if (array1 == bLEAST_MEMBER_NAME || array2 == bGREATEST_MEMBER_NAME) {
        throw new IllegalStateException(
            "Arrays cannot both be least member name or greatest member name");
      }
      return 1; // array2 < array1
    } else if (array1 == bLEAST_MEMBER_NAME || array2 == bGREATEST_MEMBER_NAME) {
      return -1; // array1 < array2
    } else {
      // Neither of the input arrays are using bLEAST_MEMBER_NAME or bGREATEST_MEMBER_NAME, so real
      // lexicographical comparison is needed
      return 0;
    }
  }

  @VisibleForTesting
  public static int javaImplementationOfAnsiCMemCmp(byte[] array1, byte[] array2) {
    int shortestLength = Math.min(array1.length, array2.length);
    for (int i = 0; i < shortestLength; i++) {
      int localComp = Byte.toUnsignedInt(array1[i]) - Byte.toUnsignedInt(array2[i]);
      if (localComp != 0) {
        return localComp;
      }
    }
    // shorter array whose items are all equal to the first items of a longer array is
    // considered 'less than'
    if (array1.length < array2.length) {
      return -1; // array1 < array2
    } else if (array1.length > array2.length) {
      return 1; // array2 < array1
    }
    return 0; // totally equal - should never happen, because you can't have two members
    // with same name...
  }

  public abstract static class AbstractOrderedSetEntry
      implements Comparable<AbstractOrderedSetEntry>,
      Sizeable {
    byte[] member;
    byte[] scoreBytes;
    double score;

    private AbstractOrderedSetEntry() {}

    public int compareTo(AbstractOrderedSetEntry o) {
      int comparison = Double.compare(score, o.score);
      if (comparison == 0) {
        // Scores equal, try lexical ordering
        return compareMembers(member, o.member);
      }
      return comparison;
    }

    public byte[] getMember() {
      return member;
    }

    public byte[] getScoreBytes() {
      return scoreBytes;
    }

    public double getScore() {
      return score;
    }

    public abstract int compareMembers(byte[] array1, byte[] array2);

    public abstract int getSizeInBytes();
  }

  // Entry used to store data in the scoreSet
  public static class OrderedSetEntry extends AbstractOrderedSetEntry {

    public static final int ORDERED_SET_ENTRY_OVERHEAD = memoryOverhead(OrderedSetEntry.class);

    public OrderedSetEntry(byte[] member, byte[] score) {
      this.member = member;
      this.scoreBytes = stripTrailingZeroFromDouble(score);
      this.score = processByteArrayAsDouble(score);
    }

    @Override
    public int compareMembers(byte[] array1, byte[] array2) {
      return javaImplementationOfAnsiCMemCmp(array1, array2);
    }

    @Override
    public int getSizeInBytes() {
      // don't include the member size since it is accounted
      // for as the key on the Hash.
      return ORDERED_SET_ENTRY_OVERHEAD + memoryOverhead(scoreBytes);
    }

    public void updateScore(byte[] newScore) {
      if (!Arrays.equals(newScore, scoreBytes)) {
        scoreBytes = newScore;
        score = processByteArrayAsDouble(newScore);
      }
    }
  }

  // Dummy entry used to find the rank of an element with the given score for inclusive or
  // exclusive ranges
  static class ScoreDummyOrderedSetEntry extends AbstractOrderedSetEntry {

    ScoreDummyOrderedSetEntry(double score, boolean isExclusive, boolean isMinimum) {
      // If we are using an exclusive minimum comparison, or an inclusive maximum comparison then
      // this entry should act as if it is greater than the entry it's being compared to
      this.member = isExclusive ^ isMinimum ? bLEAST_MEMBER_NAME : bGREATEST_MEMBER_NAME;
      this.scoreBytes = null;
      this.score = score;
    }

    @Override
    public int compareMembers(byte[] array1, byte[] array2) {
      return checkDummyMemberNames(array1, array2);
    }

    @Override
    // This object should never be persisted, so its size never needs to be calculated
    public int getSizeInBytes() {
      return 0;
    }
  }

  // Dummy entry used to find the rank of an element with the given member name for lexically
  // ordered sets
  static class MemberDummyOrderedSetEntry extends AbstractOrderedSetEntry {
    final boolean isExclusive;
    final boolean isMinimum;

    MemberDummyOrderedSetEntry(byte[] member, double score, boolean isExclusive,
        boolean isMinimum) {
      this.member = member;
      this.scoreBytes = null;
      this.score = score;
      this.isExclusive = isExclusive;
      this.isMinimum = isMinimum;
    }

    @Override
    public int compareMembers(byte[] array1, byte[] array2) {
      int dummyNameComparison = checkDummyMemberNames(array1, array2);
      if (dummyNameComparison != 0) {
        return dummyNameComparison;
      } else {
        // If not using dummy member names, move on to actual lexical comparison
        int stringComparison = javaImplementationOfAnsiCMemCmp(array1, array2);
        if (stringComparison == 0) {
          // If the member names are equal but we are using an exclusive minimum comparison, or an
          // inclusive maximum comparison then this entry should act as if it is greater than the
          // entry it's being compared to
          return isMinimum ^ isExclusive ? -1 : 1;
        }
        return stringComparison;
      }
    }

    @Override
    // This object should never be persisted, so its size never needs to be calculated
    public int getSizeInBytes() {
      return 0;
    }
  }

  public static class MemberMap
      extends SizeableObject2ObjectOpenCustomHashMapWithCursor<byte[], OrderedSetEntry> {

    public MemberMap(int size) {
      super(size, HASH_STRATEGY);
    }

    public MemberMap(Map<byte[], RedisSortedSet.OrderedSetEntry> initialElements) {
      super(initialElements, HASH_STRATEGY);
    }

    @Override
    protected int sizeKey(byte[] key) {
      return memoryOverhead(key);
    }

    @Override
    protected int sizeValue(OrderedSetEntry value) {
      return value.getSizeInBytes();
    }
  }

  /**
   * ScoreSet does not keep track of the size of the element instances since they
   * are already accounted for by the MemberMap.
   */
  public static class ScoreSet extends OrderStatisticsTree<AbstractOrderedSetEntry> {
  }

}