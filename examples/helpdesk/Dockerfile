# syntax=docker/dockerfile:1
#
# The helpdesk twin as an image: the mocksys base image plus the helpdesk kit,
# generated at build time. Build the base first, both from the repository root:
#
#   docker build -f packaging/docker/Dockerfile -t mocksys:local .
#   docker build -f examples/helpdesk/Dockerfile -t helpdesk-twin .
#   docker run --rm -p 8080:8080 helpdesk-twin
#
# Kit flags are build args: docker build --build-arg RATE_LIMIT=100 ...

ARG MOCKSYS_IMAGE=mocksys:local
FROM ${MOCKSYS_IMAGE}

ARG RATE_LIMIT=5
ARG ACCESS_TTL=3600
COPY --chown=node:node examples/kits/helpdesk /twins/kits/helpdesk
RUN mocksys kit helpdesk --rate-limit "$RATE_LIMIT" --token-ttl "$ACCESS_TTL" --port 9400 \
 && mocksys validate helpdesk/helpdesk

ENV MOCKSYS_SERVE="helpdesk/helpdesk=8080"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s \
  CMD node -e "fetch('http://127.0.0.1:8080/v2/status').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"
