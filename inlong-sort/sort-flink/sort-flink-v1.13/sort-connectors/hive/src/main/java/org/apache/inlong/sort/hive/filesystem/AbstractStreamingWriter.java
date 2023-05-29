/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.hive.filesystem;

import org.apache.inlong.sort.base.dirty.DirtyData;
import org.apache.inlong.sort.base.dirty.DirtyOptions;
import org.apache.inlong.sort.base.dirty.DirtyType;
import org.apache.inlong.sort.base.dirty.sink.DirtySink;
import org.apache.inlong.sort.base.metric.MetricOption;
import org.apache.inlong.sort.base.metric.MetricOption.RegisteredMetric;
import org.apache.inlong.sort.base.metric.MetricState;
import org.apache.inlong.sort.base.metric.SinkMetricData;
import org.apache.inlong.sort.base.util.CalculateObjectSizeUtils;
import org.apache.inlong.sort.base.util.MetricStateUtils;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.functions.sink.filesystem.Bucket;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketLifeCycleListener;
import org.apache.flink.streaming.api.functions.sink.filesystem.Buckets;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSinkHelper;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;

import static org.apache.inlong.sort.base.Constants.DIRTY_BYTES_OUT;
import static org.apache.inlong.sort.base.Constants.DIRTY_RECORDS_OUT;
import static org.apache.inlong.sort.base.Constants.INLONG_METRIC_STATE_NAME;
import static org.apache.inlong.sort.base.Constants.NUM_BYTES_OUT;
import static org.apache.inlong.sort.base.Constants.NUM_RECORDS_OUT;

/**
 * Operator for file system sink. It is a operator version of {@link StreamingFileSink}. It can send
 * file and bucket information to downstream.
 */
