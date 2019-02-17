// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.multisite;

import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventFamily;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Configuration {
  private static final Logger log = LoggerFactory.getLogger(Configuration.class);

  static final String INSTANCE_ID_FILE = "instanceId.data";

  // common parameters to cache and index sections
  static final String THREAD_POOL_SIZE_KEY = "threadPoolSize";

  static final int DEFAULT_INDEX_MAX_TRIES = 2;
  static final int DEFAULT_INDEX_RETRY_INTERVAL = 30000;
  private static final int DEFAULT_POLLING_INTERVAL_MS = 1000;
  static final int DEFAULT_THREAD_POOL_SIZE = 4;
  static final String NUM_STRIPED_LOCKS = "numStripedLocks";
  static final int DEFAULT_NUM_STRIPED_LOCKS = 10;
  static final String ENABLE_KEY = "enabled";
  static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
  static final boolean DEFAULT_ENABLE_PROCESSING = true;
  static final String KAFKA_SECTION = "kafka";
  public static final String KAFKA_PROPERTY_PREFIX = "KafkaProp-";

  private final AutoReindex autoReindex;
  private final KafkaPublisher publisher;
  private final Cache cache;
  private final Event event;
  private final Index index;
  private final KafkaSubscriber subscriber;
  private final Kafka kafka;
  private HealthCheck healthCheck;

  public enum PeerInfoStrategy {
    STATIC
  }

  @Inject
  Configuration(PluginConfigFactory pluginConfigFactory, @PluginName String pluginName) {
    Config cfg = pluginConfigFactory.getGlobalPluginConfig(pluginName);
    autoReindex = new AutoReindex(cfg);
    kafka = new Kafka(cfg);
    publisher = new KafkaPublisher(cfg);
    subscriber = new KafkaSubscriber(cfg);
    cache = new Cache(cfg);
    event = new Event(cfg);
    index = new Index(cfg);
    healthCheck = new HealthCheck(cfg);
  }

  public Kafka getKafka() {
    return kafka;
  }

  public KafkaPublisher kafkaPublisher() {
    return publisher;
  }

  public AutoReindex autoReindex() {
    return autoReindex;
  }

  public Cache cache() {
    return cache;
  }

  public Event event() {
    return event;
  }

  public Index index() {
    return index;
  }

  public HealthCheck healthCheck() {
    return healthCheck;
  }

  public KafkaSubscriber kafkaSubscriber() {
    return subscriber;
  }

  private static int getInt(Config cfg, String section, String name, int defaultValue) {
    try {
      return cfg.getInt(section, name, defaultValue);
    } catch (IllegalArgumentException e) {
      log.error("invalid value for {}; using default value {}", name, defaultValue);
      log.debug("Failed to retrieve integer value: {}", e.getMessage(), e);
      return defaultValue;
    }
  }

  private static String getString(
      Config cfg, String section, String subsection, String name, String defaultValue) {
    String value = cfg.getString(section, subsection, name);
    if (!Strings.isNullOrEmpty(value)) {
      return value;
    }
    return defaultValue;
  }

  public static class AutoReindex {
    static final String AUTO_REINDEX_SECTION = "autoReindex";
    static final String DELAY = "delay";
    static final String POLL_INTERVAL = "pollInterval";

    private final boolean enabled;
    private final long delaySec;
    private final long pollSec;

    public AutoReindex(Config cfg) {
      this.enabled = cfg.getBoolean(AUTO_REINDEX_SECTION, ENABLE_KEY, false);
      this.delaySec =
          ConfigUtil.getTimeUnit(cfg, AUTO_REINDEX_SECTION, null, DELAY, 10L, TimeUnit.SECONDS);
      this.pollSec =
          ConfigUtil.getTimeUnit(
              cfg, AUTO_REINDEX_SECTION, null, POLL_INTERVAL, 0L, TimeUnit.SECONDS);
    }

    public boolean enabled() {
      return enabled;
    }

    public long delaySec() {
      return delaySec;
    }

    public long pollSec() {
      return pollSec;
    }
  }

  private static Map<EventFamily, Boolean> eventsEnabled(Config config, String subsection) {
    Map<EventFamily, Boolean> eventsEnabled = new HashMap<>();
    for (EventFamily eventFamily : EventFamily.values()) {
      String enabledConfigKey = eventFamily.lowerCamelName() + "Enabled";

      eventsEnabled.put(
          eventFamily,
          config.getBoolean(
              KAFKA_SECTION, subsection, enabledConfigKey, DEFAULT_ENABLE_PROCESSING));
    }
    return eventsEnabled;
  }

  private static void applyKafkaConfig(Config config, String subsectionName, Properties target) {
    for (String section : config.getSubsections(KAFKA_SECTION)) {
      if (section.equals(subsectionName)) {
        for (String name : config.getNames(KAFKA_SECTION, section, true)) {
          if (name.startsWith(KAFKA_PROPERTY_PREFIX)) {
            Object value = config.getString(KAFKA_SECTION, subsectionName, name);
            String configProperty = name.replaceFirst(KAFKA_PROPERTY_PREFIX, "");
            String propName =
                CaseFormat.LOWER_CAMEL
                    .to(CaseFormat.LOWER_HYPHEN, configProperty)
                    .replaceAll("-", ".");
            log.info("[{}] Setting kafka property: {} = {}", subsectionName, propName, value);
            target.put(propName, value);
          }
        }
      }
    }
    target.put(
        "bootstrap.servers",
        getString(
            config, KAFKA_SECTION, null, "bootstrapServers", DEFAULT_KAFKA_BOOTSTRAP_SERVERS));
  }

  public static class Kafka {
    private final Map<EventFamily, String> eventTopics;
    private final String bootstrapServers;

    private static final Map<EventFamily, String> EVENT_TOPICS =
        ImmutableMap.of(
            EventFamily.INDEX_EVENT, "GERRIT.EVENT.INDEX",
            EventFamily.STREAM_EVENT, "GERRIT.EVENT.STREAM",
            EventFamily.CACHE_EVENT, "GERRIT.EVENT.CACHE",
            EventFamily.PROJECT_LIST_EVENT, "GERRIT.EVENT.PROJECT.LIST");

    Kafka(Config config) {
      this.bootstrapServers =
          getString(
              config, KAFKA_SECTION, null, "bootstrapServers", DEFAULT_KAFKA_BOOTSTRAP_SERVERS);

      this.eventTopics = new HashMap<>();
      for (Map.Entry<EventFamily, String> topicDefault : EVENT_TOPICS.entrySet()) {
        String topicConfigKey = topicDefault.getKey().lowerCamelName() + "Topic";
        eventTopics.put(
            topicDefault.getKey(),
            getString(config, KAFKA_SECTION, null, topicConfigKey, topicDefault.getValue()));
      }
    }

    public String getTopic(EventFamily eventType) {
      return eventTopics.get(eventType);
    }

    public String getBootstrapServers() {
      return bootstrapServers;
    }
  }

  public static class KafkaPublisher extends Properties {
    private static final long serialVersionUID = 0L;

    public static final String KAFKA_STRING_SERIALIZER = StringSerializer.class.getName();

    public static final String KAFKA_PUBLISHER_SUBSECTION = "publisher";
    public static final boolean DEFAULT_BROKER_ENABLED = false;

    private final boolean enabled;
    private final Map<EventFamily, Boolean> eventsEnabled;

    private KafkaPublisher(Config cfg) {
      enabled =
          cfg.getBoolean(
              KAFKA_SECTION, KAFKA_PUBLISHER_SUBSECTION, ENABLE_KEY, DEFAULT_BROKER_ENABLED);

      eventsEnabled = eventsEnabled(cfg, KAFKA_PUBLISHER_SUBSECTION);

      if (enabled) {
        setDefaults();
        applyKafkaConfig(cfg, KAFKA_PUBLISHER_SUBSECTION, this);
      }
    }

    private void setDefaults() {
      put("acks", "all");
      put("retries", 0);
      put("batch.size", 16384);
      put("linger.ms", 1);
      put("buffer.memory", 33554432);
      put("key.serializer", KAFKA_STRING_SERIALIZER);
      put("value.serializer", KAFKA_STRING_SERIALIZER);
      put("reconnect.backoff.ms", 5000L);
    }

    public boolean enabled() {
      return enabled;
    }

    public boolean enabledEvent(EventFamily eventType) {
      return eventsEnabled.get(eventType);
    }
  }

  public class KafkaSubscriber extends Properties {
    static final String KAFKA_SUBSCRIBER_SUBSECTION = "subscriber";

    private final boolean enabled;
    private final Integer pollingInterval;
    private Map<EventFamily, Boolean> eventsEnabled;
    private final Config cfg;

    public KafkaSubscriber(Config cfg) {
      this.pollingInterval =
          cfg.getInt(
              KAFKA_SECTION,
              KAFKA_SUBSCRIBER_SUBSECTION,
              "pollingIntervalMs",
              DEFAULT_POLLING_INTERVAL_MS);
      this.cfg = cfg;

      enabled = cfg.getBoolean(KAFKA_SECTION, KAFKA_SUBSCRIBER_SUBSECTION, ENABLE_KEY, false);

      eventsEnabled = eventsEnabled(cfg, KAFKA_SUBSCRIBER_SUBSECTION);

      if (enabled) {
        applyKafkaConfig(cfg, KAFKA_SUBSCRIBER_SUBSECTION, this);
      }
    }

    public boolean enabled() {
      return enabled;
    }

    public boolean enabledEvent(EventFamily eventFamily) {
      return eventsEnabled.get(eventFamily);
    }

    public Properties initPropsWith(UUID instanceId) {
      String groupId =
          getString(
              cfg, KAFKA_SECTION, KAFKA_SUBSCRIBER_SUBSECTION, "groupId", instanceId.toString());
      this.put("group.id", groupId);

      return this;
    }

    public Integer getPollingInterval() {
      return pollingInterval;
    }

    private String getString(
        Config cfg, String section, String subsection, String name, String defaultValue) {
      String value = cfg.getString(section, subsection, name);
      if (!Strings.isNullOrEmpty(value)) {
        return value;
      }
      return defaultValue;
    }
  }

  /** Common parameters to cache, event, index */
  public abstract static class Forwarding {
    static final boolean DEFAULT_SYNCHRONIZE = true;
    static final String SYNCHRONIZE_KEY = "synchronize";

    private final boolean synchronize;

    private Forwarding(Config cfg, String section) {
      synchronize = getBoolean(cfg, section, SYNCHRONIZE_KEY, DEFAULT_SYNCHRONIZE);
    }

    private static boolean getBoolean(
        Config cfg, String section, String name, boolean defaultValue) {
      try {
        return cfg.getBoolean(section, name, defaultValue);
      } catch (IllegalArgumentException e) {
        log.error("invalid value for {}; using default value {}", name, defaultValue);
        log.debug("Failed to retrieve boolean value: {}", e.getMessage(), e);
        return defaultValue;
      }
    }

    public boolean synchronize() {
      return synchronize;
    }
  }

  public static class Cache extends Forwarding {
    static final String CACHE_SECTION = "cache";
    static final String PATTERN_KEY = "pattern";

    private final int threadPoolSize;
    private final List<String> patterns;

    private Cache(Config cfg) {
      super(cfg, CACHE_SECTION);
      threadPoolSize = getInt(cfg, CACHE_SECTION, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      patterns = Arrays.asList(cfg.getStringList(CACHE_SECTION, null, PATTERN_KEY));
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public List<String> patterns() {
      return Collections.unmodifiableList(patterns);
    }
  }

  public static class Event extends Forwarding {
    static final String EVENT_SECTION = "event";

    private Event(Config cfg) {
      super(cfg, EVENT_SECTION);
    }
  }

  public static class Index extends Forwarding {
    static final String INDEX_SECTION = "index";
    static final String MAX_TRIES_KEY = "maxTries";
    static final String RETRY_INTERVAL_KEY = "retryInterval";

    private final int threadPoolSize;
    private final int retryInterval;
    private final int maxTries;

    private final int numStripedLocks;

    private Index(Config cfg) {
      super(cfg, INDEX_SECTION);
      threadPoolSize = getInt(cfg, INDEX_SECTION, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      retryInterval = getInt(cfg, INDEX_SECTION, RETRY_INTERVAL_KEY, DEFAULT_INDEX_RETRY_INTERVAL);
      maxTries = getInt(cfg, INDEX_SECTION, MAX_TRIES_KEY, DEFAULT_INDEX_MAX_TRIES);
      numStripedLocks = getInt(cfg, INDEX_SECTION, NUM_STRIPED_LOCKS, DEFAULT_NUM_STRIPED_LOCKS);
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public int retryInterval() {
      return retryInterval;
    }

    public int maxTries() {
      return maxTries;
    }

    public int numStripedLocks() {
      return numStripedLocks;
    }
  }

  public static class HealthCheck {
    static final String HEALTH_CHECK_SECTION = "healthCheck";
    static final boolean DEFAULT_HEALTH_CHECK_ENABLED = true;

    private final boolean enabled;

    private HealthCheck(Config cfg) {
      enabled = cfg.getBoolean(HEALTH_CHECK_SECTION, ENABLE_KEY, DEFAULT_HEALTH_CHECK_ENABLED);
    }

    public boolean enabled() {
      return enabled;
    }
  }
}
