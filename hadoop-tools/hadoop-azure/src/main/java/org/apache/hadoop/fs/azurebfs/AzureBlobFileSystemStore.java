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
package org.apache.hadoop.fs.azurebfs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.thirdparty.com.google.common.base.Strings;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.Futures;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.ListenableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.EtagSource;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants;
import org.apache.hadoop.fs.azurebfs.constants.FileSystemUriSchemes;
import org.apache.hadoop.fs.azurebfs.constants.FileSystemConfigurations;
import org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsRestOperationException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureBlobFileSystemException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.ConcurrentWriteOperationDetectedException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.FileSystemOperationUnhandledException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidAbfsRestOperationException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidFileSystemPropertyException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidUriAuthorityException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidUriException;
import org.apache.hadoop.fs.azurebfs.contracts.services.AzureServiceErrorCode;
import org.apache.hadoop.fs.azurebfs.contracts.services.ListResultEntrySchema;
import org.apache.hadoop.fs.azurebfs.contracts.services.ListResultSchema;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.TrileanConversionException;
import org.apache.hadoop.fs.azurebfs.enums.Trilean;
import org.apache.hadoop.fs.azurebfs.extensions.SASTokenProvider;
import org.apache.hadoop.fs.azurebfs.extensions.ExtensionHelper;
import org.apache.hadoop.fs.azurebfs.oauth2.AccessTokenProvider;
import org.apache.hadoop.fs.azurebfs.oauth2.AzureADAuthenticator;
import org.apache.hadoop.fs.azurebfs.oauth2.IdentityTransformer;
import org.apache.hadoop.fs.azurebfs.oauth2.IdentityTransformerInterface;
import org.apache.hadoop.fs.azurebfs.services.AbfsAclHelper;
import org.apache.hadoop.fs.azurebfs.services.AbfsClient;
import org.apache.hadoop.fs.azurebfs.services.AbfsClientContext;
import org.apache.hadoop.fs.azurebfs.services.AbfsClientContextBuilder;
import org.apache.hadoop.fs.azurebfs.services.AbfsCounters;
import org.apache.hadoop.fs.azurebfs.services.AbfsHttpOperation;
import org.apache.hadoop.fs.azurebfs.services.AbfsInputStream;
import org.apache.hadoop.fs.azurebfs.services.AbfsInputStreamContext;
import org.apache.hadoop.fs.azurebfs.services.AbfsInputStreamStatisticsImpl;
import org.apache.hadoop.fs.azurebfs.services.AbfsOutputStream;
import org.apache.hadoop.fs.azurebfs.services.AbfsOutputStreamContext;
import org.apache.hadoop.fs.azurebfs.services.AbfsOutputStreamStatisticsImpl;
import org.apache.hadoop.fs.azurebfs.services.AbfsPermission;
import org.apache.hadoop.fs.azurebfs.services.AbfsRestOperation;
import org.apache.hadoop.fs.azurebfs.services.AuthType;
import org.apache.hadoop.fs.azurebfs.services.ExponentialRetryPolicy;
import org.apache.hadoop.fs.azurebfs.services.AbfsLease;
import org.apache.hadoop.fs.azurebfs.services.SharedKeyCredentials;
import org.apache.hadoop.fs.azurebfs.services.AbfsPerfTracker;
import org.apache.hadoop.fs.azurebfs.services.AbfsPerfInfo;
import org.apache.hadoop.fs.azurebfs.services.ListingSupport;
import org.apache.hadoop.fs.azurebfs.utils.Base64;
import org.apache.hadoop.fs.azurebfs.utils.CRC64;
import org.apache.hadoop.fs.azurebfs.utils.DateTimeUtils;
import org.apache.hadoop.fs.azurebfs.utils.TracingContext;
import org.apache.hadoop.fs.azurebfs.utils.UriUtils;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.store.DataBlocks;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.BlockingThreadPoolExecutorService;
import org.apache.hadoop.util.SemaphoredDelegatingExecutor;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.apache.http.client.utils.URIBuilder;

import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_EQUALS;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_FORWARD_SLASH;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_HYPHEN;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_PLUS;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_STAR;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.CHAR_UNDERSCORE;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.COPY_STATUS_ABORTED;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.COPY_STATUS_FAILED;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.COPY_STATUS_SUCCESS;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.FORWARD_SLASH;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.ROOT_PATH;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.SINGLE_WHITE_SPACE;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.TOKEN_VERSION;
import static org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys.AZURE_ABFS_ENDPOINT;
import static org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys.FS_AZURE_BUFFERED_PREAD_DISABLE;
import static org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys.FS_AZURE_IDENTITY_TRANSFORM_CLASS;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.CONTENT_LENGTH;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.X_MS_COPY_ID;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.X_MS_COPY_SOURCE;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.X_MS_COPY_STATUS;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.X_MS_COPY_STATUS_DESCRIPTION;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.X_MS_META_HDI_ISFOLDER;

