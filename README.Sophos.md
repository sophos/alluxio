The main branch of this fork is `sophos/main`. It was forked from the upstream
`release-2.9.5` branch.

## Fork-specific changes

- Support of assumeRole for AWS S3 access and arm64 CPU
  Commit: `7f10520ef2fa317663b32008f0592d635b1fcd24`
- Fixing the Netty EPoll library dependencies to make it compatible with Trino client
  Commit: `98e48089af2494c07306855d9a20bd8a2e1c247e`
- Adding support for capacity overprovisioning allowing to take all available disk space
  Commit: `a3e0f795ce29cee409aee3673d6f692438be8cd8`
- Support for Docker registry proxy
  Commit: `f763dfc308df906c0b5f82d89f64be67127eae11`
- ACL inheritance preservation during metadata sync
  Branch feature: `sophos/release-2.9.6-acl`
- Kubernetes TokenReview-based custom authentication provider
  Branch feature: current working tree

## ACL Inheritance On Metadata Sync

### What problem this solves

When Alluxio lazily materializes directory metadata from UFS during sync, the
normal parent default ACL inheritance flow can be overwritten by the UFS mode
that arrives with the synced directory. In shared-cache deployments that can
re-open `other::rwx` on newly discovered descendants even when the mount root
was intentionally tightened.

This fork adds a master-side flag to preserve the inherited ACL for
metadata-loaded directories.

### Property

```properties
alluxio.security.authorization.sync.inherit-parent-acl=false
```

Default is `false` for safe rollout.

### How it behaves

- `false`: keep historical behavior. Metadata-loaded directories may still take
  their access-side mode from UFS.
- `true`: if the parent directory has a non-empty default ACL, metadata-loaded
  child directories keep the inherited access ACL instead of reopening access
  from the UFS mode.

### Recommended rollout

1. Deploy the patched image with the flag still `false`.
2. Confirm the target mount roots already have the desired default ACL entries.
3. Flip `alluxio.security.authorization.sync.inherit-parent-acl=true`.
4. In the same rollout window, tighten root `other::---` and backfill already
   cached descendants.

Do not tighten root `other::---` before enabling the inheritance flag if your
existing descendants were previously materialized without the desired inherited
ACL, or you can strand legitimate reads.

### Example master configuration

```properties
alluxio.security.authorization.permission.enabled=true
alluxio.security.authorization.permission.umask=022
alluxio.security.authorization.sync.inherit-parent-acl=true
```

### Example mount-root ACL

Example for a `trino-metabase` tenant:

```bash
alluxio fs setfacl -m \
  user:trino-metabase:r-x,other::---,default:user:trino-metabase:r-x,default:other::--- \
  /data-series-shared-eu-west-1-dev-tlm-datalake
```

### Example backfill after enabling the flag

Use bounded recursion against already-cached metadata:

```bash
alluxio fs -Dalluxio.user.file.metadata.sync.interval=-1 setfacl -R \
  -m user:trino-metabase:r-x,other::---,default:user:trino-metabase:r-x,default:other::--- \
  /data-series-shared-eu-west-1-dev-tlm-datalake
```

This backfill is still required once after rollout for descendants that were
materialized before the flag was enabled.

## Kubernetes TokenReview Authentication Provider

### What problem this solves

With `alluxio.security.authentication.type=SIMPLE`, the server trusts whatever
user name the client claims. In a shared deployment that means any pod able to
reach the master can potentially claim another tenant's Alluxio identity.

This fork adds a custom authentication provider that validates Kubernetes
projected ServiceAccount tokens through the TokenReview API.

### What is implemented here

This change adds the server-side provider and its configuration surface:

- `alluxio.security.authentication.k8s.api.endpoint`
- `alluxio.security.authentication.k8s.ca.path`
- `alluxio.security.authentication.k8s.service.account.token.path`
- `alluxio.security.authentication.k8s.audience`
- `alluxio.security.authentication.k8s.service.account.namespace`
- `alluxio.security.authentication.k8s.service.account.name.template`
- `alluxio.security.authentication.k8s.cache.ttl`

The provider validates:

