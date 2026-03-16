/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.timeseries.sampleread.aggregation;

import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.opennms.integration.api.v1.timeseries.Aggregation;
import org.opennms.integration.api.v1.timeseries.DataPoint;
import org.opennms.integration.api.v1.timeseries.MetaTagNames;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.Tag;
import org.opennms.integration.api.v1.timeseries.TimeSeriesData;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableDataPoint;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableTimeSeriesData;
import org.opennms.netmgt.measurements.model.Source;
import org.opennms.netmgt.timeseries.sampleread.LateAggregationParams;

/** Aggregates samples into step-sized buckets, replicating the former Newts aggregation behaviour. */
public class NewtsLikeSampleAggregator {

    private final String resourceId;
    private final Instant start;
    private final Instant end;
    private final List<Source> currentSources;
    private final LateAggregationParams lag;
    private final Metric metric;

    private NewtsLikeSampleAggregator(String resourceId, Instant start, Instant end, List<Source> currentSources,
                                      final LateAggregationParams lag, Metric metric) {
        this.resourceId = checkNotNull(resourceId, "resourceId argument");
        this.start = checkNotNull(start, "start argument");
        this.end = checkNotNull(end, "end argument");
        this.currentSources = checkNotNull(currentSources, "currentSources argument");
        checkNotNull(lag, "lag argument");
        this.lag = lag;
        this.metric = checkNotNull(metric, "metric argument");
    }

    public static NewtsLikeSampleAggregatorBuilder builder() {
        return new NewtsLikeSampleAggregatorBuilder();
    }

    public TimeSeriesData process(TimeSeriesData input) {
        checkNotNull(input, "input argument");

        final Metric inputMetric = input.getMetric();
        final Source source = currentSources.get(0);
        final Aggregation aggregation = toAggregation(source.getAggregation());

        List<DataPoint> sorted = input.getDataPoints().stream()
                .sorted(Comparator.comparing(DataPoint::getTime))
                .collect(Collectors.toList());

        List<Sample> samples;
        if (isCounter(inputMetric)) {
            samples = computeRates(sorted, inputMetric);
        } else {
            samples = toSamples(sorted, inputMetric);
        }

        List<Sample> aggregated = SampleAggregator.builder()
                .samples(samples)
                .aggregation(aggregation)
                .startTime(start)
                .endTime(end)
                .bucketSize(Duration.ofMillis(lag.getStep()))
                .expectedMetric(inputMetric)
                .build()
                .computeAggregatedSamples();

        List<DataPoint> dataPoints = aggregated.stream()
                .map(s -> ImmutableDataPoint.builder().time(s.getTime()).value(s.getValue()).build())
                .collect(Collectors.toList());

        return ImmutableTimeSeriesData.builder()
                .metric(metric)
                .dataPoints(dataPoints)
                .build();
    }

    private boolean isCounter(Metric m) {
        Tag mtypeTag = m.getFirstTagByKey(MetaTagNames.mtype);
        if (mtypeTag == null) {
            return false;
        }
        try {
            return Metric.Mtype.count == Metric.Mtype.valueOf(mtypeTag.getValue());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<Sample> computeRates(List<DataPoint> sorted, Metric m) {
        List<Sample> rates = new ArrayList<>(sorted.size());
        for (int i = 1; i < sorted.size(); i++) {
            DataPoint prev = sorted.get(i - 1);
            DataPoint curr = sorted.get(i);
            long dtMs = curr.getTime().toEpochMilli() - prev.getTime().toEpochMilli();
            double rate;
            if (dtMs <= 0 || dtMs > lag.getHeartbeat() || curr.getValue() < prev.getValue()) {
                rate = Double.NaN;
            } else {
                rate = (curr.getValue() - prev.getValue()) / (dtMs / 1000.0);
            }
            rates.add(ImmutableSample.builder().metric(m).time(curr.getTime()).value(rate).build());
        }
        return rates;
    }

    private List<Sample> toSamples(List<DataPoint> points, Metric m) {
        return points.stream()
                .map(dp -> ImmutableSample.builder().metric(m).time(dp.getTime()).value(dp.getValue()).build())
                .collect(Collectors.toList());
    }

    private static Aggregation toAggregation(String fn) {
        if ("average".equalsIgnoreCase(fn) || "avg".equalsIgnoreCase(fn)) {
            return Aggregation.AVERAGE;
        } else if ("max".equalsIgnoreCase(fn)) {
            return Aggregation.MAX;
        } else if ("min".equalsIgnoreCase(fn)) {
            return Aggregation.MIN;
        } else {
            throw new IllegalArgumentException("Unsupported aggregation function: " + fn);
        }
    }

    public static class NewtsLikeSampleAggregatorBuilder {
        private String resourceId;
        private Instant start;
        private Instant end;
        private List<Source> currentSources;
        private LateAggregationParams lateAggregationParams;
        private Metric metric;

        NewtsLikeSampleAggregatorBuilder() {
        }

        public NewtsLikeSampleAggregatorBuilder resource(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public NewtsLikeSampleAggregatorBuilder start(Instant start) {
            this.start = start;
            return this;
        }

        public NewtsLikeSampleAggregatorBuilder end(Instant end) {
            this.end = end;
            return this;
        }

        public NewtsLikeSampleAggregatorBuilder currentSources(List<Source> currentSources) {
            this.currentSources = currentSources;
            return this;
        }

        public NewtsLikeSampleAggregatorBuilder lag(final LateAggregationParams lateAggregationParams) {
            this.lateAggregationParams = lateAggregationParams;
            return this;
        }

        public NewtsLikeSampleAggregatorBuilder metric(Metric metric) {
            this.metric = metric;
            return this;
        }

        public NewtsLikeSampleAggregator build() {
            return new NewtsLikeSampleAggregator(resourceId, start, end, currentSources, lateAggregationParams, metric);
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", NewtsLikeSampleAggregatorBuilder.class.getSimpleName() + "[", "]")
                    .add("resourceId=" + resourceId)
                    .add("start=" + start)
                    .add("end=" + end)
                    .add("currentSources=" + currentSources)
                    .add("lateAggregationParams=" + lateAggregationParams)
                    .add("metric=" + metric)
                    .toString();
        }
    }
}
