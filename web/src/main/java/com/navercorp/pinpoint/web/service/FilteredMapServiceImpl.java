/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.web.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navercorp.pinpoint.common.bo.SpanBo;
import com.navercorp.pinpoint.common.bo.SpanEventBo;
import com.navercorp.pinpoint.common.service.ServiceTypeRegistryService;
import com.navercorp.pinpoint.common.trace.HistogramSchema;
import com.navercorp.pinpoint.common.trace.HistogramSlot;
import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.web.applicationmap.ApplicationMap;
import com.navercorp.pinpoint.web.applicationmap.ApplicationMapBuilder;
import com.navercorp.pinpoint.web.applicationmap.link.MatcherGroup;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkDataDuplexMap;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkDataMap;
import com.navercorp.pinpoint.web.dao.*;
import com.navercorp.pinpoint.web.filter.Filter;
import com.navercorp.pinpoint.web.util.TimeWindow;
import com.navercorp.pinpoint.web.util.TimeWindowDownSampler;
import com.navercorp.pinpoint.web.vo.*;
import com.navercorp.pinpoint.web.vo.scatter.ApplicationScatterScanResult;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

/**
 * @author netspider
 * @author emeroad
 * @author minwoo.jung
 */
@Service
public class FilteredMapServiceImpl implements FilteredMapService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private TraceDao traceDao;

    @Autowired
    private ApplicationTraceIndexDao applicationTraceIndexDao;

    @Autowired
    private AgentInfoService agentInfoService;
    
    @Autowired(required=false)
    private MatcherGroup matcherGroup;

    @Autowired
    private ServiceTypeRegistryService registry;

    private static final Object V = new Object();

    @Override
    public LimitedScanResult<List<TransactionId>> selectTraceIdsFromApplicationTraceIndex(String applicationName, Range range, int limit) {
        if (applicationName == null) {
            throw new NullPointerException("applicationName must not be null");
        }
        if (range == null) {
            throw new NullPointerException("range must not be null");
        }
        if (logger.isTraceEnabled()) {
            logger.trace("scan(selectTraceIdsFromApplicationTraceIndex) {}, {}", applicationName, range);
        }

        return this.applicationTraceIndexDao.scanTraceIndex(applicationName, range, limit);
    }
    
    @Override
    public LimitedScanResult<List<TransactionId>> selectTraceIdsFromApplicationTraceIndex(String applicationName, SelectedScatterArea area, int limit) {
        if (applicationName == null) {
            throw new NullPointerException("applicationName must not be null");
        }
        if (area == null) {
            throw new NullPointerException("area must not be null");
        }
        if (logger.isTraceEnabled()) {
            logger.trace("scan(selectTraceIdsFromApplicationTraceIndex) {}, {}", applicationName, area);
        }

        return this.applicationTraceIndexDao.scanTraceIndex(applicationName, area, limit);
    }

    @Override
    @Deprecated
    public LoadFactor linkStatistics(Range range, List<TransactionId> traceIdSet, Application sourceApplication, Application destinationApplication, Filter filter) {
        if (sourceApplication == null) {
            throw new NullPointerException("sourceApplication must not be null");
        }
        if (destinationApplication == null) {
            throw new NullPointerException("destApplicationName must not be null");
        }
        if (filter == null) {
            throw new NullPointerException("filter must not be null");
        }

        StopWatch watch = new StopWatch();
        watch.start();

        List<List<SpanBo>> originalList = this.traceDao.selectAllSpans(traceIdSet);
        List<SpanBo> filteredTransactionList = filterList(originalList, filter);

        LoadFactor statistics = new LoadFactor(range);

        // TODO need to handle these separately by node type (like fromToFilter)

        // scan transaction list
        for (SpanBo span : filteredTransactionList) {
            if (sourceApplication.equals(span.getApplicationId(), registry.findServiceType(span.getApplicationServiceType()))) {
                List<SpanEventBo> spanEventBoList = span.getSpanEventBoList();
                if (spanEventBoList == null) {
                    continue;
                }

                // find dest elapsed time
                for (SpanEventBo spanEventBo : spanEventBoList) {
                    if (destinationApplication.equals(spanEventBo.getDestinationId(), registry.findServiceType(spanEventBo.getServiceType()))) {
                        // find exception
                        boolean hasException = spanEventBo.hasException();
                        // add sample
                        // TODO : need timeslot value instead of the actual value
                        statistics.addSample(span.getStartTime() + spanEventBo.getStartElapsed(), spanEventBo.getEndElapsed(), 1, hasException);
                        break;
                    }
                }
            }
        }

        watch.stop();
        logger.info("Fetch link statistics elapsed. {}ms", watch.getLastTaskTimeMillis());

        return statistics;
    }

    private List<SpanBo> filterList(List<List<SpanBo>> transactionList, Filter filter) {
        final List<SpanBo> filteredResult = new ArrayList<SpanBo>();
        for (List<SpanBo> transaction : transactionList) {
            if (filter.include(transaction)) {
                filteredResult.addAll(transaction);
            }
        }
        return filteredResult;
    }

    private List<List<SpanBo>> filterList2(List<List<SpanBo>> transactionList, Filter filter) {
        final List<List<SpanBo>> filteredResult = new ArrayList<List<SpanBo>>();
        for (List<SpanBo> transaction : transactionList) {
            if (filter.include(transaction)) {
                filteredResult.add(transaction);
            }
        }
        return filteredResult;
    }

    @Override
    public ApplicationMap selectApplicationMap(TransactionId transactionId) {
        if (transactionId == null) {
            throw new NullPointerException("transactionId must not be null");
        }
        List<TransactionId> transactionIdList = new ArrayList<TransactionId>();
        transactionIdList.add(transactionId);
        // FIXME from,to -1
        Range range = new Range(-1, -1);
        return selectApplicationMap(transactionIdList, range, range, Filter.NONE);
    }

    /**
     * filtered application map
     */
    @Override
    public ApplicationMap selectApplicationMap(List<TransactionId> transactionIdList, Range originalRange, Range scanRange, Filter filter) {
        if (transactionIdList == null) {
            throw new NullPointerException("transactionIdList must not be null");
        }
        if (filter == null) {
            throw new NullPointerException("filter must not be null");
        }

        StopWatch watch = new StopWatch();
        watch.start();

        final List<List<SpanBo>> filterList = selectFilteredSpan(transactionIdList, filter);

        ApplicationMap map = createMap(originalRange, scanRange, filterList);

        watch.stop();
        logger.debug("Select filtered application map elapsed. {}ms", watch.getTotalTimeMillis());

        return map;
    }

    private List<List<SpanBo>> selectFilteredSpan(List<TransactionId> transactionIdList, Filter filter) {
        // filters out recursive calls by looking at each objects
        // do not filter here if we change to a tree-based collision check in the future. 
        final Collection<TransactionId> recursiveFilterList = recursiveCallFilter(transactionIdList);

        // FIXME might be better to simply traverse the List<Span> and create a process chain for execution
        final List<List<SpanBo>> originalList = this.traceDao.selectAllSpans(recursiveFilterList);

        return filterList2(originalList, filter);
    }

    private ApplicationMap createMap(Range range, Range scanRange, List<List<SpanBo>> filterList) {

        // TODO inject TimeWindow from elsewhere 
        final TimeWindow window = new TimeWindow(range, TimeWindowDownSampler.SAMPLER);


        final LinkDataDuplexMap linkDataDuplexMap = new LinkDataDuplexMap();

        final DotExtractor dotExtractor = new DotExtractor(scanRange, registry);
        final ResponseHistogramBuilder mapHistogramSummary = new ResponseHistogramBuilder(range);
        /**
         * Convert to statistical data
         */
        for (List<SpanBo> transaction : filterList) {
            final Map<Long, SpanBo> transactionSpanMap = checkDuplicatedSpanId(transaction);

            for (SpanBo span : transaction) {
                final Application parentApplication = createParentApplication(span, transactionSpanMap);
                final Application spanApplication = new Application(span.getApplicationId(), registry.findServiceType(span.getApplicationServiceType()));

                // records the Span's response time statistics
                recordSpanResponseTime(spanApplication, span, mapHistogramSummary, span.getCollectorAcceptTime());

                if (!spanApplication.getServiceType().isRecordStatistics() || spanApplication.getServiceType().isRpcClient()) {
                    // span's serviceType is probably not set correctly
                    logger.warn("invalid span application:{}", spanApplication);
                    continue;
                }

                final short slotTime = getHistogramSlotTime(span, spanApplication.getServiceType());
                // might need to reconsider using collector's accept time for link statistics.
                // we need to convert to time window's timestamp. If not, it may lead to OOM due to mismatch in timeslots. 
                long timestamp = window.refineTimestamp(span.getCollectorAcceptTime());

                if (parentApplication.getServiceType() == ServiceType.USER) {
                    // Outbound data
                    if (logger.isTraceEnabled()) {
                        logger.trace("span user:{} {} -> span:{} {}", parentApplication, span.getAgentId(), spanApplication, span.getAgentId());
                    }
                    final LinkDataMap sourceLinkData = linkDataDuplexMap.getSourceLinkDataMap();
                    sourceLinkData.addLinkData(parentApplication, span.getAgentId(), spanApplication,  span.getAgentId(), timestamp, slotTime, 1);

                    if (logger.isTraceEnabled()) {
                        logger.trace("span target user:{} {} -> span:{} {}", parentApplication, span.getAgentId(), spanApplication, span.getAgentId());
                    }
                    // Inbound data
                    final LinkDataMap targetLinkDataMap = linkDataDuplexMap.getTargetLinkDataMap();
                    targetLinkDataMap.addLinkData(parentApplication, span.getAgentId(), spanApplication, span.getAgentId(), timestamp, slotTime, 1);
                } else {
                    // Inbound data
                    if (logger.isTraceEnabled()) {
                        logger.trace("span target parent:{} {} -> span:{} {}", parentApplication, span.getAgentId(), spanApplication, span.getAgentId());
                    }
                    final LinkDataMap targetLinkDataMap = linkDataDuplexMap.getTargetLinkDataMap();
                    targetLinkDataMap.addLinkData(parentApplication, span.getAgentId(), spanApplication, span.getAgentId(), timestamp, slotTime, 1);
                }


                addNodeFromSpanEvent(span, window, linkDataDuplexMap, transactionSpanMap);
                dotExtractor.addDot(span);
            }
        }
        List<ApplicationScatterScanResult> applicationScatterScanResult = dotExtractor.getApplicationScatterScanResult();

        ApplicationMapBuilder applicationMapBuilder = new ApplicationMapBuilder(range, matcherGroup);
        mapHistogramSummary.build();
        ApplicationMap map = applicationMapBuilder.build(linkDataDuplexMap, agentInfoService, mapHistogramSummary);

        map.setApplicationScatterScanResult(applicationScatterScanResult);

        return map;
    }

    private Map<Long, SpanBo> checkDuplicatedSpanId(List<SpanBo> transaction) {
        final Map<Long, SpanBo> transactionSpanMap = new HashMap<Long, SpanBo>();
        for (SpanBo span : transaction) {
            final SpanBo old = transactionSpanMap.put(span.getSpanId(), span);
            if (old != null) {
                logger.warn("duplicated span found:{}", old);
            }
        }
        return transactionSpanMap;
    }

    private void recordSpanResponseTime(Application application, SpanBo span, ResponseHistogramBuilder responseHistogramBuilder, long timeStamp) {
        responseHistogramBuilder.addHistogram(application, span, timeStamp);
    }


    private void addNodeFromSpanEvent(SpanBo span, TimeWindow window, LinkDataDuplexMap linkDataDuplexMap, Map<Long, SpanBo> transactionSpanMap) {
        /**
         * add span event statistics
         */
        final List<SpanEventBo> spanEventBoList = span.getSpanEventBoList();
        if (CollectionUtils.isEmpty(spanEventBoList)) {
            return;
        }
        final Application srcApplication = new Application(span.getApplicationId(), registry.findServiceType(span.getApplicationServiceType()));

        LinkDataMap sourceLinkDataMap = linkDataDuplexMap.getSourceLinkDataMap();
        for (SpanEventBo spanEvent : spanEventBoList) {

            ServiceType destServiceType = registry.findServiceType(spanEvent.getServiceType());
            if (!destServiceType.isRecordStatistics()) {
                // internal method
                continue;
            }
            // convert to Unknown if destServiceType is a rpc client and there is no acceptor.
            // acceptor exists if there is a span with spanId identical to the current spanEvent's next spanId.
            // logic for checking acceptor
            if (destServiceType.isRpcClient()) {
                if (!transactionSpanMap.containsKey(spanEvent.getNextSpanId())) {
                    destServiceType = ServiceType.UNKNOWN;
                }
            }

            final String dest = spanEvent.getDestinationId();
            final Application destApplication = new Application(dest, destServiceType);

            final short slotTime = getHistogramSlotTime(spanEvent, destServiceType);

            // FIXME
            final long spanEventTimeStamp = window.refineTimestamp(span.getStartTime() + spanEvent.getStartElapsed());
            if (logger.isTraceEnabled()) {
                logger.trace("spanEvent  src:{} {} -> dest:{} {}", srcApplication, span.getAgentId(), destApplication, spanEvent.getEndPoint());
            }
            // endPoint may be null
            final String destinationAgentId = StringUtils.defaultString(spanEvent.getEndPoint());
            sourceLinkDataMap.addLinkData(srcApplication, span.getAgentId(), destApplication, destinationAgentId, spanEventTimeStamp, slotTime, 1);
        }
    }

    private Application createParentApplication(SpanBo span, Map<Long, SpanBo> transactionSpanMap) {
        final SpanBo parentSpan = transactionSpanMap.get(span.getParentSpanId());
        if (span.isRoot() || parentSpan == null) {
            String applicationName = span.getApplicationId();
            ServiceType serviceType = ServiceType.USER;
            return new Application(applicationName, serviceType);
        } else {
            String parentApplicationName = parentSpan.getApplicationId();

            ServiceType serviceType = registry.findServiceType(parentSpan.getApplicationServiceType());
            return new Application(parentApplicationName, serviceType);
        }
    }

    private short getHistogramSlotTime(SpanEventBo spanEvent, ServiceType serviceType) {
        return getHistogramSlotTime(spanEvent.hasException(), spanEvent.getEndElapsed(), serviceType);
    }

    private short getHistogramSlotTime(SpanBo span, ServiceType serviceType) {
        boolean allException = span.getErrCode() != 0;
        return getHistogramSlotTime(allException, span.getElapsed(), serviceType);
    }

    private short getHistogramSlotTime(boolean hasException, int elapsedTime, ServiceType serviceType) {
        if (hasException) {
            return serviceType.getHistogramSchema().getErrorSlot().getSlotTime();
        } else {
            final HistogramSchema schema = serviceType.getHistogramSchema();
            final HistogramSlot histogramSlot = schema.findHistogramSlot(elapsedTime);
            return histogramSlot.getSlotTime();
        }
    }

    private Collection<TransactionId> recursiveCallFilter(List<TransactionId> transactionIdList) {
        if (transactionIdList == null) {
            throw new NullPointerException("transactionIdList must not be null");
        }

        List<TransactionId> crashKey = new ArrayList<TransactionId>();
        Map<TransactionId, Object> filterMap = new LinkedHashMap<TransactionId, Object>(transactionIdList.size());
        for (TransactionId transactionId : transactionIdList) {
            Object old = filterMap.put(transactionId, V);
            if (old != null) {
                crashKey.add(transactionId);
            }
        }
        if (crashKey.size() != 0) {
            Set<TransactionId> filteredTransactionId = filterMap.keySet();
            logger.info("transactionId crash found. original:{} filter:{} crashKey:{}", transactionIdList.size(), filteredTransactionId.size(), crashKey);
            return filteredTransactionId;
        }
        return transactionIdList;
    }


}
