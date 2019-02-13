// Copyright (C) 2019 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite.kafka.consumer;

import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedEventHandler;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedIndexChangeHandler;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedIndexingHandler;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.ChangeIndexEvent;
import java.io.IOException;
import java.util.Optional;

public class ForwardedEventRouter {

  private final ForwardedIndexChangeHandler indexChangeHandler;
  private final ForwardedEventHandler streamEventHandler;

  @Inject
  public ForwardedEventRouter(
      ForwardedIndexChangeHandler indexChangeHandler, ForwardedEventHandler streamEventHandler) {
    this.indexChangeHandler = indexChangeHandler;
    this.streamEventHandler = streamEventHandler;
  }

  void route(Event sourceEvent) throws IOException, OrmException, PermissionBackendException {
    if (sourceEvent instanceof ChangeIndexEvent) {
      ChangeIndexEvent changeIndexEvent = (ChangeIndexEvent) sourceEvent;
      ForwardedIndexingHandler.Operation operation =
          changeIndexEvent.deleted
              ? ForwardedIndexingHandler.Operation.DELETE
              : ForwardedIndexingHandler.Operation.INDEX;
      indexChangeHandler.index(
          changeIndexEvent.projectName + "~" + changeIndexEvent.changeId,
          operation,
          Optional.of(changeIndexEvent));
    } else if (sourceEvent instanceof Event) {
      streamEventHandler.dispatch(sourceEvent);
    } else {
      throw new UnsupportedOperationException(
          String.format("Cannot route event %s", sourceEvent.getType()));
    }
  }
}