FROM eclipse-temurin:21-jdk

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy build files first for better caching
COPY build.sbt .
COPY project/ project/

# Download dependencies
RUN sbt update

# Copy source code
COPY . .

# Compile
RUN sbt compile

# Expose ports
EXPOSE 8080 9090

# Default command runs the Creative Assessment UI
CMD ["sbt", "core/runMain promovolve.publisher.assessment.CreativeAssessmentUI"]
