# Runtime image only. The distribution is built outside Docker with `./gradlew :conformance:installDist`
# (the Gradle build also contains the Android target of `contracts`, which needs the Android SDK).
# One image, three jobs: the suite (default), the fake Google (`fake-google`) and the self-check (`self-check`).
FROM eclipse-temurin:21-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin conformance
# The fake Google writes the fixtures here, in a volume the backend under test mounts.
RUN mkdir /fixtures && chown conformance /fixtures
COPY build/install/conformance /app

USER conformance
EXPOSE 9000
ENTRYPOINT ["/app/bin/conformance"]
