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

package alluxio.underfs.s3a;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.conf.PropertyKey;
import alluxio.file.options.DescendantType;
import alluxio.retry.RetryPolicy;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UfsDirectoryStatus;
import alluxio.underfs.UfsFileStatus;
import alluxio.underfs.UfsLoadResult;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.underfs.options.OpenOptions;
import alluxio.util.CommonUtils;
import alluxio.util.ModeUtils;
import alluxio.util.UnderFileSystemUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.io.PathUtils;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.regions.Regions;
import com.amazonaws.util.RuntimeHttpUtils;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.Http2Configuration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.Copy;
import software.amazon.awssdk.transfer.s3.model.CopyRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * S3 {@link UnderFileSystem} implementation based on the aws-java-sdk-s3 library.
 */
@ThreadSafe
public class S3AUnderFileSystem extends ObjectUnderFileSystem {
    private static final Logger LOG = LoggerFactory.getLogger(S3AUnderFileSystem.class);

    /** Static hash for a directory's empty contents. */
    private static final String DIR_HASH;

    /** Default owner of objects if owner cannot be determined. */
    private static final String DEFAULT_OWNER = "";

    private static final String S3_SERVICE_NAME = "s3";

    /** AWS-SDK v2 sync S3 client. Drives every data-plane method from Phase 2 onwards. */
    private final S3Client mS3Client;

    /** AWS-SDK v2 async S3 client — drives async listings and backs the v2 transfer manager. */
    private final S3AsyncClient mAsyncClient;

    /** Bucket name of user's configured Alluxio bucket. */
    private final String mBucketName;

    /** Executor for executing upload tasks in streaming upload. */
    private final ListeningExecutorService mExecutor;

    /** AWS-SDK v2 S3 Transfer Manager — drives {@link S3AOutputStream} multipart uploads. */
    private final S3TransferManager mTransferManager;

    /** Whether the streaming upload is enabled. */
    private final boolean mStreamingUploadEnabled;

    /** The permissions associated with the bucket. Fetched once and assumed to be immutable. */
    private final Supplier<ObjectPermissions> mPermissions
            = CommonUtils.memoize(this::getPermissionsInternal);

    static {
        byte[] dirByteHash = DigestUtils.md5(new byte[0]);
        DIR_HASH = new String(Base64.getEncoder().encode(dirByteHash));
    }

