# syntax=docker/dockerfile:1
#
# The mocksys base image: the CLI and its daemon, built from this checkout, with an
# entrypoint that serves twins to other containers and the host. A twin image starts
# FROM this one and adds its kit or scenario (see examples/helpdesk/Dockerfile).
#
#   docker build -f packaging/docker/Dockerfile -t mocksys:local .
#
# The build context is the repository root. Java is needed only to compile the
# ClojureScript, so it stays in the build stage.

FROM node:22-bookworm AS build
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
WORKDIR /src
COPY package.json package-lock.json shadow-cljs.edn ./
RUN npm ci
COPY src ./src
RUN npx shadow-cljs release cli && npm prune --omit=dev

FROM node:22-bookworm-slim
# tini reaps and signals the daemon; socat publishes the loopback-only mocks.
RUN apt-get update \
 && apt-get install -y --no-install-recommends socat tini \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/mocksys
COPY --from=build /src/node_modules ./node_modules
COPY --from=build /src/out/mocksys.js ./out/mocksys.js
COPY package.json ./
COPY bin ./bin
COPY packaging/docker/entrypoint.sh /usr/local/bin/mocksys-entrypoint
RUN ln -s /opt/mocksys/bin/mocksys /usr/local/bin/mocksys \
 && mkdir -p /twins/kits && chown -R node:node /twins

# One store and one fixed admin port, so `docker exec <c> mocksys ...` reaches the
# daemon the entrypoint started.
ENV MOCKSYS_HOME=/twins MOCKSYS_ADMIN_PORT=2626
USER node
WORKDIR /twins
ENTRYPOINT ["tini", "-g", "--", "mocksys-entrypoint"]
