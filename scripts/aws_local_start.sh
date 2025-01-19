#!/bin/bash

docker-compose up -d
sleep 2

CERT_PATH="src/main/resources/server.crt"
CONTAINER_ID=$(docker ps --filter "name=localstack" --format "{{.ID}}")
echo ">> Container ID: $CONTAINER_ID"
# Check if LocalStack is running
if [ -z "$CONTAINER_ID" ]; then
  echo "LocalStack container is not running. Exiting."
  exit 1
fi

# Copy the certificate into the container
docker cp "$CERT_PATH" "$CONTAINER_ID:/usr/local/share/ca-certificates/server.crt"

# Update the trusted certificates in the container
docker exec -it "$CONTAINER_ID" update-ca-certificates

echo "Certificate successfully added to LocalStack container."

# Set up dummy AWS credentials and region
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566 #http://host.docker.internal:4566 

# Prime Secrets Manager
echo ">> Creating and populating Secrets Manager"
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret --name MySecretKey --secret-string "originalKey"
sleep 1
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager update-secret --secret-id MySecretKey --secret-string "secretKey"
sleep 1
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret --name SessionKey --secret-string "theWayIsShut"

# Create an SNS Topic and set raw delivery (JSON only)
echo ">> Creating SNS Topic"
aws sns create-topic --name SecretKeyRotation --endpoint-url=$AWS_ENDPOINT_URL

# Create an IAM Role for Lambda
echo ">> Creating IAM Role"
cat <<EOF > trust-policy.json
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

aws --endpoint-url=$AWS_ENDPOINT_URL iam create-role \
    --role-name MyLambdaRole \
    --assume-role-policy-document file://trust-policy.json

echo ">> Attaching IAM Policy to Role"
aws --endpoint-url=$AWS_ENDPOINT_URL iam attach-role-policy \
    --role-name MyLambdaRole \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Retrieve Role ARN
ROLE_ARN=$(aws --endpoint-url=$AWS_ENDPOINT_URL iam get-role \
    --role-name MyLambdaRole \
    --query 'Role.Arn' --output text)
echo "Role ARN: $ROLE_ARN"

# Create the Lambda Function
echo ">> Creating Lambda Function"
aws lambda create-function \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --function-name NotifySecretChange \
    --runtime python3.9 \
    --role "$ROLE_ARN" \
    --handler secretLambda.lambda_handler \
    --zip-file fileb://scripts/function.zip \
    --environment Variables={EVENT_BUS_NAME=default}

# Set Auto-Rotate Cadence
echo ">> Set Auto-Rotate Cadence"
aws secretsmanager rotate-secret \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn arn:aws:lambda:us-east-1:000000000000:function:NotifySecretChange \
    --rotation-rules AutomaticallyAfterDays=30

# Add Lambda Permission for SecretsManager
echo ">> Add Permission Policy"
aws lambda add-permission \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --function-name NotifySecretChange \
    --statement-id SecretsManagerInvoke \
    --action lambda:InvokeFunction \
    --principal secretsmanager.amazonaws.com \
    --source-arn arn:aws:secretsmanager:us-east-1:000000000000:secret:MySecretKey-*

# Create an EventBridge Rule
echo ">> Create EventBridge Rule"
aws events put-rule \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --name NotifyOnSecretChange \
    --event-pattern '{
        "source": ["aws.secretsmanager"],
        "detail-type": ["AWS API Call via CloudTrail"],
        "detail": {
            "eventName": ["UpdateSecret", "RotateSecret"]
        }
    }'

# Attach SNS Topic as a Target
echo ">> Attach SNS Topic as a Target"
aws events put-targets \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --rule NotifyOnSecretChange \
    --targets '[{"Id": "1", "Arn": "arn:aws:sns:us-east-1:000000000000:SecretKeyRotation"}]'

# Grant EventBridge Permission to Publish to SNS
echo ">> Grant EventBridge Permission"
aws sns add-permission \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --topic-arn arn:aws:sns:us-east-1:000000000000:SecretKeyRotation \
    --label AllowEventBridge \
    --aws-account-id 000000000000 \
    --action-name Publish

echo ">> Done!"