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

package com.googlesource.gerrit.plugins.multisite.index;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;

public class ProjectCheckerImpl implements ProjectChecker {
  private final ProjectCache projectCache;

  @Inject
  ProjectCheckerImpl(ProjectCache projectCache) {
    this.projectCache = projectCache;
  }

  @Override
  public boolean isProjectUpToDate(Project.NameKey projectName) {
    return projectCache.get(projectName).isPresent();
  }
}
