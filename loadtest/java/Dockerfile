# Use OpenJDK 17 as base image with Maven pre-installed
FROM maven:3.9.3-eclipse-temurin-17 as builder

# Set working directory
WORKDIR /app

# Copy project files
COPY . .

# Build the application and ensure Spring Boot JAR is properly packaged
RUN mvn clean package -DskipTests

# Use OpenJDK 17 for runtime
FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/java-springboot-app-1.0.0.jar ./app.jar

# Ensure the JAR is executable
RUN chmod +x app.jar

# Expose application port
EXPOSE 8000

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
