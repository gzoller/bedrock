package co.blocke.bedrock
package services
package aws

import zio.*
import java.time.Duration
import java.util.concurrent.TimeUnit

// A helper case class to store both the value and its optional expiration time.
private case class CacheEntry(value: String, expireAt: Option[Long])

trait AwsRedis:
  def set(
    key: String,
    value: String,
    expireTime: Option[Duration] = None
  ): ZIO[Any, Nothing, Unit]
  def get(key: String): ZIO[Any, Nothing, Option[String]]
  def getDel(key: String): ZIO[Any, Nothing, Option[String]]

final case class LiveAwsRedis() extends AwsRedis:
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

object AwsRedis:
  def live: ZLayer[Any, Nothing, AwsRedis] = ZLayer.succeed(LiveAwsRedis())
