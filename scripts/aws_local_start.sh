#!/bin/bash

docker-compose up -d
sleep 4

#container_name="localstack"
#until [ "$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null)" == "true" ]; do
#  echo "Waiting for container $container_name to be running..."
#  sleep 1
#done
#echo "Container $container_name is running."

# Set up dummy AWS credentials and region
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566

# prime Secrets Manager with a previous entry so there's always an AWSCURRENT and AWSPREVIOUS
echo ">> Creating and populating Secrets Manager"
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret --name MySecretKey --secret-string "originalKey"
sleep 2
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager update-secret --secret-id MySecretKey --secret-string "secretKey"
sleep 2
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret --name SessionKey --secret-string "theWayIsShut"

# Establish a topic in SNS for notification of Secret Key rotations
echo ">> Creating SNS Topic"
aws sns create-topic --name SecretKeyRotation --endpoint-url=$AWS_ENDPOINT_URL
sleep 2

# Create the lambda function
echo ">> Creating Lambda Function"
aws lambda create-function \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --function-name NotifySecretChange \
    --runtime python3.9 \
    --role bedrock \
    --handler lambda_function.lambda_handler \
    --zip-file fileb://scripts/function.zip \
    --environment Variables={EVENT_BUS_NAME=default}
sleep 2

# Set an auto-rotate cadence
echo ">> Set Auto-Rotate Cadence"
aws secretsmanager rotate-secret \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn arn:aws:lambda:us-east-1:000000000000:function:NotifySecretChange \
    --rotation-rules AutomaticallyAfterDays=30
sleep 2

# Configure Secrets Manager to Trigger the Lambda Function
# Step 1: Create a resource-based policy for Secrets Manager to invoke the lambda function
echo ">> Add permission policy"
aws lambda add-permission \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --function-name NotifySecretChange \
    --statement-id SecretsManagerInvoke \
    --action lambda:InvokeFunction \
    --principal secretsmanager.amazonaws.com \
    --source-arn arn:aws:secretsmanager:us-east-1:123456789012:secret:MySecretKey-*
sleep 2

# Step 2: Attach a lambda rotation configuration to the secret
echo ">> Attach Lambda Rotation to Secret"
aws secretsmanager rotate-secret \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn arn:aws:lambda:us-east-1:000000000000:function:NotifySecretChange
sleep 2

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
sleep 2

# Attach SNS Topic as a target
#echo "Attach SNS Topic as Target"
echo ">> Attach SNS Topic as a Target"
aws events put-targets \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --rule NotifyOnSecretChange \
    --targets "Id"="1","Arn"="arn:aws:sns:us-east-1:000000000000:SecretKeyRotation"
sleep 2

# Grant EventBridge permission to publish to SNS
echo ">> Grant EventBridge Permision"
aws sns add-permission \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --topic-arn arn:aws:sns:us-east-1:000000000000:SecretKeyRotation \
    --label AllowEventBridge \
    --aws-account-id 123456789012 \
    --action-name Publish
