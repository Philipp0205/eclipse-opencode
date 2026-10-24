# Isolated end-to-end test: stock Eclipse + the freshly built plugin on a virtual X display.
# Build context must be the repository root (see test/e2e/run.sh).

# ---- stage 1: build the p2 repository from source ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -B -DskipTests package

# ---- stage 2: stock Eclipse + plugin + Xvfb ----
FROM eclipse-temurin:21-jdk-noble
ARG ECLIPSE_RELEASE=2026-03
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb x11-utils imagemagick xdotool dbus-x11 ca-certificates curl unzip git \
      libgtk-3-0t64 libwebkit2gtk-4.1-0 libcanberra-gtk3-module adwaita-icon-theme \
      fonts-dejavu-core x11vnc \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://download.eclipse.org/technology/epp/downloads/release/${ECLIPSE_RELEASE}/R/eclipse-java-${ECLIPSE_RELEASE}-R-linux-gtk-x86_64.tar.gz" \
      | tar -xz -C /opt

# opencode CLI (the plugin spawns `opencode serve`)
RUN curl -fsSL https://opencode.ai/install | bash \
    && ln -sf /root/.opencode/bin/opencode /usr/local/bin/opencode

COPY --from=build /src/com.opencode.eclipse.repository/target/repository /opt/plugin-repo
RUN /opt/eclipse/eclipse -nosplash -consoleLog \
      -application org.eclipse.equinox.p2.director \
      -repository file:/opt/plugin-repo \
      -installIU com.opencode.eclipse.feature.feature.group \
      -vm "$JAVA_HOME/bin"

COPY test/e2e/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
