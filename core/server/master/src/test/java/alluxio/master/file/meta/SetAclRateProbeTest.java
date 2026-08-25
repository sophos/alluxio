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

import static org.mockito.Mockito.mock;

import alluxio.AlluxioTestDirectory;
import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.file.options.DescendantType;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.SetAclAction;
import alluxio.master.CoreMasterContext;
import alluxio.master.MasterRegistry;
import alluxio.master.MasterTestUtils;
import alluxio.master.block.BlockMaster;
import alluxio.master.block.BlockMasterFactory;
import alluxio.master.file.RpcContext;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.CreatePathContext;
import alluxio.master.file.meta.InodeTree.LockPattern;
import alluxio.master.file.meta.options.MountInfo;
import alluxio.master.journal.NoopJournalContext;
import alluxio.master.metastore.InodeStore;
import alluxio.master.metastore.ReadOption;
import alluxio.master.metastore.SkippableInodeIterator;
import alluxio.master.metastore.caching.CachingInodeStore;
import alluxio.master.metastore.heap.HeapInodeStore;
import alluxio.master.metastore.rocks.RocksInodeStore;
import alluxio.master.metrics.MetricsMaster;
import alluxio.master.metrics.MetricsMasterFactory;
import alluxio.proto.journal.File.SetAclEntry;
import alluxio.security.authorization.AclEntry;
import alluxio.security.authorization.Mode;
import alluxio.underfs.UfsManager;
import alluxio.util.io.PathUtils;
import alluxio.util.proto.ProtoUtils;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Companion to {@link DescendantHeapProbeTest}. That probe answered "how much heap does the walk
 * retain"; this one answers "where does the wall-clock time per inode actually go".
 *
 * A pilot backfill of 852,991 paths took ~34 minutes, i.e. ~2.4ms of single-threaded work per
 * inode. That is fsync/network scale, not in-memory scale, so something blocks. This probe splits
 * the per-inode cost into the two candidates that live inside the master's walk:
 *
 *   walk-only     -- the streaming iterator reading each inode (metastore READ cost)
 *   walk + setAcl -- the same walk, writing an ACL to every inode (adds metastore WRITE cost)
 *
 * and runs both against Heap and Caching(Rocks) so the metastore's share is visible directly
 * rather than inferred. Journal cost is deliberately excluded: RpcContext.NOOP does not journal,
 * so anything unexplained here points at the journal/Raft path instead.
 */
@RunWith(Parameterized.class)
@Ignore("Measurement probe, not a regression test: no assertions, and timings are machine- and "
    + "disk-specific. Run by hand with -Dtest=SetAclRateProbeTest.")
public class SetAclRateProbeTest {
  private static final String TEST_OWNER = "user1";
  private static final String TEST_GROUP = "group1";
  private static final Mode TEST_DIR_MODE = new Mode((short) 0755);
  private static final Mode TEST_FILE_MODE = new Mode((short) 0644);

  private static final int DIRS = Integer.getInteger("probe.dirs", 1000);
  private static final int FILES_PER_DIR = Integer.getInteger("probe.filesPerDir", 50);

  /** Deliberately far smaller than the tree, so Caching(Rocks) really goes to RocksDB. */
  private static final int INODE_CACHE_SIZE = 1000;

  private final String mStoreName;
  private final InodeStore mInodeStore;
  private InodeTree mTree;
  private MasterRegistry mRegistry;
  private CreateFileContext mFileContext;
  private CreateDirectoryContext mDirContext;

  @Parameters(name = "{0}")
  public static Iterable<Object[]> parameters() throws Exception {
    String dir =
        AlluxioTestDirectory.createTemporaryDirectory("setacl-rate-probe").getAbsolutePath();
    // Must be set HERE: CachingInodeStore reads the cache size in its constructor, which runs
    // from the test-class constructor, before @Rule application.
    Configuration.set(PropertyKey.MASTER_METASTORE_INODE_CACHE_MAX_SIZE, INODE_CACHE_SIZE);
    return Arrays.<Object[]>asList(
        new Object[] {"Caching(Rocks)",
            (Supplier<InodeStore>) () -> new CachingInodeStore(new RocksInodeStore(dir),
                new InodeLockManager())},
        new Object[] {"Heap", (Supplier<InodeStore>) HeapInodeStore::new});
  }

  public SetAclRateProbeTest(String storeName, Supplier<InodeStore> supplier) {
    mStoreName = storeName;
    mInodeStore = supplier.get();
  }

  @Rule
  public TemporaryFolder mTestFolder = new TemporaryFolder();

  @Rule
  public alluxio.ConfigurationRule mConfigurationRule =
      new alluxio.ConfigurationRule(new ImmutableMap.Builder<PropertyKey, Object>()
          .put(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_ENABLED, true)
          .put(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_SUPERGROUP, "test-supergroup")
          .build(), Configuration.modifiableGlobal());

