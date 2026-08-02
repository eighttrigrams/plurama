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

COPY tracker/package.json tracker/package-lock.json ./tracker/
RUN cd tracker && npm install

COPY tracker/shadow-cljs.edn ./tracker/
COPY tracker/deps.edn        ./tracker/
COPY tracker/build.clj       ./tracker/
COPY tracker/src             ./tracker/src
COPY tracker/resources       ./tracker/resources

RUN cd tracker && npx shadow-cljs release app

COPY treina/package.json treina/package-lock.json ./treina/
RUN cd treina && npm install

COPY treina/shadow-cljs.edn ./treina/
COPY treina/deps.edn        ./treina/
COPY treina/build.clj       ./treina/
COPY treina/src             ./treina/src
COPY treina/resources       ./treina/resources

RUN cd treina && npx shadow-cljs release app

COPY music/package.json music/package-lock.json ./music/
RUN cd music && npm install

COPY music/shadow-cljs.edn ./music/
COPY music/deps.edn        ./music/
COPY music/build.clj       ./music/
COPY music/src             ./music/src
COPY music/resources       ./music/resources

RUN cd music && npx shadow-cljs release app

COPY cookbook/package.json cookbook/package-lock.json ./cookbook/
RUN cd cookbook && npm ci

COPY cookbook/shadow-cljs.edn ./cookbook/
COPY cookbook/deps.edn        ./cookbook/
COPY cookbook/build.clj       ./cookbook/
COPY cookbook/src             ./cookbook/src
COPY cookbook/resources       ./cookbook/resources

RUN cd cookbook && npx shadow-cljs release app

COPY plurama/deps.edn  ./plurama/
COPY plurama/build.clj ./plurama/
COPY plurama/src       ./plurama/src
COPY plurama/resources ./plurama/resources

RUN cd plurama && clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

COPY plurama/config.prod.edn ./plurama/config.edn
COPY plurama/mail.yaml ./plurama/mail.yaml

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /opt/plurama/target/plurama-0.0.1-standalone.jar /app/app.jar
COPY --from=builder /opt/plurama/config.edn /app/config.edn
COPY --from=builder /opt/plurama/mail.yaml /app/mail.yaml

EXPOSE 8080

ENTRYPOINT ["java", \
            "-Xms256m", \
            "-Xmx768m", \
            "-XX:MaxMetaspaceSize=256m", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "-jar", "/app/app.jar"]
