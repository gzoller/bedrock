package com.me
package auth

import zio._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResponse}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration

object SecretKeyManager:

  def getSecretKey(awsRegion: Option[Region]): Task[String] = 
    ZIO.attemptBlocking {
        val secretName = "MySecretKey"

        val (endpointOverride, credentialsProvider) = if !awsRegion.isDefined then 
            // LocalStack configuration
            (
              Some(new java.net.URI("http://localhost:4566")), // LocalStack endpoint
              StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")) // Mock credentials
            )
          else
            // Non-local configuration, ie running on real AWS (uses default AWS credentials)
            (None, DefaultCredentialsProvider.create())

        val secretsClientBuilder = SecretsManagerClient.builder()
          .region( awsRegion.getOrElse(Region.US_EAST_1) )
          .credentialsProvider(credentialsProvider)
        endpointOverride.map( secretsClientBuilder.endpointOverride )
        val secretsClient = secretsClientBuilder.build()
        
        try {
          // Create a request to retrieve the secret
          val getSecretValueRequest = GetSecretValueRequest.builder()
            .secretId(secretName)
            .build()

          // Fetch the secret value
          val getSecretValueResponse: GetSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest)

          // Return the secret string
          getSecretValueResponse.secretString()
        } finally {
            secretsClient.close()
        }
    }
