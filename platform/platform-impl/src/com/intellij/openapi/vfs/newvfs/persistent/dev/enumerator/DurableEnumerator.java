// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent.dev.enumerator;

import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.io.DurableDataEnumerator;
import com.intellij.util.io.dev.appendonlylog.AppendOnlyLog;
import com.intellij.openapi.vfs.newvfs.persistent.dev.appendonlylog.AppendOnlyLogOverMMappedFile;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.io.ScannableDataEnumeratorEx;
import com.intellij.util.io.dev.enumerator.KeyDescriptorEx;
import com.intellij.util.io.dev.intmultimaps.DurableIntToMultiIntMap;
import com.intellij.util.io.dev.intmultimaps.NonDurableNonParallelIntToMultiIntMap;
import com.intellij.openapi.vfs.newvfs.persistent.dev.intmultimaps.extendiblehashmap.ExtendibleHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;


/**
 * Persistent enumerator for objects.
 * 'Durable' is to separate it from {@link com.intellij.util.io.PersistentEnumerator}, which is conceptually
 * the same, but right now tightly bounded to BTree-based implementation.
 * <p>
 * Implementation uses append-only log to store objects, and some (pluggable) Map[object.hash -> id*].
 */
public final class DurableEnumerator<V> implements DurableDataEnumerator<V>,
                                                   ScannableDataEnumeratorEx<V> {

  public static final int DATA_FORMAT_VERSION = 1;

  public static final int PAGE_SIZE = 8 << 20;

  private final @NotNull AppendOnlyLog valuesLog;

  private final @NotNull KeyDescriptorEx<V> valueDescriptor;

  //MAYBE RC: durable map is not _required_ here. We could go with:
  //          1) in-memory map, transient & re-populated from log on each start
  //          2) swappable in-memory/on-disk map, there on-disk part is transient and
  //             map is re-populated from log on each start
  //          3) on-disk map, durable between restarts re-populated from log only on
  //             corruption

  private final @NotNull DurableIntToMultiIntMap valueHashToId;

  public static <K> DurableEnumerator<K> openWithInMemoryMap(@NotNull Path storagePath,
                                                             @NotNull KeyDescriptorEx<K> valueDescriptor) throws IOException {
    AppendOnlyLog valuesLog = AppendOnlyLogOverMMappedFile.openLog(
      storagePath,
      DATA_FORMAT_VERSION
    );

    return new DurableEnumerator<>(
      valueDescriptor,
      valuesLog,
      () -> fillValueHashToIdMap(valuesLog, valueDescriptor, new NonDurableNonParallelIntToMultiIntMap())
    );
  }

  public static <K> DurableEnumerator<K> openWithDurableMap(@NotNull Path storagePath,
                                                            @NotNull KeyDescriptorEx<K> valueDescriptor) throws IOException {
    String name = storagePath.getName(storagePath.getNameCount() - 1).toString();
    Path hashToIdPath = storagePath.resolveSibling(name + ".hashToId");
    AppendOnlyLog valuesLog = AppendOnlyLogOverMMappedFile.openLog(
      storagePath,
      DATA_FORMAT_VERSION
    );
    ExtendibleHashMap valueHashToId = ExtendibleHashMap.open(hashToIdPath);
    return new DurableEnumerator<>(
      valueDescriptor,
      valuesLog,
      () -> valueHashToId
    );
  }

  //TODO RC: valueHashToId could be loaded async -- to not delay initialization (see DurableStringEnumerator)
  //TODO RC: if valueHashToId is missed/corrupted -> we could rebuild it from .valuesLog

  private static <K> @NotNull DurableIntToMultiIntMap fillValueHashToIdMap(@NotNull AppendOnlyLog valuesLog,
                                                                           @NotNull KeyDescriptorEx<K> valueDescriptor,
                                                                           @NotNull DurableIntToMultiIntMap valueHashToId)
    throws IOException {
    valuesLog.forEachRecord((logId, buffer) -> {
      K value = valueDescriptor.read(buffer);

      int valueHash = adjustHash(valueDescriptor.hashCodeOf(value));
      int id = convertLogIdToEnumeratorId(logId);

      valueHashToId.put(valueHash, id);
      return true;
    });
    return valueHashToId;
  }

  private DurableEnumerator(@NotNull KeyDescriptorEx<V> valueDescriptor,
                            @NotNull AppendOnlyLog valuesLog,
                            @NotNull ThrowableComputable<DurableIntToMultiIntMap, IOException> mapFactory) throws IOException {
    this.valueDescriptor = valueDescriptor;
    this.valuesLog = valuesLog;
    this.valueHashToId = mapFactory.compute();
  }

  @Override
  public boolean isDirty() {
    //TODO RC: with mapped files we actually don't know are there any unsaved changes,
    //         since OS is responsible for that. We could force OS to flush the changes,
    //         but we couldn't ask are there changes.
    //         I think return false is +/- safe option, since the data is almost always
    //         'safe' (as long as OS doesn't crash), but it is a bit logically inconsistent:
    //         .isDirty() is supposed to return false if .force() has nothing to do, but
    //         .force() still _can_ something, i.e. forcing OS to flush.
    return false;
  }

  @Override
  public void force() throws IOException {
    valuesLog.flush(true);
    valueHashToId.flush();
  }

  @Override
  public void close() throws IOException {
    ExceptionUtil.runAllAndRethrowAllExceptions(
      new IOException("Can't close " + valuesLog + "/" + valueHashToId),
      valuesLog::close,
      valueHashToId::close
    );
  }

  @Override
  public int enumerate(@Nullable V value) throws IOException {
    if (value == null) {
      return NULL_ID;
    }
    return lookupOrCreateIdForValue(value);
  }

  @Override
  public int tryEnumerate(@Nullable V value) throws IOException {
    if (value == null) {
      return NULL_ID;
    }

    return lookupIdForValue(value);
  }

  @Override
  public @Nullable V valueOf(int valueId) throws IOException {
    if (valueId == NULL_ID) {
      return null;
    }
    return valuesLog.read(valueId, valueDescriptor::read);
  }

  @Override
  public boolean forEach(@NotNull ValueReader<? super V> reader) throws IOException {
    return valuesLog.forEachRecord((recordId, buffer) -> {
      int valueId = convertLogIdToEnumeratorId(recordId);
      V value = valueDescriptor.read(buffer);
      return reader.read(valueId, value);
    });
  }

  @Override
  public int recordsCount() throws IOException {
    return valueHashToId.size();
  }

  @Override
  public String toString() {
    return "DurableEnumerator[" +
           "log: " + valuesLog +
           ", hashToId: " + valueHashToId +
           ", descriptor=" + valueDescriptor +
           ']';
  }

  /**
   * append-log identifies records by _long_ id, while enumerator API uses _int_ ids -- the method does the
   * conversion
   */
  private static int convertLogIdToEnumeratorId(long logRecordId) {
    return Math.toIntExact(logRecordId);
  }

  private int lookupIdForValue(@NotNull V value) throws IOException {
    int valueHash = adjustHash(valueDescriptor.hashCodeOf(value));
    return valueHashToId.lookup(valueHash, candidateId -> {
      V candidateKey = valuesLog.read(candidateId, valueDescriptor::read);
      return valueDescriptor.areEqual(candidateKey, value);
    });
  }

  private int lookupOrCreateIdForValue(@NotNull V value) throws IOException {
    int valueHash = adjustHash(valueDescriptor.hashCodeOf(value));
    return valueHashToId.lookupOrInsert(
      valueHash,
      candidateId -> {
        V candidateValue = valuesLog.read(candidateId, valueDescriptor::read);
        return valueDescriptor.areEqual(candidateValue, value);
      },
      _valueHash_ -> {
        long logRecordId = valueDescriptor.saveToLog(value, valuesLog);
        return convertLogIdToEnumeratorId(logRecordId);
      });
  }

  private static int adjustHash(int hash) {
    if (hash == DurableIntToMultiIntMap.NO_VALUE) {
      //DurableIntToMultiIntMap doesn't allow 0 keys/values, hence replace 0 key with just anything !=0.
      // Key (=hash) doesn't identify value uniquely anyway, hence this replacement just adds another
      // collision -- basically, we replaced original Key.hash with our own hash, which avoids 0 at
      // the cost of slightly higher collision chances
      return -1;// anything !=0 will do
    }
    return hash;
  }
}
