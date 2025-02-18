package co.blocke.bedrock
package services
package aws

import zio.*
import java.util.concurrent.TimeUnit
import io.lettuce.core._
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands

// A helper case class to store both the value and its optional expiration time.
private case class CacheEntry(value: String, expireAt: Option[Long])

trait AwsRedis:
  def set(
    key: String,
    value: String,
    expireTime: Option[Duration] = None
  ): ZIO[Any, Throwable, Unit]
  def get(key: String): ZIO[Any, Throwable, Option[String]]
  def getDel(key: String): ZIO[Any, Throwable, Option[String]]

final case class FakeAwsRedis() extends AwsRedis:
  // The cache now holds CacheEntry values.
  private val cache = scala.collection.mutable.Map[String, CacheEntry]()

  // Sets a key with an optional expiration time.
  def set(
    key: String,
    value: String,
    expireTime: Option[Duration] = None
  ): ZIO[Any, Nothing, Unit] =
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // Calculate the expiration instant, if any.
      expireAt = expireTime.map(d => now + d.toMillis)
      _ = cache(key) = CacheEntry(value, expireAt)
    } yield ()

  // Helper method to check if the entry is expired.
  private def isExpired(entry: CacheEntry, now: Long): Boolean =
    entry.expireAt.exists(exp => now >= exp)

  // Returns the value if it exists and is not expired.
  def get(key: String): ZIO[Any, Nothing, Option[String]] =
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      entryOpt = cache.get(key)
      result <- entryOpt match {
        case Some(entry) if isExpired(entry, now) =>
          ZIO.succeed {
            cache.remove(key)
            None
          }
        case entry => ZIO.succeed(entry.map(_.value))
      }
    } yield result

  // Returns and deletes the value if it exists and is not expired.
  def getDel(key: String): ZIO[Any, Nothing, Option[String]] =
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      entryOpt = cache.get(key)
      result <- entryOpt match {
        case Some(entry) if isExpired(entry, now) =>
          ZIO.succeed {
            cache.remove(key)
            None
          }
        case Some(entry) =>
          ZIO.succeed {
            cache.remove(key)
            Some(entry.value)
          }
        case None => ZIO.succeed(None)
      }
    } yield result


final case class LiveAwsRedis(
                               client: RedisClient,
                               connection: StatefulRedisConnection[String, String],
                               asyncCommands: RedisAsyncCommands[String, String]
                             ) extends AwsRedis {

  override def set(
                    key: String,
                    value: String,
                    expireTime: Option[Duration] = None
                  ): ZIO[Any, Throwable, Unit] =
    ZIO.fromCompletableFuture(
      expireTime match {
        case Some(duration) => asyncCommands.setex(key, duration.toSeconds, value).toCompletableFuture
        case None           => asyncCommands.set(key, value).toCompletableFuture
      }
    ).unit.tapError(err => ZIO.logError(s"Redis set failed: ${err.getMessage}"))

  override def get(key: String): ZIO[Any, Throwable, Option[String]] =
    ZIO.fromCompletableFuture(asyncCommands.get(key).toCompletableFuture)
      .map(Option(_))
      .tapError(err => ZIO.logError(s"Redis get failed: ${err.getMessage}"))

  override def getDel(key: String): ZIO[Any, Throwable, Option[String]] =
    ZIO.fromCompletableFuture(asyncCommands.get(key).toCompletableFuture)
      .map(Option(_))
      .tapError(err => ZIO.logError(s"Redis getDel failed: ${err.getMessage}"))
}


object AwsRedis {
  def live: ZLayer[AWSConfig, Throwable, AwsRedis] =
    ZLayer.scoped {
      for {
        awsConfig <- ZIO.service[AWSConfig]
        _         <- ZIO.logInfo(s"Initializing Redis client... (is live AWS = ${awsConfig.liveAws})")

        redisInstance <- if (!awsConfig.liveAws) {
          ZIO.succeed(FakeAwsRedis()) // Use fake implementation for localstack
        } else {
          for {
            redisUri   <- ZIO.fromOption(awsConfig.redisUri)
              .orElseFail(new RuntimeException("Redis URI not set"))
            client     <- ZIO.attempt(RedisClient.create(redisUri))
            connection <- ZIO.attempt(client.connect())
            asyncCmds  <- ZIO.attempt(connection.async())

            _ <- ZIO.addFinalizer(
              ZIO.succeed {
                connection.close()
                client.shutdown()
              }
            )

            _ <- ZIO.logInfo("Redis client successfully initialized")
          } yield LiveAwsRedis(client, connection, asyncCmds)
        }
      } yield redisInstance
    }
}

