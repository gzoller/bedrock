package com.me
package auth

import zio._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResponse, ListSecretVersionIdsRequest}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import scala.jdk.CollectionConverters.*


case class Key(version: String, value: String)

/**
  * Get a secret from AWS Secrets Manager
  */
case class SecretKeyManager(awsRegion: Option[Region]):

  def getSecretKey: ZIO[Any, Throwable, (Key,Option[Key])] = 
    ZIO.attemptBlocking {
        val secretName = "MySecretKey"

        val (endpointOverride, credentialsProvider) = if !awsRegion.isDefined then 
            // LocalStack configuration if no actual AWS is found
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
          val versionsRequest = ListSecretVersionIdsRequest.builder()
            .secretId(secretName)
            .build()
          val versionsResponse = secretsClient.listSecretVersionIds(versionsRequest)
          val currentVersion = versionsResponse.versions.asScala.find(_.versionStages.contains("AWSCURRENT")).map(_.versionId)
            .getOrElse(throw new Exception("No current version found for secret")) // There must be a current version!
          val previousVersion = versionsResponse.versions.asScala.find(_.versionStages.contains("AWSPREVIOUS")).map(_.versionId)
          // There may or may not be a previous version.

          // Create a request to retrieve the current secret
          val currentSecret = {
            val getSecretValueRequest = GetSecretValueRequest.builder()
              .secretId(secretName)
              .versionId(currentVersion)
              .build()

            // Fetch the secret value
            val getSecretValueResponse: GetSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest)

            // Return the secret string
            Key(currentVersion, getSecretValueResponse.secretString())
            }

          // Create a request to retrieve the current secret
          val previousSecret = previousVersion.map{ prevVer => 
            val getSecretValueRequest = GetSecretValueRequest.builder()
              .secretId(secretName)
              .versionId(prevVer)
              .build()

            // Fetch the secret value
            val getSecretValueResponse: GetSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest)

            // Return the secret string
            Key(prevVer, getSecretValueResponse.secretString())
            }

            (currentSecret, previousSecret)
        } finally {
            secretsClient.close()
        }
    }
