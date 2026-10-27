# dao.star cart demo.  Build context: the repository root, with the product
# images present at resources/dao_star_cart/public/images/ (gitignored; the
# orchestrator copies them to the build host out of band).
#
#   docker build -f demo/docker/dao-star-cart/Dockerfile -t dao-star-cart .
#   docker run -p 3004:3004 -v star-cart-data:/data -e CATALOG_PATH=/data/catalog.edn dao-star-cart

FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
# deps first, so the dependency layer caches
COPY demo/deps.edn demo/deps.edn
RUN cd demo && clj -P
COPY src/cljc src/cljc
COPY src/clj src/clj
COPY resources resources
COPY demo/src demo/src
COPY demo/resources demo/resources
RUN cd demo && clj -P

FROM clojure:temurin-21-tools-deps
WORKDIR /app
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /root/.clojure /root/.clojure
COPY --from=build /app /app
# the demo runs from /app/demo: its deps.edn reaches the library sources at ../src
WORKDIR /app/demo
ENV PORT=3004 \
    CATALOG_PATH=/data/catalog.edn \
    ASSET_ROOT=/app/demo/resources/dao_star_cart/public
VOLUME /data
EXPOSE 3004
CMD ["clj", "-M", "-m", "dao.star-cart.main"]
