FROM maven:3.6.3-jdk-11-slim as build-hapi
WORKDIR /tmp/test-pct-payer

COPY pom.xml .
RUN mvn -ntp dependency:go-offline

COPY src/ /tmp/test-pct-payer/src/
RUN mvn clean install -DskipTests

FROM build-hapi AS build-distroless
RUN mvn package spring-boot:repackage -Pboot
RUN mkdir /app && \
    cp /tmp/test-pct-payer/target/ROOT.war /app/main.war

FROM gcr.io/distroless/java-debian10:11 AS release-distroless
COPY --chown=nonroot:nonroot --from=build-distroless /app /app
EXPOSE 8081
# 65532 is the nonroot user's uid
# used here instead of the name to allow Kubernetes to easily detect that the container
# is running as a non-root (uid != 0) user.
USER 65532:65532
WORKDIR /app
CMD ["/app/main.war"]

FROM tomcat:9.0.38-jdk11-openjdk-slim-buster

RUN mkdir -p /data/hapi/lucenefiles && chmod 775 /data/hapi/lucenefiles
COPY --from=build-hapi /tmp/test-pct-payer/target/*.war /usr/local/tomcat/webapps/

EXPOSE 8081

CMD ["catalina.sh", "run"]
