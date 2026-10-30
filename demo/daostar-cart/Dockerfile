# dao.star cart demo.  Docker is OPTIONAL: the demo runs with a plain JVM
# (see README, "Run it").  This file is for running it in a container.
#
# Build context: this directory (demo/daostar).  The library comes from the
# pinned datomworld GitHub commit in deps.edn, so the context needs only the
# demo itself (with the gitignored images under
# resources/public/images/).
#
#   cd demo/daostar
#   docker build -t dao-star-cart .
#   docker run -p 3004:3004 -v star-cart-data:/data -e CATALOG_PATH=/data/catalog.edn dao-star-cart

FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
# deps first, so the dependency layers cache (this resolves the pinned
# datomworld commit from GitHub — network required at build time)
COPY deps.edn deps.edn
RUN clj -P
COPY src src
COPY resources resources

FROM clojure:temurin-21-tools-deps
WORKDIR /app
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /root/.clojure /root/.clojure
COPY --from=build /root/.gitlibs /root/.gitlibs
COPY --from=build /app /app
ENV PORT=3004 \
    CATALOG_PATH=/data/catalog.edn \
    ASSET_ROOT=/app/resources/public \
    LANG=C.UTF-8
# LANG=C.UTF-8 sets sun.jnu.encoding so the static edge can serve the
# catalog's non-ASCII image filenames (e.g. Joycon小手柄.jpg)
VOLUME /data
EXPOSE 3004
CMD ["clj", "-M", "-m", "dao.star-cart.main"]
