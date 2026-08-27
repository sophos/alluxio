/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file.meta;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.grpc.SetAclAction;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.journal.NoopJournalContext;
import alluxio.master.metastore.InodeStore;
import alluxio.master.metastore.heap.HeapInodeStore;
import alluxio.proto.journal.File.SetAclEntry;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.security.authorization.AclEntry;
import alluxio.util.proto.ProtoUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * Tests how {@link InodeTreePersistentState} handles a SetAcl entry naming an inode that is not
 * in the store. The two call paths deliberately disagree (CSA-22628):
 *
 * Journal replay tolerates it. A single unreplayable entry used to throw NoSuchElementException
 * out of {@link InodeTreePersistentState#processJournalEntry}, which crashlooped the master with
 * no recovery short of destroying its Raft group -- observed in production.
 *
 * The live apply path does not tolerate it. It holds a write lock on the target inode, so an
 * absent inode there means corrupt in-memory state, and continuing would journal a divergence.
 */
public class InodeTreePersistentStateSetAclTest {
  private static final long EXISTING_INODE_ID = 100;
  private static final long MISSING_INODE_ID = 999;

  private InodeStore mInodeStore;
  private InodeTreePersistentState mState;

  @Before
  public void before() {
    // ProcessUtils#fatalError throws instead of calling System.exit under test mode, which is
    // what makes the strict path assertable at all.
    Configuration.set(PropertyKey.TEST_MODE, true);
    mInodeStore = new HeapInodeStore();
    mState = new InodeTreePersistentState(mInodeStore, new InodeLockManager(),
        new TtlBucketList(mInodeStore));
  }

  @After
  public void after() {
    Configuration.reloadProperties();
  }

  @Test
  public void replayIgnoresSetAclForMissingInode() {
    assertTrue("the entry should still be recognised and consumed",
        mState.processJournalEntry(journalEntryFor(MISSING_INODE_ID)));
  }

  @Test
  public void replayAppliesSetAclForPresentInode() {
    // Positive control: the tolerant path must not swallow entries that can be applied.
    mInodeStore.writeNewInode(MutableInodeDirectory.create(EXISTING_INODE_ID, 0, "existing",
        CreateDirectoryContext.defaults()));

    assertTrue(mState.processJournalEntry(journalEntryFor(EXISTING_INODE_ID)));

    assertTrue("the named-user entry should have been applied to the inode",
        mInodeStore.get(EXISTING_INODE_ID).get().getACL().getEntries().stream()
            .anyMatch(e -> "testuser".equals(e.getSubject())));
  }

  @Test
  public void liveApplyOfSetAclForMissingInodeFailsFast() {
    try {
      mState.applyAndJournal(() -> NoopJournalContext.INSTANCE, setAclEntryFor(MISSING_INODE_ID));
      fail("the live apply path must not tolerate a missing inode");
    } catch (RuntimeException e) {
      // ProcessUtils#fatalError rethrows its formatted message under test mode. Assert on the
      // inode id so this cannot pass on some unrelated RuntimeException.
      assertTrue("expected the failure to name the missing inode, got: " + e.getMessage(),
          e.getMessage().contains(String.valueOf(MISSING_INODE_ID)));
    }
  }

  private static AclEntry aclEntry() {
    return AclEntry.fromCliString("user:testuser:rwx");
  }

  private static SetAclEntry setAclEntryFor(long inodeId) {
    return SetAclEntry.newBuilder()
        .setId(inodeId)
        .setOpTimeMs(0)
        .setAction(ProtoUtils.toProto(SetAclAction.MODIFY))
        .addAllEntries(Collections.singletonList(ProtoUtils.toProto(aclEntry())))
        .build();
  }

  private static JournalEntry journalEntryFor(long inodeId) {
    return JournalEntry.newBuilder().setSetAcl(setAclEntryFor(inodeId)).build();
  }
}
