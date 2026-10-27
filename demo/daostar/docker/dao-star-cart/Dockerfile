# dao.star cart demo.  The build context is this directory (demo/daostar):
# the library comes from the pinned datomworld GitHub commit in deps.edn, so
# the context needs only the demo itself (with the gitignored images present
# under resources/dao_star_cart/public/images/).
#
#   cd demo/daostar
#   docker build -f docker/dao-star-cart/Dockerfile -t dao-star-cart .
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
COPY --from=build /app /app
ENV PORT=3004 \
    CATALOG_PATH=/data/catalog.edn \
    ASSET_ROOT=/app/resources/dao_star_cart/public \
    LANG=C.UTF-8
# LANG=C.UTF-8 sets sun.jnu.encoding so the static edge can serve the
# catalog's non-ASCII image filenames (e.g. Joycon小手柄.jpg)
VOLUME /data
EXPOSE 3004
CMD ["clj", "-M", "-m", "dao.star-cart.main"]
