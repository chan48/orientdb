/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.index.lsmtree.sebtree;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.index.lsmtree.encoders.OByteEncoder;
import com.orientechnologies.orient.core.index.lsmtree.encoders.OEncoder;
import com.orientechnologies.orient.core.index.lsmtree.encoders.OPageIndexEncoder;
import com.orientechnologies.orient.core.index.lsmtree.encoders.OPagePositionEncoder;
import com.orientechnologies.orient.core.index.lsmtree.encoders.impl.OEncoderDurablePage;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;

/**
 * @author Sergey Sitnikov
 */
public class OSebTreeNode<K, V> extends OEncoderDurablePage {

  private static final OEncoder.Provider<Integer> POSITION_ENCODER_PROVIDER     = OEncoder.runtime()
      .getProvider(OPagePositionEncoder.class, OEncoder.Size.PreferFixed);
  private static final OEncoder.Provider<Long>    POINTER_ENCODER_PROVIDER      = OEncoder.runtime()
      .getProvider(OPageIndexEncoder.class, OEncoder.Size.PreferFixed);
  private static final OEncoder.Provider<Byte>    RECORD_FLAGS_ENCODER_PROVIDER = OEncoder.runtime()
      .getProvider(OByteEncoder.class, OEncoder.Size.PreferFixed);

  private static final int FREE_DATA_POSITION_OFFSET = NEXT_FREE_POSITION;
  private static final int FLAGS_OFFSET              = FREE_DATA_POSITION_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int SIZE_OFFSET               = FLAGS_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int TREE_SIZE_OFFSET          = SIZE_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int LEFT_POINTER_OFFSET       = TREE_SIZE_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int MARKER_COUNT_OFFSET       = LEFT_POINTER_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int LEFT_SIBLING_OFFSET       = MARKER_COUNT_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int RIGHT_SIBLING_OFFSET      = LEFT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int RECORDS_OFFSET            = RIGHT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int PAGE_SPACE = (MAX_PAGE_SIZE_BYTES - RECORDS_OFFSET);
  private static final int HALF_SIZE  = PAGE_SPACE / 2;
  /* internal */ static final int MAX_ENTRY_SIZE = PAGE_SPACE / 3;

  private static final int CLONE_BUFFER_SIZE = 4 * 1024;

  private static final int LEAF_FLAG_MASK           = 0b0000_0000__0000_0000___0000_0000__0000_0001;
  private static final int CONTINUED_FROM_FLAG_MASK = 0b0000_0000__0000_0000___0000_0000__0000_0010;
  private static final int CONTINUED_TO_FLAG_MASK   = 0b0000_0000__0000_0000___0000_0000__0000_0100;
  private static final int RECORD_FLAGS_FLAG_MASK   = 0b0000_0000__0000_0000___0000_0000__0000_1000;
  private static final int EXTENSION_FLAG_MASK      = 0b0000_0000__1000_0000___0000_0000__0000_0000;
  private static final int ENCODERS_VERSION_MASK    = 0b1111_1111__0000_0000___0000_0000__0000_0000;
  private static final int ENCODERS_VERSION_SHIFT   = 24;

  private static final int FREE_DATA_POSITION_FIELD = 0b0000_0000__0000_0000___0000_0000__0000_0001;
  private static final int FLAGS_FIELD              = 0b0000_0000__0000_0000___0000_0000__0000_0010;
  private static final int SIZE_FIELD               = 0b0000_0000__0000_0000___0000_0000__0000_0100;
  private static final int TREE_SIZE_FIELD          = 0b0000_0000__0000_0000___0000_0000__0000_1000;
  private static final int MARKER_COUNT_FIELD       = 0b0000_0000__0000_0000___0000_0000__0001_0000;

  private static final byte TOMBSTONE_RECORD_FLAG_MASK = 0b0000_0001;

  private final OEncoder.Provider<K> keyProvider;
  private final OEncoder.Provider<V> valueProvider;
  private final boolean              tombstoneDelete;

  private OEncoder<K>          keyEncoder;
  private OEncoder<V>          valueEncoder;
  private OPagePositionEncoder positionEncoder;
  private OPageIndexEncoder    pointerEncoder;
  private OByteEncoder         recordFlagsEncoder;

  private boolean keysInlined;
  private boolean valuesInlined;
  private int     recordSize;
  private int     markerSize;

  private int loadedFields = 0;
  private int dirtyFields  = 0;

  private int  freeDataPosition;
  private int  flags;
  private int  size;
  private long treeSize;
  private int  markerCount;

  public static boolean isInsertionPoint(int searchIndex) {
    return searchIndex < 0;
  }

  public static int toIndex(int insertionPoint) {
    return -insertionPoint - 1;
  }

  public static int toInsertionPoint(int index) {
    return -(index + 1);
  }

  public static int toMinusOneBasedIndex(int searchIndex) {
    return isInsertionPoint(searchIndex) ? toIndex(searchIndex) - 1 : searchIndex;
  }

