package com.sproutsocial.nsq;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.sproutsocial.nsq.jmx.SubscriberMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

public class Subscriber extends BasePubSub implements SubscriberMXBean {

    private final List<HostAndPort> lookups = Lists.newArrayList();
    private final List<Subscription> subscriptions = Lists.newArrayList();
    private final int lookupIntervalSecs;
    private int maxInFlightPerSubscription = 200;
    private int maxFlushDelayMillis = 2000;
    private int maxAttempts = 5;
    private FailedMessageHandler failedMessageHandler = null;
    private AtomicInteger failedMessageCount = new AtomicInteger();
    private AtomicInteger handlerErrorCount = new AtomicInteger();

    private static final Logger logger = LoggerFactory.getLogger(Subscriber.class);

    protected Subscriber(int lookupIntervalSecs) {
        this.lookupIntervalSecs = lookupIntervalSecs;
        scheduleAtFixedRate(new Runnable() {
            public void run() {
                lookup();
            }
        }, lookupIntervalSecs * 1000, lookupIntervalSecs * 1000, true);
    }

    public Subscriber(int lookupIntervalSecs, String... lookupHosts) {
        this(lookupIntervalSecs);
        for (String h : lookupHosts) {
            lookups.add(HostAndPort.fromString(h).withDefaultPort(4161));
        }
    }

    public synchronized void subscribe(String topic, String channel, MessageHandler handler) {
        Client.addSubscriber(this);
        Subscription sub = new Subscription(topic, channel, handler, this);
        subscriptions.add(sub);
        sub.checkConnections(lookupTopic(topic));
    }

    private synchronized void lookup() {
        if (isStopping) {
            return;
        }
        for (Subscription sub : subscriptions) {
            sub.checkConnections(lookupTopic(sub.getTopic()));
        }
    }

    protected Set<HostAndPort> lookupTopic(String topic) {
        Set<HostAndPort> nsqds = new HashSet<HostAndPort>();
        for (HostAndPort lookup : lookups) {
            try {
                URL url = new URL(String.format("http://%s/lookup?topic=%s", lookup, topic));
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                if (con.getResponseCode() != 200) {
                    logger.debug("ignoring lookup resp:{} nsqlookupd:{} topic:{}", con.getResponseCode(), lookup, topic);
                    continue;
                }
                JsonNode root = Client.mapper.readTree(con.getInputStream()); //don't need another buffer here, jackson buffers
                JsonNode producers = root.get("data").get("producers");
                for (int i = 0; i < producers.size(); i++) {
                    JsonNode prod = producers.get(i);
                    nsqds.add(HostAndPort.fromParts(prod.get("broadcast_address").asText(), prod.get("tcp_port").asInt()));
                }
                con.getInputStream().close();
            }
            catch (Exception e) {
                logger.error("lookup error, ignoring nsqlookupd:{} topic:{}", lookup, topic, e);
            }
        }
        //logger.debug("lookup topic:{} result:{}", topic, nsqds);
        return nsqds;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        for (Subscription subscription : subscriptions) {
            subscription.stop();
        }
        logger.info("subscriber stopped");
    }

    @Override
    public synchronized int getMaxInFlightPerSubscription() {
        return maxInFlightPerSubscription;
    }

    @Override
    public synchronized void setMaxInFlightPerSubscription(int maxInFlightPerSubscription) {
        this.maxInFlightPerSubscription = maxInFlightPerSubscription;
        for (Subscription subscription : subscriptions) {
            subscription.distributeMaxInFlight();
        }
    }

    @Override
    public synchronized int getMaxFlushDelayMillis() {
        return maxFlushDelayMillis;
    }

    @Override
    public synchronized void setMaxFlushDelayMillis(int maxFlushDelayMillis) {
        this.maxFlushDelayMillis = maxFlushDelayMillis;
    }

    @Override
    public synchronized int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public synchronized void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public synchronized FailedMessageHandler getFailedMessageHandler() {
        return failedMessageHandler;
    }

    public synchronized void setFailedMessageHandler(FailedMessageHandler failedMessageHandler) {
        this.failedMessageHandler = failedMessageHandler;
    }

    @Override
    public synchronized int getLookupIntervalSecs() {
        return lookupIntervalSecs;
    }

    @Override
    public synchronized List<String> getLookups() {
        return Util.sortedStrings(lookups);
    }

    @Override
    public synchronized List<String> getSubscriptions() {
        return Util.sortedStrings(subscriptions);
    }

    public Integer getExecutorQueueSize() {
        ExecutorService executor = Client.getExecutor();
        return executor instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor)executor).getQueue().size() : null;
    }

    @Override
    public int getFailedMessageCount() {
        return failedMessageCount.get();
    }

    @Override
    public int getHandlerErrorCount() {
        return handlerErrorCount.get();
    }

    public void messageFailed() {
        failedMessageCount.incrementAndGet();
    }

    public void handlerError() {
        handlerErrorCount.incrementAndGet();
    }

    public synchronized int getConnectionCount() {
        int count = 0;
        for (Subscription subscription : subscriptions) {
            count += subscription.getConnectionCount();
        }
        return count;
    }

}