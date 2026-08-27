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
import alluxio.security.authorization.Mode;
import alluxio.underfs.UfsManager;
import alluxio.util.io.PathUtils;

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
import java.util.function.Supplier;

/**
 * Measures the retained heap of the two ways a recursive file system operation can walk a
 * subtree, under each inode-store configuration.
 *
 * WHY THIS EXISTS (CSA-22628). Recursive `setAcl` calls
 * {@link InodeTree#getDescendants(LockedInodePath)}, which materialises an
 * {@code ArrayList<LockedInodePath>} holding a WRITE_EDGE lock on EVERY descendant for the whole
 * operation -- an O(N) heap term. The alternative is the streaming
 * {@link alluxio.master.metastore.RecursiveInodeIterator}, which keeps a stack of open iterators
 * and releases locks as it leaves each directory -- O(depth).
 *
 * An earlier benchmark rejected the streaming approach on the grounds that it saved only ~11% of
 * heap. That number is suspect, because a HEAP inode store keeps every inode on-heap permanently:
 * an O(N) term that dwarfs the walk itself and that PRODUCTION DOES NOT HAVE (production runs
 * ROCKS with a bounded cache, alluxio.master.metastore.inode.cache.max.size). This probe measures
 * both arms under every store so that claim can be checked rather than assumed -- if the saving is
 * large under Caching(Rocks) and small under Heap, the original measurement was reading a term
 * that does not exist in production.
 *
 * MEASURED 2026-08-25 (202k inodes, cache=1000, -Xmx4g), Caching(Rocks):
 *
 *   inodes    eager retained   bytes/inode   streaming retained
 *    25,250        18 MB           749            ~0 MB
 *   101,000        73 MB           763            ~0 MB
 *   202,000       147 MB           764            ~0 MB
 *
 * Eager grows linearly at a stable ~764 bytes per descendant; streaming stays flat. Extrapolated
 * to charlie AZ-a (Master.TotalPaths 4.5M) the eager walk retains ~3.2GB, which together with the
 * ~0.93GB inode cache accounts for 4.1GB of the master's 5GB heap -- matching the
 * "free memory = 3M" observed during the CSA-22628 incident.
 *
 * Note the earlier "streaming only saves 11%" claim is NOT reproduced, and the HEAP-vs-ROCKS
 * hypothesis for it is also not supported: the saving is ~97% under Caching(Rocks) and ~99% under
 * Heap. Whatever the old benchmark measured, it was not retained heap.
 *
 * Not an assertion-based test: it prints PROBE lines and is meant to be read, not to gate CI.
 */
@RunWith(Parameterized.class)
@Ignore("Measurement probe, not a regression test: it has no assertions and takes ~50s. Heap "
    + "readings are too environment-sensitive to gate CI on. Run it by hand with "
    + "-Dtest=DescendantHeapProbeTest when changing how a recursive operation walks a subtree.")
public class DescendantHeapProbeTest {
  private static final String TEST_OWNER = "user1";
  private static final String TEST_GROUP = "group1";
  private static final Mode TEST_DIR_MODE = new Mode((short) 0755);
  private static final Mode TEST_FILE_MODE = new Mode((short) 0644);

  /** Subtree shape. Wide and shallow, like the datalake tenant/date layers. */
  /**
   * Overridable so the scaling claim can be checked rather than inferred: eager retention should
   * grow linearly with the inode count while streaming retention stays flat, because one is O(N)
   * and the other O(tree depth).
   */
  private static final int DIRS = 2000;
  private static final int FILES_PER_DIR = Integer.getInteger("probe.filesPerDir", 100);

  /**
   * Inode cache size for the Caching(Rocks) arm, deliberately far SMALLER than the tree.
   *
   * This is the whole point of the parameterisation. On charlie the master holds ~4.5M paths
   * against alluxio.master.metastore.inode.cache.max.size=500000, so the overwhelming majority of
   * inodes live in RocksDB and not on the heap. If the probe left the default cache size
   * (maxMemory/2000/2, i.e. ~500k entries on a 2g test heap) the entire probe tree would sit in
   * cache and Caching(Rocks) would behave exactly like Heap -- measuring nothing.
   */
  private static final int INODE_CACHE_SIZE = 1000;

