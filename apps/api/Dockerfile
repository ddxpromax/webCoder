FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --create-home --uid 10001 webcoder \
    && mkdir -p /data/majo \
    && chown -R webcoder:webcoder /data/majo
WORKDIR /app
COPY --from=build /build/target/api-0.0.1-SNAPSHOT.jar /app/api.jar
USER webcoder
ENV MAJO_WORKING_DIR=/data/majo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]
