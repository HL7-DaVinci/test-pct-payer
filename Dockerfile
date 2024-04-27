FROM maven:3.9.6-eclipse-temurin-17 as build
WORKDIR /tmp/server

COPY pom.xml .
COPY src src

RUN mvn clean install -DskipTests
RUN mvn package -DskipTests spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/server/target/ROOT.war /app/main.war



FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY --from=build /app/main.war /app/main.war

ENTRYPOINT [ "java", "-jar", "/app/main.war" ]
