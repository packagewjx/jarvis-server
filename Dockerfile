FROM gradle:9.4.0-jdk17 AS build

WORKDIR /workspace
COPY . .
RUN gradle :server:installDist --no-daemon

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        gettext-base \
        nginx \
        openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/server/build/install/server /opt/jarvis
COPY docker/nginx.conf.template /etc/nginx/templates/jarvis.conf.template
COPY docker/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh \
    && mkdir -p /etc/nginx/tls /var/log/jarvis \
    && rm -f /etc/nginx/conf.d/default.conf /etc/nginx/sites-enabled/default

EXPOSE 443

ENTRYPOINT ["/entrypoint.sh"]
