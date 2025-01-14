package co.blocke.bedrock
package services
package auth

import aws.AwsEnvironment

import zio.*
import zio.http.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResponse, ListSecretVersionIdsRequest}
import scala.jdk.CollectionConverters.*


/**
  * Get a secret from AWS Secrets Manager
  */
trait SecretKeyManager:
  def getSecretKey: ZIO[Any, Throwable, KeyBundle]


final case class LiveSecretKeyManager(authConfig: AuthConfig, awsRegion: Option[Region]) extends SecretKeyManager:

  def getSecretKey: ZIO[Any, Throwable, KeyBundle] = 
    for {
      localstackUri <- ZIO
        .attempt(new java.net.URI(authConfig.localstackUrl.get))  // ZZZ this is optional...treat accordingly

      outcome <- ZIO.attemptBlocking {
          val (endpointOverride, credentialsProvider) = if !awsRegion.isDefined then 
              // LocalStack configuration if no actual AWS is found
              (
                Some(localstackUri), // LocalStack endpoint
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
              .secretId(authConfig.secretName)
              .build()
            val versionsResponse = secretsClient.listSecretVersionIds(versionsRequest)
            val currentVersion = versionsResponse.versions.asScala.find(_.versionStages.contains("AWSCURRENT")).map(_.versionId)
              .getOrElse(throw new Exception("No current version found for secret")) // There must be a current version!
            val previousVersion = versionsResponse.versions.asScala.find(_.versionStages.contains("AWSPREVIOUS")).map(_.versionId)
            // There may or may not be a previous version.

            def getSecret(secretName: String, ver: Option[String]): Key = {
              val getSecretValueRequestRaw = GetSecretValueRequest.builder()
                .secretId(secretName)
              val getSecretValueRequest = 
                ver
                  .map( v => getSecretValueRequestRaw.versionId(v).build() )
                  .getOrElse( getSecretValueRequestRaw.build() )

              // Fetch the secret value
              val getSecretValueResponse: GetSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest)

              // Return the secret string
              Key(currentVersion, getSecretValueResponse.secretString(), getSecretValueResponse.createdDate)
            }
            
            // Retreive the current version of the key
            val currentSecret = getSecret(authConfig.secretName, Some(currentVersion))

            // Retreive the previous version of the key
            val previousSecret = previousVersion.map{ prevVer => 
              getSecret(authConfig.secretName, Some(prevVer))
              }

            val sesssionSecret = getSecret(authConfig.sessionSecretName, None)

            KeyBundle(currentSecret, previousSecret, sesssionSecret)
          } finally {
              secretsClient.close()
          }
        }
      _ <- ZIO.logInfo("Loaded secret keys")
    } yield (outcome)


object SecretKeyManager:
  def live: ZLayer[AuthConfig & Client & AwsEnvironment, Throwable, SecretKeyManager] =
    ZLayer.fromZIO {
      for {
        authConfig <- ZIO.service[AuthConfig]
        awsEnv <- ZIO.service[AwsEnvironment]
        region <- awsEnv.getRegion
      } yield LiveSecretKeyManager(authConfig, region)
    }
