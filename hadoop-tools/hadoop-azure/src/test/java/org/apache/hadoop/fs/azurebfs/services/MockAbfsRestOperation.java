/*
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

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.hadoop.fs.azurebfs.AbfsStatistic;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsRestOperationException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureBlobFileSystemException;
import org.apache.hadoop.fs.azurebfs.contracts.services.AppendRequestParameters;
import org.apache.hadoop.fs.azurebfs.contracts.services.ReadRequestParameters;
import org.apache.hadoop.fs.azurebfs.utils.TracingContext;

public class MockAbfsRestOperation extends AbfsRestOperation {

  private int errStatusOptimizedRest = 0;
  private boolean mockRequestExceptionOptimizedRest = false;
  private boolean mockConnectionExceptionOptimizedRest = false;
  private ReadRequestParameters readRequestParameters;
  private AppendRequestParameters appendRequestParameters;

  MockAbfsRestOperation(final AbfsRestOperationType operationType,
      final AbfsClient client,
      final String method,
      final URL url,
      final List<AbfsHttpHeader> requestHeaders) {
    super(operationType, client, method, url, requestHeaders);
  }

  MockAbfsRestOperation(AbfsRestOperationType operationType,
      AbfsClient client,
      String method,
      URL url,
      List<AbfsHttpHeader> requestHeaders,
      byte[] buffer,
      int bufferOffset,
      int bufferLength,
      String sasTokenForReuse,
      ReadRequestParameters readRequestParameters,
      Callable headerUpDownCallable) {
    super(operationType, client, method, url, requestHeaders, buffer,
        bufferOffset, bufferLength, sasTokenForReuse, headerUpDownCallable);
    this.readRequestParameters = readRequestParameters;
  }

  MockAbfsRestOperation(AbfsRestOperationType operationType,
      AbfsClient client,
      String method,
      URL url,
      List<AbfsHttpHeader> requestHeaders,
      byte[] buffer,
      int bufferOffset,
      int bufferLength,
      String sasTokenForReuse,
      AppendRequestParameters appendRequestParameters) {
    super(operationType, client, method, url, requestHeaders, buffer,
        bufferOffset, bufferLength, sasTokenForReuse);
    this.appendRequestParameters = appendRequestParameters;
  }

  @Override
  protected AbfsHttpConnection getHttpOperation() throws IOException {
    if (AbfsRestOperationType.OptimizedRead.equals(getOperationType())
        || AbfsRestOperationType.OptimizedAppend.equals(getOperationType()) || AbfsRestOperationType.Append.equals(getOperationType())) {
      return new MockAbfsHttpConnection(getUrl(), getMethod(),
          getRequestHeaders(), getHeaderUpDownCallable());
    }
    return super.getHttpOperation();
  }

  protected void processResponse(AbfsHttpOperation httpOperation)
      throws IOException {
    if (AbfsRestOperationType.OptimizedRead.equals(getOperationType())
        || AbfsRestOperationType.OptimizedAppend.equals(getOperationType())) {
      signalErrorConditionToMockAbfsOptimizedRestConn(
          (MockAbfsHttpConnection) httpOperation);
    }
    httpOperation.processResponse(getBuffer(), getBufferOffset(),
        getBufferLength());
  }

  @Override
  public void execute(final TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    if (isMockConnErrorActiveForRead() || isMockConnErrorActiveForWrite()) {
      getAbfsClient().getAbfsCounters()
          .incrementCounter(AbfsStatistic.CONNECTIONS_MADE, 1);
      throw new AbfsRestOperationException(500, "500", "500", null);
    }
    super.execute(tracingContext);
  }

  private boolean isMockConnErrorActiveForRead() {
    return readRequestParameters != null &&
        readRequestParameters.isOptimizedRestConnection() &&
        mockConnectionExceptionOptimizedRest;
  }

  private boolean isMockConnErrorActiveForWrite() {
    return appendRequestParameters != null
        && appendRequestParameters.isOptimizedRestConnection()
        && mockConnectionExceptionOptimizedRest;
  }

  private void signalErrorConditionToMockAbfsOptimizedRestConn(MockAbfsHttpConnection httpOperation) {
    if (errStatusOptimizedRest != 0) {
      httpOperation.induceError(errStatusOptimizedRest);
    }

    if (mockRequestExceptionOptimizedRest) {
      httpOperation.induceRequestException();
    }

    if (mockConnectionExceptionOptimizedRest) {
      httpOperation.induceConnectionException();
    }
  }

  public void induceFpRestError(int httpStatus) {
    errStatusOptimizedRest = httpStatus;
  }

  public void induceFpRestRequestException() {
    mockRequestExceptionOptimizedRest = true;
  }

  public void induceFpRestConnectionException() {
    mockConnectionExceptionOptimizedRest = true;
  }
}
