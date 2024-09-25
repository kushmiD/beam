/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.iceberg;

import static org.apache.beam.sdk.util.Preconditions.checkArgumentNotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.catalog.Catalog;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A PTransform that writes rows to files according to their dynamic destination. If there are too
 * many destinations in a single bundle, some rows will be written to a secondary output and must be
 * written via another method.
 */
class WriteUngroupedRowsToFiles
    extends PTransform<PCollection<Row>, WriteUngroupedRowsToFiles.Result> {

  /**
   * Maximum number of writers that will be created per bundle. Any elements requiring more writers
   * will be spilled.
   */
  @VisibleForTesting static final int DEFAULT_MAX_WRITERS_PER_BUNDLE = 20;

  private static final long DEFAULT_MAX_BYTES_PER_FILE = (1L << 29); // 512mb

  private static final TupleTag<FileWriteResult> WRITTEN_FILES_TAG = new TupleTag<>("writtenFiles");
  private static final TupleTag<Row> WRITTEN_ROWS_TAG = new TupleTag<Row>("writtenRows") {};
  private static final TupleTag<Row> SPILLED_ROWS_TAG = new TupleTag<Row>("spilledRows") {};

  private final String filePrefix;
  private final DynamicDestinations dynamicDestinations;
  private final IcebergCatalogConfig catalogConfig;

  WriteUngroupedRowsToFiles(
      IcebergCatalogConfig catalogConfig, DynamicDestinations dynamicDestinations) {
    this.catalogConfig = catalogConfig;
    this.dynamicDestinations = dynamicDestinations;
    this.filePrefix = UUID.randomUUID().toString();
  }

  @Override
  public Result expand(PCollection<Row> input) {

    PCollectionTuple resultTuple =
        input.apply(
            ParDo.of(
                    new WriteUngroupedRowsToFilesDoFn(
                        catalogConfig,
                        dynamicDestinations,
                        filePrefix,
                        DEFAULT_MAX_WRITERS_PER_BUNDLE,
                        DEFAULT_MAX_BYTES_PER_FILE))
                .withOutputTags(
                    WRITTEN_FILES_TAG,
                    TupleTagList.of(ImmutableList.of(WRITTEN_ROWS_TAG, SPILLED_ROWS_TAG))));

    return new Result(
        input.getPipeline(),
        resultTuple.get(WRITTEN_FILES_TAG),
        resultTuple.get(WRITTEN_ROWS_TAG).setCoder(input.getCoder()),
        resultTuple.get(SPILLED_ROWS_TAG).setCoder(input.getCoder()));
  }

  /**
   * The result of this transform has two components: the records that were written and the records
   * that spilled over and need to be written by a subsquent method.
   */
  static class Result implements POutput {

    private final Pipeline pipeline;
    private final PCollection<Row> writtenRows;
    private final PCollection<Row> spilledRows;
    private final PCollection<FileWriteResult> writtenFiles;

    private Result(
        Pipeline pipeline,
        PCollection<FileWriteResult> writtenFiles,
        PCollection<Row> writtenRows,
        PCollection<Row> spilledRows) {
      this.pipeline = pipeline;
      this.writtenFiles = writtenFiles;
      this.writtenRows = writtenRows;
      this.spilledRows = spilledRows;
    }

    public PCollection<Row> getWrittenRows() {
      return writtenRows;
    }

    public PCollection<Row> getSpilledRows() {
      return spilledRows;
    }

    public PCollection<FileWriteResult> getWrittenFiles() {
      return writtenFiles;
    }

    @Override
    public Pipeline getPipeline() {
      return pipeline;
    }

    @Override
    public Map<TupleTag<?>, PValue> expand() {
      return ImmutableMap.<TupleTag<?>, PValue>builder()
          .put(WRITTEN_FILES_TAG, writtenFiles)
          .put(WRITTEN_ROWS_TAG, writtenRows)
          .put(SPILLED_ROWS_TAG, spilledRows)
          .build();
    }

    @Override
    public void finishSpecifyingOutput(
        String transformName, PInput input, PTransform<?, ?> transform) {
      // noop
    }
  }

  /**
   * A DoFn that writes each input row to its assigned destination and outputs a result object
   * summarizing what it accomplished for a given bundle.
   *
   * <p>Specifically, the outputs are:
   *
   * <ul>
   *   <li>(main output) the written files
   *   <li>the written records
   *   <li>the spilled records which were not written
   * </ul>
   */
  private static class WriteUngroupedRowsToFilesDoFn extends DoFn<Row, FileWriteResult> {

    private final String filename;
    private final int maxWritersPerBundle;
    private final long maxFileSize;
    private final DynamicDestinations dynamicDestinations;
    private final IcebergCatalogConfig catalogConfig;
    private transient @MonotonicNonNull Catalog catalog;
    private transient @Nullable RecordWriterManager recordWriterManager;

    public WriteUngroupedRowsToFilesDoFn(
        IcebergCatalogConfig catalogConfig,
        DynamicDestinations dynamicDestinations,
        String filename,
        int maximumWritersPerBundle,
        long maxFileSize) {
      this.catalogConfig = catalogConfig;
      this.dynamicDestinations = dynamicDestinations;
      this.filename = filename;
      this.maxWritersPerBundle = maximumWritersPerBundle;
      this.maxFileSize = maxFileSize;
    }

    private org.apache.iceberg.catalog.Catalog getCatalog() {
      if (catalog == null) {
        this.catalog = catalogConfig.catalog();
      }
      return catalog;
    }

    @StartBundle
    public void startBundle() {
      recordWriterManager =
          new RecordWriterManager(getCatalog(), filename, maxFileSize, maxWritersPerBundle);
    }

    @ProcessElement
    public void processElement(
        @Element Row element, BoundedWindow window, PaneInfo pane, MultiOutputReceiver out)
        throws Exception {

      Row data = checkArgumentNotNull(element.getRow("data"), "Input row missing `data` field.");
      Row destMetadata =
          checkArgumentNotNull(element.getRow("dest"), "Input row missing `dest` field.");
      IcebergDestination destination = dynamicDestinations.instantiateDestination(destMetadata);
      WindowedValue<IcebergDestination> windowedDestination =
          WindowedValue.of(destination, window.maxTimestamp(), window, pane);

      // Attempt to write record. If the writer is saturated and cannot accept
      // the record, spill it over to WriteGroupedRowsToFiles
      boolean writeSuccess;
      try {
        writeSuccess =
            Preconditions.checkNotNull(recordWriterManager).write(windowedDestination, data);
      } catch (Exception e) {
        try {
          Preconditions.checkNotNull(recordWriterManager).close();
        } catch (Exception closeException) {
          e.addSuppressed(closeException);
        }
        throw e;
      }
      out.get(writeSuccess ? WRITTEN_ROWS_TAG : SPILLED_ROWS_TAG).output(element);
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      if (recordWriterManager == null) {
        return;
      }
      recordWriterManager.close();
      for (Map.Entry<WindowedValue<IcebergDestination>, List<ManifestFile>> destinationAndFiles :
          Preconditions.checkNotNull(recordWriterManager).getManifestFiles().entrySet()) {
        WindowedValue<IcebergDestination> windowedDestination = destinationAndFiles.getKey();

        for (ManifestFile manifestFile : destinationAndFiles.getValue()) {
          c.output(
              FileWriteResult.builder()
                  .setManifestFile(manifestFile)
                  .setTableIdentifier(windowedDestination.getValue().getTableIdentifier())
                  .build(),
              windowedDestination.getTimestamp(),
              Iterables.getFirst(windowedDestination.getWindows(), null));
        }
      }
      recordWriterManager = null;
    }
  }
}