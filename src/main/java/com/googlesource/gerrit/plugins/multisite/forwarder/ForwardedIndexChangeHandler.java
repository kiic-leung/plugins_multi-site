// Copyright (C) 2018 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite.forwarder;

import com.google.common.base.Splitter;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.Configuration;
import com.googlesource.gerrit.plugins.multisite.Configuration.Index;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.ChangeIndexEvent;
import com.googlesource.gerrit.plugins.multisite.index.ChangeChecker;
import com.googlesource.gerrit.plugins.multisite.index.ChangeCheckerImpl;
import com.googlesource.gerrit.plugins.multisite.index.ForwardedIndexExecutor;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Index a change using {@link ChangeIndexer}. This class is meant to be used on the receiving side
 * of the {@link IndexEventForwarder} since it will prevent indexed change to be forwarded again
 * causing an infinite forwarding loop between the 2 nodes. It will also make sure no concurrent
 * indexing is done for the same change id
 */
@Singleton
public class ForwardedIndexChangeHandler
    extends ForwardedIndexingHandler<String, ChangeIndexEvent> {
  private final ChangeIndexer indexer;
  private final ScheduledExecutorService indexExecutor;
  private final OneOffRequestContext oneOffCtx;
  private final int retryInterval;
  private final int maxTries;
  private final ChangeCheckerImpl.Factory changeCheckerFactory;

  @Inject
  ForwardedIndexChangeHandler(
      ChangeIndexer indexer,
      Configuration configuration,
      @ForwardedIndexExecutor ScheduledExecutorService indexExecutor,
      OneOffRequestContext oneOffCtx,
      ChangeCheckerImpl.Factory changeCheckerFactory) {
    super(configuration.index().numStripedLocks());
    this.indexer = indexer;
    this.indexExecutor = indexExecutor;
    this.oneOffCtx = oneOffCtx;
    this.changeCheckerFactory = changeCheckerFactory;

    Index indexConfig = configuration.index();
    this.retryInterval = indexConfig != null ? indexConfig.retryInterval() : 0;
    this.maxTries = indexConfig != null ? indexConfig.maxTries() : 0;
  }

  @Override
  protected void doIndex(String id, Optional<ChangeIndexEvent> indexEvent) {
    doIndex(id, indexEvent, 0);
  }

  private void doIndex(String id, Optional<ChangeIndexEvent> indexEvent, int retryCount) {
    ChangeChecker checker = changeCheckerFactory.create(id);
    Optional<ChangeNotes> changeNotes = checker.getChangeNotes();
    if (changeNotes.isPresent()) {
      ChangeNotes notes = changeNotes.get();
      reindex(notes);

      if (checker.isChangeUpToDate(indexEvent)) {
        if (retryCount > 0) {
          log.warn("Change {} has been eventually indexed after {} attempt(s)", id, retryCount);
        } else {
          log.debug("Change {} successfully indexed", id);
        }
      } else {
        log.warn(
            "Change {} seems too old compared to the event timestamp (event={} >> change-Ts={})",
            id,
            indexEvent,
            checker);
        rescheduleIndex(id, indexEvent, retryCount + 1);
      }
    } else {
      log.warn(
          "Change {} not present yet in local Git repository (event={}) after {} attempt(s)",
          id,
          indexEvent,
          retryCount);
      if (!rescheduleIndex(id, indexEvent, retryCount + 1)) {
        log.error(
            "Change {} could not be found in the local Git repository (event={})", id, indexEvent);
      }
    }
  }

  private void reindex(ChangeNotes notes) {
    try (ManualRequestContext ctx = oneOffCtx.open()) {
      notes.reload();
      indexer.index(notes.getChange());
    }
  }

  private boolean rescheduleIndex(
      String id, Optional<ChangeIndexEvent> indexEvent, int retryCount) {
    if (retryCount > maxTries) {
      log.error(
          "Change {} could not be indexed after {} retries. Change index could be stale.",
          id,
          retryCount);
      return false;
    }

    log.warn(
        "Retrying for the #{} time to index Change {} after {} msecs",
        retryCount,
        id,
        retryInterval);
    @SuppressWarnings("unused")
    Future<?> possiblyIgnoredError =
        indexExecutor.schedule(
            () -> {
              try (ManualRequestContext ctx = oneOffCtx.open()) {
                Context.setForwardedEvent(true);
                doIndex(id, indexEvent, retryCount);
              } catch (Exception e) {
                log.warn("Change {} could not be indexed", id, e);
              }
            },
            retryInterval,
            TimeUnit.MILLISECONDS);
    return true;
  }

  @Override
  protected void doDelete(String id, Optional<ChangeIndexEvent> indexEvent) {
    indexer.delete(parseChangeId(id));
    log.debug("Change {} successfully deleted from index", id);
  }

  private static Change.Id parseChangeId(String id) {
    Change.Id changeId = Change.id(Integer.parseInt(Splitter.on("~").splitToList(id).get(1)));
    return changeId;
  }
}