  public static <K> int compareKeys(K a, K b) {
    return ODefaultComparator.INSTANCE.compare(a, b);
  }

  public OSebTreeNode(OCacheEntry page, OEncoder.Provider<K> keyProvider, OEncoder.Provider<V> valueProvider,
      boolean tombstoneDelete) {
    super(page);
    this.keyProvider = keyProvider;
    this.valueProvider = valueProvider;
    this.tombstoneDelete = tombstoneDelete;
  }

  public OSebTreeNode<K, V> beginRead() {
    //    System.out.println("+r " + getPointer());

    cacheEntry.acquireSharedLock();

    flags = getIntValue(FLAGS_OFFSET);
    size = getIntValue(SIZE_OFFSET);

    initialize(false);
    return this;
  }

  public OSebTreeNode<K, V> endRead() {
    //    System.out.println("-r " + getPointer());

    assert dirtyFields == 0;

    loadedFields = 0;

    cacheEntry.releaseSharedLock();
    return this;
  }

  public OSebTreeNode<K, V> beginWrite() {
    //    System.out.println("+w " + getPointer());

    cacheEntry.acquireExclusiveLock();

    flags = getIntValue(FLAGS_OFFSET);
    size = getIntValue(SIZE_OFFSET);

    initialize(false);
    return this;
  }

  public OSebTreeNode<K, V> endWrite() {
    //    System.out.println("-w " + getPointer());

    if (dirtyFields != 0) {
      if (dirty(FREE_DATA_POSITION_FIELD))
        setIntValue(FREE_DATA_POSITION_OFFSET, freeDataPosition);
      if (dirty(FLAGS_FIELD))
        setIntValue(FLAGS_OFFSET, flags);
      if (dirty(SIZE_FIELD))
        setIntValue(SIZE_OFFSET, size);
      if (dirty(TREE_SIZE_FIELD))
        setLongValue(TREE_SIZE_OFFSET, treeSize);
      if (dirty(MARKER_COUNT_FIELD))
        setIntValue(MARKER_COUNT_OFFSET, markerCount);
    }

    loadedFields = 0;
    dirtyFields = 0;

    cacheEntry.releaseExclusiveLock();
    return this;
  }

  public OSebTreeNode<K, V> beginCreate() {
    //    System.out.println("+c " + getPointer());

    cacheEntry.acquireExclusiveLock();
    return this;
  }

  public void create(boolean leaf) {
    setFreeDataPosition(MAX_PAGE_SIZE_BYTES);
    setLeaf(leaf);
    setContinuedFrom(false);
    setContinuedTo(false);
    setHasRecordFlags(leaf && tombstoneDelete);
    setEncodersVersion(OSebTree.ENCODERS_VERSION);
    setFlag(EXTENSION_FLAG_MASK, false);
    setSize(0);
    setTreeSize(0);
    setMarkerCount(0);
    setLeftSibling(0);
    setRightSibling(0);

    initialize(true);
  }

  public OSebTreeNode<K, V> createDummy() {
    setFreeDataPosition(MAX_PAGE_SIZE_BYTES);
    return this;
  }

  public long getPointer() {
    return pointer.getPageIndex();
  }

  public int indexOf(K key) {
    return binarySearchRecord(key);
  }

  public long pointerAt(int keyIndex) {
    if (isInsertionPoint(keyIndex)) {
      final int index = toIndex(keyIndex);
      return index == 0 ? getLeftPointer() : getPointer(index - 1);
    } else
      return getPointer(keyIndex);
  }

  public V valueAt(int index) {
    navigateToValue(index);
    return valueEncoder.decode(this);
  }

  public K keyAt(int index) {
    return getKey(index);
  }

  public boolean isTombstoneRecord(int index) {
    return tombstoneDelete && getRecordFlag(index, TOMBSTONE_RECORD_FLAG_MASK);
  }

  public void insertTombstone(int index, K key, int keySize) {
    addTombstone(toIndex(index), key, keySize);
  }

  public Marker markerAt(int index) {
    navigateToMarker(index);
    return new Marker(index, positionEncoder.decodeInteger(this), pointerEncoder.decodeLong(this),
        positionEncoder.decodeInteger(this));
  }

  public Marker markerForPointerAt(int index) {
    final int searchIndex = binarySearchMarker(index);
    return isInsertionPoint(searchIndex) ? null : markerAt(searchIndex);
  }

  public Marker nearestMarker(int pointerSearchIndex) {
    final int searchIndex = binarySearchMarker(toMinusOneBasedIndex(pointerSearchIndex));
    final int markerIndex = isInsertionPoint(searchIndex) ? toIndex(searchIndex) - 1 : searchIndex;
    return markerAt(markerIndex == -1 ? 0 : markerIndex);
  }

