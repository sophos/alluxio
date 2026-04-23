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
- Recursive `setfacl -R` silently skips default ACL entries on files
  Branch feature: `sophos/release-2.9.6-acl`
- Per-side `updateMask` gate: explicit default mask no longer suppresses
  access-mask auto-recompute (and vice versa)
  Branch feature: `sophos/release-2.9.6-acl`
- Kubernetes TokenReview-based custom authentication provider
  Branch feature: `sophos/release-2.9.6-acl`
- Internal Alluxio service account pass-through in the TokenReview provider,
  so Alluxio's own master↔master and worker↔master RPCs authenticate under
  the same `CUSTOM` auth mode as external tenants
  Branch feature: `sophos/release-2.9.6-acl`
- Helm chart: `alluxio.extraVolumes` / `alluxio.extraVolumeMounts` helpers
  extended to accept `projected` volumes (required by the TokenReview
  provider, which only trusts audience-bound projected tokens)
  Branch feature: `sophos/release-2.9.6-acl`

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
materialized before the flag was enabled. The mixed access + default entry
list above works on a subtree that contains files because of the companion
`setfacl -R` patch described below; on stock Alluxio the same command aborts
on the first file with "Can not set default ACL for a file".

## `setfacl -R` Skips Default Entries On Files

### What problem this solves

POSIX default ACLs are a directory-only concept, but upstream
`setAclSingleInode` throws `UnsupportedOperationException` on any default
entry applied to a file. The recursive driver passes the full entries list
to every descendant, so `setfacl -R -m "<access>,default:..." <path>` aborts
on the first file in the walk — setfacl is all-or-nothing per inode.

This breaks the cached-inode backfill pattern that the ACL-inheritance patch
above explicitly recommends: to propagate refreshed defaults to pre-existing
cached directories you need to recursively apply a mixed access + default
entries list over a subtree that mixes dirs and files.

### How it behaves

Inside `setAclRecursive`, the entries list is partitioned once into access
entries and default entries. For each descendant, the driver checks the
inode type and projects the list: directories receive the full list,
files receive access entries only. Matches linux `setfacl -R` semantics.

The non-recursive path — the root inode applied by `setAclRecursive` and any
explicit single-target call through `setAclSingleInode` — is unchanged.
Passing `default:*` directly at a file with no recursion still raises
`UnsupportedOperationException`, which is the user error the guard is there
to catch.

### No flag

No configuration knob. This is straight POSIX behavior; the previous throw
was a bug that ACL inheritance depended on fixing to be operationally useful.

## Per-Side `updateMask` Gate

### What problem this solves

`MutableInode.updateMask(entries)` is the hook `setAcl` uses to recompute the
POSIX mask after named/owning-group entries change. Upstream treated ANY
`AclEntryType.MASK` in the input list as a global "caller supplied mask,
don't touch anything" signal and returned immediately — regardless of whether
the supplied mask was an access mask or a default mask.

That silently broke the exact workflow this fork exists to support. The
alluxio-config ACL backfill applies a mixed spec per mount, e.g.:

```
user:trino-metabase:r-x,
default:user::rwx, default:group::---,
default:mask::rwx, default:other::---,
default:user:trino-metabase:r-x
```

The list carries a named-user *access* grant AND an explicit *default* mask,
but NO explicit access mask. On a fresh-from-sync inode whose
`ExtendedACLEntries` mask is still the initialised `---`, the upstream
early-return skipped the access-mask recompute. The named entry was then
AND'ed against a `---` mask and the tenant that was just granted `r-x` got
denied. Externally this looked like new UFS-synced partitions being
unreadable to a user who clearly had an ACL for them (confirmed via
`getfacl`: `mask::---` shadowing `user:trino-metabase:r-x`).

### How it behaves

`updateMask` now tracks access-side and default-side mask presence in the
input list independently, and gates auto-recompute per side:

- access mask provided → skip access-mask recompute, honour supplied value
- access mask NOT provided → recompute access mask from named/owning-group
- same rule for default mask, independently

This matches linux `setfacl` semantics. It means a caller can explicitly set
`default:mask::rwx` and still have the access mask auto-computed to cover
whatever named entries are in the same call.

### No flag

