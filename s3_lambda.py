import os
import re
import json
import uuid
import logging
import datetime
import requests
import smart_open
import boto3
import botocore
import base64

# HL7V2 message queue
_Q_NAME = 'csels-xlr-hl7v2-translator-queue'
_Q_URL  = 'https://sqs.us-east-1.amazonaws.com/681252169616/csels-xlr-hl7v2-translator-queue'  

def s3_event(s3_event):
    """
    This is an AWS Lambda function that gets called whenever a new file
    appears in the S3 bucket.
    It opens the file, extracts the messages, and puts them on the
    NETSS message queue.
    """
    app.log.debug('Received S3:ObjectCreated event for bucket {0}, key {1}'.
                  format(s3_event.bucket, s3_event.key))

    # stores line numbers of invalid messages
    invalid_messages = []
    
    filename = 's3://{0}/{1}'.format(s3_event.bucket, s3_event.key)
    obj = s3.get_object(Bucket = s3_event.bucket, Key = s3_event.key) 
    hl7v2_message = obj['Body'].read().decode("utf-8")
    b64Message=base64.b64encode(hl7v2_message.encode())
    try:
        q_response = _sqs.send_message(
            QueueUrl = _Q_URL,
            MessageBody = b64Message
        )
    except _sqs.meta.client.exceptions.InvalidMessageContents as err:
        app.log.error(err.response)
    except _sqs.meta.client.exceptions.UnsupportedOperation as err:
        app.log.error(err.response)
        
    if q_response is not None and 'MessageId' in q_response:
        app.log.debug('Enqueued message: {0}, MessageId: {1}'.
                      format(netss_msg, q_response['MessageId']))
            
    # Delete the file since all messages have been enqueued.
    # For some reason this doesn't work with the s3 client, so
    # using bucket object instead.
    bucket = boto3.resource('s3').Bucket(_S3_UPLOAD_BUCKET)
    s3_response = bucket.delete_objects(
        Delete = {
            'Objects': [
                {
                    'Key' : s3_event.key
                }
            ]
        }
    )

    status_code = s3_response['ResponseMetadata']['HTTPStatusCode']
    if 200 == status_code:
        if 'Deleted' in s3_response:
            deleted_obj = s3_response['Deleted'][0]
            deleted_key = deleted_obj['Key']
            if deleted_key == s3_event.key:
                app.log.debug('Successfully deleted file {0}'.
                              format(s3_event.key))
    else:
        app.log.error('S3 File delete failed: ')
        app.log.error(s3_response)