  public int getLastPointerIndexOfMarkerAt(int index) {
    return index == getMarkerCount() - 1 ? getSize() - 1 : getMarkerPointerIndex(index + 1) - 1;
  }

  public void insertMarker(int index, int recordIndex, long blockIndex, int blockUsage) {
    allocateMarker(index);
    positionEncoder.encode(recordIndex, this);
    pointerEncoder.encodeLong(blockIndex, this);
    positionEncoder.encodeInteger(blockUsage, this);

    setMarkerCount(getMarkerCount() + 1);
  }

  public void insertMarkerForPointerAt(int pointerIndex, long blockIndex, int blockUsage) {
    final int searchIndex = binarySearchMarker(pointerIndex);
    insertMarker(isInsertionPoint(searchIndex) ? toIndex(searchIndex) : searchIndex, pointerIndex, blockIndex, blockUsage);
  }

  public void updateMarker(int index, int blockPagesUsed) {
    navigateToMarker(index);
    seek(positionEncoder.maximumSize() + pointerEncoder.maximumSize());
    positionEncoder.encodeInteger(blockPagesUsed, this);
  }

  public void updateMarker(int index, long blockIndex, int blockPagesUsed) {
    navigateToMarker(index);
    seek(positionEncoder.maximumSize());
    pointerEncoder.encodeLong(blockIndex, this);
    positionEncoder.encodeInteger(blockPagesUsed, this);
  }

  public void updatePointer(int index, long pointer) {
    if (index == -1)
      setLeftPointer(pointer);
    else {
      setPosition(recordValuePosition(index));
      pointerEncoder.encodeLong(pointer, this);
    }
  }

  public int keySizeAt(int index) {
    if (keysInlined)
      return keyEncoder.maximumSize();
    else {
      navigateToKey(index);
      return keyEncoder.exactSizeInStream(this);
    }
  }

  public int valueSizeAt(int index, boolean tombstone) {
    if (valuesInlined)
      return valueEncoder.maximumSize();
    else if (tombstone)
      return 0;
    else {
      navigateToValue(index);
      return valueEncoder.exactSizeInStream(this);
    }
  }

  public int fullEntrySize(int keySize, int valueSize) {
    int size = keySize + valueSize;

    if (!keysInlined)
      size += positionEncoder.maximumSize();

    if (isLeaf()) {
      if (!valuesInlined)
        size += positionEncoder.maximumSize();

      if (hasRecordFlags())
        size += recordFlagsEncoder.maximumSize();
    }

    return size;
  }

  public int fullTombstoneSize(int keySize) {
    assert isLeaf();

    int size = recordSize;

    if (!keysInlined)
      size += keySize;

    return size;
  }

  public void checkEntrySize(int entrySize, OSebTree<K, V> tree) {
    if (entrySize > MAX_ENTRY_SIZE)
      throw new OSebTreeException("Too large entry size " + entrySize + ", maximum possible size is " + MAX_ENTRY_SIZE, tree);
  }

  public boolean deltaFits(int sizeDelta) {
    return sizeDelta <= getFreeBytes();
  }

  public boolean markerFits() {
    return deltaFits(markerSize);
  }

  public void updateValue(int index, V value, int valueSize, int currentValueSize, boolean tombstone) {
    navigateToValue(index);

    if (!valuesInlined && (currentValueSize != valueSize || tombstone)) {
      int dataPosition = getFreeDataPosition();

      if (!tombstone)
        dataPosition = deleteData(dataPosition, getPosition(), currentValueSize);
      dataPosition = allocateData(dataPosition, valueSize);

      setPosition(recordValuePosition(index));
      positionEncoder.encodeInteger(dataPosition, this);

      setFreeDataPosition(dataPosition);
      setPosition(dataPosition);
    }

    valueEncoder.encode(value, this);

    if (tombstone)
      markRecordAsNonTombstone(index);
  }

  public void insertValue(int index, K key, int keySize, V value, int valueSize) {
    addKey(toIndex(index), key, keySize, value, valueSize);
  }

  public void insertPointer(int index, K key, int keySize, long pointer) {

    // Insert pointer.

    addKey(index, key, keySize, pointer);

    // Update marker indexes.

    for (int i = getMarkerCount() - 1; i >= 0; --i) {
      navigateToMarker(i);
      final int markerPointerIndex = positionEncoder.decodeInteger(this);
      if (markerPointerIndex >= index) {
        seek(-positionEncoder.maximumSize());
        positionEncoder.encodeInteger(markerPointerIndex + 1, this);
      } else
        break;
    }
  }

  public void moveTailTo(OSebTreeNode<K, V> destination, int length) {
    if (length == 0)
      return;

    if (isLeaf())
      leafMoveTailTo(destination, length);
    else
      nonLeafMoveTailTo(destination, length);
  }

