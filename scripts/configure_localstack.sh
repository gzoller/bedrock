#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Terraform
export TF_VAR_environment="localstack"
export TF_VAR_aws_access_key="test"
export TF_VAR_aws_secret_key="test"
cd /terraform/envs/localstack
tflocal init
tflocal apply -auto-approve

# Install Mini_HTTPD if not installed
echo "Updating package lists..."
apt-get update
echo "Installing Mini_HTTPD..."
apt-get install -y mini-httpd

# Ensure no previous Mini_HTTPD instance is running
echo "Stopping any existing Mini_HTTPD instances..."
pkill -f "mini_httpd" 2>/dev/null || true  # Ignore errors if no process is found

# Give the system a moment to fully release the port
sleep 1

# Set up readiness check directory
mkdir -p /var/www
echo "READY" > /var/www/index.html

# Create a minimal config file
cat <<EOF > /etc/mini-httpd.conf
port=8085
nochroot
dir=/var/www
host=0.0.0.0  # Force IPv4 binding to prevent conflicts
logfile=/var/log/mini-httpd.log
EOF

# Verify that the port is free before starting Mini_HTTPD
if netstat -tulnp | grep ":8085 " > /dev/null; then
    echo "Error: Port 8085 is still in use! Waiting for cleanup..."
    sleep 2
    if netstat -tulnp | grep ":8085 " > /dev/null; then
        echo "Port 8085 is still occupied. Forcing kill..."
        fuser -k 8085/tcp 2>/dev/null
        sleep 1
    fi
fi

# Start Mini_HTTPD in the foreground (prevent defunct processes)
echo "Starting Mini_HTTPD..."
mini_httpd -C /etc/mini-httpd.conf -D &

echo -e "${GREEN}>>> READY! <<<${NC}"
