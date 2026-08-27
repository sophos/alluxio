#
# The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
# (the "License"). You may not use this work except in compliance with the License, which is
# available at www.apache.org/licenses/LICENSE-2.0
#
# This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied, as more fully set forth in the License.
#
# See the NOTICE file distributed with this work for information regarding copyright ownership.
#

# ARG defined before the first FROM can be used in FROM lines
# Only 8 and 11 are supported.
ARG JAVA_VERSION=11
ARG PROXY=""

# Sophos fork (CSA-21950): rebased off quay.io/centos/centos:stream9 onto
# redhat/ubi9-minimal:latest. Stream9 ships ~hundreds of default RPMs (X11
# client libs, GStreamer, GLib, OpenJPEG, libxml2, etc.) that BlackDuck SCA
# flagged as critical/high but Alluxio never touches. UBI9-minimal subscribes
# to ubi-9-baseos-rpms + ubi-9-appstream-rpms by default and ships only what
# microdnf installs explicitly -- ~3x smaller image and roughly 80% less BD
# noise out of the box. Build-only tools (gcc, cmake, autoconf...) now live
# in a discardable libfuse-builder stage so they never land in a runtime
# layer.

# Setup CSI
FROM ${PROXY}golang:1.25-alpine AS csi-dev
ENV GO111MODULE=on
RUN mkdir -p /alluxio-csi
COPY ./csi /alluxio-csi
RUN cd /alluxio-csi && \
    CGO_ENABLED=0 go build -o /usr/local/bin/alluxio-csi

# We have to do an ADD to put the tarball into extractor, then do a COPY with chown into final
# ADD then chown in two steps will double the image size
#   See - https://stackoverflow.com/questions/30085621/why-does-chown-increase-size-of-docker-image
#       - https://github.com/moby/moby/issues/5505
#       - https://github.com/moby/moby/issues/6119
# ADD with chown doesn't chown the files inside tarball
#   See - https://github.com/moby/moby/issues/35525
#
# Was alpine:3.10.2 (released 2019, EOL since 2021). UBI9-minimal is the
# same base the runtime stage uses, so the layer cache is shared and the
# pull is free. tar/gzip aren't in ubi9-minimal by default -- microdnf
# them in for the extraction step only.
FROM ${PROXY}redhat/ubi9-minimal:latest AS alluxio-extractor
RUN microdnf install -y --nodocs tar gzip && microdnf clean all
# Note that downloads for *-SNAPSHOT tarballs are not available.
ARG ALLUXIO_TARBALL=http://downloads.alluxio.io/downloads/files/2.9.6/alluxio-2.9.6-bin.tar.gz
# (Alert):It's not recommended to set this Argument to true, unless you know exactly what you are doing
ARG ENABLE_DYNAMIC_USER=false

ADD ${ALLUXIO_TARBALL} /opt/
# Remote tarball needs to be untarred. Local tarball is untarred automatically.
# Use ln -s instead of mv to avoid issues with Centos (see https://github.com/moby/moby/issues/27358)
RUN cd /opt && \
    (if ls | grep -q ".tar.gz"; then tar -xzf *.tar.gz && rm *.tar.gz; fi) && \
    ln -s alluxio-* alluxio