  public int countEntriesToMoveUntilHalfFree() { // todo: account to markers (?)
    final int size = getSize();
    final boolean leaf = isLeaf();

    int entriesToMove = 0;
    int bytesFree = getFreeBytes();
    for (int i = size - 1; size >= 0; --i) {
      if (bytesFree >= HALF_SIZE)
        break;

      navigateToKey(i);
      final int keySize = keyEncoder.exactSizeInStream(this);

      final int valueSize;
      if (leaf) {
        if (isTombstoneRecord(i))
          valueSize = valuesInlined ? valueEncoder.maximumSize() : 0;
        else {
          navigateToValue(i);
          valueSize = valueEncoder.exactSizeInStream(this);
        }
      } else
        valueSize = pointerEncoder.maximumSize();

      final int fullSize = fullEntrySize(keySize, valueSize);

      bytesFree += fullSize;
      ++entriesToMove;
    }

    return entriesToMove;
  }

  public void cloneFrom(OSebTreeNode<K, V> node) {
    this.setPosition(0);
    node.setPosition(0);

    for (int i = 0; i < MAX_PAGE_SIZE_BYTES / CLONE_BUFFER_SIZE; ++i)
      this.write(node.read(CLONE_BUFFER_SIZE));
  }

  public void convertToNonLeaf() {
    setFreeDataPosition(MAX_PAGE_SIZE_BYTES);
    setLeaf(false);
    setContinuedFrom(false);
    setContinuedTo(false);
    setHasRecordFlags(false);
    setEncodersVersion(OSebTree.ENCODERS_VERSION);
    setFlag(EXTENSION_FLAG_MASK, false);
    setSize(0);
    setMarkerCount(0);

    initialize(true);
  }

  public void delete(int index, int keySize, int valueSize) {
    if (tombstoneDelete && isLeaf())
      convertToTombstone(index, valueSize);
    else
      removeKey(index, keySize, valueSize);
  }

  public int getFreeDataPosition() {
    if (absent(FREE_DATA_POSITION_FIELD)) {
      freeDataPosition = getIntValue(FREE_DATA_POSITION_OFFSET);
      loaded(FREE_DATA_POSITION_FIELD);
    }
    return freeDataPosition;
  }

  public void setFreeDataPosition(int value) {
    changed(FREE_DATA_POSITION_FIELD);
    freeDataPosition = value;
  }

  public int getSize() {
    return size;
  }

  public void setSize(int value) {
    changed(SIZE_FIELD);
    size = value;
  }

  public long getTreeSize() {
    if (absent(TREE_SIZE_FIELD)) {
      treeSize = getLongValue(TREE_SIZE_OFFSET);
      loaded(TREE_SIZE_FIELD);
    }
    return treeSize;
  }

  public void setTreeSize(long value) {
    changed(TREE_SIZE_FIELD);
    treeSize = value;
  }

  public int getMarkerCount() {
    if (absent(MARKER_COUNT_FIELD)) {
      markerCount = getIntValue(MARKER_COUNT_OFFSET);
      loaded(MARKER_COUNT_FIELD);
    }
    return markerCount;
  }

  public void setMarkerCount(int value) {
    changed(MARKER_COUNT_FIELD);
    markerCount = value;
  }

  public int getFlags() {
    return flags;
  }

  public void setFlags(int value) {
    changed(FLAGS_FIELD);
    flags = value;
  }

  public void setFlag(int mask, boolean value) {
    if (value)
      setFlags(getFlags() | mask);
    else
      setFlags(getFlags() & ~mask);
  }

  public boolean getFlag(int mask) {
    return (getFlags() & mask) != 0;
  }

  public boolean isLeaf() {
    return getFlag(LEAF_FLAG_MASK);
  }

  public void setLeaf(boolean value) {
    setFlag(LEAF_FLAG_MASK, value);
  }

  public boolean isContinuedFrom() {
    return getFlag(CONTINUED_FROM_FLAG_MASK);
  }

  public void setContinuedFrom(boolean value) {
    setFlag(CONTINUED_FROM_FLAG_MASK, value);
  }

  public boolean isContinuedTo() {
    return getFlag(CONTINUED_TO_FLAG_MASK);
  }

  public void setContinuedTo(boolean value) {
    setFlag(CONTINUED_TO_FLAG_MASK, value);
  }

  public boolean hasRecordFlags() {
    return getFlag(RECORD_FLAGS_FLAG_MASK);
  }

  public void setHasRecordFlags(boolean value) {
    setFlag(RECORD_FLAGS_FLAG_MASK, value);
  }

  public int getEncodersVersion() {
    return (getFlags() & ENCODERS_VERSION_MASK) >>> ENCODERS_VERSION_SHIFT;
  }

  public void setEncodersVersion(int value) {
    setFlags((byte) ((value << ENCODERS_VERSION_SHIFT & ENCODERS_VERSION_MASK) | (getFlags() & ~ENCODERS_VERSION_MASK)));
  }

  public long getLeftPointer() {
    assert !isLeaf();
    return getLongValue(LEFT_POINTER_OFFSET);
  }

