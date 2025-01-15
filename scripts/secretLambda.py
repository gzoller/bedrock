import boto3
import os

eventbridge = boto3.client('events')

def lambda_handler(event, context):
    secret_name = event['detail']['name']
    response = eventbridge.put_events(
        Entries=[
            {
                'Source': 'custom.secrets-manager',
                'DetailType': 'SecretChanged',
                'Detail': f'{{"secret_name": "{secret_name}"}}',
                'EventBusName': os.environ['EVENT_BUS_NAME']
            }
        ]
    )
    print(f"Event sent: {response}")