# dao.star cart demo.  Build context: the repository root, with the product
# images present at resources/dao_star_cart/public/images/ (gitignored; the
# orchestrator copies them to the build host out of band).
#
#   docker build -f demo/daostar/docker/dao-star-cart/Dockerfile -t dao-star-cart .
#   docker run -p 3004:3004 -v star-cart-data:/data -e CATALOG_PATH=/data/catalog.edn dao-star-cart

FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
# deps first, so the dependency layer caches
COPY demo/daostar/deps.edn demo/daostar/deps.edn
RUN cd demo/daostar && clj -P
COPY src/cljc src/cljc
COPY src/clj src/clj
COPY resources resources
COPY demo/daostar/src demo/daostar/src
COPY demo/daostar/resources demo/daostar/resources
RUN cd demo/daostar && clj -P

FROM clojure:temurin-21-tools-deps
WORKDIR /app
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /root/.clojure /root/.clojure
COPY --from=build /app /app
# the demo runs from /app/demo: its deps.edn reaches the library sources at ../src
WORKDIR /app/demo/daostar
ENV PORT=3004 \
    CATALOG_PATH=/data/catalog.edn \
    ASSET_ROOT=/app/demo/daostar/resources/dao_star_cart/public \
    LANG=C.UTF-8
# LANG=C.UTF-8 sets sun.jnu.encoding so the static edge can serve the
# catalog's non-ASCII image filenames (e.g. Joycon小手柄.jpg)
VOLUME /data
EXPOSE 3004
CMD ["clj", "-M", "-m", "dao.star-cart.main"]
