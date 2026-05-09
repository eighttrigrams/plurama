FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

RUN apk add --no-cache nodejs npm

COPY personalist/package.json personalist/package-lock.json ./personalist/
RUN cd personalist && npm install

COPY personalist/shadow-cljs.edn ./personalist/
COPY personalist/deps.edn        ./personalist/
COPY personalist/build.clj       ./personalist/
COPY personalist/src             ./personalist/src
COPY personalist/resources       ./personalist/resources

RUN cd personalist && npx shadow-cljs release app

COPY blog/deps.edn   ./blog/
COPY blog/build.clj  ./blog/
COPY blog/src        ./blog/src
COPY blog/resources  ./blog/resources

COPY plurama/deps.edn  ./plurama/
COPY plurama/build.clj ./plurama/
COPY plurama/src       ./plurama/src
COPY plurama/resources ./plurama/resources

RUN cd plurama && clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

COPY plurama/config.prod.edn ./plurama/config.edn

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /opt/plurama/target/plurama-0.0.1-standalone.jar /app/app.jar
COPY --from=builder /opt/plurama/config.edn /app/config.edn

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Xms256m", \
            "-Xmx768m", \
            "-XX:MaxMetaspaceSize=256m", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "-jar", "/app/app.jar"]
