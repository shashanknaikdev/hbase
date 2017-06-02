/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.hadoop.hbase.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.client.replication.ReplicationAdmin;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.BulkLoadHFileRequest;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.regionserver.StorefileRefresherChore;
import org.apache.hadoop.hbase.regionserver.TestHRegionServerBulkLoad;
import org.apache.hadoop.hbase.replication.ReplicationPeerConfig;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Category(MediumTests.class)
public class TestReplicaWithCluster {
  private static final Log LOG = LogFactory.getLog(TestReplicaWithCluster.class);

  private static final int NB_SERVERS = 3;
  private static final byte[] row = TestReplicaWithCluster.class.getName().getBytes();
  private static final HBaseTestingUtility HTU = new HBaseTestingUtility();

  // second minicluster used in testing of replication
  private static HBaseTestingUtility HTU2;
  private static final byte[] f = HConstants.CATALOG_FAMILY;

  private final static int REFRESH_PERIOD = 1000;
  private final static int META_SCAN_TIMEOUT_IN_MILLISEC = 200;

  /**
   * This copro is used to synchronize the tests.
   */
  public static class SlowMeCopro extends BaseRegionObserver {
    static final AtomicLong sleepTime = new AtomicLong(0);
    static final AtomicReference<CountDownLatch> cdl =
        new AtomicReference<CountDownLatch>(new CountDownLatch(0));

    public SlowMeCopro() {
    }

    @Override
    public void preGetOp(final ObserverContext<RegionCoprocessorEnvironment> e,
                         final Get get, final List<Cell> results) throws IOException {

      if (e.getEnvironment().getRegion().getRegionInfo().getReplicaId() == 0) {
        CountDownLatch latch = cdl.get();
        try {
          if (sleepTime.get() > 0) {
            LOG.info("Sleeping for " + sleepTime.get() + " ms");
            Thread.sleep(sleepTime.get());
          } else if (latch.getCount() > 0) {
            LOG.info("Waiting for the counterCountDownLatch");
            latch.await(2, TimeUnit.MINUTES); // To help the tests to finish.
            if (latch.getCount() > 0) {
              throw new RuntimeException("Can't wait more");
            }
          }
        } catch (InterruptedException e1) {
          LOG.error(e1);
        }
      } else {
        LOG.info("We're not the primary replicas.");
      }
    }
  }

  /**
   * This copro is used to simulate region server down exception for Get and Scan
   */
  public static class RegionServerStoppedCopro extends BaseRegionObserver {

    public RegionServerStoppedCopro() {
    }

    @Override
    public void preGetOp(final ObserverContext<RegionCoprocessorEnvironment> e,
        final Get get, final List<Cell> results) throws IOException {

      int replicaId = e.getEnvironment().getRegion().getRegionInfo().getReplicaId();

      // Fail for the primary replica and replica 1
      if (e.getEnvironment().getRegion().getRegionInfo().getReplicaId() <= 1) {
        LOG.info("Throw Region Server Stopped Exceptoin for replica id " + replicaId);
        throw new RegionServerStoppedException("Server " +
            e.getEnvironment().getRegionServerServices().getServerName()
            + " not running");
      } else {
        LOG.info("We're replica region " + replicaId);
      }
    }

