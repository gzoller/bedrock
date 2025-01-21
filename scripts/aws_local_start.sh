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
SNS_TOPIC_ARN=$(aws sns create-topic --name SecretKeyRotation --endpoint-url=$AWS_ENDPOINT_URL --query "TopicArn" --output text)

# Create an IAM Role for Lambda
echo ">> Creating IAM Role"
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
echo "   Role ARN: $ROLE_ARN"

#echo ">> Attaching IAM Policy to Role"
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

# Create the rotation Lambda function
echo ">> Creating Rotation Lambda Function"
ROTATION_LAMBDA_ARN=$(aws lambda create-function \
    --endpoint-url $AWS_ENDPOINT_URL \
    --region $AWS_DEFAULT_REGION \
    --function-name RotateSecretFunction \
    --runtime python3.9 \
    --role "$ROLE_ARN" \
    --handler rotationLambda.lambda_handler \
    --environment Variables={SNS_TOPIC_ARN=$SNS_TOPIC_ARN} \
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

echo ">> Done!"