  /** Repeat count; the max retention across rounds is reported, to ride out gc noise. */
  private static final int ROUNDS = 3;

  private final String mStoreName;
  private final InodeStore mInodeStore;
  private InodeTree mTree;
  private MasterRegistry mRegistry;
  private CreateFileContext mFileContext;
  private CreateDirectoryContext mDirContext;

  @Parameters(name = "{0}")
  public static Iterable<Object[]> parameters() throws Exception {
    String dir =
        AlluxioTestDirectory.createTemporaryDirectory("descendant-heap-probe").getAbsolutePath();
    // Must be set HERE, not in a @Rule: CachingInodeStore reads the cache size in its
    // constructor, and the suppliers below are invoked from the test-class constructor, which
    // JUnit runs before rules are applied.
    Configuration.set(PropertyKey.MASTER_METASTORE_INODE_CACHE_MAX_SIZE, INODE_CACHE_SIZE);
    return Arrays.<Object[]>asList(
        // The production shape: ROCKS behind a bounded cache.
        new Object[] {"Caching(Rocks)",
            (Supplier<InodeStore>) () -> new CachingInodeStore(new RocksInodeStore(dir),
                new InodeLockManager())},
        // The shape a unit-test benchmark falls into by default, where every inode is on-heap
        // forever and swamps whatever the walk itself costs.
        new Object[] {"Heap", (Supplier<InodeStore>) HeapInodeStore::new});
  }