  public void setLeftPointer(long pointer) {
    assert !isLeaf();
    setLongValue(LEFT_POINTER_OFFSET, pointer);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  public void setLeftSibling(long pointer) {
    setLongValue(LEFT_SIBLING_OFFSET, pointer);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  public void setRightSibling(long pointer) {
    setLongValue(RIGHT_SIBLING_OFFSET, pointer);
  }

  public OEncoder<K> getKeyEncoder() {
    return keyEncoder;
  }

  public OEncoder<V> getValueEncoder() {
    return valueEncoder;
  }

  public OPageIndexEncoder getPointerEncoder() {
    return pointerEncoder;
  }

  @Override
  public String toString() {
    return (isLeaf() ? "Leaf " : "Int. ") + getPointer();
  }

  /* internal */ OCacheEntry getPage() {
    return cacheEntry;
  }

  private int binarySearchMarker(int recordIndex) {
    int low = 0;
    int high = getMarkerCount() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      int midVal = getMarkerPointerIndex(mid);

      final int order = Integer.compare(recordIndex, midVal);
      if (order > 0)
        low = mid + 1;
      else if (order < 0)
        high = mid - 1;
      else
        return mid; // found
    }
    return -(low + 1);  // not found
  }

  private int getMarkerPointerIndex(int markerIndex) {
    navigateToMarker(markerIndex);
    return positionEncoder.decodeInteger(this);
  }

  private int binarySearchRecord(K key) {
    int low = 0;
    int high = getSize() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      K midVal = getKey(mid);

      final int order = compareKeys(key, midVal);
      if (order > 0)
        low = mid + 1;
      else if (order < 0)
        high = mid - 1;
      else
        return mid; // found
    }
    return -(low + 1);  // not found
  }

  private K getKey(int index) {
    navigateToKey(index);
    return keyEncoder.decode(this);
  }

  private void navigateToFlags(int index) {
    setPosition(recordFlagsPosition(index));
  }

  private void navigateToKey(int index) {
    setPosition(recordKeyPosition(index));

    if (!keysInlined)
      setPosition(positionEncoder.decodeInteger(this));
  }

  private void navigateToValue(int index) {
    setPosition(recordValuePosition(index));

    if (!valuesInlined)
      setPosition(positionEncoder.decodeInteger(this));
  }

  private void navigateToMarker(int index) {
    setPosition(markerPosition(index));
  }

  private long getPointer(int index) {
    setPosition(recordValuePosition(index));
    return pointerEncoder.decodeLong(this);
  }

  private void addKey(int index, K key, int keySize, V value, int valueSize) {
    allocateRecord(index);
    if (keysInlined)
      keyEncoder.encode(key, this);
    else {
      final int dataPosition = allocateData(getFreeDataPosition(), keySize);
      positionEncoder.encodeInteger(dataPosition, this);

      setPosition(dataPosition);
      keyEncoder.encode(key, this);

      setFreeDataPosition(dataPosition);
    }

    setPosition(recordValuePosition(index));
    if (valuesInlined)
      valueEncoder.encode(value, this);
    else {
      final int dataPosition = allocateData(getFreeDataPosition(), valueSize);
      positionEncoder.encodeInteger(dataPosition, this);

      setPosition(dataPosition);
      valueEncoder.encode(value, this);

      setFreeDataPosition(dataPosition);
    }

    if (hasRecordFlags())
      setRecordFlags(index, (byte) 0);

    setSize(getSize() + 1);
  }

  private void addTombstone(int index, K key, int keySize) {
    allocateRecord(index);
    if (keysInlined)
      keyEncoder.encode(key, this);
    else {
      final int dataPosition = allocateData(getFreeDataPosition(), keySize);
      positionEncoder.encodeInteger(dataPosition, this);

      setPosition(dataPosition);
      keyEncoder.encode(key, this);

      setFreeDataPosition(dataPosition);
    }
    markRecordAsTombstone(index);
    setSize(getSize() + 1);
  }

  private void addKey(int index, K key, int keySize, long pointer) {
    allocateRecord(index);

    if (keysInlined)
      keyEncoder.encode(key, this);
    else {
      final int dataPosition = allocateData(getFreeDataPosition(), keySize);
      positionEncoder.encodeInteger(dataPosition, this);

      setPosition(dataPosition);
      keyEncoder.encode(key, this);

      setFreeDataPosition(dataPosition);
    }

    setPosition(recordValuePosition(index));
    pointerEncoder.encodeLong(pointer, this);

    setSize(getSize() + 1);
  }

