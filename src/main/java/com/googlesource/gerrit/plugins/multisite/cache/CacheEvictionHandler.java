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

package com.googlesource.gerrit.plugins.multisite.cache;

import com.google.common.cache.RemovalNotification;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.cache.CacheRemovalListener;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.multisite.forwarder.Context;
import com.googlesource.gerrit.plugins.multisite.forwarder.Forwarder;
import java.util.concurrent.Executor;

class CacheEvictionHandler<K, V> implements CacheRemovalListener<K, V> {
  private final Executor executor;
  private final DynamicSet<Forwarder> forwarders;
  private final String pluginName;
  private final CachePatternMatcher matcher;

  @Inject
  CacheEvictionHandler(
      DynamicSet<Forwarder> forwarders,
      @CacheExecutor Executor executor,
      @PluginName String pluginName,
      CachePatternMatcher matcher) {
    this.forwarders = forwarders;
    this.executor = executor;
    this.pluginName = pluginName;
    this.matcher = matcher;
  }

  @Override
  public void onRemoval(String plugin, String cache, RemovalNotification<K, V> notification) {
    if (!Context.isForwardedEvent() && !notification.wasEvicted() && matcher.matches(cache)) {
      executor.execute(new CacheEvictionTask(cache, notification.getKey()));
    }
  }

  class CacheEvictionTask implements Runnable {
    private final String cacheName;
    private final Object key;

    CacheEvictionTask(String cacheName, Object key) {
      this.cacheName = cacheName;
      this.key = key;
    }

    @Override
    public void run() {
      forwarders.forEach(f -> f.evict(cacheName, key));
    }

    @Override
    public String toString() {
      return String.format(
          "[%s] Evict key '%s' from cache '%s' in target instance", pluginName, key, cacheName);
    }
  }
}
