import json
import boto3
import os

sns_client = boto3.client('sns', endpoint_url=os.getenv('AWS_ENDPOINT_URL', None))

# Environment variable for the SNS Topic ARN
SNS_TOPIC_ARN = os.getenv("SNS_TOPIC_ARN")

def lambda_handler(event, context):
    print(f"!!!!! Received event: {json.dumps(event, indent=2)}")

    # Extract details from the EventBridge event
    detail = event.get('detail', {})
    event_name = detail.get('eventName')
    secret_id = detail.get('requestParameters', {}).get('secretId')

    # Validate the presence of required fields
    if not event_name or not secret_id:
        print("!!!!! Error: Missing required fields in the event.")
        return {
            "statusCode": 400,
            "body": "Missing required fields in the event."
        }

# case class SnsMessage(
#   Type: String,
#   SignatureVersion: String = "1",
#   Signature: String = "",
#   SigningCertURL: String = "",
#   SubscribeURL: Option[String] = None,
#   Message: Option[String] = None,
#   MessageId: Option[String] = None,
#   TopicArn: Option[String] = None,
#   Token: Option[String] = None,
#   Timestamp: Option[String] = None
# ) {
    # Construct the SNS message payload
    message = {
        "Type": "Notification",
        "Message": "Time to refresh keys"
        # "EventName": event_name,
        # "SecretId": secret_id,
        # "Time": event.get('time', ""),  # Extract event timestamp
        # "Region": event.get('region', ""),  # Extract AWS region
        # "Source": event.get('source', "aws.secretsmanager")  # Default source if not provided
    }

    # Publish the message to SNS
    try:
        response = sns_client.publish(
            TopicArn=SNS_TOPIC_ARN,
            Message=json.dumps(message),  # Send the message as JSON
            Subject=f"Secrets Manager Change: {event_name}"
        )
        print(f"!!!!! Message published to SNS: {response}")
    except Exception as e:
        print(f"!!!!! Error publishing to SNS: {str(e)}")
        return {
            "statusCode": 500,
            "body": f"Error publishing to SNS: {str(e)}"
        }

    return {
        "statusCode": 200,
        "body": f"Successfully processed event: {event_name} for secret {secret_id}"
    }