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
export AWS_ENDPOINT_URL=http://localhost:4566

# Prime Secrets Manager
echo ">> Creating and populating Secrets Manager"
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret \
    --name MySecretKey \
    --secret-string "initialValue" \
    --region $AWS_DEFAULT_REGION 
aws --endpoint-url=$AWS_ENDPOINT_URL secretsmanager create-secret \
    --name SessionKey \
    --region $AWS_DEFAULT_REGION \
    --secret-string "theWayIsShut"

# Create an SNS Topic and set raw delivery (JSON only)
echo ">> Creating SNS Topic"
export SNS_TOPIC_ARN=$(aws sns create-topic --name SecretKeyRotation --endpoint-url=$AWS_ENDPOINT_URL --query "TopicArn" --output text)

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

aws --endpoint-url $AWS_ENDPOINT_URL iam create-role \
    --role-name MyLambdaRole \
    --assume-role-policy-document file://trust-policy.json
rm trust-policy.json

echo ">> Attaching IAM Policy to Role"
aws --endpoint-url $AWS_ENDPOINT_URL iam attach-role-policy \
    --role-name MyLambdaRole \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Grant Lambda permission to publish to SNS
aws --endpoint-url $AWS_ENDPOINT_URL iam put-role-policy \
    --role-name MyLambdaRole \
    --policy-name PublishToSNS \
    --policy-document "{
        \"Version\": \"2012-10-17\",
        \"Statement\": [
            {
                \"Effect\": \"Allow\",
                \"Action\": \"sns:Publish\",
                \"Resource\": \"$SNS_TOPIC_ARN\"
            }
        ]
    }"

# Retrieve Role ARN
ROLE_ARN=$(aws --endpoint-url $AWS_ENDPOINT_URL iam get-role \
    --role-name MyLambdaRole \
    --query 'Role.Arn' --output text)
echo "Role ARN: $ROLE_ARN"

# Create the rotation Lambda function
echo ">> Creating Rotation Lambda Function"
ROTATION_LAMBDA_ARN=$(aws lambda create-function \
    --endpoint-url $AWS_ENDPOINT_URL \
    --region $AWS_DEFAULT_REGION \
    --function-name RotateSecretFunction \
    --runtime python3.9 \
    --role "$ROLE_ARN" \
    --handler rotationLambda.lambda_handler \
    --zip-file fileb://scripts/rotationLambda.zip \
    --query "FunctionArn" --output text)
aws --endpoint-url $AWS_ENDPOINT_URL lambda wait function-active-v2 --function-name RotateSecretFunction  
echo "!!! Rotation Lambda ARN: $ROTATION_LAMBDA_ARN"
aws lambda add-permission \
    --endpoint-url $AWS_ENDPOINT_URL \
    --function-name RotateSecretFunction \
    --statement-id SecretsManagerInvoke \
    --action lambda:InvokeFunction \
    --principal secretsmanager.amazonaws.com \
    --source-arn arn:aws:secretsmanager:us-east-1:000000000000:secret:MySecretKey-*

# Attach the rotation Lambda to the secret
echo ">> Setting up Rotation Lambda for MySecretKey"
aws secretsmanager rotate-secret \
    --endpoint-url $AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn $ROTATION_LAMBDA_ARN \
    --rotation-rules AutomaticallyAfterDays=30    
sleep 1

#------------------ Notification System ------------------#

#aws cloudtrail create-trail \
#    --endpoint-url=$AWS_ENDPOINT_URL \
#    --name TestTrail \
#    --s3-bucket-name test-bucket

#aws cloudtrail start-logging \
#    --endpoint-url=$AWS_ENDPOINT_URL \
#    --name TestTrail

# Create the notification Lambda function
echo ">> Creating Notification Lambda Function"
NOTIFICATION_LAMBDA_ARN=$(aws lambda create-function \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --region $AWS_DEFAULT_REGION \
    --function-name NotifySecretChange \
    --runtime python3.9 \
    --role "$ROLE_ARN" \
    --handler secretNotificationLambda.lambda_handler \
    --zip-file fileb://scripts/secretNotificationLambda.zip \
    --environment Variables={SNS_TOPIC_ARN=$SNS_TOPIC_ARN} \
    --query "FunctionArn" --output text)
aws --endpoint-url=$AWS_ENDPOINT_URL lambda wait function-active-v2 --function-name NotifySecretChange  


echo ">> Creating EventBridge Rule for Secret Updates"
aws events put-rule \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --name NotifyOnSecretChange \
    --region $AWS_DEFAULT_REGION \
    --event-pattern '{
        "source": ["aws.secretsmanager"],
        "detail-type": ["AWS API Call via CloudTrail"],
        "detail": {
            "eventName": ["UpdateSecret", "RotateSecret"]
        }
    }'

RULE_ARN=$(aws events describe-rule \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --name NotifyOnSecretChange \
    --query "Arn" --output text)

echo "EventBridge Rule ARN: $RULE_ARN"

# Add EventBridge as a permissioned service to invoke the notification Lambda
echo ">> Adding Lambda Permission for EventBridge"
aws lambda add-permission \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --function-name NotifySecretChange \
    --statement-id AllowEventBridgeInvoke \
    --action lambda:InvokeFunction \
    --principal events.amazonaws.com \
    --source-arn $RULE_ARN

# Attach the EventBridge rule to the notification Lambda
echo ">> Attaching EventBridge Rule to NotifySecretChange Lambda"
aws events put-targets \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --rule NotifyOnSecretChange \
    --targets "Id"="1","Arn"="$NOTIFICATION_LAMBDA_ARN"

# Test rotation and notification integration
echo ">> Triggering Secret Rotation"
aws secretsmanager rotate-secret \
    --region $AWS_DEFAULT_REGION \
    --endpoint-url $AWS_ENDPOINT_URL \
    --secret-id MySecretKey \
    --rotation-lambda-arn $ROTATION_LAMBDA_ARN \
    --rotation-rules AutomaticallyAfterDays=30  

echo ">> Done!"
