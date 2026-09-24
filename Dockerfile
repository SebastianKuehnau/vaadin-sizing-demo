FROM ghcr.io/jqlang/jq:latest AS jq-stage

FROM eclipse-temurin:25-jdk AS build
COPY --from=jq-stage /jq /usr/bin/jq

ENV HOME=/app
RUN mkdir -p $HOME
WORKDIR $HOME
COPY . $HOME

# The Observability Kit is a commercial component. Pass your Vaadin Pro key as a secret with id "proKey"
# (compose.yaml does this for you):
#
#   $ docker build --secret id=proKey,src=$HOME/.vaadin/proKey .
#
# If you have a Vaadin Offline key, pass it as a secret with id "offlineKey":
#
#   $ docker build --secret id=offlineKey,src=$HOME/.vaadin/offlineKey .

# Additional Maven profiles, e.g. "loadtest" for an image under load test (see compose.loadtest.yaml)
ARG MAVEN_PROFILES=""

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=proKey \
    --mount=type=secret,id=offlineKey \
    sh -c 'PRO_KEY=$(jq -r ".proKey // empty" /run/secrets/proKey 2>/dev/null || echo "") && \
    OFFLINE_KEY=$(cat /run/secrets/offlineKey 2>/dev/null || echo "") && \
    ./mvnw clean package -DskipTests ${MAVEN_PROFILES:+-P$MAVEN_PROFILES} ${PRO_KEY:+-Dvaadin.proKey=$PRO_KEY} ${OFFLINE_KEY:+-Dvaadin.offlineKey=$OFFLINE_KEY}'

FROM eclipse-temurin:25-jre-alpine
COPY --from=build /app/target/*.jar app.jar
# Can be overridden, e.g. with "prod,loadtest" (see compose.loadtest.yaml)
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
