# CSA-21975 — Phase 0 Inventory: AWS SDK v2 Migration + S3 Express Support

> Status: Phase 3 (Maven cleanup) code work done — image build / dev cutover still outstanding
> Branch: `sophos/CSA-21975-sdk-v2-migration` (from `sophos/release-2.9.6`)
> Full plan: see `alluxio-sdk-v2-and-s3express-plan.md` in the `taegis/trino` monorepo.

## 1. Why

The Sophos fork's `underfs/s3a` module still uses AWS Java SDK **v1** for every write path (PUT/COPY/DELETE/multipart upload, ACL ops, TransferManager). SDK v1 has been on EOL maintenance mode since Jul-2024 and will go fully end-of-life Dec-2025. It also has zero awareness of **S3 Express One Zone (directory buckets)** — it can neither resolve their zonal endpoints nor mint the per-bucket session credentials required by `CreateSession`. Trino CTAS into Sophos's `search-dev-results--use2-az1--x-s3` directory bucket fails today with `NoSuchBucket` for exactly this reason.

Migrating the entire S3 UFS to SDK v2 simultaneously closes the CVE/EOL exposure and unblocks S3 Express. v2 also brings async I/O, the standard pluggable HTTP client, and built-in IRSA support via `WebIdentityTokenFileCredentialsProvider`, all of which the fork already partially adopts (see §3.b).

## 2. Repo state at branch creation

| Artifact | Value |
| --- | --- |
| Fork repo | `git@github.com:sophos/alluxio.git` |
| Working branch | `sophos/CSA-21975-sdk-v2-migration` |
| Base branch | `sophos/release-2.9.6` (Sophos-namespaced release branch from upstream `release-2.9.6`) |
| SDK v1 version pin | `aws.amazonaws.version = 1.12.797` (terminal maintenance release; root `pom.xml:127`) |
| SDK v2 version pin | `awssdk.version = 2.44.7` via `software.amazon.awssdk:bom` (root `pom.xml:141`, `pom.xml:843`) |
| S3 Express plugin present? | **Yes** — `software/amazon/awssdk/services/s3/internal/s3express/S3ExpressPlugin.class` etc. ship in `s3-2.44.7.jar`. No SDK version bump required. |

## 3. SDK footprint inventory

### a. AWS SDK v1 imports — modules to migrate

`rg "^import com\.amazonaws\." --type java -l` returned 26 files. Categorized:

| Module | Main sources | Tests | Notes |
| --- | --- | --- | --- |
| `underfs/s3a/` | 7 files | 6 files | **Primary migration target.** All write paths use v1. |
| `core/common/` | 2 files | 1 test | `EC2MetadataUtils` only (EC2 instance detection in `EnvironmentUtils`, `UpdateCheck`). v2 equivalent: `software.amazon.awssdk.regions.internal.util.EC2MetadataUtils`. Out of scope for S3 Express but in scope for the v1 cleanup. |
| `core/server/proxy/` | 1 file | — | `AuthorizationV4Validator` uses `com.amazonaws.SdkClientException` + `com.amazonaws.auth.SigningAlgorithm`. Trino-target S3 proxy — different surface. Migrate alongside v1 cleanup (phase 6). |
| `core/server/master/` | — | 2 tests | `FileSystemMasterS3UfsTest`, `MetadataSyncV2TestBase` — integration tests that build mock S3 fixtures with v1. Rewrite in lockstep with `underfs/s3a`. |
| `core/server/worker/` | — | 1 test | `ShortCircuitBlockWriteHandlerTest` — single import to triage. |
| `tests/` | — | 1 test | `FileSystemS3UfsIntegrationTest` — integration test. |
| `table/server/underdb/glue/` | 5 files | 3 tests | **Dead code** — module dropped from the Maven reactor in commit `426722b4ab`. Delete after migration or leave alone (compiler never sees it). |

### b. AWS SDK v2 imports already present

`rg "^import software\.amazon\.awssdk\." --type java -l` returned 4 files:

- `underfs/s3a/src/main/java/alluxio/underfs/s3a/S3AUnderFileSystem.java` — 19 v2 imports. Used for:
  - `S3AsyncClient` (async listings)
  - `NettyNioAsyncHttpClient` (non-blocking HTTP)
  - `Http2Configuration`, `ProxyConfiguration`, `ClientAsyncConfiguration`, `ClientOverrideConfiguration`
  - `Region`, `S3Configuration`
  - `DefaultCredentialsProvider`, `StaticCredentialsProvider`, `AwsBasicCredentials`, `AwsCredentialsProvider`
  - List/HEAD models: `ListObjectsV2Response`, `S3Object`, `CommonPrefix`, `HeadObjectRequest`, `NoSuchKeyException`, `S3Exception`
