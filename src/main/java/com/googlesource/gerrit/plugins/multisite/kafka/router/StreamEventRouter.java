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

package com.googlesource.gerrit.plugins.multisite.kafka.router;

import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedEventHandler;

@Singleton
public class StreamEventRouter implements ForwardedStreamEventRouter {
  private final ForwardedEventHandler streamEventHandler;

  @Inject
  public StreamEventRouter(ForwardedEventHandler streamEventHandler) {
    this.streamEventHandler = streamEventHandler;
  }

  @Override
  public void route(Event sourceEvent) throws OrmException, PermissionBackendException {
    streamEventHandler.dispatch(sourceEvent);
  }
}