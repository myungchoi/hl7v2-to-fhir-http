import os
import re
import json
import uuid
import logging
import datetime
import boto3
import botocore
import base64
import urllib

# HL7V2 message queue
_Q_NAME = 'csels-xlr-hl7v2-translator-queue'
_Q_URL  = 'https://sqs.us-east-1.amazonaws.com/681252169616/csels-xlr-hl7v2-translator-queue'  
s3 = boto3.client('s3')
_sqs = boto3.client('sqs')

def s3_event(s3_event):
    """
    This is an AWS Lambda function that gets called whenever a new file
    appears in the S3 bucket.
    It opens the file, extracts the messages, and puts them on the
    NETSS message queue.
    """
    s3_bucket = event['Records'][0]['s3']['bucket']['name']
    s3_key = urllib.parse.unquote_plus(event['Records'][0]['s3']['object']['key'],encoding='utf-8')
    print('Received S3:ObjectCreated event for bucket {0}, key {1}'.
                  format(s3_bucket, s3_key))

    # stores line numbers of invalid messages
    invalid_messages = []
    
    filename = 's3://{0}/{1}'.format(s3_bucket, s3_key)
    obj = s3.get_object(Bucket = s3_ebucket, Key = s3_key) 
    hl7v2_message = obj['Body'].read().decode("utf-8")
    b64Message=base64.b64encode(hl7v2_message.encode())
    try:
        q_response = _sqs.send_message(
            QueueUrl = _Q_URL,
            MessageBody = b64Message.decode()
        )
    except Exception as err:
        print(err)
        
    if q_response is not None and 'MessageId' in q_response:
        print('Enqueued message: {0}, MessageId: {1}'.
                      format(hl7v2_message, q_response['MessageId']))
            
    # Delete the file since all messages have been enqueued.
    # For some reason this doesn't work with the s3 client, so
    # using bucket object instead.
    bucket = boto3.resource('s3').Bucket(s3_bucket)
    s3_response = bucket.delete_objects(
        Delete = {
            'Objects': [
                {
                    'Key' : s3_key
                }
            ]
        }
    )

    status_code = s3_response['ResponseMetadata']['HTTPStatusCode']
    if 200 == status_code:
        if 'Deleted' in s3_response:
            deleted_obj = s3_response['Deleted'][0]
            deleted_key = deleted_obj['Key']
            if deleted_key == s3_key:
                print('Successfully deleted file {0}'.
                              format(s3_key))
    else:
        print('S3 File delete failed: ')
        print(s3_response)