/**
 * Provides the bridging logic between Hadoop's abstract filesystem and Azure Storage.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class AzureBlobFileSystemStore implements Closeable, ListingSupport {
  private static final Logger LOG = LoggerFactory.getLogger(AzureBlobFileSystemStore.class);

  private AbfsClient client;
  private URI uri;
  private String userName;
  private String primaryUserGroup;
  private static final String TOKEN_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'";
  private static final String XMS_PROPERTIES_ENCODING = "ISO-8859-1";
  private static final int GET_SET_AGGREGATE_COUNT = 2;

  private final Map<AbfsLease, Object> leaseRefs;

  private final AbfsConfiguration abfsConfiguration;
  private final Set<String> azureAtomicRenameDirSet;
  private Set<String> azureInfiniteLeaseDirSet;
  private Trilean isNamespaceEnabled;
  private final AuthType authType;
  private final UserGroupInformation userGroupInformation;
  private final IdentityTransformerInterface identityTransformer;
  private final AbfsPerfTracker abfsPerfTracker;
  private final AbfsCounters abfsCounters;

  private final Boolean useSecureHttp;

  private final static ExecutorService renameBlobExecutorService = Executors.newFixedThreadPool(5);

  /**
   * The set of directories where we should store files as append blobs.
   */
  private Set<String> appendBlobDirSet;

  /** BlockFactory being used by this instance.*/
  private DataBlocks.BlockFactory blockFactory;
  /** Number of active data blocks per AbfsOutputStream */
  private int blockOutputActiveBlocks;
  /** Bounded ThreadPool for this instance. */
  private ExecutorService boundedThreadPool;

  /**
   * FileSystem Store for {@link AzureBlobFileSystem} for Abfs operations.
   * Built using the {@link AzureBlobFileSystemStoreBuilder} with parameters
   * required.
   * @param abfsStoreBuilder Builder for AzureBlobFileSystemStore.
   * @throws IOException Throw IOE in case of failure during constructing.
   */
  public AzureBlobFileSystemStore(
      AzureBlobFileSystemStoreBuilder abfsStoreBuilder) throws IOException {
    this.uri = abfsStoreBuilder.uri;
    String[] authorityParts = authorityParts(uri);
    final String fileSystemName = authorityParts[0];
    final String accountName = authorityParts[1];

    leaseRefs = Collections.synchronizedMap(new WeakHashMap<>());

    try {
      this.abfsConfiguration = new AbfsConfiguration(abfsStoreBuilder.configuration, accountName);
    } catch (IllegalAccessException exception) {
      throw new FileSystemOperationUnhandledException(exception);
    }

    LOG.trace("AbfsConfiguration init complete");

    this.isNamespaceEnabled = abfsConfiguration.getIsNamespaceEnabledAccount();

    this.userGroupInformation = UserGroupInformation.getCurrentUser();
    this.userName = userGroupInformation.getShortUserName();
    LOG.trace("UGI init complete");
    if (!abfsConfiguration.getSkipUserGroupMetadataDuringInitialization()) {
      try {
        this.primaryUserGroup = userGroupInformation.getPrimaryGroupName();
      } catch (IOException ex) {
        LOG.error("Failed to get primary group for {}, using user name as primary group name", userName);
        this.primaryUserGroup = userName;
      }
    } else {
      //Provide a default group name
      this.primaryUserGroup = userName;
    }
    LOG.trace("primaryUserGroup is {}", this.primaryUserGroup);

    this.azureAtomicRenameDirSet = new HashSet<>(Arrays.asList(
        abfsConfiguration.getAzureAtomicRenameDirs().split(AbfsHttpConstants.COMMA)));
    this.azureAtomicRenameDirSet.add("/hbase");
    updateInfiniteLeaseDirs();
    this.authType = abfsConfiguration.getAuthType(accountName);
    boolean usingOauth = (authType == AuthType.OAuth);
    boolean useHttps = (usingOauth || abfsConfiguration.isHttpsAlwaysUsed()) ? true : abfsStoreBuilder.isSecureScheme;
    useSecureHttp = useHttps;
    this.abfsPerfTracker = new AbfsPerfTracker(fileSystemName, accountName, this.abfsConfiguration);
    this.abfsCounters = abfsStoreBuilder.abfsCounters;
    initializeClient(uri, fileSystemName, accountName, useHttps);
    final Class<? extends IdentityTransformerInterface> identityTransformerClass =
        abfsStoreBuilder.configuration.getClass(FS_AZURE_IDENTITY_TRANSFORM_CLASS, IdentityTransformer.class,
            IdentityTransformerInterface.class);
    try {
      this.identityTransformer =
          identityTransformerClass.getConstructor(Configuration.class).newInstance(abfsStoreBuilder.configuration);
    } catch (IllegalAccessException | InstantiationException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException e) {
      throw new IOException(e);
    }
    LOG.trace("IdentityTransformer init complete");

    // Extract the directories that should contain append blobs
    String appendBlobDirs = abfsConfiguration.getAppendBlobDirs();
    if (appendBlobDirs.trim().isEmpty()) {
      this.appendBlobDirSet = new HashSet<String>();
    } else {
      this.appendBlobDirSet = new HashSet<>(Arrays.asList(
          abfsConfiguration.getAppendBlobDirs().split(AbfsHttpConstants.COMMA)));
    }
    this.blockFactory = abfsStoreBuilder.blockFactory;
    this.blockOutputActiveBlocks = abfsStoreBuilder.blockOutputActiveBlocks;
    this.boundedThreadPool = BlockingThreadPoolExecutorService.newInstance(
        abfsConfiguration.getWriteMaxConcurrentRequestCount(),
        abfsConfiguration.getMaxWriteRequestsToQueue(),
        10L, TimeUnit.SECONDS,
        "abfs-bounded");
  }

  /**
   * Checks if the given key in Azure Storage should be stored as a page
   * blob instead of block blob.
   */
  public boolean isAppendBlobKey(String key) {
    return isKeyForDirectorySet(key, appendBlobDirSet);
  }

  /**
   * @return local user name.
   * */
  public String getUser() {
    return this.userName;
  }

  /**
  * @return primary group that user belongs to.
  * */
  public String getPrimaryGroup() {
    return this.primaryUserGroup;
  }

  @Override
  public void close() throws IOException {
    List<ListenableFuture<?>> futures = new ArrayList<>();
    for (AbfsLease lease : leaseRefs.keySet()) {
      if (lease == null) {
        continue;
      }
      ListenableFuture<?> future = client.submit(() -> lease.free());
      futures.add(future);
    }
    try {
      Futures.allAsList(futures).get();
      // shutdown the threadPool and set it to null.
      HadoopExecutors.shutdown(boundedThreadPool, LOG,
          30, TimeUnit.SECONDS);
      boundedThreadPool = null;
    } catch (InterruptedException e) {
      LOG.error("Interrupted freeing leases", e);
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      LOG.error("Error freeing leases", e);
    } finally {
      IOUtils.cleanupWithLogger(LOG, client);
    }
  }

  byte[] encodeAttribute(String value) throws UnsupportedEncodingException {
    return value.getBytes(XMS_PROPERTIES_ENCODING);
  }

  String decodeAttribute(byte[] value) throws UnsupportedEncodingException {
    return new String(value, XMS_PROPERTIES_ENCODING);
  }

  private String[] authorityParts(URI uri) throws InvalidUriAuthorityException, InvalidUriException {
    final String authority = uri.getRawAuthority();
    if (null == authority) {
      throw new InvalidUriAuthorityException(uri.toString());
    }

    if (!authority.contains(AbfsHttpConstants.AZURE_DISTRIBUTED_FILE_SYSTEM_AUTHORITY_DELIMITER)) {
      throw new InvalidUriAuthorityException(uri.toString());
    }

    final String[] authorityParts = authority.split(AbfsHttpConstants.AZURE_DISTRIBUTED_FILE_SYSTEM_AUTHORITY_DELIMITER, 2);

    if (authorityParts.length < 2 || authorityParts[0] != null
        && authorityParts[0].isEmpty()) {
      final String errMsg = String
              .format("'%s' has a malformed authority, expected container name. "
                      + "Authority takes the form "
                      + FileSystemUriSchemes.ABFS_SCHEME + "://[<container name>@]<account name>",
                      uri.toString());
      throw new InvalidUriException(errMsg);
    }
    return authorityParts;
  }

  public boolean getIsNamespaceEnabled(TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try {
      return this.isNamespaceEnabled.toBoolean();
    } catch (TrileanConversionException e) {
      LOG.debug("isNamespaceEnabled is UNKNOWN; fall back and determine through"
          + " getAcl server call", e);
    }

    LOG.debug("Get root ACL status");
    try (AbfsPerfInfo perfInfo = startTracking("getIsNamespaceEnabled",
        "getAclStatus")) {
      AbfsRestOperation op = client
          .getAclStatus(AbfsHttpConstants.ROOT_PATH, tracingContext);
      perfInfo.registerResult(op.getResult());
      isNamespaceEnabled = Trilean.getTrilean(true);
      perfInfo.registerSuccess(true);
    } catch (AbfsRestOperationException ex) {
      // Get ACL status is a HEAD request, its response doesn't contain
      // errorCode
      // So can only rely on its status code to determine its account type.
      if (HttpURLConnection.HTTP_BAD_REQUEST != ex.getStatusCode()) {
        throw ex;
      }

      isNamespaceEnabled = Trilean.getTrilean(false);
    }

    return isNamespaceEnabled.toBoolean();
  }

  @VisibleForTesting
  URIBuilder getURIBuilder(final String hostName, boolean isSecure) {
    String scheme = isSecure ? FileSystemUriSchemes.HTTPS_SCHEME : FileSystemUriSchemes.HTTP_SCHEME;

    final URIBuilder uriBuilder = new URIBuilder();
    uriBuilder.setScheme(scheme);

    // For testing purposes, an IP address and port may be provided to override
    // the host specified in the FileSystem URI.  Also note that the format of
    // the Azure Storage Service URI changes from
    // http[s]://[account][domain-suffix]/[filesystem] to
    // http[s]://[ip]:[port]/[account]/[filesystem].
    String endPoint = abfsConfiguration.get(AZURE_ABFS_ENDPOINT);
    if (endPoint == null || !endPoint.contains(AbfsHttpConstants.COLON)) {
      uriBuilder.setHost(hostName);
      return uriBuilder;
    }

    // Split ip and port
    String[] data = endPoint.split(AbfsHttpConstants.COLON);
    if (data.length != 2) {
      throw new RuntimeException(String.format("ABFS endpoint is not set correctly : %s, "
              + "Do not specify scheme when using {IP}:{PORT}", endPoint));
    }
    uriBuilder.setHost(data[0].trim());
    uriBuilder.setPort(Integer.parseInt(data[1].trim()));
    uriBuilder.setPath("/" + UriUtils.extractAccountNameFromHostName(hostName));

    return uriBuilder;
  }

  public AbfsConfiguration getAbfsConfiguration() {
    return this.abfsConfiguration;
  }

  public Hashtable<String, String> getFilesystemProperties(
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("getFilesystemProperties",
            "getFilesystemProperties")) {
      LOG.debug("getFilesystemProperties for filesystem: {}",
              client.getFileSystem());

      final Hashtable<String, String> parsedXmsProperties;

      final AbfsRestOperation op = client
          .getFilesystemProperties(tracingContext);
      perfInfo.registerResult(op.getResult());

      final String xMsProperties = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_PROPERTIES);

      parsedXmsProperties = parseCommaSeparatedXmsProperties(xMsProperties);
      perfInfo.registerSuccess(true);

      return parsedXmsProperties;
    }
  }

  public void setFilesystemProperties(
      final Hashtable<String, String> properties, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    if (properties == null || properties.isEmpty()) {
      LOG.trace("setFilesystemProperties no properties present");
      return;
    }

    LOG.debug("setFilesystemProperties for filesystem: {} with properties: {}",
            client.getFileSystem(),
            properties);

    try (AbfsPerfInfo perfInfo = startTracking("setFilesystemProperties",
            "setFilesystemProperties")) {
      final String commaSeparatedProperties;
      try {
        commaSeparatedProperties = convertXmsPropertiesToCommaSeparatedString(properties);
      } catch (CharacterCodingException ex) {
        throw new InvalidAbfsRestOperationException(ex);
      }

      final AbfsRestOperation op = client
          .setFilesystemProperties(commaSeparatedProperties, tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public Hashtable<String, String> getPathStatus(final Path path,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("getPathStatus", "getPathStatus")){
      LOG.debug("getPathStatus for filesystem: {} path: {}",
              client.getFileSystem(),
              path);

      final Hashtable<String, String> parsedXmsProperties;
      final AbfsRestOperation op = client
          .getPathStatus(getRelativePath(path), true, tracingContext);
      perfInfo.registerResult(op.getResult());

      final String xMsProperties = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_PROPERTIES);

      parsedXmsProperties = parseCommaSeparatedXmsProperties(xMsProperties);

      perfInfo.registerSuccess(true);

      return parsedXmsProperties;
    }
  }

  private void copyBlob(Path srcPath,
      Path dstPath,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    AbfsRestOperation copyOp = null;
    try {
      copyOp = client.copyBlob(srcPath, dstPath,
          tracingContext);
    } catch (AbfsRestOperationException ex) {
      if(copyOp == null || !copyOp.hasResult()) {
        throw ex;
      }
      if(copyOp.getResult().getStatusCode() == HttpURLConnection.HTTP_CONFLICT) {
        final BlobProperty dstBlobProperty = getBlobProperty(dstPath, tracingContext);
        if(dstBlobProperty.getCopySourceUrl().equals(copyOp.getResult().getRequestHeaderValue(X_MS_COPY_SOURCE))) {
          return;
        }
      }
      throw ex;
    }
    final String progress = copyOp.getResult()
        .getResponseHeader(X_MS_COPY_STATUS);
    if (COPY_STATUS_SUCCESS.equals(progress)) {
      return;
    }
    final String copyId = copyOp.getResult().getResponseHeader(X_MS_COPY_ID);
    int counter = 0;
    while (counter < 10) {//TODO: better condition.
      try {
        Thread.sleep(100); //TODO: better logic for sleep period.
      } catch (Exception e) {

      }
      BlobProperty blobProperty = getBlobPropertyWithNotFoundHandling(dstPath,
          tracingContext);
      if (blobProperty != null && copyId.equals(blobProperty.getCopyId())) {
        if (COPY_STATUS_SUCCESS.equals(blobProperty.getCopyStatus())) {
          return;
        }
        if (COPY_STATUS_ABORTED.equals(blobProperty.getCopyStatus())
            || COPY_STATUS_FAILED.equals(blobProperty.getCopyStatus())) {
          throw new AbfsRestOperationException(
              HttpURLConnection.HTTP_INTERNAL_ERROR, "CopyBlobFailed", null,
              new Exception("CopyBlobFailed"));
        }
      }
      counter++;
    }
  }

  private BlobProperty getBlobPropertyWithNotFoundHandling(Path blobPath,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    try {
      return getBlobProperty(blobPath, tracingContext);
    } catch (AbfsRestOperationException ex) {
      if (ex.getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
        return null;
      }
      throw ex;
    }
  }


  private BlobProperty getBlobProperty(Path blobPath, TracingContext tracingContext) throws AzureBlobFileSystemException {
    AbfsRestOperation op = client.getBlobProperty(blobPath, tracingContext);
    BlobProperty blobProperty = new BlobProperty();
    final AbfsHttpOperation opResult = op.getResult();
    blobProperty.setIsDirectory(opResult
        .getResponseHeader(X_MS_META_HDI_ISFOLDER) != null);
    blobProperty.setUrl(op.getUrl().toString());
    blobProperty.setCopyId(opResult.getResponseHeader(X_MS_COPY_ID));
    blobProperty.setPath(blobPath);
    blobProperty.setCopySourceUrl(opResult.getResponseHeader(X_MS_COPY_SOURCE));
    blobProperty.setStatusDescription(opResult.getResponseHeader(X_MS_COPY_STATUS_DESCRIPTION));
    blobProperty.setCopyStatus(opResult.getResponseHeader(X_MS_COPY_STATUS));
    blobProperty.setContentLength(Long.parseLong(opResult.getResponseHeader(CONTENT_LENGTH)));
    return blobProperty;
  }

  /**
   * Call server API for ListBlob on the blob endpoint. This API returns a limited
   * number of blobs and provide a field called as NextMarker which is reference to
   * next list of blobs for the query. Server expects that the client calls this API
   * in loop with the NextMarker received in previous iteration of backend call for
   * the same request.
   *
   * @param sourceDirBlobPath path from where the list of blob is requried.
   * @param tracingContext object of {@link TracingContext}
   * @param maxResult define how many blobs can client handle in server response.
   * In case maxResult <= 5000, server sends number of blobs equal to the value. In
   * case maxResult > 5000, server sends maximum 5000 blobs.
   * @return List of blobProperties
   * @throws AbfsRestOperationException exception from server-calls / xml-parsing
   */
  public List<BlobProperty> getListBlobs(Path sourceDirBlobPath,
      TracingContext tracingContext, Integer maxResult)
      throws AzureBlobFileSystemException {
    List<BlobProperty> blobProperties = new ArrayList<>();
    String nextMarker = null;
    do {
      AbfsRestOperation op = client.getListBlobs(sourceDirBlobPath,
          tracingContext, nextMarker, null, maxResult);
      BlobList blobList = op.getResult().getBlobList();
      nextMarker = blobList.getNextMarker();
      blobProperties.addAll(blobList.getBlobPropertyList());
    } while (nextMarker != null);
    return blobProperties;
  }

  public void setPathProperties(final Path path,
      final Hashtable<String, String> properties, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("setPathProperties", "setPathProperties")){
      LOG.debug("setFilesystemProperties for filesystem: {} path: {} with properties: {}",
              client.getFileSystem(),
              path,
              properties);

      final String commaSeparatedProperties;
      try {
        commaSeparatedProperties = convertXmsPropertiesToCommaSeparatedString(properties);
      } catch (CharacterCodingException ex) {
        throw new InvalidAbfsRestOperationException(ex);
      }
      final AbfsRestOperation op = client
          .setPathProperties(getRelativePath(path), commaSeparatedProperties,
              tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public void createFilesystem(TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("createFilesystem", "createFilesystem")){
      LOG.debug("createFilesystem for filesystem: {}",
              client.getFileSystem());

      final AbfsRestOperation op = client.createFilesystem(tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public void deleteFilesystem(TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("deleteFilesystem", "deleteFilesystem")) {
      LOG.debug("deleteFilesystem for filesystem: {}",
              client.getFileSystem());

      final AbfsRestOperation op = client.deleteFilesystem(tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public OutputStream createFile(final Path path,
      final FileSystem.Statistics statistics, final boolean overwrite,
      final FsPermission permission, final FsPermission umask,
      TracingContext tracingContext) throws IOException {
    try (AbfsPerfInfo perfInfo = startTracking("createFile", "createPath")) {
      boolean isNamespaceEnabled = getIsNamespaceEnabled(tracingContext);
      LOG.debug("createFile filesystem: {} path: {} overwrite: {} permission: {} umask: {} isNamespaceEnabled: {}",
              client.getFileSystem(),
              path,
              overwrite,
              permission,
              umask,
              isNamespaceEnabled);

      String relativePath = getRelativePath(path);
      boolean isAppendBlob = false;
      if (isAppendBlobKey(path.toString())) {
        isAppendBlob = true;
      }

      // if "fs.azure.enable.conditional.create.overwrite" is enabled and
      // is a create request with overwrite=true, create will follow different
      // flow.
      boolean triggerConditionalCreateOverwrite = false;
      if (overwrite
          && abfsConfiguration.isConditionalCreateOverwriteEnabled()) {
        triggerConditionalCreateOverwrite = true;
      }

      AbfsRestOperation op;
      if (triggerConditionalCreateOverwrite) {
        op = conditionalCreateOverwriteFile(relativePath,
            statistics,
            isNamespaceEnabled ? getOctalNotation(permission) : null,
            isNamespaceEnabled ? getOctalNotation(umask) : null,
            isAppendBlob,
            tracingContext
        );

      } else {
        op = client.createPath(relativePath, true,
            overwrite,
            isNamespaceEnabled ? getOctalNotation(permission) : null,
            isNamespaceEnabled ? getOctalNotation(umask) : null,
            isAppendBlob,
            null,
            tracingContext);

      }
      perfInfo.registerResult(op.getResult()).registerSuccess(true);

      AbfsLease lease = maybeCreateLease(relativePath, tracingContext);

      return new AbfsOutputStream(
          populateAbfsOutputStreamContext(
              isAppendBlob,
              lease,
              client,
              statistics,
              relativePath,
              0,
              tracingContext));
    }
  }

  /**
   * Conditional create overwrite flow ensures that create overwrites is done
   * only if there is match for eTag of existing file.
   * @param relativePath
   * @param statistics
   * @param permission
   * @param umask
   * @param isAppendBlob
   * @return
   * @throws AzureBlobFileSystemException
   */
  private AbfsRestOperation conditionalCreateOverwriteFile(final String relativePath,
      final FileSystem.Statistics statistics,
      final String permission,
      final String umask,
      final boolean isAppendBlob,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    AbfsRestOperation op;

    try {
      // Trigger a create with overwrite=false first so that eTag fetch can be
      // avoided for cases when no pre-existing file is present (major portion
      // of create file traffic falls into the case of no pre-existing file).
      op = client.createPath(relativePath, true, false, permission, umask,
          isAppendBlob, null, tracingContext);

    } catch (AbfsRestOperationException e) {
      if (e.getStatusCode() == HttpURLConnection.HTTP_CONFLICT) {
        // File pre-exists, fetch eTag
        try {
          op = client.getPathStatus(relativePath, false, tracingContext);
        } catch (AbfsRestOperationException ex) {
          if (ex.getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
            // Is a parallel access case, as file which was found to be
            // present went missing by this request.
            throw new ConcurrentWriteOperationDetectedException(
                "Parallel access to the create path detected. Failing request "
                    + "to honor single writer semantics");
          } else {
            throw ex;
          }
        }

        String eTag = op.getResult()
            .getResponseHeader(HttpHeaderConfigurations.ETAG);

        try {
          // overwrite only if eTag matches with the file properties fetched befpre
          op = client.createPath(relativePath, true, true, permission, umask,
              isAppendBlob, eTag, tracingContext);
        } catch (AbfsRestOperationException ex) {
          if (ex.getStatusCode() == HttpURLConnection.HTTP_PRECON_FAILED) {
            // Is a parallel access case, as file with eTag was just queried
            // and precondition failure can happen only when another file with
            // different etag got created.
            throw new ConcurrentWriteOperationDetectedException(
                "Parallel access to the create path detected. Failing request "
                    + "to honor single writer semantics");
          } else {
            throw ex;
          }
        }
      } else {
        throw e;
      }
    }

    return op;
  }

  /**
   * Method to populate AbfsOutputStreamContext with different parameters to
   * be used to construct {@link AbfsOutputStream}.
   *
   * @param isAppendBlob   is Append blob support enabled?
   * @param lease          instance of AbfsLease for this AbfsOutputStream.
   * @param client         AbfsClient.
   * @param statistics     FileSystem statistics.
   * @param path           Path for AbfsOutputStream.
   * @param position       Position or offset of the file being opened, set to 0
   *                       when creating a new file, but needs to be set for APPEND
   *                       calls on the same file.
   * @param tracingContext instance of TracingContext for this AbfsOutputStream.
   * @return AbfsOutputStreamContext instance with the desired parameters.
   */
  private AbfsOutputStreamContext populateAbfsOutputStreamContext(
      boolean isAppendBlob,
      AbfsLease lease,
      AbfsClient client,
      FileSystem.Statistics statistics,
      String path,
      long position,
      TracingContext tracingContext) {
    int bufferSize = abfsConfiguration.getWriteBufferSize();
    if (isAppendBlob && bufferSize > FileSystemConfigurations.APPENDBLOB_MAX_WRITE_BUFFER_SIZE) {
      bufferSize = FileSystemConfigurations.APPENDBLOB_MAX_WRITE_BUFFER_SIZE;
    }
    return new AbfsOutputStreamContext(abfsConfiguration.getSasTokenRenewPeriodForStreamsInSeconds())
            .withWriteBufferSize(bufferSize)
            .enableFlush(abfsConfiguration.isFlushEnabled())
            .enableSmallWriteOptimization(abfsConfiguration.isSmallWriteOptimizationEnabled())
            .disableOutputStreamFlush(abfsConfiguration.isOutputStreamFlushDisabled())
            .withStreamStatistics(new AbfsOutputStreamStatisticsImpl())
            .withAppendBlob(isAppendBlob)
            .withWriteMaxConcurrentRequestCount(abfsConfiguration.getWriteMaxConcurrentRequestCount())
            .withMaxWriteRequestsToQueue(abfsConfiguration.getMaxWriteRequestsToQueue())
            .withLease(lease)
            .withBlockFactory(blockFactory)
            .withBlockOutputActiveBlocks(blockOutputActiveBlocks)
            .withClient(client)
            .withPosition(position)
            .withFsStatistics(statistics)
            .withPath(path)
            .withExecutorService(new SemaphoredDelegatingExecutor(boundedThreadPool,
                blockOutputActiveBlocks, true))
            .withTracingContext(tracingContext)
            .build();
  }

  public void createDirectory(final Path path, final FsPermission permission,
      final FsPermission umask, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("createDirectory", "createPath")) {
      boolean isNamespaceEnabled = getIsNamespaceEnabled(tracingContext);
      LOG.debug("createDirectory filesystem: {} path: {} permission: {} umask: {} isNamespaceEnabled: {}",
              client.getFileSystem(),
              path,
              permission,
              umask,
              isNamespaceEnabled);

      boolean overwrite =
          !isNamespaceEnabled || abfsConfiguration.isEnabledMkdirOverwrite();
      final AbfsRestOperation op = client.createPath(getRelativePath(path),
          false, overwrite,
              isNamespaceEnabled ? getOctalNotation(permission) : null,
              isNamespaceEnabled ? getOctalNotation(umask) : null, false, null,
              tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public AbfsInputStream openFileForRead(final Path path,
      final FileSystem.Statistics statistics, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    return openFileForRead(path, Optional.empty(), statistics, tracingContext);
  }

  public AbfsInputStream openFileForRead(final Path path,
      final Optional<Configuration> options,
      final FileSystem.Statistics statistics, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    try (AbfsPerfInfo perfInfo = startTracking("openFileForRead", "getPathStatus")) {
      LOG.debug("openFileForRead filesystem: {} path: {}",
              client.getFileSystem(),
              path);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getPathStatus(relativePath, false, tracingContext);
      perfInfo.registerResult(op.getResult());

      final String resourceType = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE);
      final long contentLength = Long.parseLong(op.getResult().getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH));
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

      if (parseIsDirectory(resourceType)) {
        throw new AbfsRestOperationException(
                AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
                AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
                "openFileForRead must be used with files and not directories",
                null);
      }

      perfInfo.registerSuccess(true);

      // Add statistics for InputStream
      return new AbfsInputStream(client, statistics,
              relativePath, contentLength,
              populateAbfsInputStreamContext(options),
              eTag, tracingContext);
    }
  }

  private AbfsInputStreamContext populateAbfsInputStreamContext(
      Optional<Configuration> options) {
    boolean bufferedPreadDisabled = options
        .map(c -> c.getBoolean(FS_AZURE_BUFFERED_PREAD_DISABLE, false))
        .orElse(false);
    return new AbfsInputStreamContext(abfsConfiguration.getSasTokenRenewPeriodForStreamsInSeconds())
            .withReadBufferSize(abfsConfiguration.getReadBufferSize())
            .withReadAheadQueueDepth(abfsConfiguration.getReadAheadQueueDepth())
            .withTolerateOobAppends(abfsConfiguration.getTolerateOobAppends())
            .withReadSmallFilesCompletely(abfsConfiguration.readSmallFilesCompletely())
            .withOptimizeFooterRead(abfsConfiguration.optimizeFooterRead())
            .withReadAheadRange(abfsConfiguration.getReadAheadRange())
            .withStreamStatistics(new AbfsInputStreamStatisticsImpl())
            .withShouldReadBufferSizeAlways(
                abfsConfiguration.shouldReadBufferSizeAlways())
            .withReadAheadBlockSize(abfsConfiguration.getReadAheadBlockSize())
            .withBufferedPreadDisabled(bufferedPreadDisabled)
            .build();
  }

  public OutputStream openFileForWrite(final Path path,
      final FileSystem.Statistics statistics, final boolean overwrite,
      TracingContext tracingContext) throws IOException {
    try (AbfsPerfInfo perfInfo = startTracking("openFileForWrite", "getPathStatus")) {
      LOG.debug("openFileForWrite filesystem: {} path: {} overwrite: {}",
              client.getFileSystem(),
              path,
              overwrite);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getPathStatus(relativePath, false, tracingContext);
      perfInfo.registerResult(op.getResult());

      final String resourceType = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE);
      final Long contentLength = Long.valueOf(op.getResult().getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH));

      if (parseIsDirectory(resourceType)) {
        throw new AbfsRestOperationException(
                AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
                AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
                "openFileForRead must be used with files and not directories",
                null);
      }

      final long offset = overwrite ? 0 : contentLength;

      perfInfo.registerSuccess(true);

      boolean isAppendBlob = false;
      if (isAppendBlobKey(path.toString())) {
        isAppendBlob = true;
      }

      AbfsLease lease = maybeCreateLease(relativePath, tracingContext);

      return new AbfsOutputStream(
          populateAbfsOutputStreamContext(
              isAppendBlob,
              lease,
              client,
              statistics,
              relativePath,
              offset,
              tracingContext));
    }
  }

  /**
   * Break any current lease on an ABFS file.
   *
   * @param path file name
   * @param tracingContext TracingContext instance to track correlation IDs
   * @throws AzureBlobFileSystemException on any exception while breaking the lease
   */
  public void breakLease(final Path path, final TracingContext tracingContext) throws AzureBlobFileSystemException {
    LOG.debug("lease path: {}", path);

    client.breakLease(getRelativePath(path), tracingContext);
  }

  public void rename(final Path source, final Path destination, TracingContext tracingContext) throws
          AzureBlobFileSystemException {
    final Instant startAggregate = abfsPerfTracker.getLatencyInstant();
    long countAggregate = 0;
    boolean shouldContinue;

    if(true) {
      /*
      * Destination can be either a file, directory.
      * To understand that its directory, we will do a listBlob API with prefix of
      * the destination with maxResult = 2. In case, the API results only one object,
      * we will check if there is is_Hdi metadata. If not, we will mark it as a directory.
      * If there are more than one object, then it is straight a directory.
      * If there is zero object returned, then we will understand that there is
      * nothing on the destination.
      */
      final Boolean isDstExist;
      List<BlobProperty> dstProperties = getListBlobs(destination, tracingContext, 2);
      if(dstProperties.size() > 0) {
        isDstExist = true;
      } else {
        isDstExist = false;
      }

      if(isDstExist) {
        //destination already there. Rename should not be overwriting.
        throw new AbfsRestOperationException(HttpURLConnection.HTTP_CONFLICT,
            AzureServiceErrorCode.PATH_ALREADY_EXISTS.getErrorCode(), null, null);
      }

      final Boolean isSrcExist;
      final Boolean isSrcDir;
      List<BlobProperty> srcBlobProperties = getListBlobs(source, tracingContext, null);
      if(srcBlobProperties.size() > 0) {
        isSrcExist = true;
        if(srcBlobProperties.size() > 1 || srcBlobProperties.get(0).getIsDirectory()) {
          isSrcDir = true;
        } else {
          isSrcDir = false;
        }
      } else {
        isSrcExist = false;
        isSrcDir = false;
      }

      if(!isSrcExist) {
        throw new AbfsRestOperationException(HttpURLConnection.HTTP_NOT_FOUND,
            AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(), null, null);
      }
      if(isSrcDir) {
        if (isAtomicRenameKey(source.getName())) {

        }
        List<Future> futures = new ArrayList<>();
        for(BlobProperty blobProperty : srcBlobProperties) {
          futures.add(renameBlobExecutorService.submit(() -> {
            try {
              renameBlob(createDestinationPath(destination, blobProperty, source), tracingContext, blobProperty);
            } catch (AzureBlobFileSystemException e) {
              throw new RuntimeException(e);
            }
          }));
        }
        for(Future future : futures) {
          try {
            future.get();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          } catch (ExecutionException e) {
            throw new RuntimeException(e);
          }
        }
      } else {
        renameBlob(destination, tracingContext, srcBlobProperties.get(0));
      }
      return;
    }

    if (isAtomicRenameKey(source.getName())) {
      LOG.warn("The atomic rename feature is not supported by the ABFS scheme; however rename,"
              +" create and delete operations are atomic if Namespace is enabled for your Azure Storage account.");
    }

    LOG.debug("renameAsync filesystem: {} source: {} destination: {}",
            client.getFileSystem(),
            source,
            destination);

    String continuation = null;

    String sourceRelativePath = getRelativePath(source);
    String destinationRelativePath = getRelativePath(destination);

    do {
      try (AbfsPerfInfo perfInfo = startTracking("rename", "renamePath")) {
        AbfsRestOperation op = client
            .renamePath(sourceRelativePath, destinationRelativePath,
                continuation, tracingContext);
        perfInfo.registerResult(op.getResult());
        continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);
        perfInfo.registerSuccess(true);
        countAggregate++;
        shouldContinue = continuation != null && !continuation.isEmpty();

        if (!shouldContinue) {
          perfInfo.registerAggregates(startAggregate, countAggregate);
        }
      }
    } while (shouldContinue);
  }

  private Path createDestinationPath(final Path destination,
      final BlobProperty srcBlobProperty,
      final Path source) {
    String destinationPathStr = destination.toUri().getPath();
    String sourcePathStr = source.toUri().getPath();
    String srcBlobPropertyPathStr = srcBlobProperty.getPath().toUri().getPath();
    if(sourcePathStr.equals(srcBlobPropertyPathStr)) {
      return destination;
    }
    return new Path(destinationPathStr + "/" + srcBlobPropertyPathStr.substring(sourcePathStr.length()));
  }

  private void renameBlob(final Path destination,
      final TracingContext tracingContext,
      final BlobProperty blobProperty) throws AzureBlobFileSystemException {
    copyBlob(blobProperty.getPath(), destination, tracingContext);
    client.deleteBlobPath(blobProperty, tracingContext);
  }

  public void delete(final Path path, final boolean recursive,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    final Instant startAggregate = abfsPerfTracker.getLatencyInstant();
    long countAggregate = 0;
    boolean shouldContinue = true;

    LOG.debug("delete filesystem: {} path: {} recursive: {}",
            client.getFileSystem(),
            path,
            String.valueOf(recursive));

    String continuation = null;

    String relativePath = getRelativePath(path);

    do {
      try (AbfsPerfInfo perfInfo = startTracking("delete", "deletePath")) {
        AbfsRestOperation op = client
            .deletePath(relativePath, recursive, continuation, tracingContext);
        perfInfo.registerResult(op.getResult());
        continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);
        perfInfo.registerSuccess(true);
        countAggregate++;
        shouldContinue = continuation != null && !continuation.isEmpty();

        if (!shouldContinue) {
          perfInfo.registerAggregates(startAggregate, countAggregate);
        }
      }
    } while (shouldContinue);
  }

  public FileStatus getFileStatus(final Path path,
      TracingContext tracingContext) throws IOException {
    try (AbfsPerfInfo perfInfo = startTracking("getFileStatus", "undetermined")) {
      boolean isNamespaceEnabled = getIsNamespaceEnabled(tracingContext);
      LOG.debug("getFileStatus filesystem: {} path: {} isNamespaceEnabled: {}",
              client.getFileSystem(),
              path,
              isNamespaceEnabled);

      final AbfsRestOperation op;
      if (path.isRoot()) {
        if (isNamespaceEnabled) {
          perfInfo.registerCallee("getAclStatus");
          op = client.getAclStatus(getRelativePath(path), tracingContext);
        } else {
          perfInfo.registerCallee("getFilesystemProperties");
          op = client.getFilesystemProperties(tracingContext);
        }
      } else {
        perfInfo.registerCallee("getPathStatus");
        op = client.getPathStatus(getRelativePath(path), false, tracingContext);
      }

      perfInfo.registerResult(op.getResult());
      final long blockSize = abfsConfiguration.getAzureBlockSize();
      final AbfsHttpOperation result = op.getResult();

      String eTag = extractEtagHeader(result);
      final String lastModified = result.getResponseHeader(HttpHeaderConfigurations.LAST_MODIFIED);
      final String permissions = result.getResponseHeader((HttpHeaderConfigurations.X_MS_PERMISSIONS));
      final boolean hasAcl = AbfsPermission.isExtendedAcl(permissions);
      final long contentLength;
      final boolean resourceIsDir;

      if (path.isRoot()) {
        contentLength = 0;
        resourceIsDir = true;
      } else {
        contentLength = parseContentLength(result.getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH));
        resourceIsDir = parseIsDirectory(result.getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE));
      }

      final String transformedOwner = identityTransformer.transformIdentityForGetRequest(
              result.getResponseHeader(HttpHeaderConfigurations.X_MS_OWNER),
              true,
              userName);

      final String transformedGroup = identityTransformer.transformIdentityForGetRequest(
              result.getResponseHeader(HttpHeaderConfigurations.X_MS_GROUP),
              false,
              primaryUserGroup);

      perfInfo.registerSuccess(true);

      return new VersionedFileStatus(
              transformedOwner,
              transformedGroup,
              permissions == null ? new AbfsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL)
                      : AbfsPermission.valueOf(permissions),
              hasAcl,
              contentLength,
              resourceIsDir,
              1,
              blockSize,
              DateTimeUtils.parseLastModifiedTime(lastModified),
              path,
              eTag);
    }
  }

  /**
   * @param path The list path.
   * @param tracingContext Tracks identifiers for request header
   * @return the entries in the path.
   * */
  @Override
  public FileStatus[] listStatus(final Path path, TracingContext tracingContext) throws IOException {
    return listStatus(path, null, tracingContext);
  }

  /**
   * @param path Path the list path.
   * @param startFrom the entry name that list results should start with.
   *                  For example, if folder "/folder" contains four files: "afile", "bfile", "hfile", "ifile".
   *                  Then listStatus(Path("/folder"), "hfile") will return "/folder/hfile" and "folder/ifile"
   *                  Notice that if startFrom is a non-existent entry name, then the list response contains
   *                  all entries after this non-existent entry in lexical order:
   *                  listStatus(Path("/folder"), "cfile") will return "/folder/hfile" and "/folder/ifile".
   * @param tracingContext Tracks identifiers for request header
   * @return the entries in the path start from  "startFrom" in lexical order.
   * */
  @InterfaceStability.Unstable
  @Override
  public FileStatus[] listStatus(final Path path, final String startFrom, TracingContext tracingContext) throws IOException {
    List<FileStatus> fileStatuses = new ArrayList<>();
    listStatus(path, startFrom, fileStatuses, true, null, tracingContext);
    return fileStatuses.toArray(new FileStatus[fileStatuses.size()]);
  }

  @Override
  public String listStatus(final Path path, final String startFrom,
      List<FileStatus> fileStatuses, final boolean fetchAll,
      String continuation, TracingContext tracingContext) throws IOException {
    final Instant startAggregate = abfsPerfTracker.getLatencyInstant();
    long countAggregate = 0;
    boolean shouldContinue = true;

    LOG.debug("listStatus filesystem: {} path: {}, startFrom: {}",
            client.getFileSystem(),
            path,
            startFrom);

    final String relativePath = getRelativePath(path);

    if (continuation == null || continuation.isEmpty()) {
      // generate continuation token if a valid startFrom is provided.
      if (startFrom != null && !startFrom.isEmpty()) {
        continuation = getIsNamespaceEnabled(tracingContext)
            ? generateContinuationTokenForXns(startFrom)
            : generateContinuationTokenForNonXns(relativePath, startFrom);
      }
    }

    do {
      try (AbfsPerfInfo perfInfo = startTracking("listStatus", "listPath")) {
        AbfsRestOperation op = client.listPath(relativePath, false,
            abfsConfiguration.getListMaxResults(), continuation,
            tracingContext);
        perfInfo.registerResult(op.getResult());
        continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);
        ListResultSchema retrievedSchema = op.getResult().getListResultSchema();
        if (retrievedSchema == null) {
          throw new AbfsRestOperationException(
                  AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
                  AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
                  "listStatusAsync path not found",
                  null, op.getResult());
        }

        long blockSize = abfsConfiguration.getAzureBlockSize();

        for (ListResultEntrySchema entry : retrievedSchema.paths()) {
          final String owner = identityTransformer.transformIdentityForGetRequest(entry.owner(), true, userName);
          final String group = identityTransformer.transformIdentityForGetRequest(entry.group(), false, primaryUserGroup);
          final FsPermission fsPermission = entry.permissions() == null
                  ? new AbfsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL)
                  : AbfsPermission.valueOf(entry.permissions());
          final boolean hasAcl = AbfsPermission.isExtendedAcl(entry.permissions());

          long lastModifiedMillis = 0;
          long contentLength = entry.contentLength() == null ? 0 : entry.contentLength();
          boolean isDirectory = entry.isDirectory() == null ? false : entry.isDirectory();
          if (entry.lastModified() != null && !entry.lastModified().isEmpty()) {
            lastModifiedMillis = DateTimeUtils.parseLastModifiedTime(
                entry.lastModified());
          }

          Path entryPath = new Path(File.separator + entry.name());
          entryPath = entryPath.makeQualified(this.uri, entryPath);

          fileStatuses.add(
                  new VersionedFileStatus(
                          owner,
                          group,
                          fsPermission,
                          hasAcl,
                          contentLength,
                          isDirectory,
                          1,
                          blockSize,
                          lastModifiedMillis,
                          entryPath,
                          entry.eTag()));
        }

        perfInfo.registerSuccess(true);
        countAggregate++;
        shouldContinue =
            fetchAll && continuation != null && !continuation.isEmpty();

        if (!shouldContinue) {
          perfInfo.registerAggregates(startAggregate, countAggregate);
        }
      }
    } while (shouldContinue);

    return continuation;
  }

  // generate continuation token for xns account
  private String generateContinuationTokenForXns(final String firstEntryName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(firstEntryName)
            && !firstEntryName.startsWith(AbfsHttpConstants.ROOT_PATH),
            "startFrom must be a dir/file name and it can not be a full path");

    StringBuilder sb = new StringBuilder();
    sb.append(firstEntryName).append("#$").append("0");

    CRC64 crc64 = new CRC64();
    StringBuilder token = new StringBuilder();
    token.append(crc64.compute(sb.toString().getBytes(StandardCharsets.UTF_8)))
            .append(SINGLE_WHITE_SPACE)
            .append("0")
            .append(SINGLE_WHITE_SPACE)
            .append(firstEntryName);

    return Base64.encode(token.toString().getBytes(StandardCharsets.UTF_8));
  }

  // generate continuation token for non-xns account
  private String generateContinuationTokenForNonXns(String path, final String firstEntryName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(firstEntryName)
            && !firstEntryName.startsWith(AbfsHttpConstants.ROOT_PATH),
            "startFrom must be a dir/file name and it can not be a full path");

    // Notice: non-xns continuation token requires full path (first "/" is not included) for startFrom
    path = AbfsClient.getDirectoryQueryParameter(path);
    final String startFrom = (path.isEmpty() || path.equals(ROOT_PATH))
            ? firstEntryName
            : path + ROOT_PATH + firstEntryName;

    SimpleDateFormat simpleDateFormat = new SimpleDateFormat(TOKEN_DATE_PATTERN, Locale.US);
    String date = simpleDateFormat.format(new Date());
    String token = String.format("%06d!%s!%06d!%s!%06d!%s!",
            path.length(), path, startFrom.length(), startFrom, date.length(), date);
    String base64EncodedToken = Base64.encode(token.getBytes(StandardCharsets.UTF_8));

    StringBuilder encodedTokenBuilder = new StringBuilder(base64EncodedToken.length() + 5);
    encodedTokenBuilder.append(String.format("%s!%d!", TOKEN_VERSION, base64EncodedToken.length()));

    for (int i = 0; i < base64EncodedToken.length(); i++) {
      char current = base64EncodedToken.charAt(i);
      if (CHAR_FORWARD_SLASH == current) {
        current = CHAR_UNDERSCORE;
      } else if (CHAR_PLUS == current) {
        current = CHAR_STAR;
      } else if (CHAR_EQUALS == current) {
        current = CHAR_HYPHEN;
      }
      encodedTokenBuilder.append(current);
    }

    return encodedTokenBuilder.toString();
  }

  public void setOwner(final Path path, final String owner, final String group,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfo = startTracking("setOwner", "setOwner")) {

      LOG.debug(
              "setOwner filesystem: {} path: {} owner: {} group: {}",
              client.getFileSystem(),
              path,
              owner,
              group);

      final String transformedOwner = identityTransformer.transformUserOrGroupForSetRequest(owner);
      final String transformedGroup = identityTransformer.transformUserOrGroupForSetRequest(group);

      final AbfsRestOperation op = client.setOwner(getRelativePath(path),
              transformedOwner,
              transformedGroup,
              tracingContext);

      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public void setPermission(final Path path, final FsPermission permission,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfo = startTracking("setPermission", "setPermission")) {

      LOG.debug(
              "setPermission filesystem: {} path: {} permission: {}",
              client.getFileSystem(),
              path,
              permission);

      final AbfsRestOperation op = client.setPermission(getRelativePath(path),
          String.format(AbfsHttpConstants.PERMISSION_FORMAT,
              permission.toOctal()), tracingContext);

      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public void modifyAclEntries(final Path path, final List<AclEntry> aclSpec,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfoGet = startTracking("modifyAclEntries", "getAclStatus")) {

      LOG.debug(
              "modifyAclEntries filesystem: {} path: {} aclSpec: {}",
              client.getFileSystem(),
              path,
              AclEntry.aclSpecToString(aclSpec));

      identityTransformer.transformAclEntriesForSetRequest(aclSpec);
      final Map<String, String> modifyAclEntries = AbfsAclHelper.deserializeAclSpec(AclEntry.aclSpecToString(aclSpec));
      boolean useUpn = AbfsAclHelper.isUpnFormatAclEntries(modifyAclEntries);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getAclStatus(relativePath, useUpn, tracingContext);
      perfInfoGet.registerResult(op.getResult());
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

      final Map<String, String> aclEntries = AbfsAclHelper.deserializeAclSpec(op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL));

      AbfsAclHelper.modifyAclEntriesInternal(aclEntries, modifyAclEntries);

      perfInfoGet.registerSuccess(true).finishTracking();

      try (AbfsPerfInfo perfInfoSet = startTracking("modifyAclEntries", "setAcl")) {
        final AbfsRestOperation setAclOp = client
            .setAcl(relativePath, AbfsAclHelper.serializeAclSpec(aclEntries),
                eTag, tracingContext);
        perfInfoSet.registerResult(setAclOp.getResult())
                .registerSuccess(true)
                .registerAggregates(perfInfoGet.getTrackingStart(), GET_SET_AGGREGATE_COUNT);
      }
    }
  }

  public void removeAclEntries(final Path path, final List<AclEntry> aclSpec,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfoGet = startTracking("removeAclEntries", "getAclStatus")) {

      LOG.debug(
              "removeAclEntries filesystem: {} path: {} aclSpec: {}",
              client.getFileSystem(),
              path,
              AclEntry.aclSpecToString(aclSpec));

      identityTransformer.transformAclEntriesForSetRequest(aclSpec);
      final Map<String, String> removeAclEntries = AbfsAclHelper.deserializeAclSpec(AclEntry.aclSpecToString(aclSpec));
      boolean isUpnFormat = AbfsAclHelper.isUpnFormatAclEntries(removeAclEntries);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getAclStatus(relativePath, isUpnFormat, tracingContext);
      perfInfoGet.registerResult(op.getResult());
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

      final Map<String, String> aclEntries = AbfsAclHelper.deserializeAclSpec(op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL));

      AbfsAclHelper.removeAclEntriesInternal(aclEntries, removeAclEntries);

      perfInfoGet.registerSuccess(true).finishTracking();

      try (AbfsPerfInfo perfInfoSet = startTracking("removeAclEntries", "setAcl")) {
        final AbfsRestOperation setAclOp = client
            .setAcl(relativePath, AbfsAclHelper.serializeAclSpec(aclEntries),
                eTag, tracingContext);
        perfInfoSet.registerResult(setAclOp.getResult())
                .registerSuccess(true)
                .registerAggregates(perfInfoGet.getTrackingStart(), GET_SET_AGGREGATE_COUNT);
      }
    }
  }

  public void removeDefaultAcl(final Path path, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfoGet = startTracking("removeDefaultAcl", "getAclStatus")) {

      LOG.debug(
              "removeDefaultAcl filesystem: {} path: {}",
              client.getFileSystem(),
              path);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getAclStatus(relativePath, tracingContext);
      perfInfoGet.registerResult(op.getResult());
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);
      final Map<String, String> aclEntries = AbfsAclHelper.deserializeAclSpec(op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL));
      final Map<String, String> defaultAclEntries = new HashMap<>();

      for (Map.Entry<String, String> aclEntry : aclEntries.entrySet()) {
        if (aclEntry.getKey().startsWith("default:")) {
          defaultAclEntries.put(aclEntry.getKey(), aclEntry.getValue());
        }
      }

      aclEntries.keySet().removeAll(defaultAclEntries.keySet());

      perfInfoGet.registerSuccess(true).finishTracking();

      try (AbfsPerfInfo perfInfoSet = startTracking("removeDefaultAcl", "setAcl")) {
        final AbfsRestOperation setAclOp = client
            .setAcl(relativePath, AbfsAclHelper.serializeAclSpec(aclEntries),
                eTag, tracingContext);
        perfInfoSet.registerResult(setAclOp.getResult())
                .registerSuccess(true)
                .registerAggregates(perfInfoGet.getTrackingStart(), GET_SET_AGGREGATE_COUNT);
      }
    }
  }

  public void removeAcl(final Path path, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfoGet = startTracking("removeAcl", "getAclStatus")){

      LOG.debug(
              "removeAcl filesystem: {} path: {}",
              client.getFileSystem(),
              path);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getAclStatus(relativePath, tracingContext);
      perfInfoGet.registerResult(op.getResult());
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

      final Map<String, String> aclEntries = AbfsAclHelper.deserializeAclSpec(op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL));
      final Map<String, String> newAclEntries = new HashMap<>();

      newAclEntries.put(AbfsHttpConstants.ACCESS_USER, aclEntries.get(AbfsHttpConstants.ACCESS_USER));
      newAclEntries.put(AbfsHttpConstants.ACCESS_GROUP, aclEntries.get(AbfsHttpConstants.ACCESS_GROUP));
      newAclEntries.put(AbfsHttpConstants.ACCESS_OTHER, aclEntries.get(AbfsHttpConstants.ACCESS_OTHER));

      perfInfoGet.registerSuccess(true).finishTracking();

      try (AbfsPerfInfo perfInfoSet = startTracking("removeAcl", "setAcl")) {
        final AbfsRestOperation setAclOp = client
            .setAcl(relativePath, AbfsAclHelper.serializeAclSpec(newAclEntries),
                eTag, tracingContext);
        perfInfoSet.registerResult(setAclOp.getResult())
                .registerSuccess(true)
                .registerAggregates(perfInfoGet.getTrackingStart(), GET_SET_AGGREGATE_COUNT);
      }
    }
  }

  public void setAcl(final Path path, final List<AclEntry> aclSpec,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfoGet = startTracking("setAcl", "getAclStatus")) {

      LOG.debug(
              "setAcl filesystem: {} path: {} aclspec: {}",
              client.getFileSystem(),
              path,
              AclEntry.aclSpecToString(aclSpec));

      identityTransformer.transformAclEntriesForSetRequest(aclSpec);
      final Map<String, String> aclEntries = AbfsAclHelper.deserializeAclSpec(AclEntry.aclSpecToString(aclSpec));
      final boolean isUpnFormat = AbfsAclHelper.isUpnFormatAclEntries(aclEntries);

      String relativePath = getRelativePath(path);

      final AbfsRestOperation op = client
          .getAclStatus(relativePath, isUpnFormat, tracingContext);
      perfInfoGet.registerResult(op.getResult());
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

      final Map<String, String> getAclEntries = AbfsAclHelper.deserializeAclSpec(op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL));

      AbfsAclHelper.setAclEntriesInternal(aclEntries, getAclEntries);

      perfInfoGet.registerSuccess(true).finishTracking();

      try (AbfsPerfInfo perfInfoSet = startTracking("setAcl", "setAcl")) {
        final AbfsRestOperation setAclOp =
                client.setAcl(relativePath,
                AbfsAclHelper.serializeAclSpec(aclEntries), eTag, tracingContext);
        perfInfoSet.registerResult(setAclOp.getResult())
                .registerSuccess(true)
                .registerAggregates(perfInfoGet.getTrackingStart(), GET_SET_AGGREGATE_COUNT);
      }
    }
  }

  public AclStatus getAclStatus(final Path path, TracingContext tracingContext)
      throws IOException {
    if (!getIsNamespaceEnabled(tracingContext)) {
      throw new UnsupportedOperationException(
          "This operation is only valid for storage accounts with the hierarchical namespace enabled.");
    }

    try (AbfsPerfInfo perfInfo = startTracking("getAclStatus", "getAclStatus")) {

      LOG.debug(
              "getAclStatus filesystem: {} path: {}",
              client.getFileSystem(),
              path);

      AbfsRestOperation op = client
          .getAclStatus(getRelativePath(path), tracingContext);
      AbfsHttpOperation result = op.getResult();
      perfInfo.registerResult(result);

      final String transformedOwner = identityTransformer.transformIdentityForGetRequest(
              result.getResponseHeader(HttpHeaderConfigurations.X_MS_OWNER),
              true,
              userName);
      final String transformedGroup = identityTransformer.transformIdentityForGetRequest(
              result.getResponseHeader(HttpHeaderConfigurations.X_MS_GROUP),
              false,
              primaryUserGroup);

      final String permissions = result.getResponseHeader(HttpHeaderConfigurations.X_MS_PERMISSIONS);
      final String aclSpecString = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_ACL);

      final List<AclEntry> aclEntries = AclEntry.parseAclSpec(AbfsAclHelper.processAclString(aclSpecString), true);
      identityTransformer.transformAclEntriesForGetRequest(aclEntries, userName, primaryUserGroup);
      final FsPermission fsPermission = permissions == null ? new AbfsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL)
              : AbfsPermission.valueOf(permissions);

      final AclStatus.Builder aclStatusBuilder = new AclStatus.Builder();
      aclStatusBuilder.owner(transformedOwner);
      aclStatusBuilder.group(transformedGroup);

      aclStatusBuilder.setPermission(fsPermission);
      aclStatusBuilder.stickyBit(fsPermission.getStickyBit());
      aclStatusBuilder.addEntries(aclEntries);
      perfInfo.registerSuccess(true);
      return aclStatusBuilder.build();
    }
  }

  public void access(final Path path, final FsAction mode,
      TracingContext tracingContext) throws AzureBlobFileSystemException {
    LOG.debug("access for filesystem: {}, path: {}, mode: {}",
        this.client.getFileSystem(), path, mode);
    if (!this.abfsConfiguration.isCheckAccessEnabled()
        || !getIsNamespaceEnabled(tracingContext)) {
      LOG.debug("Returning; either check access is not enabled or the account"
          + " used is not namespace enabled");
      return;
    }
    try (AbfsPerfInfo perfInfo = startTracking("access", "checkAccess")) {
      final AbfsRestOperation op = this.client
          .checkAccess(getRelativePath(path), mode.SYMBOL, tracingContext);
      perfInfo.registerResult(op.getResult()).registerSuccess(true);
    }
  }

  public boolean isAtomicRenameKey(String key) {
    return isKeyForDirectorySet(key, azureAtomicRenameDirSet);
  }

  public boolean isInfiniteLeaseKey(String key) {
    if (azureInfiniteLeaseDirSet.isEmpty()) {
      return false;
    }
    return isKeyForDirectorySet(key, azureInfiniteLeaseDirSet);
  }

  /**
   * A on-off operation to initialize AbfsClient for AzureBlobFileSystem
   * Operations.
   *
   * @param uri            Uniform resource identifier for Abfs.
   * @param fileSystemName Name of the fileSystem being used.
   * @param accountName    Name of the account being used to access Azure
   *                       data store.
   * @param isSecure       Tells if https is being used or http.
   * @throws IOException
   */
  private void initializeClient(URI uri, String fileSystemName,
      String accountName, boolean isSecure)
      throws IOException {
    if (this.client != null) {
      return;
    }

    final String url = getBaseUrlString(fileSystemName, accountName, isSecure);

    URL baseUrl;
    try {
      baseUrl = new URL(url);
    } catch (MalformedURLException e) {
      throw new InvalidUriException(uri.toString());
    }

    SharedKeyCredentials creds = null;
    AccessTokenProvider tokenProvider = null;
    SASTokenProvider sasTokenProvider = null;

    if (authType == AuthType.OAuth) {
      AzureADAuthenticator.init(abfsConfiguration);
    }

    if (authType == AuthType.SharedKey) {
      LOG.trace("Fetching SharedKey credentials");
      int dotIndex = accountName.indexOf(AbfsHttpConstants.DOT);
      if (dotIndex <= 0) {
        throw new InvalidUriException(
                uri.toString() + " - account name is not fully qualified.");
      }
      creds = new SharedKeyCredentials(accountName.substring(0, dotIndex),
            abfsConfiguration.getStorageAccountKey());
    } else if (authType == AuthType.SAS) {
      LOG.trace("Fetching SAS token provider");
      sasTokenProvider = abfsConfiguration.getSASTokenProvider();
    } else {
      LOG.trace("Fetching token provider");
      tokenProvider = abfsConfiguration.getTokenProvider();
      ExtensionHelper.bind(tokenProvider, uri,
            abfsConfiguration.getRawConfiguration());
    }

    LOG.trace("Initializing AbfsClient for {}", baseUrl);
    if (tokenProvider != null) {
      this.client = new AbfsClient(baseUrl, creds, abfsConfiguration,
          tokenProvider,
          populateAbfsClientContext());
    } else {
      this.client = new AbfsClient(baseUrl, creds, abfsConfiguration,
          sasTokenProvider,
          populateAbfsClientContext());
    }
    LOG.trace("AbfsClient init complete");
  }

  private String getBaseUrlString(final String fileSystemName,
      final String accountName,
      final boolean isSecure) {
    final URIBuilder uriBuilder = getURIBuilder(accountName, isSecure);

    final String url = uriBuilder.toString() + AbfsHttpConstants.FORWARD_SLASH + fileSystemName;
    return url;
  }

  /**
   * Populate a new AbfsClientContext instance with the desired properties.
   *
   * @return an instance of AbfsClientContext.
   */
  private AbfsClientContext populateAbfsClientContext() {
    return new AbfsClientContextBuilder()
        .withExponentialRetryPolicy(
            new ExponentialRetryPolicy(abfsConfiguration))
        .withAbfsCounters(abfsCounters)
        .withAbfsPerfTracker(abfsPerfTracker)
        .build();
  }

  private String getOctalNotation(FsPermission fsPermission) {
    Preconditions.checkNotNull(fsPermission, "fsPermission");
    return String.format(AbfsHttpConstants.PERMISSION_FORMAT, fsPermission.toOctal());
  }

  private String getRelativePath(final Path path) {
    Preconditions.checkNotNull(path, "path");
    return path.toUri().getPath();
  }

  private long parseContentLength(final String contentLength) {
    if (contentLength == null) {
      return -1;
    }

    return Long.parseLong(contentLength);
  }

  private boolean parseIsDirectory(final String resourceType) {
    return resourceType != null
        && resourceType.equalsIgnoreCase(AbfsHttpConstants.DIRECTORY);
  }

  private String convertXmsPropertiesToCommaSeparatedString(final Hashtable<String, String> properties) throws
          CharacterCodingException {
    StringBuilder commaSeparatedProperties = new StringBuilder();

    final CharsetEncoder encoder = Charset.forName(XMS_PROPERTIES_ENCODING).newEncoder();

    for (Map.Entry<String, String> propertyEntry : properties.entrySet()) {
      String key = propertyEntry.getKey();
      String value = propertyEntry.getValue();

      Boolean canEncodeValue = encoder.canEncode(value);
      if (!canEncodeValue) {
        throw new CharacterCodingException();
      }

      String encodedPropertyValue = Base64.encode(encoder.encode(CharBuffer.wrap(value)).array());
      commaSeparatedProperties.append(key)
              .append(AbfsHttpConstants.EQUAL)
              .append(encodedPropertyValue);

      commaSeparatedProperties.append(AbfsHttpConstants.COMMA);
    }

    if (commaSeparatedProperties.length() != 0) {
      commaSeparatedProperties.deleteCharAt(commaSeparatedProperties.length() - 1);
    }

    return commaSeparatedProperties.toString();
  }

  private Hashtable<String, String> parseCommaSeparatedXmsProperties(String xMsProperties) throws
          InvalidFileSystemPropertyException, InvalidAbfsRestOperationException {
    Hashtable<String, String> properties = new Hashtable<>();

    final CharsetDecoder decoder = Charset.forName(XMS_PROPERTIES_ENCODING).newDecoder();

    if (xMsProperties != null && !xMsProperties.isEmpty()) {
      String[] userProperties = xMsProperties.split(AbfsHttpConstants.COMMA);

      if (userProperties.length == 0) {
        return properties;
      }

      for (String property : userProperties) {
        if (property.isEmpty()) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        String[] nameValue = property.split(AbfsHttpConstants.EQUAL, 2);
        if (nameValue.length != 2) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        byte[] decodedValue = Base64.decode(nameValue[1]);

        final String value;
        try {
          value = decoder.decode(ByteBuffer.wrap(decodedValue)).toString();
        } catch (CharacterCodingException ex) {
          throw new InvalidAbfsRestOperationException(ex);
        }
        properties.put(nameValue[0], value);
      }
    }

    return properties;
  }

  private boolean isKeyForDirectorySet(String key, Set<String> dirSet) {
    for (String dir : dirSet) {
      if (dir.isEmpty() || key.startsWith(dir + AbfsHttpConstants.FORWARD_SLASH)) {
        return true;
      }

      try {
        URI uri = new URI(dir);
        if (null == uri.getAuthority()) {
          if (key.startsWith(dir + "/")){
            return true;
          }
        }
      } catch (URISyntaxException e) {
        LOG.info("URI syntax error creating URI for {}", dir);
      }
    }

    return false;
  }

  private AbfsPerfInfo startTracking(String callerName, String calleeName) {
    return new AbfsPerfInfo(abfsPerfTracker, callerName, calleeName);
  }

  /**
   * A File status with version info extracted from the etag value returned
   * in a LIST or HEAD request.
   * The etag is included in the java serialization.
   */
  private static final class VersionedFileStatus extends FileStatus
      implements EtagSource {

    /**
     * The superclass is declared serializable; this subclass can also
     * be serialized.
     */
    private static final long serialVersionUID = -2009013240419749458L;

    /**
     * The etag of an object.
     * Not-final so that serialization via reflection will preserve the value.
     */
    private String version;

    private VersionedFileStatus(
            final String owner, final String group, final FsPermission fsPermission, final boolean hasAcl,
            final long length, final boolean isdir, final int blockReplication,
            final long blocksize, final long modificationTime, final Path path,
            String version) {
      super(length, isdir, blockReplication, blocksize, modificationTime, 0,
              fsPermission,
              owner,
              group,
              null,
              path,
              hasAcl, false, false);

      this.version = version;
    }

    /** Compare if this object is equal to another object.
     * @param   obj the object to be compared.
     * @return  true if two file status has the same path name; false if not.
     */
    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof FileStatus)) {
        return false;
      }

      FileStatus other = (FileStatus) obj;

      if (!this.getPath().equals(other.getPath())) {// compare the path
        return false;
      }

      if (other instanceof VersionedFileStatus) {
        return this.version.equals(((VersionedFileStatus) other).version);
      }

      return true;
    }

    /**
     * Returns a hash code value for the object, which is defined as
     * the hash code of the path name.
     *
     * @return  a hash code value for the path name and version
     */
    @Override
    public int hashCode() {
      int hash = getPath().hashCode();
      hash = 89 * hash + (this.version != null ? this.version.hashCode() : 0);
      return hash;
    }

    /**
     * Returns the version of this FileStatus
     *
     * @return  a string value for the FileStatus version
     */
    public String getVersion() {
      return this.version;
    }

    @Override
    public String getEtag() {
      return getVersion();
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder(
          "VersionedFileStatus{");
      sb.append(super.toString());
      sb.append("; version='").append(version).append('\'');
      sb.append('}');
      return sb.toString();
    }
  }

  /**
   * A builder class for AzureBlobFileSystemStore.
   */
  public static final class AzureBlobFileSystemStoreBuilder {

    private URI uri;
    private boolean isSecureScheme;
    private Configuration configuration;
    private AbfsCounters abfsCounters;
    private DataBlocks.BlockFactory blockFactory;
    private int blockOutputActiveBlocks;

    public AzureBlobFileSystemStoreBuilder withUri(URI value) {
      this.uri = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder withSecureScheme(boolean value) {
      this.isSecureScheme = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder withConfiguration(
        Configuration value) {
      this.configuration = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder withAbfsCounters(
        AbfsCounters value) {
      this.abfsCounters = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder withBlockFactory(
        DataBlocks.BlockFactory value) {
      this.blockFactory = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder withBlockOutputActiveBlocks(
        int value) {
      this.blockOutputActiveBlocks = value;
      return this;
    }

    public AzureBlobFileSystemStoreBuilder build() {
      return this;
    }
  }

  @VisibleForTesting
  AbfsClient getClient() {
    return this.client;
  }

  @VisibleForTesting
  void setClient(AbfsClient client) {
    this.client = client;
  }

  @VisibleForTesting
  void setNamespaceEnabled(Trilean isNamespaceEnabled){
    this.isNamespaceEnabled = isNamespaceEnabled;
  }

  private void updateInfiniteLeaseDirs() {
    this.azureInfiniteLeaseDirSet = new HashSet<>(Arrays.asList(
        abfsConfiguration.getAzureInfiniteLeaseDirs().split(AbfsHttpConstants.COMMA)));
    // remove the empty string, since isKeyForDirectory returns true for empty strings
    // and we don't want to default to enabling infinite lease dirs
    this.azureInfiniteLeaseDirSet.remove("");
  }

  private AbfsLease maybeCreateLease(String relativePath, TracingContext tracingContext)
      throws AzureBlobFileSystemException {
    boolean enableInfiniteLease = isInfiniteLeaseKey(relativePath);
    if (!enableInfiniteLease) {
      return null;
    }
    AbfsLease lease = new AbfsLease(client, relativePath, tracingContext);
    leaseRefs.put(lease, null);
    return lease;
  }

  @VisibleForTesting
  boolean areLeasesFreed() {
    for (AbfsLease lease : leaseRefs.keySet()) {
      if (lease != null && !lease.isFreed()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Get the etag header from a response, stripping any quotations.
   * see: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag
   * @param result response to process.
   * @return the quote-unwrapped etag.
   */
  private static String extractEtagHeader(AbfsHttpOperation result) {
    String etag = result.getResponseHeader(HttpHeaderConfigurations.ETAG);
    if (etag != null) {
      // strip out any wrapper "" quotes which come back, for consistency with
      // list calls
      if (etag.startsWith("W/\"")) {
        // Weak etag
        etag = etag.substring(3);
      } else if (etag.startsWith("\"")) {
        // strong etag
        etag = etag.substring(1);
      }
      if (etag.endsWith("\"")) {
        // trailing quote
        etag = etag.substring(0, etag.length() - 1);
      }
    }
    return etag;
  }
}
