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

package org.apache.hadoop.fs.azurebfs.services;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.VisibleForTesting;
import org.apache.hadoop.fs.azurebfs.AbfsStatistic;
import org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations;

/**
 * Throttles Azure Blob File System read and write operations to achieve maximum
 * throughput by minimizing errors.  The errors occur when the account ingress
 * or egress limits are exceeded and the server-side throttles requests.
 * Server-side throttling causes the retry policy to be used, but the retry
 * policy sleeps for long periods of time causing the total ingress or egress
 * throughput to be as much as 35% lower than optimal.  The retry policy is also
 * after the fact, in that it applies after a request fails.  On the other hand,
 * the client-side throttling implemented here happens before requests are made
 * and sleeps just enough to minimize errors, allowing optimal ingress and/or
 * egress throughput.
 */
public final class AbfsClientThrottlingIntercept {
  private static final Logger LOG = LoggerFactory.getLogger(
      AbfsClientThrottlingIntercept.class);
  private static final String RANGE_PREFIX = "bytes=";
  private static AbfsClientThrottlingIntercept singleton = null;
  private AbfsClientThrottlingAnalyzer readThrottler = null;
  private AbfsClientThrottlingAnalyzer writeThrottler = null;
  private static boolean isAutoThrottlingEnabled = false;

  private Map<String, AbfsClientFileThrottlingAnalyzer>
      fileThrottlingAnalyzerMap = new HashMap<>();

  // Hide default constructor
  private AbfsClientThrottlingIntercept() {
    readThrottler = new AbfsClientThrottlingAnalyzer("read");
    writeThrottler = new AbfsClientThrottlingAnalyzer("write");
  }

  public static synchronized void initializeSingleton(boolean enableAutoThrottling) {
    if (!enableAutoThrottling) {
      return;
    }
    if (singleton == null) {
      singleton = new AbfsClientThrottlingIntercept();
      isAutoThrottlingEnabled = true;
      LOG.debug("Client-side throttling is enabled for the ABFS file system.");
    }
  }

  static void updateMetrics(AbfsRestOperationType operationType,
                            AbfsHttpOperation abfsHttpOperation, String path) {
    if (!isAutoThrottlingEnabled || abfsHttpOperation == null) {
      return;
    }

    final int status = abfsHttpOperation.getStatusCode();
    long contentLength = 0;
    // If the socket is terminated prior to receiving a response, the HTTP
    // status may be 0 or -1.  A status less than 200 or greater than or equal
    // to 500 is considered an error.
    boolean isFailedOperation = (status < HttpURLConnection.HTTP_OK
        || status >= HttpURLConnection.HTTP_INTERNAL_ERROR);

    switch (operationType) {
      case Append:
        contentLength = abfsHttpOperation.getBytesSent();
        if (contentLength > 0) {
          singleton.writeThrottler.addBytesTransferred(contentLength,
              isFailedOperation);
        }
        break;
      case ReadFile:
        String range = abfsHttpOperation.getConnection().getRequestProperty(HttpHeaderConfigurations.RANGE);
        long contentLengthRequested = getContentLengthIfKnown(range);
        long bytesToBeAddedInMetric = contentLengthRequested;

        long contentLengthReceived = abfsHttpOperation.getBytesReceived();
        if (status == HttpURLConnection.HTTP_PARTIAL
            && contentLengthRequested > contentLengthReceived) {
          /*
           * In case of server response status == 206 (partial content) and the
           * contentLength received is lesser than the requested length, we have
           * to take it as a throttling case and hence we need to add the remaining
           * bytes (contentLengthReceived - contentLength) in failure data-point.
           * */
          bytesToBeAddedInMetric = contentLengthRequested - contentLengthReceived;
          isFailedOperation = true;
          singleton.fileThrottlingAnalyzerMap.get(path).addBytesTransferred(bytesToBeAddedInMetric, true);
        }
        if (bytesToBeAddedInMetric > 0) {
          singleton.readThrottler.addBytesTransferred(
              bytesToBeAddedInMetric,
              isFailedOperation);
        }
        break;
      default:
        break;
    }
  }

  /**
   * Called before the request is sent.  Client-side throttling
   * uses this to suspend the request, if necessary, to minimize errors and
   * maximize throughput.
   */
  static void sendingRequest(AbfsRestOperationType operationType,
      AbfsCounters abfsCounters, String path) {
    if (!isAutoThrottlingEnabled) {
      return;
    }

    switch (operationType) {
      case ReadFile:
        if (singleton.readThrottler.suspendIfNecessary()
            && abfsCounters != null) {
          abfsCounters.incrementCounter(AbfsStatistic.READ_THROTTLES, 1);
        }
        if(AbfsClientFileThrottlingAnalyzer.getAnalyzer(path) == null) {
          singleton.fileThrottlingAnalyzerMap.put(path, new AbfsClientFileThrottlingAnalyzer("read", path));
        }
        singleton.fileThrottlingAnalyzerMap.get(path).updateLMT();
        break;
      case Append:
        if (singleton.writeThrottler.suspendIfNecessary()
            && abfsCounters != null) {
          abfsCounters.incrementCounter(AbfsStatistic.WRITE_THROTTLES, 1);
        }
        break;
      default:
        break;
    }
  }

  private static long getContentLengthIfKnown(String range) {
    long contentLength = 0;
    // Format is "bytes=%d-%d"
    if (range != null && range.startsWith(RANGE_PREFIX)) {
      String[] offsets = range.substring(RANGE_PREFIX.length()).split("-");
      if (offsets.length == 2) {
        contentLength = Long.parseLong(offsets[1]) - Long.parseLong(offsets[0])
                + 1;
      }
    }
    return contentLength;
  }

  @VisibleForTesting
  void setReadThrottler(final AbfsClientThrottlingAnalyzer readThrottler) {
    this.readThrottler = readThrottler;
  }

  @VisibleForTesting
  AbfsClientThrottlingAnalyzer getReadThrottler() {
    return readThrottler;
  }

  @VisibleForTesting
  static AbfsClientThrottlingIntercept getSingleton() {
    return singleton;
  }
}
