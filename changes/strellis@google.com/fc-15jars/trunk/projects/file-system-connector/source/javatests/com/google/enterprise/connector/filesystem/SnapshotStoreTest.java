// Copyright 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.filesystem;

import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 */
public class SnapshotStoreTest extends TestCase {
  private File snapshotDir;
  private SnapshotStore store;
  private Acl acl;

  @Override
  public void setUp() throws Exception {
    TestDirectoryManager testDirectoryManager = new TestDirectoryManager(this);
    File rootDir = testDirectoryManager.makeDirectory("rootDir");
    snapshotDir = new File(rootDir, "snapshots");
    store = new SnapshotStore(new File(rootDir, "snapshots"));
    List<String> users = Arrays.asList("d1\\bob", "d1\\tom");
    List<String> groups = Arrays.asList("d1\\loosers");
    acl =  Acl.newAcl(users, groups);
  }

  /**
   * Make sure that if the store contains no snapshots, an initial empty
   * snapshot is returned.
   *
   * @throws SnapshotReaderException
   */
  public void testEmptyDir() throws SnapshotStoreException {
    SnapshotReader in = store.openMostRecentSnapshot();
    assertNull(in.read());
  }

  /**
   * Make sure that if we write a snapshot and immediately read it, the contents
   * are correct.
   *
   * @throws SnapshotStoreException
   */
  public void testWriteRead() throws Exception {
    SnapshotWriter out = store.openNewSnapshotWriter();

    SnapshotRecord before =
        new SnapshotRecord("java", "/foo/bar", SnapshotRecord.Type.DIR, 12345L, acl, "check sum",
            123456L, false);
    out.write(before);

    SnapshotReader in = store.openMostRecentSnapshot();
    SnapshotRecord after = in.read();
    assertEquals(before, after);
    assertNull(in.read());
    store.close(in, out);
  }

  /**
   * Make sure that openMostRecentSnapshot always opens the most recent
   * snapshot.
   *
   * @throws SnapshotWriterException
   * @throws SnapshotReaderException
   */
  public void testSnapshotSorting() throws Exception {
    for (int k = 0; k < 10; ++k) {
      SnapshotWriter out = store.openNewSnapshotWriter();
      String path = String.format("/foo/bar/%d", k);
      SnapshotRecord before =
          new SnapshotRecord("java", path, SnapshotRecord.Type.DIR, 12345L, acl, "check sum",
              123456L, false);
      out.write(before);

      SnapshotReader in = store.openMostRecentSnapshot();
      SnapshotRecord after = in.read();
      assertEquals(before, after);
      store.close(in, out);
    }
  }

  /**
   * Make sure that after a bunch of snapshots are created, only the last three
   * remain.
   *
   * @throws SnapshotWriterException
   */
  public void testGarbageCollection() throws Exception {
    for (int k = 0; k < 10; ++k) {
      SnapshotWriter out = store.openNewSnapshotWriter();
      long readSnapshotNum = Math.max(0, k - 1);
      MonitorCheckpoint cp = new MonitorCheckpoint("foo", readSnapshotNum, 2, 1);
      store.acceptGuarantee(cp);
      store.close(null, out);
      store.deleteOldSnapshots();
    }
    File[] contents = snapshotDir.listFiles();
    for (File f : contents) {
      if (f.isHidden()) {
        // Special ".isTestDir" marker file; ignore
        continue;
      }
      assertTrue(f.getName(), f.getName().matches("snap\\.(8|9|10)"));
    }
  }

  /**
   * Make sure that a new SnapshotStore recovers correctly from checkpoints.
   *
   * @throws IOException
   * @throws SnapshotWriterException
   * @throws SnapshotReaderException
   */
  // TODO: add more recovery tests.
  public void testRecoveryBasics() throws IOException, SnapshotStoreException {
    // Create the first snapshot with modification time 12345.
    SnapshotWriter ss1 = store.openNewSnapshotWriter();
    writeRecords(ss1, 12345);
    store.close(null ,ss1);

    // Create a second snapshot with the same files, but modification time
    // 23456.
    SnapshotWriter ss2 = store.openNewSnapshotWriter();
    writeRecords(ss2, 23456);
    store.close(null ,ss2);

    // Now pretend that the file-system monitor has scanned the first 7 records
    // from each snapshot and emitted changes for them. I.e., create a
    // checkpoint
    // as if the first 7 changes have been sent to the GSA.
    MonitorCheckpoint cp = new MonitorCheckpoint("foo", 1, 7, 7);

    SnapshotStore.stich(snapshotDir, cp);
    SnapshotStore after = new SnapshotStore(snapshotDir);
    SnapshotReader reader = after.openMostRecentSnapshot();
    assertEquals(3, reader.getSnapshotNumber());

    // Snapshot should contain the first 7 records from snapshot 2 and the rest
    // from snapshot 1.
    for (int k = 0; k < 100; ++k) {
      SnapshotRecord rec = reader.read();
      assertNotNull(rec);
      assertEquals((k < 7) ? 23456 : 12345, rec.getLastModified());
    }
    store.close(reader, null);
  }

  /**
   * Write 100 records to {@code writer} with the specified {@code lastModified}
   * time.
   *
   * @param writer
   * @param lastModified
   * @throws SnapshotWriterException
   */
  private void writeRecords(SnapshotWriter writer, long lastModified)
      throws SnapshotWriterException {
    for (int k = 0; k < 100; ++k) {
      String path = String.format("/foo/bar/%d", k);
      SnapshotRecord rec =
          new SnapshotRecord("java", path, SnapshotRecord.Type.FILE, lastModified, acl,
              "check sum", 123456L, false);
      writer.write(rec);
    }
  }

  public void testTwoWriters() throws SnapshotStoreException {
    store.openNewSnapshotWriter();
    try {
      store.openNewSnapshotWriter();
      fail("opened second writer");
    } catch (IllegalStateException expected) {
      assertEquals(expected.getMessage(), "There is already an active writer.");
    }
  }
}
