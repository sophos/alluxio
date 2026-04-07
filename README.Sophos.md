The main branch of this fork is `sophos/main`. It was forked from `release-2.9.5` branch of the original repository.

The changes/additions:
- Support of assumeRole for AWS S3 access and arm64 CPU(commit: 7f10520ef2fa317663b32008f0592d635b1fcd24)
- Fixing the Netty EPoll library dependencies to make it compatible with Trino client (commit: 98e48089af2494c07306855d9a20bd8a2e1c247e)
- Adding support for capacity overprovisioning allowing to take the all available disk space (commit: a3e0f795ce29cee409aee3673d6f692438be8cd8)
- Support for Docker registry proxy (commit: f763dfc308df906c0b5f82d89f64be67127eae11)
