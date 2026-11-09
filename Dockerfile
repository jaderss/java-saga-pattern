ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG MODULE
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY messaging/pom.xml messaging/
COPY transfer-service/pom.xml transfer-service/
COPY account-service/pom.xml account-service/
COPY risk-service/pom.xml risk-service/
RUN ./mvnw -q -B -pl ${MODULE} -am dependency:go-offline
COPY messaging/src messaging/src
COPY ${MODULE}/src ${MODULE}/src
RUN ./mvnw -q -B -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:${JAVA_VERSION}-jre
COPY --from=build /app.jar /app/app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true $JAVA_OPTS -jar /app/app.jar"]
