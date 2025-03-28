/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.streaming.runtime;

import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.UnsupportedTimeCharacteristicException;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.test.streaming.runtime.TimestampITCase.AscendingRecordTimestampsWatermarkStrategy;
import org.apache.flink.util.Collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.apache.flink.runtime.state.StateBackendLoader.FORST_STATE_BACKEND_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Integration tests for interval joins. */
public class IntervalJoinITCase {

    private static List<String> testResults;

    @BeforeEach
    public void setup() {
        testResults = new ArrayList<>();
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testCanJoinOverSameKey(boolean enableAsyncState) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.fromData(
                                Tuple2.of("key", 0),
                                Tuple2.of("key", 1),
                                Tuple2.of("key", 2),
                                Tuple2.of("key", 3),
                                Tuple2.of("key", 4),
                                Tuple2.of("key", 5))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.fromData(
                                Tuple2.of("key", 0),
                                Tuple2.of("key", 1),
                                Tuple2.of("key", 2),
                                Tuple2.of("key", 3),
                                Tuple2.of("key", 4),
                                Tuple2.of("key", 5))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }

        streamOne
                .intervalJoin(streamTwo)
                .between(Duration.ofMillis(0), Duration.ofMillis(0))
                .process(
                        new ProcessJoinFunction<
                                Tuple2<String, Integer>, Tuple2<String, Integer>, String>() {
                            @Override
                            public void processElement(
                                    Tuple2<String, Integer> left,
                                    Tuple2<String, Integer> right,
                                    Context ctx,
                                    Collector<String> out)
                                    throws Exception {
                                out.collect(left + ":" + right);
                            }
                        })
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder(
                "(key,0):(key,0)",
                "(key,1):(key,1)",
                "(key,2):(key,2)",
                "(key,3):(key,3)",
                "(key,4):(key,4)",
                "(key,5):(key,5)");
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testJoinsCorrectlyWithMultipleKeys(boolean enableAsyncState) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.fromData(
                                Tuple2.of("key1", 0),
                                Tuple2.of("key2", 1),
                                Tuple2.of("key1", 2),
                                Tuple2.of("key2", 3),
                                Tuple2.of("key1", 4),
                                Tuple2.of("key2", 5))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.fromData(
                                Tuple2.of("key1", 0),
                                Tuple2.of("key2", 1),
                                Tuple2.of("key1", 2),
                                Tuple2.of("key2", 3),
                                Tuple2.of("key1", 4),
                                Tuple2.of("key2", 5))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }

        streamOne
                .intervalJoin(streamTwo)
                // if it were not keyed then the boundaries [0; 1] would lead to the pairs (1, 1),
                // (1, 2), (2, 2), (2, 3)..., so that this is not happening is what we are testing
                // here
                .between(Duration.ofMillis(0), Duration.ofMillis(1))
                .process(new CombineToStringJoinFunction())
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder(
                "(key1,0):(key1,0)",
                "(key2,1):(key2,1)",
                "(key1,2):(key1,2)",
                "(key2,3):(key2,3)",
                "(key1,4):(key1,4)",
                "(key2,5):(key2,5)");
    }

    private DataStream<Tuple2<String, Integer>> buildSourceStream(
            final StreamExecutionEnvironment env, final SourceConsumer sourceConsumer) {
        return env.addSource(
                new SourceFunction<Tuple2<String, Integer>>() {
                    @Override
                    public void run(SourceContext<Tuple2<String, Integer>> ctx) {
                        sourceConsumer.accept(ctx);
                    }

                    @Override
                    public void cancel() {
                        // do nothing
                    }
                });
    }

    // Ensure consumer func is serializable
    private interface SourceConsumer
            extends Serializable, Consumer<SourceFunction.SourceContext<Tuple2<String, Integer>>> {
        long serialVersionUID = 1L;
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testBoundedUnorderedStreamsStillJoinCorrectly(boolean enableAsyncState)
            throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.addSource(
                                new SourceFunction<Tuple2<String, Integer>>() {
                                    @Override
                                    public void run(SourceContext<Tuple2<String, Integer>> ctx) {
                                        ctx.collectWithTimestamp(Tuple2.of("key", 5), 5L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 1), 1L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 4), 4L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 3), 3L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 2), 2L);
                                        ctx.emitWatermark(new Watermark(5));
                                        ctx.collectWithTimestamp(Tuple2.of("key", 9), 9L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 8), 8L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 7), 7L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 6), 6L);
                                    }

                                    @Override
                                    public void cancel() {
                                        // do nothing
                                    }
                                })
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.addSource(
                                new SourceFunction<Tuple2<String, Integer>>() {
                                    @Override
                                    public void run(SourceContext<Tuple2<String, Integer>> ctx) {
                                        ctx.collectWithTimestamp(Tuple2.of("key", 2), 2L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 1), 1L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 3), 3L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 4), 4L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 5), 5L);
                                        ctx.emitWatermark(new Watermark(5));
                                        ctx.collectWithTimestamp(Tuple2.of("key", 8), 8L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 7), 7L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 9), 9L);
                                        ctx.collectWithTimestamp(Tuple2.of("key", 6), 6L);
                                    }

                                    @Override
                                    public void cancel() {
                                        // do nothing
                                    }
                                })
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }

        streamOne
                .intervalJoin(streamTwo)
                .between(Duration.ofMillis(-1), Duration.ofMillis(1))
                .process(new CombineToStringJoinFunction())
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder(
                "(key,1):(key,1)",
                "(key,1):(key,2)",
                "(key,2):(key,1)",
                "(key,2):(key,2)",
                "(key,2):(key,3)",
                "(key,3):(key,2)",
                "(key,3):(key,3)",
                "(key,3):(key,4)",
                "(key,4):(key,3)",
                "(key,4):(key,4)",
                "(key,4):(key,5)",
                "(key,5):(key,4)",
                "(key,5):(key,5)",
                "(key,5):(key,6)",
                "(key,6):(key,5)",
                "(key,6):(key,6)",
                "(key,6):(key,7)",
                "(key,7):(key,6)",
                "(key,7):(key,7)",
                "(key,7):(key,8)",
                "(key,8):(key,7)",
                "(key,8):(key,8)",
                "(key,8):(key,9)",
                "(key,9):(key,8)",
                "(key,9):(key,9)");
    }

    @Test
    public void testFailsWithoutUpperBound() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    final StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();
                    env.setParallelism(1);

                    DataStream<Tuple2<String, Integer>> streamOne = env.fromData(Tuple2.of("1", 1));
                    DataStream<Tuple2<String, Integer>> streamTwo = env.fromData(Tuple2.of("1", 1));

                    streamOne
                            .keyBy(new Tuple2KeyExtractor())
                            .intervalJoin(streamTwo.keyBy(new Tuple2KeyExtractor()))
                            .between(Duration.ofMillis(0), null);
                });
    }

    @Test
    public void testFailsWithoutLowerBound() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    final StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();
                    env.setParallelism(1);

                    DataStream<Tuple2<String, Integer>> streamOne = env.fromData(Tuple2.of("1", 1));
                    DataStream<Tuple2<String, Integer>> streamTwo = env.fromData(Tuple2.of("1", 1));

                    streamOne
                            .keyBy(new Tuple2KeyExtractor())
                            .intervalJoin(streamTwo.keyBy(new Tuple2KeyExtractor()))
                            .between(null, Duration.ofMillis(1));
                });
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testBoundsCanBeExclusive(boolean enableAsyncState) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }
        streamOne
                .intervalJoin(streamTwo)
                .between(Duration.ofMillis(0), Duration.ofMillis(2))
                .upperBoundExclusive()
                .lowerBoundExclusive()
                .process(new CombineToStringJoinFunction())
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder("(key,0):(key,1)", "(key,1):(key,2)");
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testBoundsCanBeInclusive(boolean enableAsyncState) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }
        streamOne
                .intervalJoin(streamTwo)
                .between(Duration.ofMillis(0), Duration.ofMillis(2))
                .process(new CombineToStringJoinFunction())
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder(
                "(key,0):(key,0)",
                "(key,0):(key,1)",
                "(key,0):(key,2)",
                "(key,1):(key,1)",
                "(key,1):(key,2)",
                "(key,2):(key,2)");
    }

    @ParameterizedTest(name = "Enable async state = {0}")
    @ValueSource(booleans = {false, true})
    public void testBoundsAreInclusiveByDefault(boolean enableAsyncState) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KeyedStream<Tuple2<String, Integer>, String> streamOne =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        KeyedStream<Tuple2<String, Integer>, String> streamTwo =
                env.fromData(Tuple2.of("key", 0), Tuple2.of("key", 1), Tuple2.of("key", 2))
                        .assignTimestampsAndWatermarks(new AscendingTuple2TimestampExtractor())
                        .keyBy(new Tuple2KeyExtractor());

        if (enableAsyncState) {
            streamOne.enableAsyncState();
            streamTwo.enableAsyncState();
            configAsyncState(env);
        }
        streamOne
                .intervalJoin(streamTwo)
                .between(Duration.ofMillis(0), Duration.ofMillis(2))
                .process(new CombineToStringJoinFunction())
                .addSink(new ResultSink());

        env.execute();

        expectInAnyOrder(
                "(key,0):(key,0)",
                "(key,0):(key,1)",
                "(key,0):(key,2)",
                "(key,1):(key,1)",
                "(key,1):(key,2)",
                "(key,2):(key,2)");
    }

    @Test
    public void testExecutionFailsInProcessingTime() {
        assertThrows(
                UnsupportedTimeCharacteristicException.class,
                () -> {
                    final StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();
                    env.setParallelism(1);

                    DataStream<Tuple2<String, Integer>> streamOne = env.fromData(Tuple2.of("1", 1));
                    DataStream<Tuple2<String, Integer>> streamTwo = env.fromData(Tuple2.of("1", 1));

                    streamOne
                            .keyBy(new Tuple2KeyExtractor())
                            .intervalJoin(streamTwo.keyBy(new Tuple2KeyExtractor()))
                            .inProcessingTime()
                            .between(Duration.ofMillis(0), Duration.ofMillis(0))
                            .process(
                                    new ProcessJoinFunction<
                                            Tuple2<String, Integer>,
                                            Tuple2<String, Integer>,
                                            String>() {
                                        @Override
                                        public void processElement(
                                                Tuple2<String, Integer> left,
                                                Tuple2<String, Integer> right,
                                                Context ctx,
                                                Collector<String> out)
                                                throws Exception {
                                            out.collect(left + ":" + right);
                                        }
                                    });
                });
    }

    private static void expectInAnyOrder(String... expected) {
        assertThat(testResults).containsExactlyInAnyOrder(expected);
    }

    private static void configAsyncState(StreamExecutionEnvironment env) {
        // For async state, by default we will use the forst state backend.
        Configuration config = Configuration.fromMap(env.getConfiguration().toMap());
        config.set(StateBackendOptions.STATE_BACKEND, FORST_STATE_BACKEND_NAME);
        env.configure(config);
    }

    private static class AscendingTuple2TimestampExtractor
            extends AscendingRecordTimestampsWatermarkStrategy<Tuple2<String, Integer>> {
        public AscendingTuple2TimestampExtractor() {
            super((e) -> Long.valueOf(e.f1));
        }
    }

    private static class ResultSink implements SinkFunction<String> {
        @Override
        public void invoke(String value, Context context) throws Exception {
            testResults.add(value);
        }
    }

    private static class CombineToStringJoinFunction
            extends ProcessJoinFunction<Tuple2<String, Integer>, Tuple2<String, Integer>, String> {
        @Override
        public void processElement(
                Tuple2<String, Integer> left,
                Tuple2<String, Integer> right,
                Context ctx,
                Collector<String> out) {
            out.collect(left + ":" + right);
        }
    }

    private static class Tuple2KeyExtractor
            implements KeySelector<Tuple2<String, Integer>, String> {

        @Override
        public String getKey(Tuple2<String, Integer> value) throws Exception {
            return value.f0;
        }
    }
}
