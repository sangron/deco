# --- Multi-stage build for GraalVM Native Image ---

# Stage 1: Build the native executable
# Use a GraalVM native-image builder image
FROM ghcr.io/graalvm/native-image:ol8-java11 AS builder

# Set the working directory
WORKDIR /app

# Copy the Maven project files
COPY pom.xml .
COPY src ./src

# Build the native image
# The 'native:compile' goal builds the native executable
RUN mvn clean package -Pnative -Dnative.maven.plugin.skip=false

# Stage 2: Create the final, small runtime image
# Use a very light base image like Alpine or Distroless
FROM alpine:latest AS runtime

# Install necessary runtime dependencies (e.g., glibc for some native images if not using distroless)
# Alpine uses musl libc, which might require specific GraalVM builds or a glibc-compatible base.
# If you run into issues, consider a glibc-based slim image like openjdk:11-jre-slim-buster or a distroless image.
# For many GraalVM apps, alpine is fine if compiled for musl.
RUN apk add --no-cache libc6-compat

# Create a non-root user for security best practices
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Set the working directory
WORKDIR /app

# Copy the native executable from the builder stage
# The native executable is usually named after the artifactId in pom.xml (e.g., deco-ai-service-oracle)
COPY --from=builder /app/target/deco-ai-service-oracle /app/deco-ai-service-oracle

# Define the port that the application will listen on.
ENV PORT 8080

# Expose the port
EXPOSE 8080

# Run the native executable when the container starts
CMD ["/app/deco-ai-service-oracle"]

# Note on GCRM.io/distroless/java:
# Distroless images are even smaller as they contain only your application and its runtime dependencies.
# If you prefer distroless (often recommended for Cloud Run):
# FROM gcr.io/distroless/static-debian11 # For purely static binaries
# FROM gcr.io/distroless/base           # For more common non-static binaries
# You would still need to ensure GraalVM compiles for the specific libc used by distroless (glibc).
# Often, using a specific GraalVM base image like `ghcr.io/graalvm/graalvm-community-openjdk-17:latest`
# and then copying from its output to a `gcr.io/distroless/java` or `gcr.io/distroless/base` final stage
# is the most robust approach. Alpine might need custom GraalVM builds for musl libc.