- `underfs/s3a/src/test/java/.../S3AUnderFileSystemTest.java` — 1 v2 import (mocks for async listings).
- `underfs/s3a/src/test/java/.../S3AUnderFileSystemMockServerTest.java` — 4 v2 imports.
- `core/server/master/src/test/java/alluxio/master/file/MetadataSyncV2TestBase.java` — 8 v2 imports.

**Key insight**: `S3AUnderFileSystem` is already a **hybrid** v1 + v2 class. SDK v2 has been used for listings and the netty NIO HTTP client; SDK v1 still handles *every* write path. The migration is therefore additive on the v2 side and subtractive on the v1 side, not a greenfield rewrite.

### c. `underfs/s3a` per-file v1 surface

| File | v1 imports | What needs to be replaced |
| --- | --- | --- |
| `S3AUnderFileSystem.java` | 35 | Client builders (`AmazonS3ClientBuilder`, `AmazonS3Client`), `TransferManager`/`TransferManagerBuilder`, credentials (`AWSCredentialsProvider`, `AWSStaticCredentialsProvider`, `BasicAWSCredentials`, `DefaultAWSCredentialsProviderChain`, `STSAssumeRoleSessionCredentialsProvider`, `AWSSecurityTokenServiceClient`), models (`ObjectMetadata`, `PutObjectRequest`, `CopyObjectRequest`, `DeleteObjectsRequest`, `DeleteObjectsResult`, `ListObjectsRequest`, `ListObjectsV2Request`, `ListObjectsV2Result`, `ObjectListing`, `AccessControlList`, `Owner`, `S3ObjectSummary`), `ClientConfiguration`, `Protocol`, `Regions`, `AwsClientBuilder`, `AwsHostNameUtils`, `Mimetypes`, `ServiceUtils`, `RuntimeHttpUtils`, `Base64`, `AmazonClientException`, `AmazonServiceException`, `SdkClientException` |
| `S3ALowLevelOutputStream.java` | 10 | `AmazonS3`, `InitiateMultipartUploadRequest`, `UploadPartRequest`, `CompleteMultipartUploadRequest`, `AbortMultipartUploadRequest`, `PartETag`, `PutObjectRequest`, `ObjectMetadata`, `Mimetypes`, `SdkClientException` |
| `S3AOutputStream.java` | 5 | `TransferManager`, `PutObjectRequest`, `ObjectMetadata`, `Mimetypes`, `Base64` |
| `S3AInputStream.java` | 4 | `AmazonS3`, `GetObjectRequest`, `S3ObjectInputStream`, `AmazonS3Exception` |
| `S3AUtils.java` | 5 | `AccessControlList`, `Grant`, `Grantee`, `GroupGrantee`, `Permission` (ACL model only) |
| `AlluxioS3Exception.java` | 2 | `AmazonClientException`, `AmazonS3Exception` (exception adapter) |
| `S3AUnderFileSystemFactory.java` | 1 | `AmazonClientException` (rethrow) |

Total: **62 v1 import statements** to remove from `underfs/s3a/main` plus their dependent code paths (~1,840 LOC across 7 files).

### d. Maven dependency graph in `underfs/s3a/pom.xml`

```xml
<!-- v2 (keep, expand) -->
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>s3</artifactId>
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>netty-nio-client</artifactId>
</dependency>

<!-- v1 (remove at end of migration) -->
<dependency>
  <groupId>com.amazonaws</groupId>
  <artifactId>aws-java-sdk-core</artifactId>
</dependency>
<dependency>
  <groupId>com.amazonaws</groupId>
  <artifactId>aws-java-sdk-s3</artifactId>
</dependency>
<dependency>
  <groupId>com.amazonaws</groupId>
  <artifactId>aws-java-sdk-sts</artifactId>
</dependency>
```

Phase 6 cleanup will also drop the `aws.amazonaws.version` property and `<dependencyManagement>` v1 entries (`pom.xml:248-261`).

## 4. Test coverage

Six classes under `underfs/s3a/src/test/java/alluxio/underfs/s3a/`:

| Test class | LOC | Coverage area |
| --- | --- | --- |
| `S3AUnderFileSystemTest.java` | 273 | Top-level UFS construction, credential provider selection, region resolution, prefix handling. Heavy v1 mocking via `Mockito`. |
| `S3AUnderFileSystemMockServerTest.java` | 230 | Uses a mock S3 server (likely `S3Mock` or `LocalStack`) to exercise listing, head, put, get, delete against the in-process fake. |
| `S3ALowLevelOutputStreamTest.java` | 236 | Multipart upload (initiate → uploadPart → complete) and abort semantics. Pure unit tests with v1 mocks. |
| `S3AOutputStreamTest.java` | 135 | TransferManager-based simple put. |
| `S3AUtilsTest.java` | 125 | ACL/Grant/Grantee conversion utilities. |
| `S3AUnderFileSystemFactoryTest.java` | 78 | Factory pattern: scheme matching, mount-option propagation. |

