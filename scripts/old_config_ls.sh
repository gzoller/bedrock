#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Set up dummy AWS credentials and region
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localstack:4566

# Prime Secrets Manager
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret \
    --name MySecretKey \
    --secret-string "initialValue" \
    --region $AWS_DEFAULT_REGION 
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret \
    --name SessionKey \
    --region $AWS_DEFAULT_REGION \
    --secret-string "theWayIsShut"
echo -e "Prime Secrets Manager... ${GREEN}Success${NC}"

# Create an SNS Topic and set raw delivery (JSON only)
SNS_TOPIC_ARN=$(aws sns create-topic --name SecretKeyRotation --endpoint-url=$AWS_ENDPOINT_URL --query "TopicArn" --output text)
echo -e "Create SNS topic for secrets rotation... ${GREEN}Success${NC}"

# Create an IAM Role for Lambda
TRUST_POLICY=$(cat <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Service": "lambda.amazonaws.com"
            },
            "Action": "sts:AssumeRole"
        }
    ]
}
EOF
)

ROLE_NAME="SecretsManagerLambdaRole"
ROLE_ARN=$(aws --endpoint-url $AWS_ENDPOINT_URL iam create-role \
    --endpoint-url $AWS_ENDPOINT_URL \
    --role-name $ROLE_NAME \
    --assume-role-policy-document "$TRUST_POLICY" \
    --query 'Role.Arn' --output text)
echo -e "Creating IAM role for lambda functions... ${GREEN}Success${NC}"
echo -e "    >> Role ARN: ${YELLOW}$ROLE_ARN${NC}"

aws --endpoint-url $AWS_ENDPOINT_URL iam attach-role-policy \
    --role-name $ROLE_NAME \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Create role to be shared between rotation and notification Lambdas
ROLE_POLICY=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sns:Publish"
      ],
      "Resource": "$SNS_TOPIC_ARN"
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:DescribeSecret",
        "secretsmanager:GetSecretValue",
        "secretsmanager:UpdateSecret",
        "secretsmanager:PutSecretValue",
        "secretsmanager:TagResource"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
EOF
)
aws iam put-role-policy \
  --region $AWS_DEFAULT_REGION \
  --endpoint-url $AWS_ENDPOINT_URL \
  --role-name $ROLE_NAME \
  --policy-name LambdaExecutionPolicy \
  --policy-document "$ROLE_POLICY"
echo -e "Attach IAM policy to role... ${GREEN}Success${NC}"

# Create the rotation Lambda function
ROTATION_LAMBDA_ARN=$(aws lambda create-function \
    --endpoint-url $AWS_ENDPOINT_URL \
    --region $AWS_DEFAULT_REGION \
    --function-name RotateSecretFunction \
    --runtime python3.9 \
    --role "$ROLE_ARN" \
    --handler rotationLambda.lambda_handler \
    --environment Variables={SNS_TOPIC_ARN=$SNS_TOPIC_ARN} \
    --zip-file fileb:///scripts/rotationLambda.zip \
    --query "FunctionArn" --output text)
aws --endpoint-url $AWS_ENDPOINT_URL lambda wait function-active-v2 --function-name RotateSecretFunction  
aws lambda add-permission \
    --endpoint-url $AWS_ENDPOINT_URL \
    --function-name RotateSecretFunction \
    --statement-id SecretsManagerInvoke \
    --action lambda:InvokeFunction \
    --principal secretsmanager.amazonaws.com \
    --source-arn arn:aws:secretsmanager:us-east-1:000000000000:secret:MySecretKey-*
echo -e "Creating rotation lambda function... ${GREEN}Success${NC}"
echo -e "    >> Rotation Lambda ARN: ${YELLOW}$ROTATION_LAMBDA_ARN${NC}"

# Attach the rotation Lambda to the secret
aws secretsmanager rotate-secret \
    --endpoint-url $AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn $ROTATION_LAMBDA_ARN \
    --rotation-rules AutomaticallyAfterDays=30    
echo "Setting up rotation lambda for secret in Secrets Manager... ${GREEN}Success${NC}"

#--------------->>> Redis/ElastiCache <<<-----------------

# Create an ElastiCache Redis Subnet Group
#aws elasticache create-cache-subnet-group \
#    --cache-subnet-group-name session-subnet-group \
#    --cache-subnet-group-description "Subnet group for Redis" \
#    --subnet-ids subnet-abc123 subnet-def456

# Create a security group
# FIX THIS!
#SECURITY_GROUP_ID=$(aws ec2 create-security-group \
#    --group-name session-security-group \
#    --description "Security group for Redis" \
#    --query "GroupId" --output text)

# Authorize inbound traffic
# (restrict the cidr in produciton!)
#aws ec2 authorize-security-group-ingress \
#    --group-id $SECURITY_GROUP_ID \
#    --protocol tcp \
#    --port 6379 \
#    --cidr 0.0.0.0/0

# Create single Node Redis Cluster
#aws elasticache create-cache-cluster \
#    --cache-cluster-id session-redis-cluster \
#    --engine redis \
#    --cache-node-type cache.t3.micro \
#    --num-cache-nodes 1 \
#    --cache-subnet-group-name session-subnet-group \
#    --security-group-ids $SECURITY_GROUP_ID \
#    --engine-version 7.0
#echo "Setting up Redis... ${GREEN}Success${NC}"

# Multi-node Redis Cluster
#aws elasticache create-replication-group \
#    --replication-group-id my-redis-replication-group \
#    --replication-group-description "Redis Replication Group" \
#    --engine redis \
#    --cache-node-type cache.t3.micro \
#    --num-node-groups 1 \
#    --replicas-per-node-group 2 \
#    --automatic-failover-enabled \
#    --cache-subnet-group-name my-redis-subnet-group \
#    --security-group-ids sg-abc123456 \
#    --engine-version 7.0


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
