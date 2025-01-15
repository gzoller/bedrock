package co.blocke.bedrock
package services
package auth

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ListSecretVersionIdsRequest
import zio.*
import zio.http.*

import aws.AwsEnvironment


/**
  * Get a secret from AWS Secrets Manager
  */
trait SecretKeyManager:
  def getSecretKey: ZIO[Any, Throwable, KeyBundle]


final case class LiveSecretKeyManager(authConfig: AuthConfig, awsEnv: AwsEnvironment) extends SecretKeyManager:

  def getSecretKey: ZIO[Any, Throwable, KeyBundle] = 
    for {
      outcome <- ZIO.attemptBlocking {
          val (endpointOverride, credentialsProvider) = awsEnv.getCreds

          val secretsClientBuilder = SecretsManagerClient.builder()
            .region( awsEnv.getRegion )
            .credentialsProvider(credentialsProvider)
          endpointOverride.foreach(secretsClientBuilder.endpointOverride)
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
      } yield LiveSecretKeyManager(authConfig, awsEnv)
    }