  private void removeKey(int index, int keySize, int valueSize) {
    if (!keysInlined) {
      setPosition(recordKeyPosition(index));
      final int keyDataPosition = positionEncoder.decodeInteger(this);
      setFreeDataPosition(deleteData(getFreeDataPosition(), keyDataPosition, keySize));
    }

    if (isLeaf() && !valuesInlined) {
      setPosition(recordValuePosition(index));
      final int valueDataPosition = positionEncoder.decodeInteger(this);
      setFreeDataPosition(deleteData(getFreeDataPosition(), valueDataPosition, valueSize));
    }

    deleteRecord(index);

    setSize(getSize() - 1);
  }

  private void convertToTombstone(int index, int valueSize) {
    assert isLeaf();

    markRecordAsTombstone(index);

    if (!valuesInlined) {
      setPosition(recordValuePosition(index));
      final int valueDataPosition = positionEncoder.decodeInteger(this);
      setFreeDataPosition(deleteData(getFreeDataPosition(), valueDataPosition, valueSize));
    }
  }

  private int allocateData(int freePosition, int length) {
    return freePosition - length;
  }

  private int deleteData(int freePosition, int position, int length) {
    if (position > freePosition) { // not the last one from the end of the page
      moveData(freePosition, freePosition + length, position - freePosition);

      final boolean leaf = isLeaf();
      final boolean hasRecordFlags = hasRecordFlags();

      setPosition(RECORDS_OFFSET);
      final int size = getSize();
      for (int i = 0; i < size; ++i) {
        if (keysInlined)
          seek(keyEncoder.maximumSize());
        else {
          final int keyPosition = getPosition();
          final int keyDataPosition = positionEncoder.decodeInteger(this);
          if (keyDataPosition < position) {
            setPosition(keyPosition);
            positionEncoder.encodeInteger(keyDataPosition + length, this);
          }
        }

        if (!leaf)
          seek(pointerEncoder.maximumSize());
        else if (valuesInlined)
          seek(valueEncoder.maximumSize());
        else {
          final int valuePosition = getPosition();
          final int valueDataPosition = positionEncoder.decodeInteger(this);
          if (valueDataPosition < position) {
            setPosition(valuePosition);
            positionEncoder.encodeInteger(valueDataPosition + length, this);
          }
        }

        if (hasRecordFlags)
          seek(recordFlagsEncoder.maximumSize());
      }
    }

    return freePosition + length;
  }

  private void allocateRecord(int index) {
    final int recordPosition = recordPosition(index);

    if (index < getSize() || getMarkerCount() > 0)
      moveData(recordPosition, recordPosition + recordSize, (getSize() - index) * recordSize + getMarkerCount() * markerSize);

    setPosition(recordPosition);
  }

  private void deleteRecord(int index) {
    final int recordPosition = recordPosition(index);

    if (index < getSize() - 1 || getMarkerCount() > 0)
      moveData(recordPosition + recordSize, recordPosition, (getSize() - index - 1) * recordSize + getMarkerCount() * markerSize);
  }

  private void allocateMarker(int index) {
    final int markerPosition = markerPosition(index);

    if (index < getMarkerCount())
      moveData(markerPosition, markerPosition + markerSize, (getMarkerCount() - index) * markerSize);

    setPosition(markerPosition);
  }

  private byte getRecordFlags(int index) {
    assert hasRecordFlags();
    navigateToFlags(index);
    return recordFlagsEncoder.decodeByte(this);
  }

  private void setRecordFlags(int index, byte flags) {
    assert hasRecordFlags();
    navigateToFlags(index);
    recordFlagsEncoder.encodeByte(flags, this);
  }

  private boolean getRecordFlag(int index, byte mask) {
    return (getRecordFlags(index) & mask) != 0;
  }

  private void setRecordFlag(int index, byte mask, boolean value) {
    if (value)
      setRecordFlags(index, (byte) (getRecordFlags(index) | mask));
    else
      setRecordFlags(index, (byte) (getRecordFlags(index) & ~mask));
  }