  @Before
  public void before() throws Exception {
    mRegistry = new MasterRegistry();
    CoreMasterContext context = MasterTestUtils.testMasterContext();
    MetricsMaster metricsMaster = new MetricsMasterFactory().create(mRegistry, context);
    mRegistry.add(MetricsMaster.class, metricsMaster);
    BlockMaster blockMaster = new BlockMasterFactory().create(mRegistry, context);
    InodeDirectoryIdGenerator idGen = new InodeDirectoryIdGenerator(blockMaster);
    UfsManager ufsManager = mock(UfsManager.class);
    MountTable mountTable = new MountTable(ufsManager, mock(MountInfo.class), Clock.systemUTC());
    mTree = new InodeTree(mInodeStore, blockMaster, idGen, mountTable, new InodeLockManager());
    mRegistry.start(true);
    mTree.initializeRoot(TEST_OWNER, TEST_GROUP, TEST_DIR_MODE, NoopJournalContext.INSTANCE);

    mFileContext = CreateFileContext
        .mergeFrom(CreateFilePOptions.newBuilder().setBlockSizeBytes(Constants.KB)
            .setMode(TEST_FILE_MODE.toProto()).setRecursive(true))
        .setOwner(TEST_OWNER).setGroup(TEST_GROUP);
    mDirContext = CreateDirectoryContext
        .mergeFrom(CreateDirectoryPOptions.newBuilder().setMode(TEST_DIR_MODE.toProto())
            .setRecursive(true))
        .setOwner(TEST_OWNER).setGroup(TEST_GROUP);
  }

  @After
  public void after() throws Exception {
    mRegistry.stop();
    mInodeStore.close();
  }

  @Test
  public void measurePerInodeRate() throws Exception {
    AlluxioURI root = new AlluxioURI("/probe");
    createPath(root, mDirContext);
    int created = 1;
    for (int d = 0; d < DIRS; d++) {
      AlluxioURI dir = new AlluxioURI(PathUtils.concatPath(root, "d" + d));
      createPath(dir, mDirContext);
      created++;
      for (int f = 0; f < FILES_PER_DIR; f++) {
        createPath(new AlluxioURI(PathUtils.concatPath(dir, "f" + f)), mFileContext);
        created++;
      }
    }
    System.out.printf("%n=== [%s] tree=%d inodes, inodeCache=%d ===%n",
        mStoreName, created, INODE_CACHE_SIZE);

    // Warm up once so JIT and RocksDB block cache are not charged to the measured pass.
    walk(root, false);

    long walkNs = walk(root, false);
    long mutateNs = walk(root, true);

    int n = created - 1; // descendants of root
    System.out.printf("[%s] walk-only      : %6d ms  -> %8.2f us/inode%n",
        mStoreName, walkNs / 1_000_000, walkNs / 1000.0 / n);
    System.out.printf("[%s] walk + setAcl  : %6d ms  -> %8.2f us/inode%n",
        mStoreName, mutateNs / 1_000_000, mutateNs / 1000.0 / n);
    System.out.printf("[%s] setAcl delta   : %6d ms  -> %8.2f us/inode%n",
        mStoreName, (mutateNs - walkNs) / 1_000_000, (mutateNs - walkNs) / 1000.0 / n);
  }

  /**
   * Walks the subtree with the streaming iterator, optionally applying an ACL to every inode.
   *
   * @param root subtree root
   * @param mutate whether to setAcl on each inode
   * @return elapsed nanoseconds
   */
  private long walk(AlluxioURI root, boolean mutate) throws Exception {
    long start = System.nanoTime();
    try (LockedInodePath rootPath =
             mTree.lockInodePath(root, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
      try (SkippableInodeIterator it = mInodeStore.getSkippableChildrenIterator(
          ReadOption.defaults(), DescendantType.ALL, false, rootPath)) {
        while (it.hasNext()) {
          InodeIterationResult r = it.next();
          if (mutate) {
            mTree.setAcl(RpcContext.NOOP, SetAclEntry.newBuilder()
                .setId(r.getInode().getId())
                .setOpTimeMs(System.currentTimeMillis())
                .setAction(ProtoUtils.toProto(SetAclAction.MODIFY))
                .addAllEntries(Collections.singletonList(
                    ProtoUtils.toProto(AclEntry.fromCliString("user:probe-user:r-x"))))
                .build());
          }
        }
      }
    }
    return System.nanoTime() - start;
  }

  private void createPath(AlluxioURI path, CreatePathContext<?, ?> context) throws Exception {
    try (LockedInodePath inodePath =
             mTree.lockInodePath(path, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
      mTree.createPath(RpcContext.NOOP, inodePath, context);
    }
  }
}
