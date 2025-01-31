package co.blocke.bedrock
package services
package auth


/*
 Elasticache in LocalStack requires a Pro licence, so until we're ready to buy one we'll use a fake session cache. 
 */
object FakeSessionCache:

  private val cache = scala.collection.mutable.Map[String, String]()

  def put(key: String, value: String): Unit = cache.put(key, value)
  def get(key: String): Option[String] = cache.get(key)