- TokenReview returned `authenticated=true`
- TokenReview returned the configured audience
- TokenReview returned the expected namespace
- returned ServiceAccount name matches the configured template
- claimed Alluxio user matches the user extracted from the ServiceAccount name

Successful `(claimed user, token)` validations are cached for the configured
TTL to reduce TokenReview load.

### Important scope note

This repository change implements the server-side provider only. It expects the
client to send the Kubernetes ServiceAccount token in the SASL password field.

If your client-side integration has not yet been updated to load and pass the
projected token, enabling `CUSTOM` auth with this provider will not work by
itself.

### Example master configuration

```properties
alluxio.security.authentication.type=CUSTOM
alluxio.security.authentication.custom.provider.class=alluxio.security.authentication.k8s.K8sTokenAuthenticationProvider

alluxio.security.authentication.k8s.api.endpoint=https://kubernetes.default.svc
alluxio.security.authentication.k8s.ca.path=/var/run/secrets/kubernetes.io/serviceaccount/ca.crt
alluxio.security.authentication.k8s.service.account.token.path=/var/run/secrets/kubernetes.io/serviceaccount/token
alluxio.security.authentication.k8s.audience=alluxio-master
alluxio.security.authentication.k8s.service.account.namespace=group-central-data-platform
alluxio.security.authentication.k8s.service.account.name.template=trino-{user}-sa
alluxio.security.authentication.k8s.cache.ttl=30sec
```

### Matching examples

With:

```properties
alluxio.security.authentication.k8s.service.account.namespace=group-central-data-platform
alluxio.security.authentication.k8s.service.account.name.template=trino-{user}-sa
```

These TokenReview identities are accepted for the corresponding claimed Alluxio
users:

- `system:serviceaccount:group-central-data-platform:trino-metabase-sa`
  Claimed user: `trino-metabase`
- `system:serviceaccount:group-central-data-platform:trino-ng-sa`
  Claimed user: `trino-ng`

These are rejected:

- Wrong namespace
  Example: `system:serviceaccount:default:trino-metabase-sa`
- Wrong ServiceAccount name
  Example: `system:serviceaccount:group-central-data-platform:reader-sa`
- Wrong claimed user
  Example: TokenReview user `...:trino-ng-sa` while the client claims `trino-metabase`
- Wrong audience
  Example: TokenReview response does not include `alluxio-master`

### Example Kubernetes setup

The Alluxio master needs a ServiceAccount token it can use to call
`authentication.k8s.io/v1/tokenreviews`.

Example RBAC:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: alluxio-tokenreviewer
rules:
- apiGroups: ["authentication.k8s.io"]
  resources: ["tokenreviews"]
  verbs: ["create"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: alluxio-tokenreviewer
subjects:
- kind: ServiceAccount
  name: alluxio-master
  namespace: group-central-data-platform
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: alluxio-tokenreviewer
```

Example projected token for a tenant workload that should authenticate as
`trino-metabase`:

```yaml
volumes:
- name: alluxio-token
  projected:
    sources:
    - serviceAccountToken:
        path: token
        audience: alluxio-master
        expirationSeconds: 3600
```

### Recommended enablement sequence

1. Roll out the patched image with `CUSTOM` auth still disabled.
2. Prepare master RBAC for `tokenreviews.create`.
3. Ensure the client path that talks to Alluxio can supply the projected token
   in the SASL password field.
4. Set `alluxio.security.authentication.type=CUSTOM`.
5. Watch authentication failures and TokenReview volume.

## Putting Both Changes Together

For shared Alluxio used by multiple Trino groups, the intended pairing is:

- Kubernetes TokenReview auth proves the caller really is the claimed tenant.
- ACL inheritance preservation keeps newly synced descendants from reopening
  cross-tenant access.

In practice the safe sequence is:

1. Ship both code changes.
2. Enable `CUSTOM` auth once clients can send projected tokens.
3. Enable `alluxio.security.authorization.sync.inherit-parent-acl=true`.
4. Tighten mount-root ACLs and run one bounded recursive backfill.

Enabling only one of the two leaves a gap:

- auth without ACL enforcement still lacks isolation
- ACL intent without trustworthy identity can still be spoofed
