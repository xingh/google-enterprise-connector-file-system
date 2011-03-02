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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Queue of {@link CheckpointAndChange} objects to be processed by the
 * {@link FileTraversalManager}. Objects are added to the queue from a
 * {@link ChangeSource} and assigned a {@link FileConnectorCheckpoint}. The
 * client accesses objects by calling {@link #resume}.
 * To facilitate retry of processing for objects in the queue
 * {@link CheckpointAndChange} objects remain until the client
 * indicates they have completed processing by calling {@link #resume}
 * with the objects checkpoint or a later objects checkpoint.
 */
class CheckpointAndChangeQueue {
  public static final int DEFAULT_MAXIMUM_QUEUE_SIZE = 500;

  private static final Logger LOG = Logger.getLogger(CheckpointAndChangeQueue.class.getName());

  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final String SENTINAL = "SENTINAL";
  private static final String RECOVERY_FILE_PREFIX = "recovery.";
  private static final String QUEUE_JSON_TAG = "Q";
  private static final String MONITOR_STATE_JSON_TAG = "MON";

  private final AtomicInteger maximumQueueSize = new AtomicInteger(DEFAULT_MAXIMUM_QUEUE_SIZE);
  private final List<CheckpointAndChange> checkpointAndChangeList;
  private final ChangeSource changeSource;
  private volatile FileConnectorCheckpoint lastCheckpoint;
  private final File persistDir;  // place to persist enqueued values
  private MonitorRestartState monitorPoints = new MonitorRestartState();

  /** Convenient way to log some IOException instances. */
  private static class LoggingIoException extends IOException {
    LoggingIoException(String msg) {
      super(msg);
      LOG.severe(msg);
    }
  }

  /** Provides entire contents of File as a single String. */
  private static String readEntireUtf8File(File file) throws IOException {
    BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
    try {
      byte bytes[] = new byte[(int) file.length()];
      bis.read(bytes);
      return new String(bytes, UTF_8);
    } finally {
      bis.close();
    }
  }

  /**
   * Returns the passed in value if it is not null. If the passed in
   * value is null this returns a valid initial checkpoint in String form.
   */
  static String initializeCheckpointStringIfNull(String checkpointString) {
    if (checkpointString == null) {
      return FileConnectorCheckpoint.newFirst().toString();
    } else {
      return checkpointString;
    }
  }

  private static FileConnectorCheckpoint constructLastCheckpoint(String checkpointString) {
    if (checkpointString == null) {
      return FileConnectorCheckpoint.newFirst();
    } else {
      return FileConnectorCheckpoint.fromJsonString(checkpointString);
    }
  }

  CheckpointAndChangeQueue(ChangeSource changeSource, File persistDir) {
    this.changeSource = changeSource;
    this.checkpointAndChangeList
        = Collections.synchronizedList(new ArrayList<CheckpointAndChange>(maximumQueueSize.get()));
    this.persistDir = persistDir;
    ensurePersistDirExists();
  }

  void ensurePersistDirExists() {
    if (!persistDir.exists()) {
      boolean made = persistDir.mkdirs();
      if (!made) {
        throw new IllegalStateException("Cannot create: " + persistDir.getAbsolutePath());
      }
    } else if (!persistDir.isDirectory()) {
      throw new IllegalStateException("Not a directory: " + persistDir.getAbsolutePath());
    }
  }

  /** Keeps checkpoint information for all known FileSystemMonitors. */
  private static class MonitorRestartState {
    /* Maps monitor's name onto its restart MonitorCheckpoint. */
    HashMap<String, MonitorCheckpoint> points;

    MonitorRestartState() {
      points = new HashMap<String,MonitorCheckpoint>();
    }

    MonitorRestartState(JSONObject persisted) throws JSONException {
      this();
      if (persisted.length() > 0) {
        for (String key : JSONObject.getNames(persisted) ) {
          JSONObject value = persisted.getJSONObject(key);
          MonitorCheckpoint monPoint = new MonitorCheckpoint(value);
          points.put(monPoint.getMonitorName(), monPoint);
        }
      }
    }

    JSONObject getJson() throws JSONException {
      JSONObject result = new JSONObject();
      for (MonitorCheckpoint monPoint : points.values()) {
        result.put(monPoint.getMonitorName(), monPoint.getJson());
      }
      return result;
    }

    void updateOnGuaranteed(List<CheckpointAndChange> checkpointAndChangeList) {
      for (CheckpointAndChange guaranteed : checkpointAndChangeList) {
        Change change = guaranteed.getChange();
        MonitorCheckpoint monitorCheckpoint = change.getMonitorCheckpoint();
        String monitorName = monitorCheckpoint.getMonitorName();
        points.put(monitorName, monitorCheckpoint);
      }
    }
  }

  /** A File that has some of the recovery logic. */
  private class RecoveryFile extends File {
    long nanoTimestamp;

    String parseOutTimeString() throws IOException {
      try {
        String basename = getName();
        if (basename.startsWith(RECOVERY_FILE_PREFIX)) {
          return basename.substring(RECOVERY_FILE_PREFIX.length());
        } else {
          throw new LoggingIoException("Invalid recovery filename: " + getAbsolutePath());
        }
      } catch(IndexOutOfBoundsException e) {
        throw new LoggingIoException("Invalid recovery filename: " + getAbsolutePath());
      }
    }

    long getTimestamp() throws IOException {
      try {
        return Long.parseLong(parseOutTimeString());
      } catch(NumberFormatException e) {
        throw new LoggingIoException("Invalid recovery filename: " + getAbsolutePath());
      }
    }

    RecoveryFile() throws IOException {
      super(persistDir, RECOVERY_FILE_PREFIX + System.nanoTime());
      nanoTimestamp = getTimestamp();
    }

    RecoveryFile(String absolutePath) throws IOException {
      super(absolutePath);
      nanoTimestamp =  getTimestamp();
    }

    boolean isOlder(RecoveryFile other) {
      return this.nanoTimestamp < other.nanoTimestamp;
    }

    /** A delete method that logs failures. */
    public void logOnFailDelete() {
      boolean deleted = super.delete();
      if (!deleted) {
        LOG.severe("Failed to delete: " + getAbsolutePath());
      }
    }

    // TODO(pjo): Move more recovery logic into this class.
  }

  private JSONObject readPersistedState(RecoveryFile file) throws IOException {
    // TODO(pjo): Move this method into RecoveryFile.
    String contents = readEntireUtf8File(file);
    if (!contents.endsWith(SENTINAL)) {
      throw new IOException("Read invalid recovery file.");
    } else {
      contents = contents.substring(0, contents.length() - SENTINAL.length());
      try {
        JSONObject json = new JSONObject(contents);
        return json;
      } catch (JSONException e) {
        throw new IOException("Failed reading persisted JSON queue.", e);
      }
    }
  }

  /** @return true if argument exists, has sentinal and readable JSON queue */
  private boolean isComplete(RecoveryFile recoveryFile) {
    // TODO(pjo): Move this method into RecoveryFile.
    try {
      readPersistedState(recoveryFile);
      return true;
    } catch(IOException e) {
      return false;
    }
  }

  private void writeRecoveryState() throws IOException {
    // TODO(pjo): Move this method into RecoveryFile.
    File recoveryFile = new RecoveryFile();
    FileOutputStream outStream = new FileOutputStream(recoveryFile);
    Writer writer = new OutputStreamWriter(outStream, UTF_8);
    try {
      JSONObject queueJson = getJson();
      try {
        queueJson.write(writer);
      } catch (JSONException e) {
        throw new IOException("Failed writing recovery file.", e);
      }
      writer.write(SENTINAL);
      writer.flush();
      outStream.getFD().sync();
    } finally {
      writer.close();
    }
  }

  private void loadUpFromRecoveryState(RecoveryFile file) throws IOException {
    // TODO(pjo): Move this method into RecoveryFile.
    JSONObject json = readPersistedState(file);
    try {
      JSONArray jsonQueue = json.getJSONArray(QUEUE_JSON_TAG);
      for (int i = 0; i < jsonQueue.length(); i++) {
        JSONObject chnch = jsonQueue.getJSONObject(i);
        checkpointAndChangeList.add(new CheckpointAndChange(chnch));
      }
      JSONObject jsonMonPoints = json.getJSONObject(MONITOR_STATE_JSON_TAG);
      monitorPoints = new MonitorRestartState(jsonMonPoints);
    } catch (JSONException e) {
      throw new IOException("Failed reading persisted JSON queue.", e);
    }
  }

  private RecoveryFile[] allRecoveryFiles() throws IOException {
    // TODO(pjo): Facilitate holding onto returned value to reduce invocations.
    File files[] = persistDir.listFiles();
    ArrayList<RecoveryFile> recoveryFiles = new ArrayList<RecoveryFile>();
    for (int i = 0; i < files.length; i++) {
      recoveryFiles.add(new RecoveryFile(files[i].getAbsolutePath()));
    }
    return recoveryFiles.toArray(new RecoveryFile[0]);
  }

  /**
   * Initialize to start processing from after the passed in checkpoint
   * or from the beginning if the passed in checkpoint is null.  Part of
   * making FileSystemMonitorManager go from "cold" to "warm".
   */
  synchronized void start(String checkpointString) throws IOException {
    LOG.info("Starting CheckpointAndChangeQueue from " + checkpointString);
    ensurePersistDirExists();
    checkpointAndChangeList.clear();
    lastCheckpoint = constructLastCheckpoint(checkpointString);
    if (null == checkpointString) {
      removeAllRecoveryState();
    } else {
      RecoveryFile current = removeExcessRecoveryState();
      loadUpFromRecoveryState(current);
      monitorPoints.updateOnGuaranteed(checkpointAndChangeList);
      // TODO: Figure out if the above call is needed.
    }
  }

  /**
   * Returns an {@link Iterator} for currently available
   * {@link CheckpointAndChange} objects that occur after the passed in
   * checkpoint. The {@link String} form of a {@link FileConnectorCheckpoint}
   * passed in is produced by calling
   * {@link FileConnectorCheckpoint#toString()}. As a side effect, Objects
   * up to and including the object with the passed in checkpoint are removed
   * from this queue.
   *
   * @param checkpointString null means return all {@link CheckpointAndChange}
   *        objects and a non null value means to return
   *        {@link CheckpointAndChange} objects with checkpoints after the
   *        passed in value.
   * @throws IOException if error occurs while manipulating recovery state
   */
  synchronized List<CheckpointAndChange> resume(String checkpointString) throws IOException {
    removeCompletedChanges(checkpointString);
    loadUpFromChangeSource();
    try {
      writeRecoveryState();
      monitorPoints.updateOnGuaranteed(checkpointAndChangeList);
    } finally {
      // TODO: Enahnce with mechanism that remembers
      // information about recovery files to avoid re-reading.
      removeExcessRecoveryState();
    }
    return getList();
  }

  synchronized void setMaximumQueueSize(int maximumQueueSize) {
    this.maximumQueueSize.set(maximumQueueSize);
  }

  private List<CheckpointAndChange> getList() {
    return Collections.unmodifiableList(checkpointAndChangeList);
  }

  synchronized Map<String, MonitorCheckpoint> getMonitorRestartPoints() {
    return new HashMap<String, MonitorCheckpoint>(monitorPoints.points);
  }

  private JSONArray getQueueAsJsonArray() {
    JSONArray jsonQueue = new JSONArray();
    for (CheckpointAndChange guaranteed : checkpointAndChangeList) {
      JSONObject encodedGuaranteedChange = guaranteed.getJson();
      jsonQueue.put(encodedGuaranteedChange);
    }
    return jsonQueue;
  }

  private JSONObject getJson() {
    JSONObject result = new JSONObject();
    try {
      result.put(QUEUE_JSON_TAG, getQueueAsJsonArray());
      result.put(MONITOR_STATE_JSON_TAG, monitorPoints.getJson());
      return result;
    } catch (JSONException e) {
      // Should never happen.
      throw new RuntimeException("internal error: failed to create JSON", e);
    }
  }

  private void removeCompletedChanges(String checkpointString) {
    if (checkpointString == null) {
      return;
    } else {
      FileConnectorCheckpoint checkpoint
          = FileConnectorCheckpoint.fromJsonString(checkpointString);
      Iterator<CheckpointAndChange> iterator = checkpointAndChangeList.iterator();
      boolean keepRemoving = true;
      while (keepRemoving && iterator.hasNext()) {
        CheckpointAndChange current = iterator.next();
        boolean pending = current.getCheckpoint().compareTo(checkpoint) > 0;
        if (pending) {
          // Current Change is not completed; first pending; stop removing.
          keepRemoving = false;
        } else {
          // Has been sent.  Remove it.
          iterator.remove();
          // Monitors can consider these changes sent too.
          // monitorPoints.updateOnCompleted(current.getChange());
        }
      }
    }
  }

  private void loadUpFromChangeSource() {
    int max = maximumQueueSize.get();
    if (checkpointAndChangeList.size() < max) {
      lastCheckpoint = lastCheckpoint.nextMajor();
    }

    while (checkpointAndChangeList.size() < max) {
      Change newChange = changeSource.getNextChange();
      if (newChange == null) {
        break;
      }
      lastCheckpoint = lastCheckpoint.next();
      checkpointAndChangeList.add(new CheckpointAndChange(lastCheckpoint, newChange));
    }
  }

  /**
   * Leaves at most one recovery file in persistDir and returns it.
   * It is an error to call this method when there is no complete
   * recovery file.  It is also an error to call this method when
   * the number of files is more than 2.
   *
   * @return the current recovery state file
   * @throws IOException if number of recovery files is wrong
   *         or no good recovery file exists
   **/
  private RecoveryFile removeExcessRecoveryState() throws IOException {
    RecoveryFile all[] = allRecoveryFiles();
    switch (all.length) {
      case 0:
        throw new LoggingIoException("No recovery state to reduce to.");
      case 1:
        RecoveryFile rf = all[0];
        if (isComplete(rf)) {
          return rf;
        } else {
          rf.logOnFailDelete();
          throw new LoggingIoException("Found incomplete recovery file: " + rf.getAbsolutePath());
        }
      case 2:
        RecoveryFile one = all[0];
        RecoveryFile two = all[1];
        boolean oneComplete = isComplete(one);
        boolean twoComplete = isComplete(two);
        if (oneComplete && twoComplete) {
          if (one.isOlder(two)) {
            one.logOnFailDelete();
            return two;
          } else {
            two.logOnFailDelete();
            return one;
          }
        } else if (oneComplete && !twoComplete) {
          two.logOnFailDelete();
          return one;
        } else if (!oneComplete && twoComplete) {
          one.logOnFailDelete();
          return two;
        } else if (!oneComplete && !twoComplete) {
          one.logOnFailDelete();
          two.logOnFailDelete();
          throw new LoggingIoException("Have two broken recovery files.");
        }
        break;
      default:
        throw new LoggingIoException("Found too many recovery files: " + Arrays.asList(all));
    }
    throw new IllegalStateException("Failed reducing recovery state.");
  }

  /** Deletes all files in persistDir. */
  private void removeAllRecoveryState() throws IOException {
    File all[] = allRecoveryFiles();
    ArrayList<String> failedToDelete = new ArrayList<String>();
    for (int i = 0; i < all.length; i++) {
      boolean deleted = all[i].delete();
      if (!deleted) {
        failedToDelete.add(all[i].getAbsolutePath());
      }
    }
    if (0 != failedToDelete.size()) {
      throw new IOException("Failed to delete: " + failedToDelete);
    }
  }

  void clean() {
    try {
      removeAllRecoveryState();
    } catch (IOException e) {
      LOG.severe("Failure: " + e);
    }

    if (!persistDir.delete()) {
      String errmsg = "Failed to delete: " + persistDir.getAbsolutePath();
      LOG.severe(errmsg);
    }
  }
}