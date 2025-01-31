#!/bin/bash

echo "LocalStack is shutting down... Stopping Mini_HTTPD."

# Find the mini-httpd process
MINI_HTTPD_PID=$(pgrep -f "mini_httpd")

if [ -n "$MINI_HTTPD_PID" ]; then
    echo "Stopping mini-httpd (PID: $MINI_HTTPD_PID)..."
    kill -SIGTERM "$MINI_HTTPD_PID"
    wait "$MINI_HTTPD_PID" 2>/dev/null
    echo "Mini_HTTPD stopped."
else
    echo "Mini_HTTPD not found."
fi