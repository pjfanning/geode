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

package org.apache.geode.redis.internal.pubsub;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.internal.cache.execute.InternalFunction;
import org.apache.geode.logging.internal.executors.LoggingThreadFactory;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.redis.internal.RegionProvider;
import org.apache.geode.redis.internal.executor.GlobPattern;
import org.apache.geode.redis.internal.netty.Client;
import org.apache.geode.redis.internal.netty.Coder;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;
import org.apache.geode.redis.internal.services.StripedExecutorService;
import org.apache.geode.redis.internal.services.StripedRunnable;

/**
 * Concrete class that manages publish and subscribe functionality. Since Redis subscriptions
 * require a persistent connection we need to have a way to track the existing clients that are
 * expecting to receive published messages.
 */
public class PubSubImpl implements PubSub {
  public static final String REDIS_PUB_SUB_FUNCTION_ID = "redisPubSubFunctionID";

  private static final int MAX_PUBLISH_THREAD_COUNT =
      Integer.getInteger("redis.max-publish-thread-count", 10);

  private static final Logger logger = LogService.getLogger();

  private final Subscriptions subscriptions;
  private final ExecutorService executor;

  /**
   * Inner class to wrap the publish action and pass it to the {@link StripedExecutorService}.
   */
  private static class PublishingRunnable implements StripedRunnable {

    private final Runnable runnable;
    private final Client client;

    public PublishingRunnable(Runnable runnable, Client client) {
      this.runnable = runnable;
      this.client = client;
    }

    @Override
    public Object getStripe() {
      return client;
    }

    @Override
    public void run() {
      runnable.run();
    }
  }

  public PubSubImpl(Subscriptions subscriptions) {
    this.subscriptions = subscriptions;

    ThreadFactory threadFactory = new LoggingThreadFactory("GeodeRedisServer-Publish-", true);
    BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
    ExecutorService innerPublishExecutor = new ThreadPoolExecutor(1, MAX_PUBLISH_THREAD_COUNT,
        60, TimeUnit.SECONDS, workQueue, threadFactory);

    executor = new StripedExecutorService(innerPublishExecutor);

    registerPublishFunction();
  }

  public int getSubscriptionCount() {
    return subscriptions.size();
  }

  @Override
  public long publish(RegionProvider regionProvider, byte[] channel, byte[] message,
      Client client) {
    executor.submit(
        new PublishingRunnable(() -> internalPublish(regionProvider, channel, message), client));

    return subscriptions.findSubscriptions(channel).size();
  }

  private void internalPublish(RegionProvider regionProvider, byte[] channel, byte[] message) {
    Set<DistributedMember> membersWithDataRegion = regionProvider.getRegionMembers();
    try {
      @SuppressWarnings("unchecked")
      ResultCollector<String[], List<Long>> subscriberCountCollector = FunctionService
          .onMembers(membersWithDataRegion)
          .setArguments(new Object[] {channel, message})
          .execute(REDIS_PUB_SUB_FUNCTION_ID);

      subscriberCountCollector.getResult();
    } catch (Exception e) {
      logger.warn("Failed to execute publish function on channel {}",
          Coder.bytesToString(channel), e);
    }
  }

  @Override
  public SubscribeResult subscribe(byte[] channel, ExecutionHandlerContext context, Client client) {
    return subscriptions.subscribe(channel, context, client);
  }

  @Override
  public SubscribeResult psubscribe(byte[] pattern, ExecutionHandlerContext context,
      Client client) {
    return subscriptions.psubscribe(pattern, context, client);
  }

  private void registerPublishFunction() {
    FunctionService.registerFunction(new InternalFunction<Object[]>() {
      @Override
      public String getId() {
        return REDIS_PUB_SUB_FUNCTION_ID;
      }

      @Override
      public void execute(FunctionContext<Object[]> context) {
        Object[] publishMessage = context.getArguments();
        publishMessageToSubscribers((byte[]) publishMessage[0], (byte[]) publishMessage[1]);
        context.getResultSender().lastResult(true);
      }

      /**
       * Since the publish process uses an onMembers function call, we don't want to re-publish
       * to members if one fails.
       * TODO: Revisit this in the event that we instead use an onMember call against individual
       * members.
       */
      @Override
      public boolean isHA() {
        return false;
      }
    });
  }

  @Override
  public long unsubscribe(byte[] channel, Client client) {
    return subscriptions.unsubscribe(channel, client);
  }

  @Override
  public long punsubscribe(GlobPattern pattern, Client client) {
    return subscriptions.unsubscribe(pattern, client);
  }

  @Override
  public List<byte[]> findSubscriptionNames(Client client) {
    return subscriptions.findSubscriptions(client).stream()
        .map(Subscription::getSubscriptionName)
        .collect(Collectors.toList());
  }

  @Override
  public List<byte[]> findChannelNames() {
    return subscriptions.findChannelNames();
  }

  @Override
  public List<byte[]> findChannelNames(byte[] pattern) {
    return subscriptions.findChannelNames(pattern);
  }

  @Override
  public List<Object> findNumberOfSubscribersPerChannel(List<byte[]> names) {
    return subscriptions.findNumberOfSubscribersPerChannel(names);
  }

  @Override
  public List<byte[]> findSubscriptionNames(Client client, Subscription.Type type) {
    return subscriptions.findSubscriptions(client).stream()
        .filter(s -> s.getType() == (type))
        .map(Subscription::getSubscriptionName)
        .collect(Collectors.toList());
  }

  @Override
  public Long findNumberOfSubscribedPatterns() {
    return subscriptions.findNumberOfPatternSubscriptions();
  }

  @VisibleForTesting
  void publishMessageToSubscribers(byte[] channel, byte[] message) {
    List<Subscription> foundSubscriptions = subscriptions.findSubscriptions(channel);
    if (foundSubscriptions.isEmpty()) {
      return;
    }

    foundSubscriptions.forEach(
        subscription -> subscription.publishMessage(channel, message));
  }

}