    /**
     * Builds the single, canonical credentials provider used by both the (still-v1)
     * sync S3 client and the v2 async client. Resolution order:
     *   1. explicit static keys ({@link PropertyKey#S3A_ACCESS_KEY},
     *      {@link PropertyKey#S3A_SECRET_KEY})
     *   2. the v2 default provider chain, which covers env vars, system properties,
     *      the shared profile, and crucially IRSA via
     *      {@code WebIdentityTokenFileCredentialsProvider} (needed by CSA-21750)
     *   3. if {@link PropertyKey#S3A_IAM_ROLE} is set, the resolved provider is
     *      wrapped in {@link StsAssumeRoleCredentialsProvider} which calls
     *      {@code sts:AssumeRole} once and refreshes near expiry
     *
     * @param conf the configuration for this UFS
     * @return the AWS SDK v2 credentials provider
     */
    public static AwsCredentialsProvider createAwsCredentialsProvider(
            UnderFileSystemConfiguration conf) {
        AwsCredentialsProvider base;
        if (conf.isSet(PropertyKey.S3A_ACCESS_KEY)
                && conf.isSet(PropertyKey.S3A_SECRET_KEY)) {
            base = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    conf.getString(PropertyKey.S3A_ACCESS_KEY),
                    conf.getString(PropertyKey.S3A_SECRET_KEY)));
        } else {
            base = DefaultCredentialsProvider.builder()
                    .asyncCredentialUpdateEnabled(true)
                    .build();
        }

        if (conf.isSet(PropertyKey.S3A_IAM_ROLE)) {
            StsClient stsClient = StsClient.builder()
                    .credentialsProvider(base)
                    .build();
            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(conf.getString(PropertyKey.S3A_IAM_ROLE))
                            .roleSessionName("alluxio")
                            .build())
                    .build();
        }
        return base;
    }

    /**
     * Constructs a new instance of {@link S3AUnderFileSystem}.
     *
     * @param uri the {@link AlluxioURI} for this UFS
     * @param conf the configuration for this UFS
     * @return the created {@link S3AUnderFileSystem} instance
     */
    public static S3AUnderFileSystem createInstance(AlluxioURI uri,
                                                    UnderFileSystemConfiguration conf) {

        AwsCredentialsProvider credentials = createAwsCredentialsProvider(conf);
        String bucketName = UnderFileSystemUtils.getBucketName(uri);

        // Set the client configuration based on Alluxio configuration values.
        ClientConfiguration clientConf = new ClientConfiguration();

        // Max error retry
        if (conf.isSet(PropertyKey.UNDERFS_S3_MAX_ERROR_RETRY)) {
            clientConf.setMaxErrorRetry(conf.getInt(PropertyKey.UNDERFS_S3_MAX_ERROR_RETRY));
        }
        clientConf.setConnectionTTL(conf.getMs(PropertyKey.UNDERFS_S3_CONNECT_TTL));
        // Socket timeout
        clientConf
                .setSocketTimeout((int) conf.getMs(PropertyKey.UNDERFS_S3_SOCKET_TIMEOUT));

        // HTTP protocol
        if (conf.getBoolean(PropertyKey.UNDERFS_S3_SECURE_HTTP_ENABLED)
                || conf.getBoolean(PropertyKey.UNDERFS_S3_SERVER_SIDE_ENCRYPTION_ENABLED)) {
            clientConf.setProtocol(Protocol.HTTPS);
        } else {
            clientConf.setProtocol(Protocol.HTTP);
        }

        // Proxy host
        if (conf.isSet(PropertyKey.UNDERFS_S3_PROXY_HOST)) {
            clientConf.setProxyHost(conf.getString(PropertyKey.UNDERFS_S3_PROXY_HOST));
        }

        // Proxy port
        if (conf.isSet(PropertyKey.UNDERFS_S3_PROXY_PORT)) {
            clientConf.setProxyPort(conf.getInt(PropertyKey.UNDERFS_S3_PROXY_PORT));
        }

        // Number of metadata and I/O threads to S3.
        int numAdminThreads = conf.getInt(PropertyKey.UNDERFS_S3_ADMIN_THREADS_MAX);
        int numTransferThreads =
                conf.getInt(PropertyKey.UNDERFS_S3_UPLOAD_THREADS_MAX);
        int numThreads = conf.getInt(PropertyKey.UNDERFS_S3_THREADS_MAX);
        if (numThreads < numAdminThreads + numTransferThreads) {
            LOG.warn("Configured s3 max threads ({}) is less than # admin threads ({}) plus transfer "
                            + "threads ({}). Using admin threads + transfer threads as max threads instead.",
                    numThreads, numAdminThreads, numTransferThreads);
            numThreads = numAdminThreads + numTransferThreads;
        }
        clientConf.setMaxConnections(numThreads);

        // Set client request timeout for all requests since multipart copy is used,
        // and copy parts can only be set with the client configuration.
        clientConf
                .setRequestTimeout((int) conf.getMs(PropertyKey.UNDERFS_S3_REQUEST_TIMEOUT));

        boolean streamingUploadEnabled =
                conf.getBoolean(PropertyKey.UNDERFS_S3_STREAMING_UPLOAD_ENABLED);

        // Signer algorithm
        if (conf.isSet(PropertyKey.UNDERFS_S3_SIGNER_ALGORITHM)) {
            clientConf.setSignerOverride(conf.getString(PropertyKey.UNDERFS_S3_SIGNER_ALGORITHM));
        }

        S3Client s3Client = createAmazonS3Sync(conf, clientConf, credentials);
        S3AsyncClient asyncClient = createAmazonS3Async(conf, clientConf, credentials);

        ExecutorService service = ExecutorServiceFactories
                .fixedThreadPool("alluxio-s3-transfer-manager-worker",
                        numTransferThreads).create();

        // The v2 transfer manager piggybacks on the existing async client. We don't size its
        // internal executor — the SDK defaults pick a sensible bounded thread pool.
        S3TransferManager transferManager = S3TransferManager.builder()
                .s3Client(asyncClient)
                .build();

        return new S3AUnderFileSystem(uri, s3Client, asyncClient, bucketName,
                service, transferManager, conf, streamingUploadEnabled);
    }

    /**
     * Create an async S3 client.
     * @param conf the conf
     * @param clientConf the (v1) client conf used for endpoint URI parsing
     * @param credentialsProvider the v2 credentials provider built by
     *        {@link #createAwsCredentialsProvider(UnderFileSystemConfiguration)} — shared with the
     *        sync client so STS assume-role and IRSA refresh consistently across both paths
     * @return the client
     */
    public static S3AsyncClient createAmazonS3Async(
            UnderFileSystemConfiguration conf,
            ClientConfiguration clientConf,
            AwsCredentialsProvider credentialsProvider) {

        S3AsyncClientBuilder clientBuilder = S3AsyncClient.builder();
        // need to check all the additional parameters for these
        S3Configuration.builder();
        ClientOverrideConfiguration.builder();
        Http2Configuration.builder();
        ClientAsyncConfiguration.builder();

        NettyNioAsyncHttpClient.Builder httpClientBuilder = NettyNioAsyncHttpClient.builder();

        if (conf.getBoolean(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS)) {
            S3Configuration config = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build();
            clientBuilder.serviceConfiguration(config);
        }

        // Proxy host
        if (conf.isSet(PropertyKey.UNDERFS_S3_PROXY_HOST)) {
            ProxyConfiguration.Builder proxyBuilder = ProxyConfiguration.builder();
            proxyBuilder.host(conf.getString(PropertyKey.UNDERFS_S3_PROXY_HOST));
            // Proxy port
            if (conf.isSet(PropertyKey.UNDERFS_S3_PROXY_PORT)) {
                proxyBuilder.port(conf.getInt(PropertyKey.UNDERFS_S3_PROXY_PORT));
            }
            httpClientBuilder.proxyConfiguration(proxyBuilder.build());
        }
        boolean regionSet = false;
        if (conf.isSet(PropertyKey.UNDERFS_S3_ENDPOINT)) {
            String endpoint = conf.getString(PropertyKey.UNDERFS_S3_ENDPOINT);
            final URI epr = RuntimeHttpUtils.toUri(endpoint, clientConf);
            clientBuilder.endpointOverride(epr);
            if (conf.isSet(PropertyKey.UNDERFS_S3_ENDPOINT_REGION)) {
                regionSet = setRegionAsync(clientBuilder,
                        conf.getString(PropertyKey.UNDERFS_S3_ENDPOINT_REGION));
            }
        } else if (conf.isSet(PropertyKey.UNDERFS_S3_REGION)) {
            regionSet = setRegionAsync(clientBuilder,
                    conf.getString(PropertyKey.UNDERFS_S3_REGION));
        }

        if (!regionSet) {
            String defaultRegion = Regions.US_EAST_1.getName();
            clientBuilder.region(Region.of(defaultRegion));
            LOG.warn("Cannot find S3 endpoint or s3 region in Alluxio configuration, "
                            + "set region to {} as default. S3 client v2 does not support global bucket access, "
                            + "considering specify the region in alluxio config.",
                    defaultRegion);
        }
        clientBuilder.httpClientBuilder(httpClientBuilder);
        clientBuilder.credentialsProvider(credentialsProvider);
        return clientBuilder.build();
    }

    private static boolean setRegionAsync(
            S3AsyncClientBuilder builder, String region) {
        try {
            builder.region(Region.of(region));
            LOG.debug("Set S3 region {} to {}", PropertyKey.UNDERFS_S3_REGION.getName(), region);
            return true;
        } catch (SdkClientException e) {
            LOG.error("S3 region {} cannot be recognized, "
                            + "fall back to use global bucket access with an extra HEAD request",
                    region, e);
            return false;
        }
    }

    private static boolean setRegionSync(
            S3ClientBuilder builder, String region) {
        try {
            builder.region(Region.of(region));
            LOG.debug("Set S3 region {} to {}", PropertyKey.UNDERFS_S3_REGION.getName(), region);
            return true;
        } catch (SdkClientException e) {
            LOG.error("S3 region {} cannot be recognized, "
                            + "fall back to use global bucket access with an extra HEAD request",
                    region, e);
            return false;
        }
    }

    /**
     * Create the canonical SDK v2 sync {@link S3Client} used by every data-plane method
     * rewritten in Phase 2 (CSA-21975) — read path, write path, ACL.
     * Config knobs mirror {@link #createAmazonS3Async(UnderFileSystemConfiguration,
     * ClientConfiguration, AwsCredentialsProvider)} so a single set of Alluxio properties
     * drives both clients identically: DNS-style addressing, proxy host/port, endpoint
     * override, region resolution with us-east-1 fallback.
     *
     * @param conf the conf
     * @param clientConf the (v1) client conf used for endpoint URI parsing — same shape as the
     *        async builder so we don't have to re-parse the endpoint twice
     * @param credentialsProvider the v2 credentials provider built by
     *        {@link #createAwsCredentialsProvider(UnderFileSystemConfiguration)} — shared with
     *        the async client so STS assume-role and IRSA refresh consistently across both paths
     * @return the v2 sync S3 client
     */
    public static S3Client createAmazonS3Sync(
            UnderFileSystemConfiguration conf,
            ClientConfiguration clientConf,
            AwsCredentialsProvider credentialsProvider) {

        S3ClientBuilder clientBuilder = S3Client.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();

        if (conf.getBoolean(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS)) {
            S3Configuration config = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build();
            clientBuilder.serviceConfiguration(config);
        }

        if (conf.isSet(PropertyKey.UNDERFS_S3_PROXY_HOST)) {
            software.amazon.awssdk.http.apache.ProxyConfiguration.Builder proxyBuilder =
                    software.amazon.awssdk.http.apache.ProxyConfiguration.builder();
            String proxyHost = conf.getString(PropertyKey.UNDERFS_S3_PROXY_HOST);
            int proxyPort = conf.isSet(PropertyKey.UNDERFS_S3_PROXY_PORT)
                    ? conf.getInt(PropertyKey.UNDERFS_S3_PROXY_PORT)
                    : -1;
            // Apache's ProxyConfiguration only accepts an endpoint URI, not host+port directly.
            String scheme = "http";
            URI proxyUri = proxyPort > 0
                    ? URI.create(scheme + "://" + proxyHost + ":" + proxyPort)
                    : URI.create(scheme + "://" + proxyHost);
            proxyBuilder.endpoint(proxyUri);
            httpClientBuilder.proxyConfiguration(proxyBuilder.build());
        }

        boolean regionSet = false;
        if (conf.isSet(PropertyKey.UNDERFS_S3_ENDPOINT)) {
            String endpoint = conf.getString(PropertyKey.UNDERFS_S3_ENDPOINT);
            final URI epr = RuntimeHttpUtils.toUri(endpoint, clientConf);
            clientBuilder.endpointOverride(epr);
            if (conf.isSet(PropertyKey.UNDERFS_S3_ENDPOINT_REGION)) {
                regionSet = setRegionSync(clientBuilder,
                        conf.getString(PropertyKey.UNDERFS_S3_ENDPOINT_REGION));
            }
        } else if (conf.isSet(PropertyKey.UNDERFS_S3_REGION)) {
            regionSet = setRegionSync(clientBuilder,
                    conf.getString(PropertyKey.UNDERFS_S3_REGION));
        }

        if (!regionSet) {
            String defaultRegion = Regions.US_EAST_1.getName();
            clientBuilder.region(Region.of(defaultRegion));
            LOG.warn("Cannot find S3 endpoint or s3 region in Alluxio configuration, "
                            + "set region to {} as default. S3 client v2 does not support global "
                            + "bucket access, considering specify the region in alluxio config.",
                    defaultRegion);
        }
        clientBuilder.httpClientBuilder(httpClientBuilder);
        clientBuilder.credentialsProvider(credentialsProvider);
        return clientBuilder.build();
    }

    /**
     * Constructor for {@link S3AUnderFileSystem}.
     *
     * @param uri the {@link AlluxioURI} for this UFS
     * @param s3Client AWS-SDK v2 sync S3 client — drives every method rewritten in Phase 2
     * @param asyncClient AWS-SDK v2 async S3 client — drives the async listing path
     * @param bucketName bucket name of user's configured Alluxio bucket
     * @param executor the executor for executing upload tasks
     * @param transferManager AWS-SDK v2 transfer manager — drives multipart uploads / copies
     * @param conf configuration for this S3A ufs
     * @param streamingUploadEnabled whether streaming upload is enabled
     */
    protected S3AUnderFileSystem(
            AlluxioURI uri, S3Client s3Client, S3AsyncClient asyncClient,
            String bucketName, ExecutorService executor,
            S3TransferManager transferManager, UnderFileSystemConfiguration conf,
            boolean streamingUploadEnabled) {
        super(uri, conf);
        mS3Client = s3Client;
        mAsyncClient = asyncClient;
        mBucketName = bucketName;
        mExecutor = MoreExecutors.listeningDecorator(executor);
        mTransferManager = transferManager;
        mStreamingUploadEnabled = streamingUploadEnabled;
    }

    @Override
    public String getUnderFSType() {
        return "s3";
    }

    // Setting S3 owner via Alluxio is not supported yet. This is a no-op.
    @Override
    public void setOwner(String path, String user, String group) {}

    // Setting S3 mode via Alluxio is not supported yet. This is a no-op.
    @Override
    public void setMode(String path, short mode) {}

    @Override
    public void cleanup() {
        long cleanAge = mUfsConf.isSet(PropertyKey.UNDERFS_S3_INTERMEDIATE_UPLOAD_CLEAN_AGE)
                ? mUfsConf.getMs(PropertyKey.UNDERFS_S3_INTERMEDIATE_UPLOAD_CLEAN_AGE)
                : (long) PropertyKey.UNDERFS_S3_INTERMEDIATE_UPLOAD_CLEAN_AGE
                .getDefaultValue();
        Instant cleanBefore = Instant.now().minusMillis(cleanAge);
        // v2 has no abortMultipartUploads(bucket, Date) convenience — list in-progress MPUs
        // and abort each one initiated before cleanBefore. Listing is paginated; we follow
        // the IsTruncated / NextKeyMarker pattern.
        String keyMarker = null;
        String uploadIdMarker = null;
        do {
            ListMultipartUploadsRequest.Builder reqBuilder = ListMultipartUploadsRequest.builder()
                    .bucket(mBucketName);
            if (keyMarker != null) {
                reqBuilder.keyMarker(keyMarker);
            }
            if (uploadIdMarker != null) {
                reqBuilder.uploadIdMarker(uploadIdMarker);
            }
            ListMultipartUploadsResponse listResp;
            try {
                listResp = mS3Client.listMultipartUploads(reqBuilder.build());
            } catch (SdkException e) {
                LOG.warn("Failed to list in-progress multipart uploads for cleanup", e);
                return;
            }
            for (MultipartUpload upload : listResp.uploads()) {
                if (upload.initiated() != null && upload.initiated().isBefore(cleanBefore)) {
                    try {
                        mS3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                                .bucket(mBucketName)
                                .key(upload.key())
                                .uploadId(upload.uploadId())
                                .build());
                    } catch (SdkException e) {
                        LOG.warn("Failed to abort stale multipart upload key={} uploadId={}",
                                upload.key(), upload.uploadId(), e);
                    }
                }
            }
            if (!Boolean.TRUE.equals(listResp.isTruncated())) {
                return;
            }
            keyMarker = listResp.nextKeyMarker();
            uploadIdMarker = listResp.nextUploadIdMarker();
        } while (true);
    }

    @Override
    public void close() {
        mExecutor.shutdown();
    }

    @Override
    protected boolean copyObject(String src, String dst) {
        LOG.debug("Copying {} to {}", src, dst);
        // Retry copy for a few times, in case some AWS internal errors happened during copy.
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                CopyObjectRequest.Builder copyBuilder = CopyObjectRequest.builder()
                        .sourceBucket(mBucketName)
                        .sourceKey(src)
                        .destinationBucket(mBucketName)
                        .destinationKey(dst);
                if (mUfsConf.getBoolean(PropertyKey.UNDERFS_S3_SERVER_SIDE_ENCRYPTION_ENABLED)) {
                    copyBuilder.serverSideEncryption(ServerSideEncryption.AES256);
                }
                // S3TransferManager.copy uses single-PUT for objects below 5 GB and switches to
                // multipart upload above; that's the same threshold MULTIPART_COPY_THRESHOLD
                // (100 MB) hint we used to give the v1 TM — the v2 TM picks its own threshold
                // and ignores per-request hints, so we pass through and let it decide.
                Copy copy = mTransferManager.copy(CopyRequest.builder()
                        .copyObjectRequest(copyBuilder.build()).build());
                copy.completionFuture().join();
                return true;
            } catch (SdkException | java.util.concurrent.CompletionException e) {
                LOG.error("Failed to copy file {} to {}", src, dst, e);
                if (i != retries - 1) {
                    LOG.error("Retrying copying file {} to {}", src, dst);
                }
            }
        }
        LOG.error("Failed to copy file {} to {}, after {} retries", src, dst, retries);
        return false;
    }

    @Override
    public boolean createEmptyObject(String key) {
        try {
            mS3Client.putObject(PutObjectRequest.builder()
                    .bucket(mBucketName)
                    .key(key)
                    .contentLength(0L)
                    .contentMD5(DIR_HASH)
                    .contentType("application/octet-stream")
                    .build(),
                RequestBody.empty());
            return true;
        } catch (SdkException e) {
            LOG.error("Failed to create object: {}", key, e);
            return false;
        }
    }

    @Override
    protected OutputStream createObject(String key) throws IOException {
        if (mStreamingUploadEnabled) {
            return new S3ALowLevelOutputStream(mBucketName, key, mS3Client, mExecutor, mUfsConf);
        }
        return new S3AOutputStream(mBucketName, key, mTransferManager,
                mUfsConf.getList(PropertyKey.TMP_DIRS),
                mUfsConf.getBoolean(PropertyKey.UNDERFS_S3_SERVER_SIDE_ENCRYPTION_ENABLED));
    }

    @Override
    protected boolean deleteObject(String key) {
        try {
            mS3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(mBucketName)
                    .key(key)
                    .build());
        } catch (SdkException e) {
            LOG.error("Failed to delete {}", key, e);
            return false;
        }
        return true;
    }

    @Override
    protected List<String> deleteObjects(List<String> keys) throws IOException {
        if (!mUfsConf.getBoolean(PropertyKey.UNDERFS_S3_BULK_DELETE_ENABLED)) {
            return super.deleteObjects(keys);
        }
        Preconditions.checkArgument(keys != null && keys.size() <= getListingChunkLengthMax());
        try {
            List<ObjectIdentifier> objectsToDelete = new ArrayList<>(keys.size());
            for (String key : keys) {
                objectsToDelete.add(ObjectIdentifier.builder().key(key).build());
            }
            DeleteObjectsResponse resp = mS3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(mBucketName)
                    .delete(Delete.builder().objects(objectsToDelete).build())
                    .build());
            List<String> deletedObjects = new ArrayList<>(resp.deleted().size());
            for (DeletedObject deletedObject : resp.deleted()) {
                deletedObjects.add(deletedObject.key());
            }
            return deletedObjects;
        } catch (SdkException e) {
            throw AlluxioS3Exception.from(e);
        }
    }

    @Override
    protected String getFolderSuffix() {
        return mUfsConf.getString(PropertyKey.UNDERFS_S3_DIRECTORY_SUFFIX);
    }

    @Override
    @Nullable
    protected ObjectListingChunk getObjectListingChunk(String key, boolean recursive)
            throws IOException {
        return getObjectListingChunk(key, recursive, null, 0);
    }

    @Nullable
    @Override
    protected ObjectListingChunk getObjectListingChunk(
            String key, boolean recursive, @Nullable String startAfter, int batchSize)
            throws IOException {
        String delimiter = recursive ? "" : PATH_SEPARATOR;
        key = PathUtils.normalizePath(key, PATH_SEPARATOR);
        // In case key is root (empty string) do not normalize prefix.
        key = key.equals(PATH_SEPARATOR) ? "" : key;
        if (mUfsConf.isSet(PropertyKey.UNDERFS_S3_LIST_OBJECTS_V1) && mUfsConf
                .getBoolean(PropertyKey.UNDERFS_S3_LIST_OBJECTS_V1)) {
            ListObjectsRequest request = ListObjectsRequest.builder()
                    .bucket(mBucketName).prefix(key).delimiter(delimiter)
                    .maxKeys(getListingChunkLength(mUfsConf)).build();
            ListObjectsResponse result = getObjectListingChunkV1(request);
            if (result != null) {
                return new S3AObjectListingChunkV1(request, result);
            }
        } else {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(mBucketName).prefix(key).delimiter(delimiter)
                    .maxKeys(getListingChunkLength(mUfsConf));
            if (startAfter != null) {
                requestBuilder.startAfter(startAfter);
            }
            if (batchSize > 0) {
                requestBuilder.maxKeys(batchSize);
            }
            ListObjectsV2Request request = requestBuilder.build();
            ListObjectsV2Response result = getObjectListingChunk(request);
            if (result != null) {
                return new S3AObjectListingChunk(request, result);
            }
        }
        return null;
    }

    // Get next chunk of listing result.
    private ListObjectsV2Response getObjectListingChunk(ListObjectsV2Request request) {
        try {
            // Query S3 for the next batch of objects.
            return mS3Client.listObjectsV2(request);
        } catch (SdkException e) {
            throw AlluxioS3Exception.from(e);
        }
    }

    // Get next chunk of listing result.
    private ListObjectsResponse getObjectListingChunkV1(ListObjectsRequest request) {
        try {
            // Query S3 for the next batch of objects.
            return mS3Client.listObjects(request);
        } catch (SdkException e) {
            throw AlluxioS3Exception.from(e);
        }
    }

    void performGetStatusAsync(
            String path, Consumer<UfsStatus> onComplete,
            Consumer<Throwable> onError) {
        String folderSuffix = getFolderSuffix();
        path = stripPrefixIfPresent(path);
        path = path.equals(folderSuffix) ? "" : path;
        if (path.isEmpty()) {
            onComplete.accept(null);
            return;
        }
        HeadObjectRequest request =
                HeadObjectRequest.builder().bucket(mBucketName).key(path).build();
        String finalPath = path;
        mAsyncClient.headObject(request).whenCompleteAsync((result, err) -> {
            if (err != null) {
                if (err.getCause() instanceof NoSuchKeyException) {
                    onComplete.accept(null);
                } else {
                    onError.accept(parseS3AsyncException(err));
                }
            } else {
                try {
                    ObjectPermissions permissions = getPermissions();
                    long bytes = mUfsConf.getBytes(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT);
                    Instant lastModifiedDate = result.lastModified();
                    Long lastModifiedTime = lastModifiedDate == null ? null
                            : lastModifiedDate.toEpochMilli();
                    UfsStatus status;
                    if (finalPath.endsWith(folderSuffix)) {
                        status = new UfsDirectoryStatus(finalPath, permissions.getOwner(),
                                permissions.getGroup(), permissions.getMode());
                    } else {
                        status = new UfsFileStatus(finalPath,
                                result.eTag().substring(1, result.eTag().length() - 1),
                                result.contentLength(), lastModifiedTime, permissions.getOwner(),
                                permissions.getGroup(), permissions.getMode(), bytes);
                    }
                    onComplete.accept(status);
                } catch (Throwable t) {
                    onError.accept(t);
                }
            }
        });
    }

    @Override
    public void performListingAsync(
            String path, @Nullable String continuationToken, @Nullable String startAfter,
            DescendantType descendantType, boolean checkStatus,
            Consumer<UfsLoadResult> onComplete, Consumer<Throwable> onError) {
        if (checkStatus) {
            Preconditions.checkState(continuationToken == null);
            performGetStatusAsync(path, status -> {
                if (status != null && (status.isFile() || descendantType == DescendantType.NONE)) {
                    onComplete.accept(new UfsLoadResult(Stream.of(status), 1, null,
                            null, false, status.isFile(), true));
                } else {
                    finishListingAsync(status, path, null, startAfter,
                            descendantType, onComplete, onError);
                }
            }, onError);
        } else {
            finishListingAsync(null, path, continuationToken, startAfter,
                    descendantType, onComplete, onError);
        }
    }

    private Throwable parseS3AsyncException(Throwable e) {
        if (e instanceof CompletionException) {
            final Throwable innerErr = e.getCause();
            if (innerErr instanceof S3Exception) {
                S3Exception innerS3Err = (S3Exception) innerErr;
                if (innerS3Err.statusCode() == 307
                        || (innerS3Err.awsErrorDetails().errorCode().equals("AuthorizationHeaderMalformed")
                        && innerS3Err.getMessage().contains("region"))) {
                    return new IOException(
                            "AWS s3 v2 client does not support global region. "
                                    + "Please either specify the region using alluxio.underfs.s3.region "
                                    + "or in your s3 endpoint alluxio.underfs.s3.endpoint.", innerS3Err);
                }
            }
            return new IOException(e.getCause());
        }
        return e;
    }

    private void finishListingAsync(@Nullable UfsStatus baseStatus,
                                    String path, @Nullable String continuationToken, @Nullable String startAfter,
                                    DescendantType descendantType,
                                    Consumer<UfsLoadResult> onComplete, Consumer<Throwable> onError) {
        // if descendant type is NONE then we only want to return the directory itself
        int maxKeys = descendantType == DescendantType.NONE ? 1 : getListingChunkLength(mUfsConf);
        path = stripPrefixIfPresent(path);
        String delimiter = descendantType == DescendantType.ALL ? "" : PATH_SEPARATOR;
        path = PathUtils.normalizePath(path, PATH_SEPARATOR);
        // In case key is root (empty string) do not normalize prefix.
        path = path.equals(PATH_SEPARATOR) ? "" : path;
        String s3StartAfter = null;
        if (path.equals("")) {
            s3StartAfter = startAfter;
        } else if (startAfter != null) {
            s3StartAfter = PathUtils.concatPath(path, startAfter);
        }
        software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder request =
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request
                        .builder().bucket(mBucketName).prefix(path).continuationToken(continuationToken)
                        .startAfter(startAfter == null ? null : s3StartAfter)
                        .delimiter(delimiter).maxKeys(maxKeys);
        String finalPath = path;
        mAsyncClient.listObjectsV2(request.build())
                .whenCompleteAsync((result, err) -> {
                    if (err != null) {
                        onError.accept(parseS3AsyncException(err));
                    } else {
                        try {
                            AlluxioURI lastItem = null;
                            String lastPrefix = result.commonPrefixes().size() == 0 ? null
                                    : result.commonPrefixes().get(result.commonPrefixes().size() - 1).prefix();
                            String lastResult = result.contents().size() == 0 ? null
                                    : result.contents().get(result.contents().size() - 1).key();
                            if (lastPrefix == null && lastResult != null) {
                                lastItem = new AlluxioURI(lastResult);
                            } else if (lastPrefix != null && lastResult == null) {
                                lastItem = new AlluxioURI(lastPrefix);
                            } else if (lastPrefix != null) { // both are non-null
                                lastItem = new AlluxioURI(lastPrefix.compareTo(lastResult) > 0
                                        ? lastPrefix : lastResult);
                            }
                            int keyCount = result.keyCount();
                            Stream<UfsStatus> resultStream = resultToStream(baseStatus, result);
                            if (descendantType == DescendantType.NONE) {
                                Preconditions.checkState(baseStatus == null);
                                // if descendant type is NONE then we only want to return the directory itself
                                Optional<Stream<UfsStatus>> str = resultStream.findFirst().map(item -> {
                                    if (item.isDirectory() && item.getName().equals(finalPath)) {
                                        return Stream.of(item);
                                    } else {
                                        if (item.getName().startsWith(finalPath)) {
                                            // in this case we received a file nested under the path, this can happen
                                            // if there was no marker object for the directory, and it contained
                                            // a nested object
                                            ObjectPermissions permissions = getPermissions();
                                            return Stream.of(new UfsDirectoryStatus(finalPath,
                                                    permissions.getOwner(), permissions.getGroup(), permissions.getMode()));
                                        }
                                    }
                                    return Stream.empty();
                                });
                                resultStream = str.orElse(Stream.empty());
                            }
                            onComplete.accept(
                                    new UfsLoadResult(resultStream,
                                            keyCount,
                                            result.nextContinuationToken(), lastItem,
                                            descendantType != DescendantType.NONE && result.isTruncated(),
                                            false, true));
                        } catch (Throwable t) {
                            onError.accept(t);
                        }
                    }
                });
    }

    private UfsStatus s3ObjToUfsStatus(
            S3Object obj, String folderSuffix, ObjectPermissions permissions, long bytes) {
        if (obj.key().endsWith(folderSuffix)) {
            return new UfsDirectoryStatus(obj.key(), permissions.getOwner(),
                    permissions.getGroup(), permissions.getMode());
        } else {
            Instant lastModifiedDate = obj.lastModified();
            Long lastModifiedTime = lastModifiedDate == null ? null
                    : lastModifiedDate.toEpochMilli();
            return new UfsFileStatus(obj.key(),
                    obj.eTag().substring(1, obj.eTag().length() - 1), obj.size(), lastModifiedTime,
                    permissions.getOwner(), permissions.getGroup(), permissions.getMode(), bytes);
        }
    }

    private UfsStatus prefixToUfsStatus(CommonPrefix prefix, ObjectPermissions permissions) {
        return new UfsDirectoryStatus(
                prefix.prefix(), permissions.getOwner(), permissions.getGroup(),
                permissions.getMode());
    }

    private Stream<UfsStatus> resultToStream(
            @Nullable UfsStatus baseStatus, ListObjectsV2Response response) {
        // Directories are either keys that end with /
        // Or common prefixes which will also end with /
        // All results contain the full path from the bucket root
        ObjectPermissions permissions = getPermissions();
        String folderSuffix = getFolderSuffix();
        long bytes = mUfsConf.getBytes(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT);
        Iterator<UfsStatus> prefixes = response.commonPrefixes().stream().map(
                prefix -> prefixToUfsStatus(prefix, permissions)).iterator();
        Stream<UfsStatus> itemStream = response.contents().stream().map(obj ->
                s3ObjToUfsStatus(obj, folderSuffix, permissions, bytes));
        if (baseStatus != null) {
            itemStream = Stream.concat(Stream.of(baseStatus), itemStream);
        }
        Iterator<UfsStatus> items = itemStream.iterator();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                IteratorUtils.collatedIterator((s1, s2) -> {
                    int val = s1.getName().compareTo(s2.getName());
                    if (val != 0) {
                        return val;
                    }
                    // If they have the same name, then return the directory first
                    if (s1.isDirectory() && s2.isDirectory()) {
                        return 0;
                    }
                    return s1.isDirectory() ? -1 : 1;
                }, prefixes, items),
                Spliterator.ORDERED), false);
    }

    /**
     * Wrapper over the v2 {@link ListObjectsV2Request} / {@link ListObjectsV2Response}.
     */
    private final class S3AObjectListingChunk implements ObjectListingChunk {
        final ListObjectsV2Request mRequest;
        final ListObjectsV2Response mResult;

        S3AObjectListingChunk(ListObjectsV2Request request, ListObjectsV2Response result) {
            Preconditions.checkNotNull(result, "result");
            mRequest = request;
            mResult = result;
        }

        @Override
        public ObjectStatus[] getObjectStatuses() {
            List<S3Object> objects = mResult.contents();
            ObjectStatus[] ret = new ObjectStatus[objects.size()];
            int i = 0;
            for (S3Object obj : objects) {
                Long lastModifiedTime = obj.lastModified() == null
                        ? null : obj.lastModified().toEpochMilli();
                ret[i++] = new ObjectStatus(obj.key(), obj.eTag(),
                        obj.size() == null ? 0L : obj.size(), lastModifiedTime);
            }
            return ret;
        }

        @Override
        public String[] getCommonPrefixes() {
            List<CommonPrefix> res = mResult.commonPrefixes();
            String[] ret = new String[res.size()];
            for (int i = 0; i < res.size(); i++) {
                ret[i] = res.get(i).prefix();
            }
            return ret;
        }

        @Override
        @Nullable
        public ObjectListingChunk getNextChunk() throws IOException {
            if (Boolean.TRUE.equals(mResult.isTruncated())) {
                // v2 request models are immutable — build the next request with the new
                // continuation token taken from the previous response.
                ListObjectsV2Request next = mRequest.toBuilder()
                        .continuationToken(mResult.nextContinuationToken()).build();
                ListObjectsV2Response nextResult = getObjectListingChunk(next);
                if (nextResult != null) {
                    return new S3AObjectListingChunk(next, nextResult);
                }
            }
            return null;
        }

        @Override
        public Boolean hasNextChunk() {
            return mResult.isTruncated();
        }
    }

    /**
     * Wrapper over the v2 {@link ListObjectsRequest} / {@link ListObjectsResponse} (the older
     * marker-paginated listing API, kept for compatibility with v1-listing-only S3 backends).
     */
    private final class S3AObjectListingChunkV1 implements ObjectListingChunk {
        final ListObjectsRequest mRequest;
        final ListObjectsResponse mResult;

        S3AObjectListingChunkV1(ListObjectsRequest request, ListObjectsResponse result) {
            Preconditions.checkNotNull(result, "result");
            mRequest = request;
            mResult = result;
        }

        @Override
        public ObjectStatus[] getObjectStatuses() {
            List<S3Object> objects = mResult.contents();
            ObjectStatus[] ret = new ObjectStatus[objects.size()];
            int i = 0;
            for (S3Object obj : objects) {
                Long lastModifiedTime = obj.lastModified() == null
                        ? null : obj.lastModified().toEpochMilli();
                ret[i++] = new ObjectStatus(obj.key(), obj.eTag(),
                        obj.size() == null ? 0L : obj.size(), lastModifiedTime);
            }
            return ret;
        }

        @Override
        public String[] getCommonPrefixes() {
            List<CommonPrefix> res = mResult.commonPrefixes();
            String[] ret = new String[res.size()];
            for (int i = 0; i < res.size(); i++) {
                ret[i] = res.get(i).prefix();
            }
            return ret;
        }

        @Override
        @Nullable
        public ObjectListingChunk getNextChunk() throws IOException {
            if (Boolean.TRUE.equals(mResult.isTruncated())) {
                ListObjectsRequest next = mRequest.toBuilder()
                        .marker(mResult.nextMarker()).build();
                ListObjectsResponse nextResult = getObjectListingChunkV1(next);
                if (nextResult != null) {
                    return new S3AObjectListingChunkV1(next, nextResult);
                }
            }
            return null;
        }
    }

    @Override
    @Nullable
    protected ObjectStatus getObjectStatus(String key) {
        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(mBucketName)
                .key(key)
                .build();
        try {
            HeadObjectResponse meta = mS3Client.headObject(req);
            Long lastModifiedTime = meta.lastModified() == null
                    ? null : meta.lastModified().toEpochMilli();
            return new ObjectStatus(key, meta.eTag(),
                    meta.contentLength() == null ? 0L : meta.contentLength(),
                    lastModifiedTime);
        } catch (NoSuchKeyException e) {
            // file not found, possible for exists calls
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw AlluxioS3Exception.from(e);
        } catch (SdkException e) {
            throw AlluxioS3Exception.from(e);
        }
    }

    @Override
    protected ObjectPermissions getPermissions() {
        return mPermissions.get();
    }

    /**
     * Since there is no group in S3 acl, the owner is reused as the group. This method calls the
     * S3 API and requires additional permissions aside from just read only. This method is best
     * effort and will continue with default permissions (no owner, no group, 0700).
     *
     * @return the permissions associated with this under storage system
     */
    private ObjectPermissions getPermissionsInternal() {
        short bucketMode =
                ModeUtils.getUMask(mUfsConf.getString(PropertyKey.UNDERFS_S3_DEFAULT_MODE)).toShort();
        String accountOwner = DEFAULT_OWNER;

        // if ACL enabled try to inherit bucket acl for all the objects.
        if (mUfsConf.getBoolean(PropertyKey.UNDERFS_S3_INHERIT_ACL)) {
            try {
                // v2 has no direct equivalent of v1's getS3AccountOwner() — the v1 call
                // is internally a ListBuckets and reads back the canonical Owner. We do
                // the same explicitly; the s3:ListAllMyBuckets grant required by v1
                // continues to be the relevant IAM action for this code path.
                ListBucketsResponse listResp = mS3Client.listBuckets();
                software.amazon.awssdk.services.s3.model.Owner owner = listResp.owner();
                GetBucketAclResponse acl = mS3Client.getBucketAcl(
                        GetBucketAclRequest.builder().bucket(mBucketName).build());

                bucketMode = S3AUtils.translateBucketAcl(acl, owner.id());
                if (mUfsConf.isSet(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING)) {
                    // Here accountOwner can be null if there is no mapping set for this owner id
                    accountOwner = CommonUtils.getValueFromStaticMapping(
                            mUfsConf.getString(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING),
                            owner.id());
                }
                if (accountOwner == null || accountOwner.equals(DEFAULT_OWNER)) {
                    // If there is no user-defined mapping, use display name or id.
                    accountOwner = owner.displayName() != null ? owner.displayName() : owner.id();
                }
            } catch (SdkException e) {
                LOG.warn("Failed to inherit bucket ACLs, proceeding with defaults. {}", e.toString());
            }
        }

        return new ObjectPermissions(accountOwner, accountOwner, bucketMode);
    }

    @Override
    protected String getRootKey() {
        if ("s3a".equals(mUri.getScheme())) {
            return Constants.HEADER_S3A + mBucketName;
        } else {
            return Constants.HEADER_S3 + mBucketName;
        }
    }

    @Override
    protected InputStream openObject(String key, OpenOptions options,
                                     RetryPolicy retryPolicy) {
        return new S3AInputStream(mBucketName, key, mS3Client, options.getOffset(), retryPolicy);
    }
}
