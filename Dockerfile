# Многоэтапная сборка по канону DAML: сначала собираем Gradle-дистрибутив, затем запускаем лёгкий рантайм.
FROM gradle:8.8.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/untitled1 /app
EXPOSE 8080
CMD ["./bin/untitled1"]
