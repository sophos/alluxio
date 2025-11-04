#!/usr/bin/env bash

set -euo pipefail

mvn -T 4C clean install -Prelease -Dawssdk.version=2.15.79 -DskipTests -Dmaven.javadoc.skip=true -Dfindbugs.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true -pl '!webui'

mkdir -p integration/docker/build
cp assembly/client/target/alluxio-assembly-client-2.9.5-jar-with-dependencies.jar integration/docker/build/alluxio-client-2.9.5.jar
cp assembly/server/target/alluxio-assembly-server-2.9.5-jar-with-dependencies.jar integration/docker/build/alluxio-server-2.9.5.jar
cp lib/alluxio-underfs-s3a-2.9.5.jar integration/docker/build/

docker buildx bake --file docker-bake.hcl all
skopeo copy --src-tls-verify=false --all docker://localhost:5005/alluxio:2.9.5.0 docker://artifactory.sophos-tools.com/byoc-docker/alluxio:2.9.5.0
