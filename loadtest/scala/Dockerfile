# Use OpenJDK 21 as base image
FROM openjdk:21-slim

# Set working directory
WORKDIR /app

# Install SBT and dependencies
RUN apt-get update && apt-get install -y curl && \
    curl -L -o sbt.tgz https://github.com/sbt/sbt/releases/download/v1.9.4/sbt-1.9.4.tgz && \
    tar -xvzf sbt.tgz && rm sbt.tgz

# Copy project files
COPY . .

# Install sbt-native-packager plugin
RUN mkdir -p project && echo 'addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.0")' > project/plugins.sbt

# Build the application
RUN ./sbt/bin/sbt compile

# Expose application port
EXPOSE 8000

# Run the application
CMD ["./sbt/bin/sbt", "run"]