public abstract class AbstractStreamingWriter<IN, OUT> extends AbstractStreamOperator<OUT>
        implements
            OneInputStreamOperator<IN, OUT>,
            BoundedOneInput {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamingWriter.class);

    // ------------------------ configuration fields --------------------------

    private final long bucketCheckInterval;

    private final StreamingFileSink.BucketsBuilder<IN, String, ? extends StreamingFileSink.BucketsBuilder<IN, String, ?>> bucketsBuilder;

    private @Nullable final String inlongMetric;
    private @Nullable final String auditHostAndPorts;
    private final DirtyOptions dirtyOptions;
    private @Nullable final DirtySink<Object> dirtySink;

    // --------------------------- runtime fields -----------------------------

    private transient Buckets<IN, String> buckets;

    private transient StreamingFileSinkHelper<IN> helper;

    private transient long currentWatermark;

    @Nullable
    private transient SinkMetricData metricData;
    private transient ListState<MetricState> metricStateListState;
    private transient MetricState metricState;

    private Long dataSize = 0L;
    private Long rowSize = 0L;

    public AbstractStreamingWriter(
            long bucketCheckInterval,
            StreamingFileSink.BucketsBuilder<IN, String, ? extends StreamingFileSink.BucketsBuilder<IN, String, ?>> bucketsBuilder,
            @Nullable String inlongMetric,
            @Nullable String auditHostAndPorts,
            DirtyOptions dirtyOptions,
            @Nullable DirtySink<Object> dirtySink) {
        this.bucketCheckInterval = bucketCheckInterval;
        this.bucketsBuilder = bucketsBuilder;
        this.inlongMetric = inlongMetric;
        this.auditHostAndPorts = auditHostAndPorts;
        this.dirtyOptions = dirtyOptions;
        this.dirtySink = dirtySink;
        setChainingStrategy(ChainingStrategy.ALWAYS);
    }

    /**
     * Notifies a partition created.
     */
    protected abstract void partitionCreated(String partition);

    /**
     * Notifies a partition become inactive. A partition becomes inactive after all the records
     * received so far have been committed.
     */
    protected abstract void partitionInactive(String partition);

    /**
     * Notifies a new file has been opened.
     *
     * <p>Note that this does not mean that the file has been created in the file system. It is only
     * created logically and the actual file will be generated after it is committed.
     */
    protected abstract void onPartFileOpened(String partition, Path newPath);

    /**
     * Commit up to this checkpoint id.
     */
    protected void commitUpToCheckpoint(long checkpointId) throws Exception {
        try {
            helper.commitUpToCheckpoint(checkpointId);
            if (metricData != null) {
                metricData.invoke(rowSize, dataSize);
            }
            rowSize = 0L;
            dataSize = 0L;
        } catch (Exception e) {
            LOG.error("hive sink commitUpToCheckpoint occurs error.", e);
            throw e;
        }
    }

    @Override
    public void open() throws Exception {
        super.open();
        MetricOption metricOption = MetricOption.builder()
                .withInlongLabels(inlongMetric)
                .withAuditAddress(auditHostAndPorts)
                .withInitRecords(metricState != null ? metricState.getMetricValue(NUM_RECORDS_OUT) : 0L)
                .withInitBytes(metricState != null ? metricState.getMetricValue(NUM_BYTES_OUT) : 0L)
                .withInitDirtyRecords(metricState != null ? metricState.getMetricValue(DIRTY_RECORDS_OUT) : 0L)
                .withInitDirtyBytes(metricState != null ? metricState.getMetricValue(DIRTY_BYTES_OUT) : 0L)
                .withRegisterMetric(RegisteredMetric.ALL)
                .build();
        if (metricOption != null) {
            metricData = new SinkMetricData(metricOption, getRuntimeContext().getMetricGroup());
        }
        if (dirtySink != null) {
            dirtySink.open(new Configuration());
        }
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);
        buckets = bucketsBuilder.createBuckets(getRuntimeContext().getIndexOfThisSubtask());

        // Set listener before the initialization of Buckets.
        buckets.setBucketLifeCycleListener(
                new BucketLifeCycleListener<IN, String>() {

                    @Override
                    public void bucketCreated(Bucket<IN, String> bucket) {
                        partitionCreated(bucket.getBucketId());
                    }

                    @Override
                    public void bucketInactive(Bucket<IN, String> bucket) {
                        partitionInactive(bucket.getBucketId());
                    }
                });

        buckets.setFileLifeCycleListener(this::onPartFileOpened);

        helper =
                new StreamingFileSinkHelper<>(
                        buckets,
                        context.isRestored(),
                        context.getOperatorStateStore(),
                        getRuntimeContext().getProcessingTimeService(),
                        bucketCheckInterval);

        currentWatermark = Long.MIN_VALUE;

        // init metric state
        if (this.inlongMetric != null) {
            this.metricStateListState = context.getOperatorStateStore().getUnionListState(
                    new ListStateDescriptor<>(
                            INLONG_METRIC_STATE_NAME, TypeInformation.of(new TypeHint<MetricState>() {
                            })));
        }
        if (context.isRestored()) {
            metricState = MetricStateUtils.restoreMetricState(metricStateListState,
                    getRuntimeContext().getIndexOfThisSubtask(), getRuntimeContext().getNumberOfParallelSubtasks());
        }
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        helper.snapshotState(context.getCheckpointId());
        if (metricData != null && metricStateListState != null) {
            MetricStateUtils.snapshotMetricStateForSinkMetricData(metricStateListState, metricData,
                    getRuntimeContext().getIndexOfThisSubtask());
        }
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        super.processWatermark(mark);
        currentWatermark = mark.getTimestamp();
    }

    @Override
    public void processElement(StreamRecord<IN> element) throws Exception {
        try {
            helper.onElement(
                    element.getValue(),
                    getProcessingTimeService().getCurrentProcessingTime(),
                    element.hasTimestamp() ? element.getTimestamp() : null,
                    currentWatermark);
            rowSize = rowSize + 1;
            if (element.getValue() != null) {
                dataSize = dataSize + CalculateObjectSizeUtils.getDataSize(element.getValue());
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("StreamingWriter write failed", e);
            if (!dirtyOptions.ignoreDirty()) {
                throw new RuntimeException(e);
            }
            if (metricData != null) {
                metricData.invokeDirtyWithEstimate(element.getValue());
            }
            if (dirtySink != null) {
                DirtyData.Builder<Object> builder = DirtyData.builder();
                try {
                    builder.setData(element.getValue())
                            .setDirtyType(DirtyType.UNDEFINED)
                            .setLabels(dirtyOptions.getLabels())
                            .setLogTag(dirtyOptions.getLogTag())
                            .setDirtyMessage(e.getMessage())
                            .setIdentifier(dirtyOptions.getIdentifier());
                    dirtySink.invoke(builder.build());
                } catch (Exception ex) {
                    if (!dirtyOptions.ignoreSideOutputErrors()) {
                        throw new RuntimeException(ex);
                    }
                    LOGGER.warn("Dirty sink failed", ex);
                }
            }
        }
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        super.notifyCheckpointComplete(checkpointId);
        commitUpToCheckpoint(checkpointId);
    }

    @Override
    public void endInput() throws Exception {
        buckets.onProcessingTime(Long.MAX_VALUE);
        helper.snapshotState(Long.MAX_VALUE);
        output.emitWatermark(new Watermark(Long.MAX_VALUE));
        commitUpToCheckpoint(Long.MAX_VALUE);
    }

    @Override
    public void dispose() throws Exception {
        super.dispose();
        if (helper != null) {
            helper.close();
        }
    }
}