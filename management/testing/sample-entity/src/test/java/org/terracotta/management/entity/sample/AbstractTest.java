/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.management.entity.sample;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.After;
import org.junit.Before;
import org.terracotta.connection.Connection;
import org.terracotta.connection.ConnectionFactory;
import org.terracotta.connection.ConnectionPropertyNames;
import org.terracotta.management.entity.management.ManagementAgentConfig;
import org.terracotta.management.entity.management.client.ManagementAgentEntity;
import org.terracotta.management.entity.management.client.ManagementAgentEntityClientService;
import org.terracotta.management.entity.management.client.ManagementAgentEntityFactory;
import org.terracotta.management.entity.management.client.ManagementAgentService;
import org.terracotta.management.entity.management.server.ManagementAgentEntityServerService;
import org.terracotta.management.entity.sample.client.CacheEntityClientService;
import org.terracotta.management.entity.sample.client.CacheFactory;
import org.terracotta.management.entity.sample.server.CacheEntityServerService;
import org.terracotta.management.entity.tms.TmsAgent;
import org.terracotta.management.entity.tms.TmsAgentConfig;
import org.terracotta.management.entity.tms.client.TmsAgentEntityClientService;
import org.terracotta.management.entity.tms.client.TmsAgentEntityFactory;
import org.terracotta.management.entity.tms.server.TmsAgentEntityServerService;
import org.terracotta.management.model.call.ContextualReturn;
import org.terracotta.management.model.capabilities.context.CapabilityContext;
import org.terracotta.management.model.stats.ContextualStatistics;
import org.terracotta.management.registry.collect.StatisticConfiguration;
import org.terracotta.passthrough.PassthroughClusterControl;
import org.terracotta.passthrough.PassthroughServer;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author Mathieu Carbou
 */
public abstract class AbstractTest {

  private final ExecutorService managementMessageExecutor = Executors.newSingleThreadExecutor();
  private final ObjectMapper mapper = new ObjectMapper();
  private final PassthroughClusterControl stripeControl;

  private Connection managementConnection;

  protected final List<CacheFactory> webappNodes = new ArrayList<>();
  protected final Map<String, List<Cache>> caches = new HashMap<>();
  protected final SynchronousQueue<ContextualReturn<?>> managementCallReturns = new SynchronousQueue<>();
  protected TmsAgent tmsAgent;
  protected ManagementAgentService managementAgentService;

  AbstractTest() {
    mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    mapper.addMixIn(CapabilityContext.class, CapabilityContextMixin.class);

    PassthroughServer activeServer = new PassthroughServer();
    activeServer.setServerName("server1");

    activeServer.registerClientEntityService(new CacheEntityClientService());
    activeServer.registerServerEntityService(new CacheEntityServerService());

    activeServer.registerClientEntityService(new ManagementAgentEntityClientService());
    activeServer.registerServerEntityService(new ManagementAgentEntityServerService());

    activeServer.registerClientEntityService(new TmsAgentEntityClientService());
    activeServer.registerServerEntityService(new TmsAgentEntityServerService());

    stripeControl = new PassthroughClusterControl("stripe-1", activeServer);
  }

  @Before
  public void setUp() throws Exception {
    connectManagementClients();

    addWebappNode();
    addWebappNode();

    getCaches("pets");
    getCaches("clients");
  }

  @After
  public void tearDown() throws Exception {
    closeNodes();
    if (managementConnection != null) {
      managementConnection.close();
    }
    stripeControl.tearDown();
    managementMessageExecutor.shutdown();
  }

  protected JsonNode readJson(String file) {
    try {
      return mapper.readTree(new File(AbstractTest.class.getResource("/" + file).toURI()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected JsonNode toJson(Object o) {
    try {
      return mapper.readTree(mapper.writeValueAsString(o));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected int size(int nodeIdx, String cacheName) {
    return caches.get(cacheName).get(nodeIdx).size();
  }

  protected String get(int nodeIdx, String cacheName, String key) {
    return caches.get(cacheName).get(nodeIdx).get(key);
  }

  protected void put(int nodeIdx, String cacheName, String key, String value) {
    caches.get(cacheName).get(nodeIdx).put(key, value);
  }

  protected void remove(int nodeIdx, String cacheName, String key) {
    caches.get(cacheName).get(nodeIdx).remove(key);
  }

  protected void closeNodes() {
    webappNodes.forEach(CacheFactory::close);
  }

  protected void getCaches(String pets) {
    caches.put(pets, webappNodes.stream().map(cacheFactory -> cacheFactory.getCache(pets)).collect(Collectors.toList()));
  }

  protected void addWebappNode() throws Exception {
    CacheFactory cacheFactory = new CacheFactory("passthrough://stripe-1:9510/pet-clinic");
    cacheFactory.init();
    webappNodes.add(cacheFactory);
  }

  public static abstract class CapabilityContextMixin {
    @JsonIgnore
    public abstract Collection<String> getRequiredAttributeNames();

    @JsonIgnore
    public abstract Collection<CapabilityContext.Attribute> getRequiredAttributes();
  }

  private void connectManagementClients() throws Exception {
    // connects to server
    Properties properties = new Properties();
    properties.setProperty(ConnectionPropertyNames.CONNECTION_NAME, getClass().getSimpleName());
    properties.setProperty(ConnectionPropertyNames.CONNECTION_TIMEOUT, "10000");
    this.managementConnection = ConnectionFactory.connect(URI.create("passthrough://stripe-1:9510/"), properties);

    // create a tms entity
    TmsAgentEntityFactory tmsAgentEntityFactory = new TmsAgentEntityFactory(managementConnection, getClass().getSimpleName());
    this.tmsAgent = tmsAgentEntityFactory.retrieveOrCreate(new TmsAgentConfig()
        .setStatisticConfiguration(new StatisticConfiguration()
            .setAverageWindowDuration(1, TimeUnit.MINUTES)
            .setHistorySize(100)
            .setHistoryInterval(1, TimeUnit.SECONDS)
            .setTimeToDisable(5, TimeUnit.SECONDS)));

    // create a management entity
    ManagementAgentEntityFactory managementAgentEntityFactory = new ManagementAgentEntityFactory(managementConnection);
    ManagementAgentEntity managementAgentEntity = managementAgentEntityFactory.retrieveOrCreate(new ManagementAgentConfig());
    this.managementAgentService = new ManagementAgentService(managementAgentEntity);
    this.managementAgentService.setManagementMessageExecutor(managementMessageExecutor);
    this.managementAgentService.setContextualReturnListener((from, managementCallId, aReturn) -> managementCallReturns.offer(aReturn));
  }

  protected void queryAllRemoteStatsUntil(Predicate<List<? extends ContextualStatistics>> test) throws Exception {
    List<? extends ContextualStatistics> statistics;
    do {
      statistics = tmsAgent.readMessages()
          .get()
          .stream()
          .filter(message -> message.getType().equals("STATISTICS"))
          .flatMap(message -> message.unwrap(ContextualStatistics.class).stream())
          .collect(Collectors.toList());
      // PLEASE KEEP THIS ! Really useful when troubleshooting stats!
      /*if (!statistics.isEmpty()) {
        System.out.println("received at " + System.currentTimeMillis() + ":");
        statistics.stream()
            .flatMap(o -> o.getStatistics().entrySet().stream())
            .forEach(System.out::println);
      }*/
      Thread.sleep(500);
    } while (!Thread.currentThread().isInterrupted() && (statistics.isEmpty() || !test.test(statistics)));
    assertFalse(Thread.currentThread().isInterrupted());
    assertTrue(test.test(statistics));
  }

}
