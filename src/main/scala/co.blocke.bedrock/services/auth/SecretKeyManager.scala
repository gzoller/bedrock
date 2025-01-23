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
      keyBundle <- ZIO.attemptBlocking {
          val (endpointOverride, credentialsProvider) = awsEnv.getCreds

          val secretsClientBuilder = SecretsManagerClient.builder()
            .region( awsEnv.getRegion )
            .credentialsProvider(credentialsProvider)
          endpointOverride.foreach(secretsClientBuilder.endpointOverride)
          val secretsClient = secretsClientBuilder.build()
          
          try {
            def getSecret(secretName: String, version: String): Key = {
              val getSecretValueRequestRaw = GetSecretValueRequest.builder()
                .secretId(secretName)
              val getSecretValueRequest = getSecretValueRequestRaw.versionId(version).build()

              // Fetch the secret value
              val getSecretValueResponse: GetSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest)

              // Return the secret string
              Key(version, getSecretValueResponse.secretString(), getSecretValueResponse.createdDate)
            }

            def getVersion(secretName: String, stage: String): Option[String] = {
              val versionsRequest = ListSecretVersionIdsRequest.builder()
                .secretId(secretName)
                .build()
              val versionsResponse = secretsClient.listSecretVersionIds(versionsRequest)
              versionsResponse.versions.asScala.find(_.versionStages.contains(stage)).map(_.versionId)
            }

            // Token Secret Versions
            val curTokenVersion = getVersion(authConfig.tokenSecretName, "AWSCURRENT")
              .getOrElse(throw new Exception("No current version found for token secret")) // There must be a current version!
            val prevTokenVersion = getVersion(authConfig.tokenSecretName, "AWSPREVIOUS")
            // There may or may not be a previous version.
            val curSessionVersion = getVersion(authConfig.sessionSecretName, "AWSCURRENT")
              .getOrElse(throw new Exception("No current version found for session secret")) // There must be a current version!

            // Retreive the secret keys
            val curTokenSecretKey = getSecret(authConfig.tokenSecretName, curTokenVersion)
            val prevTokenSecretKey = prevTokenVersion.map( getSecret(authConfig.tokenSecretName, _) )
            val sesssionSecretKey = getSecret(authConfig.sessionSecretName, curSessionVersion)

            KeyBundle(curTokenSecretKey, prevTokenSecretKey, sesssionSecretKey)
          } finally {
              secretsClient.close()
          }
        }
      _ <- ZIO.logInfo(s"Keys updated: (${keyBundle.currentTokenKey}, ${keyBundle.previousTokenKey.getOrElse("None")}, ${keyBundle.sessionKey})")
    } yield (keyBundle)


object SecretKeyManager:
  def live: ZLayer[AuthConfig & Client & AwsEnvironment, Throwable, SecretKeyManager] =
    ZLayer.fromZIO {
      for {
        _                <- ZIO.logInfo("SecretKeyManager: Loading AuthConfig")
        authConfig <- ZIO.service[AuthConfig]
        _                <- ZIO.logInfo("SecretKeyManager: Loading AwsEnvironment")
        awsEnv <- ZIO.service[AwsEnvironment]
        _                <- ZIO.logInfo("SecretKeyManager: Creating LiveSecretKeyManager")
      } yield LiveSecretKeyManager(authConfig, awsEnv)
    }
