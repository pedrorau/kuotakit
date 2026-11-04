# Runtime image only. The distribution is built outside Docker with `./gradlew :backend:installDist`
# (the Gradle build also contains the Android target of `contracts`, which needs the Android SDK).
FROM eclipse-temurin:21-jre

RUN useradd --system --no-create-home --shell /usr/sbin/nologin kuotakit
COPY build/install/backend /app

USER kuotakit
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
