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
package org.opennms.netmgt.threshd;

import static org.opennms.core.utils.InetAddressUtils.addr;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.nustaq.serialization.FSTConfiguration;
import org.opennms.core.sysprops.SystemProperties;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.features.distributed.kvstore.api.BlobStore;
import org.opennms.features.distributed.kvstore.api.SerializingBlobStore;
import org.opennms.netmgt.collection.api.CollectionResource;
import org.opennms.netmgt.model.ResourceId;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.threshd.api.ThresholdingSession;
import org.opennms.netmgt.xml.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.swrve.ratelimitedlogger.RateLimitedLog;

/**
 * <p>Abstract AbstractThresholdEvaluatorState class.</p>
 *
 * @author ranger
 * @version $Id: $
 */
public abstract class AbstractThresholdEvaluatorState<T extends AbstractThresholdEvaluatorState.AbstractState> implements ThresholdEvaluatorState {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractThresholdEvaluatorState.class);
    private static final RateLimitedLog RATE_LIMITED_LOGGER = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.ofSeconds(30))
            .build();

    private static final String UNKNOWN = "Unknown";

    public static final String FORMATED_NAN = "NaN (the threshold definition has been changed)";

    public static final FSTConfiguration fst = FSTConfiguration.createDefaultConfiguration();

    // Pre-register the classes we know we will be serializing as this increases performance
    static {
        fst.registerClass(
                ThresholdEvaluatorHighLow.ThresholdEvaluatorStateHighLow.State.class,
                ThresholdEvaluatorRelativeChange.ThresholdEvaluatorStateRelativeChange.State.class,
                ThresholdEvaluatorRearmingAbsoluteChange.ThresholdEvaluatorStateRearmingAbsoluteChange.State.class,
                ThresholdEvaluatorAbsoluteChange.ThresholdEvaluatorStateAbsoluteChange.State.class
        );
    }

    private boolean isStateDirty;

    private String key;

    private final SerializingBlobStore<T> kvStore;

    protected T state;
    
    protected final ThresholdingSession thresholdingSession;
    
    static final String THRESHOLDING_KV_CONTEXT = "thresholding";

    private final int stateTTL = SystemProperties.getInteger("org.opennms.netmgt.threshd.state_ttl",
            (int) TimeUnit.SECONDS.convert(24, TimeUnit.HOURS));
    
    private Long sequenceNumber;

    private boolean firstEvaluation = true;
    
    private String instance;


    static final Map<Class<? extends AbstractThresholdEvaluatorState.AbstractState>,
            SerializingBlobStore<? extends AbstractThresholdEvaluatorState.AbstractState>> serdesMap
            = new ConcurrentHashMap<>();

    /**
     * A last updated cache to track when the last time we know we persisted a given key was. This is for performance
     * reasons so that on fetch we can see if we already were the last ones to update and avoid a full fetch if so.
     */
    private static final Map<String, Long> lastUpdatedCache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .build(new CacheLoader<String, Long>() {
                @Override
                public Long load(String key) {
                    // We don't actually want to load anything if the key isn't in the cache
                    return null;
                }
            })
            .asMap();

    static abstract class AbstractState implements Serializable {
        String interpolatedExpression = null;
        boolean cached = false;
        ThresholdValues thresholdValues = null;

        Optional<String> getInterpolatedExpression() {
            return Optional.ofNullable(interpolatedExpression);
        }
        
        void setInterpolatedExpression(String expression) {
            interpolatedExpression = Objects.requireNonNull(expression);
        }

        public void setCached(boolean cached) {
            this.cached = cached;
        }

        public boolean isCached() {
            return cached;
        }

        public ThresholdValues getThresholdValues() {
            return thresholdValues;
        }

        public void setThresholdValues(ThresholdValues thresholdValues) {
            this.thresholdValues = thresholdValues;
        }


        @Override
        public String toString() {
            return getInterpolatedExpression().map(ie -> "interpolatedExpression=" + ie).orElse(null);
        }
    }

    AbstractThresholdEvaluatorState(BaseThresholdDefConfigWrapper threshold,
                                    ThresholdingSession thresholdingSession, Class<T> stateType) {
        Objects.requireNonNull(threshold);
        Objects.requireNonNull(thresholdingSession);
        Objects.requireNonNull(thresholdingSession.getBlobStore());

        this.thresholdingSession = thresholdingSession;
        kvStore = getKvStoreForType(stateType, thresholdingSession.getBlobStore());
        key = String.format("%d-%s-%s-%s-%s-%s", thresholdingSession.getKey().getNodeId(),
                thresholdingSession.getKey().getLocation(), threshold.getDsType(),
                threshold.getDatasourceExpression(), threshold.getType(),
                generateHashForThresholdValues(threshold));

        initializeState();
    }

    // Multiple threshold levels for trigger/rearm may end up with the same key.
    // Generate unique hashcode for threshold values.
    private String generateHashForThresholdValues(BaseThresholdDefConfigWrapper threshold) {
        Hasher hasher = Hashing.murmur3_128().newHasher();
        if (threshold.getTriggeredUEI().isPresent()) {
            hasher.putString(threshold.getTriggeredUEI().get(), StandardCharsets.UTF_8);
        }
        if (threshold.getRearmedUEI().isPresent()) {
            hasher.putString(threshold.getRearmedUEI().get(), StandardCharsets.UTF_8);
        }
        if (threshold.getValue() != null) {
            hasher.putDouble(threshold.getValue());
        } else if (!Strings.isNullOrEmpty(threshold.getValueString())) {
            hasher.putString(threshold.getValueString(), StandardCharsets.UTF_8);
        }
        if (threshold.getRearm() != null) {
            hasher.putDouble(threshold.getRearm());
        } else if (!Strings.isNullOrEmpty(threshold.getRearmString())) {
            hasher.putString(threshold.getRearmString(), StandardCharsets.UTF_8);
        }
        if (threshold.getTrigger() != null) {
            hasher.putInt(threshold.getTrigger());
        } else if (!Strings.isNullOrEmpty(threshold.getTriggerString())) {
            hasher.putString(threshold.getTriggerString(), StandardCharsets.UTF_8);
        }
        return hasher.hash().toString();
    }

    /**
     * This method serves to ensure that we only instantiate a single serdes wrapper for a given state type rather than
     * creating a separate serdes wrapper for every evaluator. The serdes wrappers will be stored in a map keyed by the
     * type they deal with.
     */
    @SuppressWarnings("unchecked") // The cast is guaranteed to work based on how we are keying the map by the type
    private static <U extends AbstractThresholdEvaluatorState.AbstractState> SerializingBlobStore<U> getKvStoreForType(Class<U> stateType, BlobStore blobStore) {
        return (SerializingBlobStore<U>) serdesMap.computeIfAbsent(stateType,
                c -> SerializingBlobStore.ofType(blobStore, fst::asByteArray, bytes -> c.cast(fst.asObject(bytes))));
    }

    protected abstract void initializeState();

    private boolean shouldPersist() {
        return isStateDirty;
    }

    private void persistStateIfNeeded() {
        if (shouldPersist()) {
            try {
                long newTimestamp = kvStore.put(key, state, THRESHOLDING_KV_CONTEXT, stateTTL);
                lastUpdatedCache.put(key, newTimestamp);

                // If we successfully stored the state we will mark that the persisted state is up to date and no longer
                // dirty
                isStateDirty = false;
            } catch (RuntimeException e) {
                RATE_LIMITED_LOGGER.warn("Failed to store state for threshold {}", key, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchState() {
        thresholdingSession.getThresholdStateMonitor().withReadLock(() -> {
            // Fetch the state to make sure we have the latest if we are thresholding in a distributed environment or if
            // this is the first time we are evaluating this evaluator
            //
            // If both of those conditions are false, then we must be on a standalone instance of OpenNMS and have the
            // state already in memory so there is no need to fetch it
            if (!isDistributed() && !firstEvaluation) {
                return;
            }

            try {
                Long lastKnownUpdate = lastUpdatedCache.get(key);

                // If we don't have a record of when this was last updated locally, get it from the store
                // Otherwise if we are evaluating for the first time we need to fetch regardless since we have no state
                if (lastKnownUpdate == null || firstEvaluation) {
                    kvStore.get(key, THRESHOLDING_KV_CONTEXT).ifPresent(v -> state = v);
                } else {
                    // Otherwise get it from the store only if our record is stale
                    kvStore.getIfStale(key, THRESHOLDING_KV_CONTEXT, lastKnownUpdate)
                            .ifPresent(o -> o.ifPresent(v -> state = v));
                }
            } catch (RuntimeException e) {
                RATE_LIMITED_LOGGER.warn("Failed to retrieve state for threshold {}", key, e);
            }
        });
    }

    /**
     * Marks the state for this evaluator as dirty. When a state is dirty it will be persisted the next time
     * {@link #persistStateIfNeeded()} is called.
     * <p>
     * Any operation that alters the state enough that it would affect threshold evaluation should call this method to
     * mark the state dirty.
     */
    protected void markDirty() {
        isStateDirty = true;
    }

    @Override
    public Status evaluate(double dsValue, Long sequenceNumber) {
       return evaluate(dsValue, null, sequenceNumber);
    }

    @Override
    public synchronized Status evaluate(double dsValue, ThresholdValues thresholdValues, Long sequenceNumber) {
        if (sequenceNumber != null) {
            // If a sequence number was provided, only fetch the state if this is the first sequence number we have seen
            // or if this was not the next sequence number (indicating someone else processed the last one)
            if (this.sequenceNumber == null || sequenceNumber != this.sequenceNumber + 1) {
                fetchState();
            }
            this.sequenceNumber = sequenceNumber;
        } else {
            // Always fetch the state to make sure we have the latest if we don't know the sequence number
            fetchState();
        }

        Status status = evaluateAfterFetch(dsValue, thresholdValues);
        if (firstEvaluation) {
            firstEvaluation = false;
            // We don't bother advertising ourselves until the first time we perform an evaluation since we will have
            // default values until that point (being reinitialized would have no effect)
            thresholdingSession.getThresholdStateMonitor().trackState(key, this);
        }
        // Persist the state if it has changed and is now dirty
        persistStateIfNeeded();
        firstEvaluation = false;
        return status;
    }

    @Override
    public ValueStatus evaluate(ExpressionThresholdValueSupplier valueSupplier, Long sequenceNumber)
            throws ThresholdExpressionException {
        ExpressionConfigWrapper.ExpressionThresholdValues expressionThresholdValues = getValueForExpressionThreshold(valueSupplier);
        Status status = evaluate(expressionThresholdValues.value, expressionThresholdValues.getThresholdValues(), sequenceNumber);

        return new ValueStatus(expressionThresholdValues.value, status, expressionThresholdValues.getThresholdValues());
    }

    @Override
    public ValueStatus evaluate(ThresholdValuesSupplier thresholdValuesSupplier, Long sequenceNumber)
            throws ThresholdExpressionException {
        ThresholdValues thresholdValues = getThresholdValues(thresholdValuesSupplier);
        Status status = evaluate(thresholdValues.getDsValue(), thresholdValues, sequenceNumber);
        return new ValueStatus(thresholdValues.getDsValue(), status, thresholdValues);
    }

    private ThresholdValues getThresholdValues(ThresholdValuesSupplier thresholdValuesSupplier) {

        if (!state.isCached()) {
            ThresholdValues thresholdValues = thresholdValuesSupplier.get();
            state.setThresholdValues(thresholdValues);
            state.setCached(true);
            return thresholdValues;
        } else {
            Double dsvalue = thresholdValuesSupplier.getDsValue();
            ThresholdValues thresholdValues = state.getThresholdValues();
            thresholdValues.setDsValue(dsvalue);
            return thresholdValues;
        }
    }

    private ExpressionConfigWrapper.ExpressionThresholdValues getValueForExpressionThreshold(ExpressionThresholdValueSupplier valueSupplier)
            throws ThresholdExpressionException {
        if (!state.isCached()) {
            LOG.debug("Interpolating the expression for state {} for the first time", state);
            ExpressionConfigWrapper.ExpressionThresholdValues expressionThresholdValues = valueSupplier.get();
            state.setInterpolatedExpression(expressionThresholdValues.expression);
            state.setThresholdValues(expressionThresholdValues.getThresholdValues());
            state.setCached(true);
            return expressionThresholdValues;
        } else {
            String interpolatedExpression = state.getInterpolatedExpression().get();
            LOG.debug("Using already cached expression {}", interpolatedExpression);
            ExpressionConfigWrapper.ExpressionThresholdValues expressionThresholdValues =
                    new ExpressionConfigWrapper.ExpressionThresholdValues(interpolatedExpression, valueSupplier.get(interpolatedExpression));
            expressionThresholdValues.setThresholdValues(state.getThresholdValues());
            return expressionThresholdValues;
        }
    }

    @Override
    public void clearState() {
        clearStateBeforePersist();
        persistStateIfNeeded();
    }

    @Override
    public synchronized void reinitialize() {
        firstEvaluation = true;
        clearStateBeforePersist();
    }

    protected abstract void clearStateBeforePersist();

    protected abstract Status evaluateAfterFetch(double dsValue, ThresholdValues thresholdValues);

    /**
     * <p>createBasicEvent</p>
     *
     * @param uei a {@link java.lang.String} object.
     * @param date a {@link java.util.Date} object.
     * @param dsValue a double.
     * @param resource a {@link org.opennms.netmgt.threshd.CollectionResourceWrapper} object.
     * @param additionalParams a {@link java.util.Map} object.
     * @return a {@link org.opennms.netmgt.xml.event.Event} object.
     */
    protected Event createBasicEvent(String uei, Date date, double dsValue, CollectionResourceWrapper resource, Map<String,String> additionalParams) {
        if (resource == null) { // Still works, mimic old code when instance value is null.
            resource = new CollectionResourceWrapper(date, 0, null, null, null, null, null, null);
        }
        String dsLabelValue = resource.getFieldValue(resource.getDsLabel());
        if (dsLabelValue == null) dsLabelValue = UNKNOWN;

        String exprLabelValue = getThresholdConfig().getExprLabel().orElse(null);
        if (exprLabelValue == null) exprLabelValue = "";

        // create the event to be sent
        EventBuilder bldr = new EventBuilder(uei, "OpenNMS.Threshd." + getThresholdConfig().getDatasourceExpression(), date);

        bldr.setNodeid(resource.getNodeId());
        bldr.setService(resource.getServiceName());

        // As a suggestion from Bug2711. Host Address will contain Interface IP Address for Interface Resource
        bldr.setInterface(addr(resource.getHostAddress()));            

        if (resource.isAnInterfaceResource() || resource.isLatencyResource()) {
            // Update threshold label if it is unknown. This is useful because usually reduction-key is associated to label parameter
            if (UNKNOWN.equals(dsLabelValue))
                dsLabelValue = resource.getIfLabel();
            // Set interface specific parameters
            bldr.addParam("ifLabel", resource.getIfLabel());
            // will be null for telemetry resources
            if (resource.getIfIndex() != null) {
                bldr.addParam("ifIndex", resource.getIfIndex());
            }
            String ipaddr = resource.getIfInfoValue("ipaddr");
            if (ipaddr != null && !"0.0.0.0".equals(ipaddr)) {
                bldr.addParam("ifIpAddress", ipaddr);
            }
        }
        if (resource.isNodeResource() && UNKNOWN.equals(dsLabelValue)) {
            dsLabelValue = CollectionResource.RESOURCE_TYPE_NODE;
        }

        // Set resource label
        bldr.addParam("label", dsLabelValue);

        // Set resource label
        bldr.addParam("expressionLabel", exprLabelValue);

        // Set event host
        bldr.setHost(InetAddressUtils.getLocalHostName());

        // Add datasource name
        bldr.addParam("ds", getThresholdConfig().getDatasourceExpression());

        // Add threshold description using the cached expression if available
        final String descr = getThresholdConfig()
                .getBasethresholddef()
                .getDescription()
                .orElseGet(() -> state.getInterpolatedExpression()
                        .orElse(getThresholdConfig().getDatasourceExpression()));

        bldr.addParam("description", descr);

        // Add last known value of the datasource fetched from its RRD file
        bldr.addParam("value", formatValue(dsValue));

        String defaultInstance = resource.isNodeResource() ? CollectionResource.RESOURCE_TYPE_NODE : UNKNOWN;

        // Add the instance name of the resource in question
        bldr.addParam("instance", resource.getInstance() == null ? defaultInstance : resource.getInstance());

        // Add the instance label of the resource in question
        bldr.addParam("instanceLabel", resource.getInstanceLabel() == null ? defaultInstance : resource.getInstanceLabel());

        bldr.addParam("resourceType", resource.getResourceTypeName());

        // Add the resource ID required to call the Graph API.
        final ResourceId resourceId = resource.getResourceId();
        bldr.addParam("resourceId", resourceId != null ? resourceId.toString() : null);

        // Add additional parameters
        if (additionalParams != null) {
            for (String p : additionalParams.keySet()) {
                bldr.addParam(p, additionalParams.get(p));
            }
        }

        return bldr.getEvent();
    }

    /**
     * <p>formatValue</p>
     *
     * @param value a {@link java.lang.Double} object.
     * @return a {@link java.lang.String} object.
     */
    protected String formatValue(double value) {
        if (Double.isNaN(value)) // When reconfiguring thresholds, the value is set to NaN.
            return FORMATED_NAN;
        String pattern = System.getProperty("org.opennms.threshd.value.decimalformat", "###.##");
        DecimalFormat valueFormatter = new DecimalFormat(pattern);
        return valueFormatter.format(value);
    }

    @Override
    public ThresholdingSession getThresholdingSession() {
        return thresholdingSession;
    }

    private boolean isDistributed() {
        return thresholdingSession.isDistributed();
    }

    @Override
    public void setInstance(String instance) {
        Objects.requireNonNull(instance);

        if (this.instance != null) {
            throw new IllegalStateException("Cannot apply instance " + instance + " since this evaluator state " +
                    "already has instance " + this.instance);
        }
        
        if (!firstEvaluation) {
            throw new IllegalStateException("This state has already been evaluated so changing the instance to " +
                    instance + " won't have an effect");
        }

        this.instance = instance;
        key = String.format("%s-%s", key, instance);
    }
    
    @VisibleForTesting
    static void clearSerdesMap() {
        serdesMap.clear();
    }
}
