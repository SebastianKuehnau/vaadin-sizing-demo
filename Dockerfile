FROM eclipse-temurin:25-jdk AS build
ENV HOME=/app
RUN mkdir -p $HOME
WORKDIR $HOME
COPY . $HOME

# The loadtest profile adds testbench-loadtest-support, which the recorded k6 scripts rely on.
# Never deploy this image to production.
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -DskipTests -Ploadtest

FROM eclipse-temurin:25-jre-alpine
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