**Coverage gaps to close during migration**:
- No S3 Express test exists today. Add a `LocalStack`-backed test for the S3 Express endpoint path or use SDK v2's `S3AsyncClient.s3ExpressEnabled()` against a stubbed `CreateSession` endpoint.
- No test verifies behavior under IRSA (`WebIdentityTokenFileCredentialsProvider`). Add one with the projected-token env vars stubbed.
- No multipart copy test. Add one.

## 5. S3 Express plugin verification (SDK v2 = 2.44.7)

`jar tf ~/.m2/repository/software/amazon/awssdk/s3/2.44.7/s3-2.44.7.jar | grep -i s3express` shows the full set:

- `software.amazon.awssdk.services.s3.s3express.*` — public API surface
- `software.amazon.awssdk.services.s3.internal.s3express.S3ExpressPlugin`
- `S3ExpressAuthSchemeProvider`, `DefaultS3ExpressAuthScheme`, `DefaultS3ExpressHttpSigner`
- `DefaultS3ExpressIdentityProvider`, `DefaultS3ExpressSessionCredentials`, `S3ExpressIdentityCache`, `CachedS3ExpressCredentials`
- `UseS3ExpressAuthResolver`, `S3ExpressUtils`, `S3ExpressIdentityKey`
- `KnownS3ExpressEndpointProperty$AuthSchemesProperty` in the endpoints resolver

Net: 2.44.7 already auto-enables `S3ExpressPlugin` when `S3Client`/`S3AsyncClient` is built via the default builder. Calling `s3ExpressEnabled(true)` (default) is sufficient — endpoint resolution and `CreateSession` are handled transparently by the resolver chain when the bucket name matches the directory-bucket suffix pattern (`--<az>--x-s3`).

## 6. Architecture decisions (locked in for this branch)

Confirmed with the user; capturing here so they don't drift:

- **In-place rewrite, no runtime flag.** `S3AUnderFileSystem` keeps its class name and file path; its guts are rewritten on SDK v2 across this branch's commits. There is no `S3UnderFileSystemV2` class, no `alluxio.underfs.s3.sdk.version` PropertyKey, and no factory dispatch. The v1 and v2 paths never coexist at runtime — every commit produces a binary that uses some mix of v1 + v2 internally (no different from today's hybrid state), and the final commit drops the last v1 imports and removes v1 deps from `pom.xml`. **Rollback path during the dev bake** is `git revert` of the merge commit + helmfile-sync the prior image tag from Harbor.
- **Per-mount `options` overrides** apply only to the endpoint/DNS knobs (`alluxio.underfs.s3.endpoint`, `alluxio.underfs.s3.disable.dns.buckets`) that Alluxio already supports. For S3 Express buckets the deployment plan is to pass:
  ```yaml
  options:
    alluxio.underfs.s3.endpoint: "https://s3express-use2-az1.us-east-2.amazonaws.com"
    alluxio.underfs.s3.disable.dns.buckets: "false"
  ```
  These become inputs to the SDK v2 client builder (`endpointOverride(...)`, `S3Configuration.pathStyleAccessEnabled(false)`).
- **IAM model for S3 Express**: only `s3express:CreateSession` on `arn:aws:s3express:<region>:<acct>:bucket/<name>` with a `StringEquals s3express:SessionMode = ReadWrite` condition. **No** `s3:GetObject`/`s3:PutObject` grants in the `s3express` ARN namespace — object-level authorization is performed against the session credentials returned by `CreateSession`. This was the bug in the original IAM draft; corrected version lives in `taegis/trino/alluxio/terraform/datalake.tf` (Phase 4 target).
- **Out of scope for this branch**: the `core/server/proxy` and `core/common` v1 → v2 conversions (`EC2MetadataUtils`, `SigningAlgorithm`, etc.). They land in a separate follow-up branch once `underfs/s3a` is green, since they don't gate S3 Express and pulling them in would balloon the diff.

## 7. Phase 0 exit criteria — status

- [x] Verify SDK v2 version target ≥ S3 Express GA (`2.44.7` ≥ `2.21.x`).
- [x] Confirm `S3ExpressPlugin` and friends ship in the BOM-resolved jars.
- [x] Inventory every v1 callsite, bucketed by module.
- [x] Inventory v2 callsites already present (so we don't duplicate work).
- [x] Map test classes to the public surface of `underfs/s3a`.
- [x] Lock in architecture decisions (in-place rewrite, per-mount options, IAM model).
- [x] Create the working branch off `sophos/release-2.9.6`.

Next phase: **Phase 1 — Foundation rewrite** — replace client construction, credentials, and exception adapter inside the existing `S3AUnderFileSystem` class with v2 equivalents. See plan doc §Phase 1.
