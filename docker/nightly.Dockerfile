# The nightly toolchain image. Built from this Dockerfile on each host;
# versions here mirror mise.toml (the pins are the contract — keep both in
# sync when bumping). One image serves all three lanes; the entrypoint does
# the clone/pin/run dance (docker/nightly-entrypoint.sh).
#
# Build (on the host):
#   docker build -t nightly:latest -f docker/nightly.Dockerfile docker/
#
# Run (see docker/nightly-cron.sh for the full invocation):
#   docker run --rm \
#     -e SHA=<pinned sha> -e LANES=node,dart -e PAR=12 -e ROLE=publish \
#     -e NIGHTLY_RESULTS=/results -e REPO_URL=git@github.com:sonwh98/datomworld.git \
#     -v /home/sto/.ssh:/run/ssh:ro \
#     -v /home/sto/ci/nightly-results:/results \
#     -v nightly-m2:/root/.m2 -v nightly-gitlibs:/root/.gitlibs \
#     -v nightly-npm:/repo/node_modules -v nightly-shadow:/repo/.shadow-cljs \
#     -v nightly-build:/repo/build -v nightly-cljd:/repo/lib/cljd-out \
#     nightly:latest
#
# The generated-asset volumes persist across runs (warm shadow-cljs/cljd
# compiles, npm packages, gitlibs/m2) while the checkout itself is cloned
# fresh at the pinned SHA every run — warm state without stale code.
# NOTE: `:cache-root` is global in shadow-cljs and :cache-root/:output-to
# through build-level --config-merge are not supported, so the compile cache
# volume holds ONE cache; per-shard :ns-regexp compiles in one lane run would
# leak cached namespaces (the known shadow behavior) — the driver therefore
# compiles ONCE per run and selects at runtime with --test.

FROM eclipse-temurin:21-jdk-jammy

ARG CLOJURE_VERSION=1.12.4.1602
ARG BABASHKA_VERSION=1.13.225
ARG NODE_VERSION=25.6.1
ARG FLUTTER_VERSION=3.47.8-stable

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash git curl openssh-client xz-utils ca-certificates libatomic1 rlwrap \
 && rm -rf /var/lib/apt/lists/*

# Clojure CLI (pinned)
RUN curl -sL "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh" -o /tmp/clj.sh \
 && bash /tmp/clj.sh \
 && rm /tmp/clj.sh

# Babashka (pinned, static binary)
RUN curl -sL "https://github.com/babashka/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-linux-amd64-static.tar.gz" \
 | tar xz -C /usr/local/bin bb

# Node (pinned)
RUN curl -sL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
 | tar xJ -C /usr/local --strip-components=1 \
 && ln -sf /usr/local/bin/node /usr/local/bin/nodejs \
 && node --version && npm --version

# Flutter (pinned stable) — test-only use; no linux-desktop toolchain needed
RUN curl -sL "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}.tar.xz" \
 | tar xJ -C /opt \
 && git config --global --add safe.directory /opt/flutter
ENV PATH="/opt/flutter/bin:${PATH}"
RUN flutter config --no-analytics >/dev/null 2>&1 || true

ENV GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=accept-new"
COPY nightly-entrypoint.sh /usr/local/bin/nightly-entrypoint.sh
RUN chmod +x /usr/local/bin/nightly-entrypoint.sh

# Run as a non-root user matching the host account (uid/gid 1000 = `sto` on
# both hosts) so bind-mounted results and named-volume caches carry host
# ownership instead of root. Flutter's SDK cache is chowned so the runtime
# user can stamp it. The clone dir and the user-level dep caches are
# pre-created and chowned at build time so the nightly user can write them.
RUN groupadd -g 1000 nightly && useradd -m -u 1000 -g nightly nightly \
 && mkdir -p /opt/flutter /repo /repo-src \
      /repo-src/node_modules /repo-src/.shadow-cljs \
      /repo-src/build /repo-src/lib/cljd-out \
      /home/nightly/.m2 /home/nightly/.gitlibs \
 && chown -R nightly:nightly /opt/flutter /repo /repo-src /home/nightly
ENV HOME=/home/nightly
USER nightly
WORKDIR /repo
CMD ["/usr/local/bin/nightly-entrypoint.sh"]
