FROM maven:3.9.11-amazoncorretto-17-debian AS build
WORKDIR /tmp/server

COPY pom.xml .
COPY src src

RUN mvn clean install -DskipTests
RUN mvn package -DskipTests spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/server/target/ROOT.war /app/main.war



FROM amazoncorretto:17-alpine
WORKDIR /app
COPY --from=build /app/main.war /app/main.war

ENTRYPOINT [ "java", "-jar", "/app/main.war" ]