No configuration knob. The upstream behaviour was a bug; there is no
scenario where you would want supplying a default mask to also freeze the
access mask at its ExtendedACLEntries initial value.

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
- `alluxio.security.authentication.k8s.internal.service.account.name`
  (optional, see "Internal Alluxio service account pass-through" below)
- `alluxio.security.authentication.k8s.internal.user`
  (optional, defaults to `alluxio`)

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

### Internal Alluxio service account pass-through

The `service.account.name.template` is shaped for external tenant clients
(e.g. a Trino `trino-metabase` pod authenticating as the `trino-metabase`
Alluxio user out of `trino-metabase-sa`). Alluxio's own master↔master and
worker↔master RPCs do not fit that shape: they authenticate as the built-in
user `alluxio` from an AZ-scoped SA such as `alluxio-eu-west-1a-sa`, which
cannot be expressed in `trino-{user}-sa` without also accidentally matching
legitimate external tenants whose user happens to equal the AZ suffix.

When the two optional master properties below are set, the provider
short-circuits the template match for any TokenReview whose reviewed SA
equals `internal.service.account.name`, accepting the request iff the
claimed Alluxio user equals `internal.user`:

```properties
alluxio.security.authentication.k8s.internal.service.account.name=alluxio-eu-west-1a-sa
alluxio.security.authentication.k8s.internal.user=alluxio
```

Leaving `internal.service.account.name` unset (the default) preserves
template-only behaviour, so the short-circuit is inert for deployments that
do not need it.

Both properties are MASTER-scoped; the provider is never constructed on the
client, so clients see no behaviour change.

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

With the internal-SA configuration on top:

```properties
alluxio.security.authentication.k8s.internal.service.account.name=alluxio-eu-west-1a-sa
alluxio.security.authentication.k8s.internal.user=alluxio
```

These TokenReview identities are additionally accepted:

- `system:serviceaccount:group-central-data-platform:alluxio-eu-west-1a-sa`
  Claimed user: `alluxio` (anything else is rejected)

These are still rejected:

- `system:serviceaccount:group-central-data-platform:alluxio-eu-west-1a-sa`
  claiming user `trino-metabase` — SA matches internal but claimed user does not

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

## Alluxio Helm Chart: Projected Volume Support

### What problem this solves

The Kubernetes TokenReview provider above only trusts tokens whose `aud`
claim matches `alluxio.security.authentication.k8s.audience`. The legacy
ServiceAccount token at
`/var/run/secrets/kubernetes.io/serviceaccount/token` is stamped with the
API-server default audience and is therefore rejected. The only standard
way to obtain an audience-bound token is a projected ServiceAccount token
volume.

The chart shipped in the binary tarball at
`integration/kubernetes/helm-chart/alluxio/` is the natural mount point for
this, but upstream's `alluxio.extraVolumes` and `alluxio.extraVolumeMounts`
helpers hardcode the volume type to `configMap` / `emptyDir` and
unconditionally emit a `readOnly:` field, so a `projected` volume cannot be
expressed through values.

### How it behaves

The two helper templates in
`integration/kubernetes/helm-chart/alluxio/templates/_helpers.tpl` are
extended minimally:

- `alluxio.extraVolumes`: adds a `projected` case that passes
  `volume.projected.sources` through verbatim, so any projection source
  type (not only `serviceAccountToken`) is supported.
- `alluxio.extraVolumeMounts`: the `readOnly:` field is now only emitted
  when the caller explicitly sets it, so existing callers that leave it
  unset no longer regress.

The `configMap` and `emptyDir` branches are unchanged.

### Why it lives here

The tarball builder (`dev/scripts/.../generate-tarball.go`) copies this
chart directory verbatim into every `alluxio-<version>-bin.tar.gz`
release, so downstream consumers that pull the chart out of the tarball
automatically pick up the patch. Fixing it upstream in the fork keeps the
chart source of truth and the Java source of truth in one repository and
avoids downstream forks of the same helper.

### Example values

```yaml
master:
  extraVolumes:
    - name: alluxio-token
      projected:
        defaultMode: 420
        sources:
          - serviceAccountToken:
              audience: alluxio-master
              expirationSeconds: 3600
              path: token
  extraVolumeMounts:
    - name: alluxio-token
      mountPath: /var/run/secrets/alluxio
      readOnly: true
```

## Alluxio Helm Chart: Writable Overlays for readOnlyRootFilesystem

