# Use official Golang image to create a binary
FROM golang:1.20 as builder

# Set working directory
WORKDIR /app

# Copy go.mod and go.sum, then download dependencies
COPY go.mod go.sum ./
RUN go mod tidy && go mod download

# Copy source code
COPY main.go ./

# Ensure dependencies are properly installed
RUN go get -d -v ./...

# Build the application binary with static linking to avoid Alpine issues
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o server main.go

# Use a minimal base image to run the application
FROM alpine:latest

WORKDIR /root/

# Install required libraries
RUN apk add --no-cache ca-certificates

# Copy the compiled binary from the builder stage
COPY --from=builder /app/server .

# Ensure the binary has execute permissions
RUN chmod +x ./server

# Expose port 8000
EXPOSE 8000

# Run the application
CMD ["./server"]
