# Reproducible EXECUTION environment for the datomic-q-v1 oracle runner.
# Datomic Local is an in-process library, so this image is only a JDK and the
# Clojure CLI; the repository checkout is mounted at run time (run-oracle.sh).
FROM eclipse-temurin:21-jdk

ARG CLOJURE_VERSION=1.12.4.1602

# curl and rlwrap are needed by the official Clojure CLI installer only.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates rlwrap \
 && curl -fsSL -o /tmp/linux-install.sh \
      "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh" \
 && chmod +x /tmp/linux-install.sh \
 && /tmp/linux-install.sh \
 && rm /tmp/linux-install.sh \
 && apt-get purge -y curl \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /repo