### What problem this solves

A prior security-hardening commit to the same chart added
`readOnlyRootFilesystem: true` to the `alluxio-master`, `alluxio-job-master`,
`alluxio-worker`, and `alluxio-job-worker` containers (to close Trivy
finding KSV-0014). That flag clashes with the image entrypoint
(`integration/docker/entrypoint.sh`), which runs at `WORKDIR=/opt/alluxio`
and conditionally writes under `conf/`:

- `conf/alluxio-env.sh` (from selected env vars)
- `conf/log4j.properties` (copied from the baked `/tmp/log4j.properties`
  template, or overwritten from `$ALLUXIO_LOG4J_PROPERTIES`)
- `conf/alluxio-site.properties` (from `$ALLUXIO_SITE_PROPERTIES`)

Under a read-only root filesystem those writes fail and the pod never
reaches the Alluxio start command. The baked `/opt/alluxio/conf/` directory
shipped in the image also has to remain visible to the running process, so
a plain emptyDir mount at `/opt/alluxio/conf` would shadow the baked files.

### How it behaves

Both `templates/master/statefulset.yaml` and `templates/worker/daemonset.yaml`
now:

1. Declare a `seed-alluxio-conf` initContainer that runs the Alluxio image
   itself and copies `/opt/alluxio/conf/.` into a shared emptyDir
   (`alluxio-conf`) before the main containers start. The copy uses `cp -R`
   rather than `cp -a`: the initContainer runs as `runAsUser: 1000` but the
   emptyDir mount point is root-owned by default, and `cp -a` preserves
   timestamps by `utimes()`-ing the destination dir, which fails with
   `Operation not permitted` for a non-root user on a root-owned dir. `cp -R`
   preserves file modes but does not touch the mount-point timestamps, so it
   succeeds without requiring `fsGroup` gymnastics; the Alluxio entrypoint
   does not rely on mtime/atime of `conf/`, so the lossy copy is harmless.
2. Mount writable overlays into every application container:
   - `alluxio-conf` at `/opt/alluxio/conf` (seeded with the baked
     configuration, now writable),
   - `alluxio-logs` at `/opt/alluxio/logs`,
   - `tmp` at `/tmp`.
3. Master-only overlays (on the `alluxio-master` and `alluxio-job-master`
   containers inside the master StatefulSet pod):
   - `alluxio-metastore-overlay` at `/opt/alluxio/metastore` on the
     `alluxio-master` container -- the RocksDB off-heap metastore
     (`blocks/`, `inodes/`). Default
     `alluxio.master.metastore.dir=${alluxio.work.dir}/metastore` resolves
     onto the read-only rootfs; providing this emptyDir lets RocksDB
     `mkdir` its subdirs at boot. Safe as ephemeral: RocksDB state is
     rebuilt from the Raft journal on startup, which is the source of
     truth. Only the `alluxio-master` container opens RocksDB --
     `alluxio-job-master` does not, so this overlay deliberately lives
     there only.
   - `alluxio-job-journal-overlay` at `/journal` on the
     `alluxio-job-master` container -- the job-master runs its own
     independent raft cluster whose journal lives at the default
     `alluxio.job.master.journal.folder=/journal`. Upstream treats that as
     a plain writable dir on the container rootfs (ephemeral, rebuilt on
     every pod restart; job-master tracks in-flight jobs only, which are
     re-queued by clients). With `readOnlyRootFilesystem: true`
     `RaftJournalSystem.format()` hits `AccessDeniedException` on
     `mkdir /journal/JobJournal`; a dedicated emptyDir preserves the
     upstream semantic. Distinct name from the `alluxio-journal` PVC used
     by `alluxio-master` to avoid confusion.
4. Register those emptyDirs at the pod level so they can be shared across
   the two containers that live in each master StatefulSet pod / each
   worker DaemonSet pod.

The change is confined to the two templates and does not touch values,
helpers, or the logserver template (logserver does not carry
`readOnlyRootFilesystem: true`).

### Why it lives here

Same reason as the projected-volume helper above: the tarball builder
copies this chart directory verbatim into every
`alluxio-<version>-bin.tar.gz`, and keeping the chart source of truth in
the fork prevents downstream patches from drifting away from the image
(entrypoint, Dockerfile, and chart all move together on release).

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