  @SuppressWarnings("unchecked")
  private void leafMoveTailTo(OSebTreeNode<K, V> destination, int length) {
    final int size = getSize();
    final int remaining = size - length;

    final boolean hasRecordFlags = hasRecordFlags();
    final boolean destinationHasRecordFlags = destination.hasRecordFlags();

    for (int i = 0; i < length; ++i) {
      final int index = remaining + i;

      navigateToKey(index);
      final int keyStart = getPosition();
      final K key = keyEncoder.decode(this);
      final int keySize = getPosition() - keyStart;

      if (isTombstoneRecord(index))
        destination.addTombstone(i, key, keySize);
      else {
        navigateToValue(index);
        final int valueStart = getPosition();
        final V value = valueEncoder.decode(this);
        final int valueSize = getPosition() - valueStart;

        destination.addKey(i, key, keySize, value, valueSize);
      }

      if (destinationHasRecordFlags)
        destination.setRecordFlags(i, hasRecordFlags ? getRecordFlags(index) : 0);
    }

    final int[] keySizes = new int[remaining];
    final int[] valueSizes = new int[remaining];
    final K[] keys = (K[]) new Object[remaining];
    final V[] values = (V[]) new Object[remaining];
    final byte[] flags = hasRecordFlags ? new byte[remaining] : null;
    final boolean[] tombstones = tombstoneDelete ? new boolean[remaining] : null;

    for (int i = 0; i < remaining; ++i) {
      navigateToKey(i);
      final int keyStart = getPosition();
      final K key = keyEncoder.decode(this);
      keys[i] = key;
      keySizes[i] = getPosition() - keyStart;

      final boolean tombstone = isTombstoneRecord(i);
      if (tombstoneDelete)
        tombstones[i] = tombstone;

      if (!tombstone) {
        navigateToValue(i);
        final int valueStart = getPosition();
        final V value = valueEncoder.decode(this);
        values[i] = value;
        valueSizes[i] = getPosition() - valueStart;
      }

      if (hasRecordFlags)
        flags[i] = getRecordFlags(i);
    }

    clear();
    for (int i = 0; i < remaining; ++i) {
      if (tombstoneDelete && tombstones[i])
        addTombstone(i, keys[i], keySizes[i]);
      else
        addKey(i, keys[i], keySizes[i], values[i], valueSizes[i]);

      if (hasRecordFlags)
        setRecordFlags(i, flags[i]);
    }
  }

  @SuppressWarnings("unchecked")
  private void nonLeafMoveTailTo(OSebTreeNode<K, V> destination, int length) {
    final int size = getSize();
    final int remaining = size - length;
    final int markerCount = getMarkerCount();

    for (int i = 0; i < length; ++i) {
      final int index = remaining + i;

      navigateToKey(index);
      final int keyStart = getPosition();
      final K key = keyEncoder.decode(this);
      final int keySize = getPosition() - keyStart;

      setPosition(recordValuePosition(index));
      destination.addKey(i, key, keySize, pointerEncoder.decodeLong(this));
    }

    final int markerSearchIndex = binarySearchMarker(remaining);
    final int markerIndex = isInsertionPoint(markerSearchIndex) ? toIndex(markerSearchIndex) : markerSearchIndex;
    navigateToMarker(markerIndex);
    for (int i = markerIndex; i < markerCount; ++i) {
      int recordIndex = positionEncoder.decodeInteger(this);
      assert recordIndex != -1; // never first marker, since at least one marker should stay in the original node
      recordIndex = recordIndex - remaining;
      destination.insertMarker(i - markerIndex, recordIndex, pointerEncoder.decodeLong(this), positionEncoder.decodeInteger(this));
    }

    final int[] keySizes = new int[remaining];
    final K[] keys = (K[]) new Object[remaining];
    final long[] pointers = new long[remaining];

    for (int i = 0; i < remaining; ++i) {
      navigateToKey(i);
      final int keyStart = getPosition();
      final K key = keyEncoder.decode(this);
      keys[i] = key;
      keySizes[i] = getPosition() - keyStart;

      setPosition(recordValuePosition(i));
      pointers[i] = pointerEncoder.decodeLong(this);
    }

    final int[] markerRecordIndexes = new int[markerIndex];
    final long[] markerPointers = new long[markerIndex];
    final int[] markersUsages = new int[markerIndex];
    navigateToMarker(0);
    for (int i = 0; i < markerIndex; ++i) {
      markerRecordIndexes[i] = positionEncoder.decodeInteger(this);
      markerPointers[i] = pointerEncoder.decodeLong(this);
      markersUsages[i] = positionEncoder.decodeInteger(this);
    }

    clear();
    for (int i = 0; i < remaining; ++i)
      addKey(i, keys[i], keySizes[i], pointers[i]);

    for (int i = 0; i < markerIndex; ++i)
      insertMarker(i, markerRecordIndexes[i], markerPointers[i], markersUsages[i]);
  }

  private void clear() {
    setSize(0);
    if (!isLeaf())
      setMarkerCount(0);
    setFreeDataPosition(MAX_PAGE_SIZE_BYTES);
  }

  private int getFreeBytes() {
    return getFreeDataPosition() - getSize() * recordSize - RECORDS_OFFSET - (isLeaf() ? 0 : getMarkerCount() * markerSize);
  }

  private int recordPosition(int index) {
    return RECORDS_OFFSET + index * recordSize;
  }

  private int recordKeyPosition(int index) {
    return recordPosition(index);
  }

  private int recordValuePosition(int index) {
    return recordKeyPosition(index) + (keysInlined ? keyEncoder.maximumSize() : positionEncoder.maximumSize());
  }

  private int recordFlagsPosition(int index) {
    assert hasRecordFlags();
    return recordValuePosition(index) + (valuesInlined ? valueEncoder.maximumSize() : positionEncoder.maximumSize());
  }

  private int markerPosition(int index) {
    assert !isLeaf();
    return RECORDS_OFFSET + getSize() * recordSize + index * markerSize;
  }