  public DescendantHeapProbeTest(String storeName, Supplier<InodeStore> supplier) {
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
  public void compareWalkRetainedHeap() throws Exception {
    AlluxioURI root = new AlluxioURI("/m");
    createPath(root, mDirContext);
    int created = 1;
    for (int d = 0; d < DIRS; d++) {
      String dirPath = PathUtils.concatPath("/m", String.format("tenant=%04d", d));
      createPath(new AlluxioURI(dirPath), mDirContext);
      created++;
      for (int f = 0; f < FILES_PER_DIR; f++) {
        createPath(new AlluxioURI(PathUtils.concatPath(dirPath, "part-" + f)), mFileContext);
        created++;
      }
    }

    System.out.printf("PROBE store=%s inodes=%d cache=%d%n",
        mStoreName, created, INODE_CACHE_SIZE);

    // Each arm is bracketed against a reading taken in the SAME gc regime once the structure has
    // been released, rather than against a shared baseline. A shared baseline drifts -- the first
    // version of this probe reported negative retention and savings over 100% because the eager
    // arm's garbage had not settled before the streaming baseline was taken.
    long eagerRetained = 0;
    long streamRetained = 0;
    long mutateRetained = 0;
    int mutateCount = 0;
    int eagerCount = 0;
    int streamCount = 0;

    for (int round = 0; round < ROUNDS; round++) {
      // ---- Arm 1: eager getDescendants, the current setAcl path -------------
      long held;
      try (LockedInodePath rootPath =
               mTree.lockInodePath(root, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
        try (LockedInodePathList descendants = mTree.getDescendants(rootPath)) {
          eagerCount = descendants.getInodePathList().size();
          // GC while the list is still reachable, so what survives IS the retained set.
          held = usedAfterGc();
        }
      }
      long releasedEager = usedAfterGc();
      eagerRetained = Math.max(eagerRetained, held - releasedEager);

      // ---- Arm 2: streaming RecursiveInodeIterator --------------------------
      long mid = 0;
      streamCount = 0;
      try (LockedInodePath rootPath =
               mTree.lockInodePath(root, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
        try (SkippableInodeIterator it = mInodeStore.getSkippableChildrenIterator(
            ReadOption.defaults(), DescendantType.ALL, false, rootPath)) {
          while (it.hasNext()) {
            it.next();
            streamCount++;
            // Sample mid-walk: the iterator is deep in the tree and holding whatever it holds.
            if (streamCount == eagerCount / 2) {
              mid = usedAfterGc();
            }
          }
        }
      }
      long releasedStream = usedAfterGc();
      streamRetained = Math.max(streamRetained, mid - releasedStream);

      // ---- Arm 3: stream AND MUTATE ------------------------------------------
      // The arms above only read. setAcl writes to every inode, which marks every cache entry
      // dirty -- and if dirty entries could not be evicted, streaming would save nothing in
      // practice. The caching inode store is write-back with an async eviction thread, so they
      // should stay bounded by the cache size; this arm checks that rather than assuming it.
      long midMutate = 0;
      int mutated = 0;
      try (LockedInodePath rootPath =
               mTree.lockInodePath(root, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
        try (SkippableInodeIterator it = mInodeStore.getSkippableChildrenIterator(
            ReadOption.defaults(), DescendantType.ALL, false, rootPath)) {
          while (it.hasNext()) {
            alluxio.master.file.meta.InodeIterationResult r = it.next();
            mTree.setAcl(alluxio.master.file.RpcContext.NOOP, SetAclEntry.newBuilder()
                .setId(r.getInode().getId())
                .setOpTimeMs(System.currentTimeMillis())
                .setAction(alluxio.util.proto.ProtoUtils.toProto(SetAclAction.MODIFY))
                // Build via the Java ACL API and convert, exactly as setAclSingleInode does.
                .addAllEntries(java.util.Collections.singletonList(
                    alluxio.util.proto.ProtoUtils.toProto(
                        alluxio.security.authorization.AclEntry.fromCliString(
                            "user:probe-user:r-x"))))
                .build());
            mutated++;
            if (mutated == eagerCount / 2) {
              midMutate = usedAfterGc();
            }
          }
        }
      }
      long releasedMutate = usedAfterGc();
      mutateRetained = Math.max(mutateRetained, midMutate - releasedMutate);
      mutateCount = mutated;
    }

    System.out.printf("PROBE store=%s arm=eager  walked=%d retained_mb=%d bytes_per_inode=%d%n",
        mStoreName, eagerCount, eagerRetained / (1024 * 1024),
        eagerCount > 0 ? eagerRetained / eagerCount : 0);
    System.out.printf("PROBE store=%s arm=stream walked=%d retained_mb=%d bytes_per_inode=%d%n",
        mStoreName, streamCount, streamRetained / (1024 * 1024),
        streamCount > 0 ? streamRetained / streamCount : 0);
    System.out.printf(
        "PROBE store=%s arm=stream+setAcl walked=%d retained_mb=%d%n",
        mStoreName, mutateCount, mutateRetained / (1024 * 1024));
    long saved = eagerRetained - streamRetained;
    long pct = eagerRetained > 0 ? (saved * 100 / eagerRetained) : 0;
    System.out.printf("PROBE store=%s SAVING eager_mb=%d stream_mb=%d saved_pct=%d "
            + "extrapolated_4.5M_eager_gb=%.2f%n",
        mStoreName, eagerRetained / (1024 * 1024), streamRetained / (1024 * 1024), pct,
        eagerCount > 0 ? (eagerRetained / (double) eagerCount) * 4_500_000d / (1024 * 1024 * 1024)
            : 0d);
    // Equivalence check: both arms must visit the same inodes, else the comparison is meaningless.
    System.out.printf("PROBE store=%s EQUIVALENT walked_same=%b%n",
        mStoreName, eagerCount == streamCount);
  }

  private static long usedAfterGc() throws InterruptedException {
    // Several rounds: one System.gc() is a hint, and the caching inode store evicts
    // asynchronously, so give it a chance to settle before reading.
    for (int i = 0; i < 4; i++) {
      System.gc();
      Thread.sleep(120);
    }
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }

  private void createPath(AlluxioURI path, CreatePathContext<?, ?> context) throws Exception {
    try (LockedInodePath inodePath =
             mTree.lockInodePath(path, LockPattern.WRITE_EDGE, NoopJournalContext.INSTANCE)) {
      mTree.createPath(alluxio.master.file.RpcContext.NOOP, inodePath, context);
    }
  }
}