RUN if [ ${ENABLE_DYNAMIC_USER} = "true" ] ; then \
       chmod -R 777 /opt/* ; \
    fi

# Build Alluxio's customized libfuse2 (fuse_2_9_5_customize_multi_threads
# branch) from source. Output: /usr/local/{bin,lib,etc}/libfuse2 artifacts
# that the final stage selectively COPYs over -- gcc / cmake / autoconf /
# git / wget never land in the runtime layer cache.
#
# Builder base stays on centos:stream9 (NOT ubi9). The libfuse autoconf
# chain depends on gettext-devel for AM_GNU_GETTEXT macros, and
# gettext-devel is in stream9's PowerTools/AppStream but missing from UBI9
# (Red Hat moved it out of the public CRB repo). Builder layers are
# discarded post-build, so this stream9 dep has zero effect on the final
# image's BD scan -- the BD scanner only sees the runtime layers, all of
# which are now ubi9-minimal.
FROM ${PROXY}quay.io/centos/centos:stream9 AS libfuse-builder
RUN \
    yum install -y --setopt=install_weak_deps=False \
      ca-certificates pkgconfig wget git \
      gcc gcc-c++ make cmake gettext-devel libtool autoconf && \
    git clone https://github.com/Alluxio/libfuse.git /tmp/libfuse && \
    cd /tmp/libfuse && \
    git checkout fuse_2_9_5_customize_multi_threads && \
    bash makeconf.sh && \
    ./configure --prefix=/usr/local && \
    make -j"$(nproc)" && \
    make install && \
    rm -rf /tmp/libfuse

# Configure Java
#
# JAVA_HOME via readlink trick because the OpenJDK RPM installs into a
# version+arch-suffixed directory like
# /usr/lib/jvm/java-11-openjdk-11.0.25.0.9-7.el9.aarch64 -- which differs
# between aarch64 and x86_64 builds. Resolve `command -v java` to the real
# binary, walk up two dirs to the JAVA_HOME root, then symlink to a
# versionless path so the ENV is simple and arch-portable.
FROM ${PROXY}redhat/ubi9-minimal:latest AS build_java8
RUN \
    microdnf upgrade -y && \
    microdnf install -y --nodocs java-1.8.0-openjdk-headless java-1.8.0-openjdk-devel && \
    microdnf clean all && \
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" && \
    ln -s "${JAVA_HOME}" /usr/lib/jvm/java-1.8.0-openjdk
ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk
# Disable JVM DNS cache in java8 (https://github.com/Alluxio/alluxio/pull/9452)
RUN echo "networkaddress.cache.ttl=0" >> ${JAVA_HOME}/jre/lib/security/java.security

FROM ${PROXY}redhat/ubi9-minimal:latest AS build_java11
RUN \
    microdnf upgrade -y && \
    microdnf install -y --nodocs java-11-openjdk-headless && \
    microdnf clean all && \
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" && \
    ln -s "${JAVA_HOME}" /usr/lib/jvm/java-11-openjdk
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk
# Disable JVM DNS cache in java11 (https://github.com/Alluxio/alluxio/pull/9452)
RUN echo "networkaddress.cache.ttl=0" >> ${JAVA_HOME}/conf/security/java.security

FROM build_java${JAVA_VERSION} AS final

WORKDIR /

# Runtime deps. Diff vs the old centos:stream9 install list:
#   DROPPED `udev`      -- systemd-udev daemon, never needed in containers,
#                          dragged in only because it shipped by default on
#                          stream9.
#   DROPPED `pkgconfig` -- build-time tool, no runtime use.
#   DROPPED `git`,
#           `wget`,
#           `gcc`, etc. -- moved into libfuse-builder; live and die there.
#   ADDED   `shadow-utils` (groupadd/useradd/usermod), `findutils` (xargs
#           used by entrypoint.sh's tier-dirs chmod loop), `procps-ng` (ps
#           for alluxio shell scripts), `tar`, `gzip` -- all defaults on
#           stream9 but minimal in ubi9-minimal, so opt-in explicitly.
#   KEPT    `ca-certificates` (S3 TLS), `fuse3`, `fuse3-libs`.
#   --nodocs strips man pages and locale junk per package -- harmless and
#           shaves a few MB.
RUN microdnf install -y --nodocs \
      ca-certificates \
      findutils \
      fuse3 \
      fuse3-libs \
      gzip \
      procps-ng \
      shadow-utils \
      tar && \
    microdnf clean all

# Pull in the Alluxio-customized libfuse2 build from the libfuse-builder
# stage. COPY preserves file modes, including the SUID bit on
# /usr/local/bin/fusermount that lets non-root mount(2) calls inside the
# container succeed via the helper. ldconfig refreshes the dynamic linker
# cache so libfuse.so.2 resolves without relying solely on
# LD_LIBRARY_PATH.
#
# Note: libfuse2's `make install` does NOT drop a default fuse.conf -- the
# /etc/fuse.conf that the dynamic-user RUN below appends `user_allow_other`
# to is provided by the fuse3 RPM installed above.
COPY --from=libfuse-builder /usr/local/bin/fusermount /usr/local/bin/fusermount
COPY --from=libfuse-builder /usr/local/lib/libfuse* /usr/local/lib/
RUN ldconfig

# Configuration for the modified libfuse2
ENV MAX_IDLE_THREADS="64"

# /lib64 is for rocksdb native libraries, /usr/local/lib is for libfuse2 native libraries.
# Don't `:${LD_LIBRARY_PATH}` -- the JDK base image leaves it unset and BuildKit warns
# (UndefinedVar) on the dangling expansion. ldconfig already registers /usr/local/lib
# above, so the trailing colon never carried meaning.
ENV LD_LIBRARY_PATH="/lib64:/usr/local/lib"

ARG ALLUXIO_USERNAME=alluxio
ARG ALLUXIO_GROUP=alluxio
ARG ALLUXIO_UID=1000
ARG ALLUXIO_GID=1000

# For dev image to know the user
ENV ALLUXIO_DEV_UID=${ALLUXIO_UID}

ARG ENABLE_DYNAMIC_USER=true

# Add Tini for Alluxio helm charts (https://github.com/Alluxio/alluxio/pull/12233)
# - https://github.com/krallin/tini
ENV TINI_VERSION=v0.18.0
ARG TARGETARCH
ADD https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-static-${TARGETARCH} /usr/local/bin/tini
RUN chmod +x /usr/local/bin/tini

# If Alluxio user, group, gid, and uid aren't root|0, create the alluxio user and set file permissions accordingly
RUN if [ ${ALLUXIO_USERNAME} != "root" ] \
    && [ ${ALLUXIO_GROUP} != "root" ] \
    && [ ${ALLUXIO_UID} -ne 0 ] \
    && [ ${ALLUXIO_GID} -ne 0 ]; then \
      groupadd --gid ${ALLUXIO_GID} ${ALLUXIO_GROUP} && \
      useradd --system -m --uid ${ALLUXIO_UID} --gid ${ALLUXIO_GROUP} ${ALLUXIO_USERNAME} && \
      usermod -a -G root ${ALLUXIO_USERNAME} && \
      mkdir -p /journal && \
      chown -R ${ALLUXIO_UID}:${ALLUXIO_GID} /journal && \
      chmod -R g=u /journal && \
      mkdir /mnt/alluxio-fuse && \
      chown -R ${ALLUXIO_UID}:${ALLUXIO_GID} /mnt/alluxio-fuse; \
    fi

# Docker 19.03+ required to expand variables in --chown argument
# https://github.com/moby/buildkit/pull/926#issuecomment-503943557
COPY --from=alluxio-extractor --chown=${ALLUXIO_USERNAME}:${ALLUXIO_GROUP} /opt /opt/
COPY --chown=${ALLUXIO_USERNAME}:${ALLUXIO_GROUP} conf /opt/alluxio/conf/
COPY --chown=${ALLUXIO_USERNAME}:${ALLUXIO_GROUP} entrypoint.sh /
COPY --from=csi-dev /usr/local/bin/alluxio-csi /usr/local/bin/

RUN if [ ${ENABLE_DYNAMIC_USER} = "true" ] ; then \
       chmod -R 777 /journal; \
       chmod -R 777 /mnt; \
       # Enable user_allow_other option for fuse in non-root mode
       echo "user_allow_other" >> /etc/fuse.conf; \
    fi

USER ${ALLUXIO_UID}

WORKDIR /opt/alluxio

ENV PATH="/opt/alluxio/bin:${PATH}"

ENTRYPOINT ["/entrypoint.sh"]