  private void markRecordAsTombstone(int index) {
    assert tombstoneDelete;
    setRecordFlag(index, TOMBSTONE_RECORD_FLAG_MASK, true);
  }

  private void markRecordAsNonTombstone(int index) {
    assert tombstoneDelete;
    setRecordFlag(index, TOMBSTONE_RECORD_FLAG_MASK, false);
  }

  private void initialize(boolean force) {
    if (keyEncoder != null && !force)
      return;

    keyEncoder = keyProvider.getEncoder(getEncodersVersion());
    valueEncoder = valueProvider.getEncoder(getEncodersVersion());

    positionEncoder = POSITION_ENCODER_PROVIDER.getEncoder(getEncodersVersion());
    pointerEncoder = POINTER_ENCODER_PROVIDER.getEncoder(getEncodersVersion());

    keysInlined = keyEncoder.isOfBoundSize() && keyEncoder.maximumSize() <= OSebTree.INLINE_KEYS_SIZE_THRESHOLD;
    valuesInlined = valueEncoder.isOfBoundSize() && valueEncoder.maximumSize() <= OSebTree.INLINE_VALUES_SIZE_THRESHOLD;

    recordSize = keysInlined ? keyEncoder.maximumSize() : positionEncoder.maximumSize();
    if (isLeaf())
      recordSize += valuesInlined ? valueEncoder.maximumSize() : positionEncoder.maximumSize();
    else {
      recordSize += pointerEncoder.maximumSize();
      markerSize = positionEncoder.maximumSize() + pointerEncoder.maximumSize() + positionEncoder.maximumSize();
    }

    if (hasRecordFlags()) {
      recordFlagsEncoder = RECORD_FLAGS_ENCODER_PROVIDER.getEncoder(getEncodersVersion());
      recordSize += recordFlagsEncoder.maximumSize();
    }
  }

  private boolean absent(int field) {
    return (loadedFields & field) == 0;
  }

  private void loaded(int field) {
    loadedFields |= field;
  }

  private boolean dirty(int field) {
    return (dirtyFields & field) != 0;
  }

  private void changed(int field) {
    dirtyFields |= field;
    loadedFields |= field;
  }

  /* internal */ void verifyNonLeaf() {
    final int markerCount = getMarkerCount();
    for (int i = 0; i < markerCount; ++i) {
      final Marker marker = markerAt(i);
      final long firstPage = marker.blockIndex;
      final long lastPage = firstPage + 16;

      final int lastPointerIndexOfMarkerAt = getLastPointerIndexOfMarkerAt(i);
      for (int j = marker.pointerIndex; j < lastPointerIndexOfMarkerAt; ++j) {
        final long pointer = pointerAt(j);
        assert pointer >= firstPage && pointer < lastPage;
      }
    }
  }

  @SuppressWarnings("unchecked")
  /* internal */ void dump(int level) {
    for (int i = 0; i < level; ++i)
      System.out.print('\t');
    System.out.print(isLeaf() ? "Leaf " : "Int. ");
    System.out.print(getPointer() + ": ");

    if (isContinuedFrom())
      System.out.print("... ");

    if (getLeftSibling() != 0)
      System.out.print("<-" + getLeftSibling() + " ");

    for (int i = -1; i < getSize(); ++i) {
      if (isLeaf()) {
        if (i > -1) {
          K key = keyAt(i);
          if (key instanceof String && ((String) key).length() > 3)
            key = (K) ((String) key).substring(0, 3);
          V value = isTombstoneRecord(i) ? (V) "⚰⚰⚰" : valueAt(i);
          if (value instanceof String && ((String) value).length() > 3)
            value = (V) ((String) value).substring(0, 3);
          System.out.print(key + " " + value + ", ");
        }
      } else {
        final Marker marker = markerForPointerAt(i);
        if (marker != null)
          System.out.print("M(" + marker.blockIndex + ", " + marker.blockPagesUsed + "), ");

        final long pointer = pointerAt(i);
        if (i == -1)
          System.out.print("P(" + pointer + "), ");
        else {
          K key = keyAt(i);
          if (key instanceof String)
            key = (K) ((String) key).substring(0, 3);
          System.out.print(key + " P(" + pointer + "), ");
        }
      }
    }

    if (getRightSibling() != 0)
      System.out.print(getRightSibling() + "-> ");

    if (isContinuedTo())
      System.out.print("...");

    System.out.println();
  }

  public static class Marker {

    public final int  index;
    public final int  pointerIndex;
    public final long blockIndex;
    public final int  blockPagesUsed;

    public Marker(int index, int pointerIndex, long blockIndex, int blockPagesUsed) {
      this.index = index;
      this.pointerIndex = pointerIndex;
      this.blockIndex = blockIndex;
      this.blockPagesUsed = blockPagesUsed;
    }

    @Override
    public String toString() {
      return Long.toString(blockIndex) + ":" + blockPagesUsed + " at " + index;
    }

  }

}