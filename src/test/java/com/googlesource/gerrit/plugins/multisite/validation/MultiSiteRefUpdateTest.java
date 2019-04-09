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

package com.googlesource.gerrit.plugins.multisite.validation;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;

import com.googlesource.gerrit.plugins.multisite.validation.dfsrefdb.SharedRefDatabase;
import com.googlesource.gerrit.plugins.multisite.validation.dfsrefdb.zookeeper.RefFixture;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MultiSiteRefUpdateTest implements RefFixture {

  @Mock SharedRefDatabase sharedRefDb;
  @Mock RefUpdate refUpdate;
  private final Ref oldRef =
      new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, A_TEST_REF_NAME, AN_OBJECT_ID_1);
  private final Ref newRef =
      new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, A_TEST_REF_NAME, AN_OBJECT_ID_2);

  @Rule public TestName nameRule = new TestName();

  @Override
  public String testBranch() {
    return "branch_" + nameRule.getMethodName();
  }

  private void setMockRequiredReturnValues() {
    doReturn(oldRef).when(refUpdate).getRef();
    doReturn(A_TEST_REF_NAME).when(refUpdate).getName();
    doReturn(AN_OBJECT_ID_2).when(refUpdate).getNewObjectId();
    doReturn(newRef).when(sharedRefDb).newRef(A_TEST_REF_NAME, AN_OBJECT_ID_2);
  }

  @Test
  public void newUpdateShouldValidateAndSucceed() throws IOException {
    setMockRequiredReturnValues();

    // When compareAndPut succeeds
    doReturn(true).when(sharedRefDb).compareAndPut(A_TEST_PROJECT_NAME, oldRef, newRef);
    doReturn(Result.NEW).when(refUpdate).update();

    MultiSiteRefUpdate multiSiteRefUpdate =
        new MultiSiteRefUpdate(sharedRefDb, A_TEST_PROJECT_NAME, refUpdate);

    assertThat(multiSiteRefUpdate.update()).isEqualTo(Result.NEW);
  }

  @Test(expected = IOException.class)
  public void newUpdateShouldValidateAndFailWithIOException() throws IOException {
    setMockRequiredReturnValues();

    // When compareAndPut fails
    doReturn(false).when(sharedRefDb).compareAndPut(A_TEST_PROJECT_NAME, oldRef, newRef);

    MultiSiteRefUpdate multiSiteRefUpdate =
        new MultiSiteRefUpdate(sharedRefDb, A_TEST_PROJECT_NAME, refUpdate);
    multiSiteRefUpdate.update();
  }

  @Test
  public void deleteShouldValidateAndSucceed() throws IOException {
    setMockRequiredReturnValues();

    // When compareAndPut succeeds
    doReturn(true).when(sharedRefDb).compareAndRemove(A_TEST_PROJECT_NAME, oldRef);
    doReturn(Result.FORCED).when(refUpdate).delete();

    MultiSiteRefUpdate multiSiteRefUpdate =
        new MultiSiteRefUpdate(sharedRefDb, A_TEST_PROJECT_NAME, refUpdate);

    assertThat(multiSiteRefUpdate.delete()).isEqualTo(Result.FORCED);
  }

  @Test(expected = IOException.class)
  public void deleteShouldValidateAndFailWithIOException() throws IOException {
    setMockRequiredReturnValues();

    // When compareAndPut fails
    doReturn(false).when(sharedRefDb).compareAndRemove(A_TEST_PROJECT_NAME, oldRef);

    MultiSiteRefUpdate multiSiteRefUpdate =
        new MultiSiteRefUpdate(sharedRefDb, A_TEST_PROJECT_NAME, refUpdate);
    multiSiteRefUpdate.delete();
  }
}