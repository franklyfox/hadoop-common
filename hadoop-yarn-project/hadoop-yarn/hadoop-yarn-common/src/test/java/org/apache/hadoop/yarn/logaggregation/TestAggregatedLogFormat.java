/**
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

package org.apache.hadoop.yarn.logaggregation;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogKey;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogReader;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogValue;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogWriter;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class TestAggregatedLogFormat {

  private static final File testWorkDir = new File("target",
      "TestAggregatedLogFormat");
  private static final Configuration conf = new Configuration();
  private static final FileSystem fs;
  private static final char filler = 'x';
  private static final Log LOG = LogFactory
      .getLog(TestAggregatedLogFormat.class);

  static {
    try {
      fs = FileSystem.get(conf);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Before
  @After
  public void cleanupTestDir() throws Exception {
    Path workDirPath = new Path(testWorkDir.getAbsolutePath());
    LOG.info("Cleaning test directory [" + workDirPath + "]");
    fs.delete(workDirPath, true);
  }

  //Verify the output generated by readAContainerLogs(DataInputStream, Writer)
  @Test
  public void testReadAcontainerLogs1() throws Exception {
    Configuration conf = new Configuration();
    File workDir = new File(testWorkDir, "testReadAcontainerLogs1");
    Path remoteAppLogFile =
        new Path(workDir.getAbsolutePath(), "aggregatedLogFile");
    Path srcFileRoot = new Path(workDir.getAbsolutePath(), "srcFiles");
    ContainerId testContainerId = BuilderUtils.newContainerId(1, 1, 1, 1);
    Path t =
        new Path(srcFileRoot, testContainerId.getApplicationAttemptId()
            .getApplicationId().toString());
    Path srcFilePath = new Path(t, testContainerId.toString());

    int numChars = 80000;

    writeSrcFile(srcFilePath, "stdout", numChars);

    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    LogWriter logWriter = new LogWriter(conf, remoteAppLogFile, ugi);

    LogKey logKey = new LogKey(testContainerId);
    LogValue logValue =
        new LogValue(Collections.singletonList(srcFileRoot.toString()),
            testContainerId, ugi.getShortUserName());

    logWriter.append(logKey, logValue);
    logWriter.closeWriter();

    // make sure permission are correct on the file
    FileStatus fsStatus =  fs.getFileStatus(remoteAppLogFile);
    Assert.assertEquals("permissions on log aggregation file are wrong",  
      FsPermission.createImmutable((short) 0640), fsStatus.getPermission()); 

    LogReader logReader = new LogReader(conf, remoteAppLogFile);
    LogKey rLogKey = new LogKey();
    DataInputStream dis = logReader.next(rLogKey);
    Writer writer = new StringWriter();
    LogReader.readAcontainerLogs(dis, writer);
    
    String s = writer.toString();
    int expectedLength =
        "\n\nLogType:stdout".length() + ("\nLogLength:" + numChars).length()
            + "\nLog Contents:\n".length() + numChars;
    Assert.assertTrue("LogType not matched", s.contains("LogType:stdout"));
    Assert.assertTrue("LogLength not matched", s.contains("LogLength:" + numChars));
    Assert.assertTrue("Log Contents not matched", s.contains("Log Contents"));
    
    StringBuilder sb = new StringBuilder();
    for (int i = 0 ; i < numChars ; i++) {
      sb.append(filler);
    }
    String expectedContent = sb.toString();
    Assert.assertTrue("Log content incorrect", s.contains(expectedContent));
    
    Assert.assertEquals(expectedLength, s.length());
  }

  @Test(timeout=10000)
  public void testContainerLogsFileAccess() throws IOException {
    // This test will run only if NativeIO is enabled as SecureIOUtils 
    // require it to be enabled.
    Assume.assumeTrue(NativeIO.isAvailable());
    Configuration conf = new Configuration();
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        "kerberos");
    UserGroupInformation.setConfiguration(conf);
    File workDir = new File(testWorkDir, "testContainerLogsFileAccess1");
    Path remoteAppLogFile =
        new Path(workDir.getAbsolutePath(), "aggregatedLogFile");
    Path srcFileRoot = new Path(workDir.getAbsolutePath(), "srcFiles");

    String data = "Log File content for container : ";
    // Creating files for container1. Log aggregator will try to read log files
    // with illegal user.
    ContainerId testContainerId1 = BuilderUtils.newContainerId(1, 1, 1, 1);
    Path appDir =
        new Path(srcFileRoot, testContainerId1.getApplicationAttemptId()
            .getApplicationId().toString());
    Path srcFilePath1 = new Path(appDir, testContainerId1.toString());
    String stdout = "stdout";
    String stderr = "stderr";
    writeSrcFile(srcFilePath1, stdout, data + testContainerId1.toString()
        + stdout);
    writeSrcFile(srcFilePath1, stderr, data + testContainerId1.toString()
        + stderr);

    UserGroupInformation ugi =
        UserGroupInformation.getCurrentUser();
    LogWriter logWriter = new LogWriter(conf, remoteAppLogFile, ugi);

    LogKey logKey = new LogKey(testContainerId1);
    String randomUser = "randomUser";
    LogValue logValue =
        spy(new LogValue(Collections.singletonList(srcFileRoot.toString()),
            testContainerId1, randomUser));
    
    // It is trying simulate a situation where first log file is owned by
    // different user (probably symlink) and second one by the user itself.
    when(logValue.getUser()).thenReturn(randomUser).thenReturn(
        ugi.getShortUserName());
    logWriter.append(logKey, logValue);

    logWriter.closeWriter();
    
    BufferedReader in =
        new BufferedReader(new FileReader(new File(remoteAppLogFile
            .toUri().getRawPath())));
    String line;
    StringBuffer sb = new StringBuffer("");
    while ((line = in.readLine()) != null) {
      LOG.info(line);
      sb.append(line);
    }
    line = sb.toString();
    
    String stdoutFile1 =
        StringUtils.join(
            Path.SEPARATOR,
            Arrays.asList(new String[] {
                srcFileRoot.toUri().toString(),
                testContainerId1.getApplicationAttemptId().getApplicationId()
                    .toString(), testContainerId1.toString(), stderr }));
    String message1 =
        "Owner '" + ugi.getShortUserName() + "' for path " + stdoutFile1
        + " did not match expected owner '" + randomUser + "'";
    
    String stdoutFile2 =
        StringUtils.join(
            Path.SEPARATOR,
            Arrays.asList(new String[] {
                srcFileRoot.toUri().toString(),
                testContainerId1.getApplicationAttemptId().getApplicationId()
                    .toString(), testContainerId1.toString(), stdout }));
    String message2 =
        "Owner '" + ugi.getShortUserName() + "' for path "
            + stdoutFile2 + " did not match expected owner '"
            + ugi.getShortUserName() + "'";
    
    Assert.assertTrue(line.contains(message1));
    Assert.assertFalse(line.contains(message2));
    Assert.assertFalse(line.contains(data + testContainerId1.toString()
        + stderr));
    Assert.assertTrue(line.contains(data + testContainerId1.toString()
        + stdout));
  }
  
  private void writeSrcFile(Path srcFilePath, String fileName, long length)
      throws IOException {
    OutputStreamWriter osw = getOutputStreamWriter(srcFilePath, fileName);
    int ch = filler;
    for (int i = 0; i < length; i++) {
      osw.write(ch);
    }
    osw.close();
  }
  
  private void writeSrcFile(Path srcFilePath, String fileName, String data)
      throws IOException {
    OutputStreamWriter osw = getOutputStreamWriter(srcFilePath, fileName);
    osw.write(data);
    osw.close();
  }

  private OutputStreamWriter getOutputStreamWriter(Path srcFilePath,
      String fileName) throws IOException, FileNotFoundException,
      UnsupportedEncodingException {
    File dir = new File(srcFilePath.toString());
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new IOException("Unable to create directory : " + dir);
      }
    }
    File outputFile = new File(new File(srcFilePath.toString()), fileName);
    FileOutputStream os = new FileOutputStream(outputFile);
    OutputStreamWriter osw = new OutputStreamWriter(os, "UTF8");
    return osw;
  }
}