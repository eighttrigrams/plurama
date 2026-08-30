FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

RUN apk add --no-cache nodejs npm

COPY personalist/package.json personalist/package-lock.json ./personalist/
# personalist depends on the IJKL editor library as `file:vendor/...tgz`. The
# library itself lives outside this build context (in the keyboard-wizardry repo),
# so what is committed here is the packed tarball, and npm install below needs it
# on disk before it runs. ../vendor-editor.sh keeps it and blog's bundle in step
# with the library, and plurama's `make check-editor` fails a deploy if they drift.
COPY personalist/vendor ./personalist/vendor
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
# As for personalist above: the IJKL editor library is a file: dependency on a
# packed tarball committed here, because the library itself lives outside this
# build context. ../vendor-editor.sh keeps it in step; `make check-editor` fails
# a deploy if it drifts.
COPY tracker/vendor ./tracker/vendor
RUN cd tracker && npm install

COPY tracker/shadow-cljs.edn ./tracker/
COPY tracker/deps.edn        ./tracker/
COPY tracker/build.clj       ./tracker/
COPY tracker/src             ./tracker/src
COPY tracker/resources       ./tracker/resources

RUN cd tracker && npx shadow-cljs release app

COPY treina/package.json treina/package-lock.json ./treina/
# As for personalist and tracker above: the packed editor library, because the
# library itself is outside this build context.
COPY treina/vendor ./treina/vendor
RUN cd treina && npm install

COPY treina/shadow-cljs.edn ./treina/
COPY treina/deps.edn        ./treina/
COPY treina/build.clj       ./treina/
COPY treina/src             ./treina/src
COPY treina/resources       ./treina/resources

RUN cd treina && npx shadow-cljs release app

COPY music/package.json music/package-lock.json ./music/
# As above.
COPY music/vendor ./music/vendor
RUN cd music && npm install

COPY music/shadow-cljs.edn ./music/
COPY music/deps.edn        ./music/
COPY music/build.clj       ./music/
COPY music/src             ./music/src
COPY music/resources       ./music/resources

# Corvo, before music's release build and not after it, because that build
# compiles corvo's ClojureScript: music/shadow-cljs.edn carries
# "../corvo/src/lib" on :source-paths, and music/deps.edn names
# {:local/root "../corvo"} for the assets under corvo/resources/public/corvo.
#
# No package.json, no npm install, no shadow-cljs release of its own — like
# us-vs-them below, corvo is a library here rather than an app. It has no npm
# dependency music does not already install, and it produces no JavaScript
# artefact: its namespaces go into music's bundle and its resources go onto the
# classpath, which is why this sibling is COPYed without a build step.
COPY corvo/deps.edn   ./corvo/
COPY corvo/src        ./corvo/src
COPY corvo/resources  ./corvo/resources

RUN cd music && npx shadow-cljs release app

# us-vs-them is a library and not an app: no npm, no shadow-cljs, nothing to
# release. It is here because cookbook/deps.edn names it {:local/root
# "../us-vs-them"} and tools.deps resolves that relative to the file declaring
# it — so the uberjar step below follows /opt/plurama → /opt/cookbook →
# /opt/us-vs-them, and without this the build dies at once with
#     Local lib eighttrigrams/us-vs-them not found: /opt/us-vs-them
# Its `src` goes into the uberjar the same way cookbook's does: `b/uber` walks the
# basis, and a :local/root lib's :paths are on it. That is why there is no
# copy-dir or ns-compile entry for it in plurama/build.clj either.
COPY us-vs-them/deps.edn ./us-vs-them/
COPY us-vs-them/src      ./us-vs-them/src

COPY cookbook/package.json cookbook/package-lock.json ./cookbook/
# As above.
COPY cookbook/vendor ./cookbook/vendor
RUN cd cookbook && npm install

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
