#!/usr/bin/env fish

# Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>


function require_env --argument-names name value
    if test -z "$value"
        echo "Missing required environment variable: $name" >&2
        exit 1
    end
end

# CI vs local environment detection (DOCKER_IN_DOCKER is set in TeamCity K8s agents)
if set -q DOCKER_IN_DOCKER
    # TeamCity agent runs as uid 1000; ensure skopeo/containers-image uses writable dirs (avoid /run/containers)
    set -gx XDG_RUNTIME_DIR /opt/buildagent/temp/xdg-runtime
    mkdir -p $XDG_RUNTIME_DIR
    # Use docker plugin in CI
    set docker_cmd docker
    set buildx_cmd docker buildx
else
    # Use Homebrew docker path and standalone buildx on macOS
    set docker_cmd /opt/homebrew/bin/docker # nerdctl not supported
    set buildx_cmd docker-buildx
end

set -l registry docker.loxal.net
set -l image loxal/page-finder
set -l tag latest
if set -q DOCKER_TAG; and test -n "$DOCKER_TAG"
    set tag $DOCKER_TAG
end

set -l full_tag "$registry/$image:$tag"

require_env ADMIN_SITE_SECRET "$ADMIN_SITE_SECRET"

# Login first (buildx --push needs registry auth).
echo $ADMIN_SITE_SECRET | $docker_cmd login --username minion --password-stdin $registry; or exit 1

# With the docker-container buildx driver, images are not loaded locally unless --load is used.
# Push directly from buildx to avoid "An image does not exist locally" errors.
$buildx_cmd build --push \
    --platform linux/amd64 \
    -f page-finder-service/Dockerfile \
    --tag $full_tag \
    .; or exit 1