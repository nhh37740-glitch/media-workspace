FROM eclipse-temurin:17-jre-jammy

ARG MEDIA_UID=1000
ARG MEDIA_GID=1000

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app /var/lib/media-workspace/storage /var/log/media-workspace/worker \
    && chown -R ${MEDIA_UID}:${MEDIA_GID} /app /var/lib/media-workspace /var/log/media-workspace

WORKDIR /app
COPY media-worker.jar /app/media-worker.jar

USER ${MEDIA_UID}:${MEDIA_GID}
ENV FFMPEG_PATH=/usr/bin/ffmpeg \
    FFPROBE_PATH=/usr/bin/ffprobe
ENTRYPOINT ["java"]
CMD ["-Xms128m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-Xss512k", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC", "-jar", "/app/media-worker.jar"]