    @Override
    public RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> e,
        final Scan scan, final RegionScanner s) throws IOException {

      int replicaId = e.getEnvironment().getRegion().getRegionInfo().getReplicaId();

      // Fail for the primary replica and replica 1
      if (e.getEnvironment().getRegion().getRegionInfo().getReplicaId() <= 1) {
        LOG.info("Throw Region Server Stopped Exceptoin for replica id " + replicaId);
        throw new RegionServerStoppedException("Server " +
            e.getEnvironment().getRegionServerServices().getServerName()
            + " not running");
      } else {
        LOG.info("We're replica region " + replicaId);
      }

      return null;
    }
  }

  /**
   * This copro is used to slow down the primary meta region scan a bit
   */
  public static class RegionServerHostingPrimayMetaRegionSlowOrStopCopro extends BaseRegionObserver {
    static boolean slowDownPrimaryMetaScan = false;
    static boolean throwException = false;

    @Override
    public void preGetOp(final ObserverContext<RegionCoprocessorEnvironment> e,
        final Get get, final List<Cell> results) throws IOException {

      int replicaId = e.getEnvironment().getRegion().getRegionInfo().getReplicaId();

      // Fail for the primary replica, but not for meta
      if (throwException) {
        if (!e.getEnvironment().getRegion().getRegionInfo().isMetaRegion() && (replicaId == 0)) {
          LOG.info("Get, throw Region Server Stopped Exceptoin for region " + e.getEnvironment()
              .getRegion().getRegionInfo());
          throw new RegionServerStoppedException("Server " +
              e.getEnvironment().getRegionServerServices().getServerName() + " not running");
        }
      } else {
        LOG.info("Get, We're replica region " + replicaId);
      }
    }

    @Override
    public RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> e,
        final Scan scan, final RegionScanner s) throws IOException {

      int replicaId = e.getEnvironment().getRegion().getRegionInfo().getReplicaId();

      // Slow down with the primary meta region scan
      if (e.getEnvironment().getRegion().getRegionInfo().isMetaRegion() && (replicaId == 0)) {
        if (slowDownPrimaryMetaScan) {
          LOG.info("Scan with primary meta region, slow down a bit");
          try {
            Thread.sleep(META_SCAN_TIMEOUT_IN_MILLISEC - 50);
          } catch (InterruptedException ie) {
            // Ingore
          }
        }

        // Fail for the primary replica
        if (throwException) {
          LOG.info("Scan, throw Region Server Stopped Exceptoin for replica " + e.getEnvironment()
              .getRegion().getRegionInfo());

          throw new RegionServerStoppedException("Server " +
              e.getEnvironment().getRegionServerServices().getServerName() + " not running");
        } else {
          LOG.info("Scan, We're replica region " + replicaId);
        }
      } else {
        LOG.info("Scan, We're replica region " + replicaId);
      }

      return null;
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    // enable store file refreshing
    HTU.getConfiguration().setInt(
        StorefileRefresherChore.REGIONSERVER_STOREFILE_REFRESH_PERIOD, REFRESH_PERIOD);

    HTU.getConfiguration().setFloat("hbase.regionserver.logroll.multiplier", 0.0001f);
    HTU.getConfiguration().setInt("replication.source.size.capacity", 10240);
    HTU.getConfiguration().setLong("replication.source.sleepforretries", 100);
    HTU.getConfiguration().setInt("hbase.regionserver.maxlogs", 2);
    HTU.getConfiguration().setLong("hbase.master.logcleaner.ttl", 10);
    HTU.getConfiguration().setInt("zookeeper.recovery.retry", 1);
    HTU.getConfiguration().setInt("zookeeper.recovery.retry.intervalmill", 10);

    // Wait for primary call longer so make sure that it will get exception from the primary call
    HTU.getConfiguration().setInt("hbase.client.primaryCallTimeout.get", 1000000);
    HTU.getConfiguration().setInt("hbase.client.primaryCallTimeout.scan", 1000000);

    // Enable meta replica at server side
    HTU.getConfiguration().setInt("hbase.meta.replica.count", 2);

    // Make sure master does not host system tables.
    HTU.getConfiguration().set("hbase.balancer.tablesOnMaster", "none");

    // Set system coprocessor so it can be applied to meta regions
    HTU.getConfiguration().set("hbase.coprocessor.region.classes",
        RegionServerHostingPrimayMetaRegionSlowOrStopCopro.class.getName());

    HTU.getConfiguration().setInt(HConstants.HBASE_CLIENT_MEAT_REPLICA_SCAN_TIMEOUT,
        META_SCAN_TIMEOUT_IN_MILLISEC * 1000);

    HTU.startMiniCluster(NB_SERVERS);
    HTU.getHBaseCluster().startMaster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (HTU2 != null) {
      HTU2.shutdownMiniCluster();
    }
    HTU.shutdownMiniCluster();
  }

  @Test (timeout=30000)
  public void testCreateDeleteTable() throws IOException {
    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testCreateDeleteTable");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(SlowMeCopro.class.getName());
    Table table = HTU.createTable(hdt, new byte[][]{f}, HTU.getConfiguration());

    Put p = new Put(row);
    p.add(f, row, row);
    table.put(p);

    Get g = new Get(row);
    Result r = table.get(g);
    Assert.assertFalse(r.isStale());

    try {
      // But if we ask for stale we will get it
      SlowMeCopro.cdl.set(new CountDownLatch(1));
      g = new Get(row);
      g.setConsistency(Consistency.TIMELINE);
      r = table.get(g);
      Assert.assertTrue(r.isStale());
      SlowMeCopro.cdl.get().countDown();
    } finally {
      SlowMeCopro.cdl.get().countDown();
      SlowMeCopro.sleepTime.set(0);
    }

    HTU.getHBaseAdmin().disableTable(hdt.getTableName());
    HTU.deleteTable(hdt.getTableName());
  }

  @Test (timeout=120000)
  public void testChangeTable() throws Exception {
    HTableDescriptor hdt = HTU.createTableDescriptor("testChangeTable");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(SlowMeCopro.class.getName());
    Table table = HTU.createTable(hdt, new byte[][]{f}, HTU.getConfiguration());

    // basic test: it should work.
    Put p = new Put(row);
    p.add(f, row, row);
    table.put(p);

    Get g = new Get(row);
    Result r = table.get(g);
    Assert.assertFalse(r.isStale());

    // Add a CF, it should work.
    HTableDescriptor bHdt = HTU.getHBaseAdmin().getTableDescriptor(hdt.getTableName());
    HColumnDescriptor hcd = new HColumnDescriptor(row);
    hdt.addFamily(hcd);
    HTU.getHBaseAdmin().disableTable(hdt.getTableName());
    HTU.getHBaseAdmin().modifyTable(hdt.getTableName(), hdt);
    HTU.getHBaseAdmin().enableTable(hdt.getTableName());
    HTableDescriptor nHdt = HTU.getHBaseAdmin().getTableDescriptor(hdt.getTableName());
    Assert.assertEquals("fams=" + Arrays.toString(nHdt.getColumnFamilies()),
        bHdt.getColumnFamilies().length + 1, nHdt.getColumnFamilies().length);

    p = new Put(row);
    p.add(row, row, row);
    table.put(p);

    g = new Get(row);
    r = table.get(g);
    Assert.assertFalse(r.isStale());

    try {
      SlowMeCopro.cdl.set(new CountDownLatch(1));
      g = new Get(row);
      g.setConsistency(Consistency.TIMELINE);
      r = table.get(g);
      Assert.assertTrue(r.isStale());
    } finally {
      SlowMeCopro.cdl.get().countDown();
      SlowMeCopro.sleepTime.set(0);
    }

    HTU.getHBaseCluster().stopMaster(0);
    Admin admin = new HBaseAdmin(HTU.getConfiguration());
    nHdt =admin.getTableDescriptor(hdt.getTableName());
    Assert.assertEquals("fams=" + Arrays.toString(nHdt.getColumnFamilies()),
        bHdt.getColumnFamilies().length + 1, nHdt.getColumnFamilies().length);

    admin.disableTable(hdt.getTableName());
    admin.deleteTable(hdt.getTableName());
    HTU.getHBaseCluster().startMaster();
    admin.close();
  }

  @SuppressWarnings("deprecation")
  @Test (timeout=300000)
  public void testReplicaAndReplication() throws Exception {
    HTableDescriptor hdt = HTU.createTableDescriptor("testReplicaAndReplication");
    hdt.setRegionReplication(NB_SERVERS);

    HColumnDescriptor fam = new HColumnDescriptor(row);
    fam.setScope(HConstants.REPLICATION_SCOPE_GLOBAL);
    hdt.addFamily(fam);

    hdt.addCoprocessor(SlowMeCopro.class.getName());
    HTU.getHBaseAdmin().createTable(hdt, HBaseTestingUtility.KEYS_FOR_HBA_CREATE_TABLE);

    Configuration conf2 = HBaseConfiguration.create(HTU.getConfiguration());
    conf2.set(HConstants.HBASE_CLIENT_INSTANCE_ID, String.valueOf(-1));
    conf2.set(HConstants.ZOOKEEPER_ZNODE_PARENT, "/2");
    MiniZooKeeperCluster miniZK = HTU.getZkCluster();

    HTU2 = new HBaseTestingUtility(conf2);
    HTU2.setZkCluster(miniZK);
    HTU2.startMiniCluster(NB_SERVERS);
    LOG.info("Setup second Zk");
    HTU2.getHBaseAdmin().createTable(hdt, HBaseTestingUtility.KEYS_FOR_HBA_CREATE_TABLE);

    ReplicationAdmin admin = new ReplicationAdmin(HTU.getConfiguration());
    ReplicationPeerConfig rpc = new ReplicationPeerConfig();
    rpc.setClusterKey(HTU2.getClusterKey());
    admin.addPeer("2", rpc);
    admin.close();

    Put p = new Put(row);
    p.add(row, row, row);
    final Table table = new HTable(HTU.getConfiguration(), hdt.getTableName());
    table.put(p);

    HTU.getHBaseAdmin().flush(table.getName());
    LOG.info("Put & flush done on the first cluster. Now doing a get on the same cluster.");

    Waiter.waitFor(HTU.getConfiguration(), 1000, new Waiter.Predicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
        try {
          SlowMeCopro.cdl.set(new CountDownLatch(1));
          Get g = new Get(row);
          g.setConsistency(Consistency.TIMELINE);
          Result r = table.get(g);
          Assert.assertTrue(r.isStale());
          return  !r.isEmpty();
        } finally {
          SlowMeCopro.cdl.get().countDown();
          SlowMeCopro.sleepTime.set(0);
        }
      }});
    table.close();
    LOG.info("stale get on the first cluster done. Now for the second.");

    final Table table2 = new HTable(HTU.getConfiguration(), hdt.getTableName());
    Waiter.waitFor(HTU.getConfiguration(), 1000, new Waiter.Predicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
        try {
          SlowMeCopro.cdl.set(new CountDownLatch(1));
          Get g = new Get(row);
          g.setConsistency(Consistency.TIMELINE);
          Result r = table2.get(g);
          Assert.assertTrue(r.isStale());
          return  !r.isEmpty();
        } finally {
          SlowMeCopro.cdl.get().countDown();
          SlowMeCopro.sleepTime.set(0);
        }
      }});
    table2.close();

    HTU.getHBaseAdmin().disableTable(hdt.getTableName());
    HTU.deleteTable(hdt.getTableName());

    HTU2.getHBaseAdmin().disableTable(hdt.getTableName());
    HTU2.deleteTable(hdt.getTableName());

    // We shutdown HTU2 minicluster later, in afterClass(), as shutting down
    // the minicluster has negative impact of deleting all HConnections in JVM.
  }

  @Test (timeout=30000)
  public void testBulkLoad() throws IOException {
    // Create table then get the single region for our new table.
    LOG.debug("Creating test table");
    HTableDescriptor hdt = HTU.createTableDescriptor("testBulkLoad");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(SlowMeCopro.class.getName());
    Table table = HTU.createTable(hdt, new byte[][]{f}, HTU.getConfiguration());

    // create hfiles to load.
    LOG.debug("Creating test data");
    Path dir = HTU.getDataTestDirOnTestFS("testBulkLoad");
    final int numRows = 10;
    final byte[] qual = Bytes.toBytes("qual");
    final byte[] val  = Bytes.toBytes("val");
    final List<Pair<byte[], String>> famPaths = new ArrayList<Pair<byte[], String>>();
    for (HColumnDescriptor col : hdt.getColumnFamilies()) {
      Path hfile = new Path(dir, col.getNameAsString());
      TestHRegionServerBulkLoad.createHFile(HTU.getTestFileSystem(), hfile, col.getName(),
        qual, val, numRows);
      famPaths.add(new Pair<byte[], String>(col.getName(), hfile.toString()));
    }

    // bulk load HFiles
    LOG.debug("Loading test data");
    @SuppressWarnings("deprecation")
    final HConnection conn = HTU.getHBaseAdmin().getConnection();
    RegionServerCallable<Void> callable = new RegionServerCallable<Void>(
      conn, hdt.getTableName(), TestHRegionServerBulkLoad.rowkey(0)) {
        @Override
        public Void call(int timeout) throws Exception {
          LOG.debug("Going to connect to server " + getLocation() + " for row "
            + Bytes.toStringBinary(getRow()));
          byte[] regionName = getLocation().getRegionInfo().getRegionName();
          BulkLoadHFileRequest request =
            RequestConverter.buildBulkLoadHFileRequest(famPaths, regionName, true);
          getStub().bulkLoadHFile(null, request);
          return null;
        }
      };
    RpcRetryingCallerFactory factory = new RpcRetryingCallerFactory(HTU.getConfiguration());
    RpcRetryingCaller<Void> caller = factory.<Void> newCaller();
    caller.callWithRetries(callable, 10000);

    // verify we can read them from the primary
    LOG.debug("Verifying data load");
    for (int i = 0; i < numRows; i++) {
      byte[] row = TestHRegionServerBulkLoad.rowkey(i);
      Get g = new Get(row);
      Result r = table.get(g);
      Assert.assertFalse(r.isStale());
    }

    // verify we can read them from the replica
    LOG.debug("Verifying replica queries");
    try {
      SlowMeCopro.cdl.set(new CountDownLatch(1));
      for (int i = 0; i < numRows; i++) {
        byte[] row = TestHRegionServerBulkLoad.rowkey(i);
        Get g = new Get(row);
        g.setConsistency(Consistency.TIMELINE);
        Result r = table.get(g);
        Assert.assertTrue(r.isStale());
      }
      SlowMeCopro.cdl.get().countDown();
    } finally {
      SlowMeCopro.cdl.get().countDown();
      SlowMeCopro.sleepTime.set(0);
    }

    HTU.getHBaseAdmin().disableTable(hdt.getTableName());
    HTU.deleteTable(hdt.getTableName());
  }

  @Test
  public void testReplicaGetWithPrimaryDown() throws IOException {
    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testCreateDeleteTable");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(RegionServerStoppedCopro.class.getName());
    try {
      // Retry less so it can fail faster
      HTU.getConfiguration().setInt("hbase.client.retries.number", 1);

      Table table = HTU.createTable(hdt, new byte[][] { f }, null);

      Put p = new Put(row);
      p.addColumn(f, row, row);
      table.put(p);

      // Flush so it can be picked by the replica refresher thread
      HTU.flush(table.getName());

      // Sleep for some time until data is picked up by replicas
      try {
        Thread.sleep(2 * REFRESH_PERIOD);
      } catch (InterruptedException e1) {
        LOG.error(e1);
      }

      // But if we ask for stale we will get it
      Get g = new Get(row);
      g.setConsistency(Consistency.TIMELINE);
      Result r = table.get(g);
      Assert.assertTrue(r.isStale());
    } finally {
      HTU.getConfiguration().unset("hbase.client.retries.number");
      HTU.getHBaseAdmin().disableTable(hdt.getTableName());
      HTU.deleteTable(hdt.getTableName());
    }
  }

  @Test
  public void testReplicaScanWithPrimaryDown() throws IOException {
    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testCreateDeleteTable");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(RegionServerStoppedCopro.class.getName());

    try {
      // Retry less so it can fail faster
      HTU.getConfiguration().setInt("hbase.client.retries.number", 1);

      Table table = HTU.createTable(hdt, new byte[][] { f }, null);

      Put p = new Put(row);
      p.addColumn(f, row, row);
      table.put(p);

      // Flush so it can be picked by the replica refresher thread
      HTU.flush(table.getName());

      // Sleep for some time until data is picked up by replicas
      try {
        Thread.sleep(2 * REFRESH_PERIOD);
      } catch (InterruptedException e1) {
        LOG.error(e1);
      }

      // But if we ask for stale we will get it
      // Instantiating the Scan class
      Scan scan = new Scan();

      // Scanning the required columns
      scan.addFamily(f);
      scan.setConsistency(Consistency.TIMELINE);

      // Getting the scan result
      ResultScanner scanner = table.getScanner(scan);

      Result r = scanner.next();

      Assert.assertTrue(r.isStale());
    } finally {
      HTU.getConfiguration().unset("hbase.client.retries.number");
      HTU.getHBaseAdmin().disableTable(hdt.getTableName());
      HTU.deleteTable(hdt.getTableName());
    }
  }

  // This test is to test when hbase.client.metaReplicaCallTimeout.scan is configured, meta table
  // scan will always get the result from primary meta region as long as the result is returned
  // within configured hbase.client.metaReplicaCallTimeout.scan from primary meta region.
  @Test
  public void testGetRegionLocationFromPrimaryMetaRegion() throws IOException, InterruptedException {
    HTU.getHBaseAdmin().setBalancerRunning(false, true);

    ((ConnectionManager.HConnectionImplementation) HTU.getHBaseAdmin().getConnection()).
        setUseMetaReplicas(true);

    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testGetRegionLocationFromPrimaryMetaRegion");
    hdt.setRegionReplication(2);
    try {

      HTU.createTable(hdt, new byte[][] { f }, null);

      RegionServerHostingPrimayMetaRegionSlowOrStopCopro.slowDownPrimaryMetaScan = true;

      // Get user table location, always get it from the primary meta replica
      RegionLocations url = ((ClusterConnection) HTU.getConnection())
          .locateRegion(hdt.getTableName(), row, false, false);

    } finally {
      RegionServerHostingPrimayMetaRegionSlowOrStopCopro.slowDownPrimaryMetaScan = false;
      ((ConnectionManager.HConnectionImplementation) HTU.getConnection().getAdmin().getConnection()).setUseMetaReplicas(false);
      HTU.getConnection().getAdmin().setBalancerRunning(true, true);
      HTU.getConnection().getAdmin().disableTable(hdt.getTableName());
      HTU.deleteTable(hdt.getTableName());
    }
  }


  // This test is to simulate the case that the meta region and the primary user region
  // are down, hbase client is able to access user replica regions and return stale data.
  // Meta replica is enabled to show the case that the meta replica region could be out of sync
  // with the primary meta region.
  @Test
  public void testReplicaGetWithPrimaryAndMetaDown() throws IOException, InterruptedException {
    HTU.getConnection().getAdmin().setBalancerRunning(false, true);

    ((ConnectionManager.HConnectionImplementation)HTU.getConnection().getAdmin().getConnection()).setUseMetaReplicas(true);

    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testReplicaGetWithPrimaryAndMetaDown");
    hdt.setRegionReplication(2);
    try {

      Table table = HTU.createTable(hdt, new byte[][] { f }, null);

      // Get Meta location
      RegionLocations mrl = ((ClusterConnection) HTU.getConnection())
          .locateRegion(TableName.META_TABLE_NAME,
              HConstants.EMPTY_START_ROW, false, false);

      // Get user table location
      RegionLocations url = ((ClusterConnection) HTU.getConnection())
          .locateRegion(hdt.getTableName(), row, false, false);

      // Make sure that user primary region is co-hosted with the meta region
      if (!url.getDefaultRegionLocation().getServerName().equals(
          mrl.getDefaultRegionLocation().getServerName())) {
        HTU.moveRegionAndWait(url.getDefaultRegionLocation().getRegionInfo(),
            mrl.getDefaultRegionLocation().getServerName());
      }

      // Make sure that the user replica region is not hosted by the same region server with
      // primary
      if (url.getRegionLocation(1).getServerName().equals(mrl.getDefaultRegionLocation()
          .getServerName())) {
        HTU.moveRegionAndWait(url.getRegionLocation(1).getRegionInfo(),
            url.getDefaultRegionLocation().getServerName());
      }

      // Wait until the meta table is updated with new location info
      while (true) {
        mrl = ((ClusterConnection) HTU.getConnection())
            .locateRegion(TableName.META_TABLE_NAME, HConstants.EMPTY_START_ROW, false, false);

        // Get user table location
        url = ((ClusterConnection) HTU.getConnection())
            .locateRegion(hdt.getTableName(), row, false, true);

        LOG.info("meta locations " + mrl);
        LOG.info("table locations " + url);
        ServerName a = url.getDefaultRegionLocation().getServerName();
        ServerName b = mrl.getDefaultRegionLocation().getServerName();
        if(a.equals(b)) {
          break;
        } else {
          LOG.info("Waiting for new region info to be updated in meta table");
          Thread.sleep(100);
        }
      }

      Put p = new Put(row);
      p.addColumn(f, row, row);
      table.put(p);

      // Flush so it can be picked by the replica refresher thread
      HTU.flush(table.getName());

      // Sleep for some time until data is picked up by replicas
      try {
        Thread.sleep(2 * REFRESH_PERIOD);
      } catch (InterruptedException e1) {
        LOG.error(e1);
      }

      // Simulating the RS down
      RegionServerHostingPrimayMetaRegionSlowOrStopCopro.throwException = true;

      // The first Get is supposed to succeed
      Get g = new Get(row);
      g.setConsistency(Consistency.TIMELINE);
      Result r = table.get(g);
      Assert.assertTrue(r.isStale());

      // The second Get will succeed as well
      r = table.get(g);
      Assert.assertTrue(r.isStale());
  
    } finally {
      ((ConnectionManager.HConnectionImplementation)HTU.getConnection().getAdmin().getConnection()).setUseMetaReplicas(false);
      RegionServerHostingPrimayMetaRegionSlowOrStopCopro.throwException = false;
      HTU.getConnection().getAdmin().setBalancerRunning(true, true);
      HTU.getConnection().getAdmin().disableTable(hdt.getTableName());
      HTU.deleteTable(hdt.getTableName());
    }
  }

  @Test
  public void testReplicaGetWithRpcClientImpl() throws IOException {
    HTU.getConfiguration().setBoolean("hbase.ipc.client.specificThreadForWriting", true);
    HTU.getConfiguration().set("hbase.rpc.client.impl", "org.apache.hadoop.hbase.ipc.RpcClientImpl");
    // Create table then get the single region for our new table.
    HTableDescriptor hdt = HTU.createTableDescriptor("testReplicaGetWithRpcClientImpl");
    hdt.setRegionReplication(NB_SERVERS);
    hdt.addCoprocessor(SlowMeCopro.class.getName());

    try {
      Table table = HTU.createTable(hdt, new byte[][] { f }, null);

      Put p = new Put(row);
      p.addColumn(f, row, row);
      table.put(p);

      // Flush so it can be picked by the replica refresher thread
      HTU.flush(table.getName());

      // Sleep for some time until data is picked up by replicas
      try {
        Thread.sleep(2 * REFRESH_PERIOD);
      } catch (InterruptedException e1) {
        LOG.error(e1);
      }

      try {
        // Create the new connection so new config can kick in
        Connection connection = ConnectionFactory.createConnection(HTU.getConfiguration());
        Table t = connection.getTable(hdt.getTableName());

        // But if we ask for stale we will get it
        SlowMeCopro.cdl.set(new CountDownLatch(1));
        Get g = new Get(row);
        g.setConsistency(Consistency.TIMELINE);
        Result r = t.get(g);
        Assert.assertTrue(r.isStale());
        SlowMeCopro.cdl.get().countDown();
      } finally {
        SlowMeCopro.cdl.get().countDown();
        SlowMeCopro.sleepTime.set(0);
      }
    } finally {
      HTU.getConfiguration().unset("hbase.ipc.client.specificThreadForWriting");
      HTU.getConfiguration().unset("hbase.rpc.client.impl");
      HTU.getHBaseAdmin().disableTable(hdt.getTableName());
      HTU.deleteTable(hdt.getTableName());
    }
  }
}
