package co.blocke.bedrock
package util

import com.fasterxml.uuid.Generators
import java.util.Base64
import java.nio.ByteBuffer


object UUIDutil:

  /**
   * Generate a base64-encoded random UUIDv7 string
   *
   * @return Base64-encoded UUIDv7 string
   */
  def base64Id: String = 
    // UUIDv7 generator (time-based--these can be sorted and will keep their order!)
    val uuidv7 = Generators.timeBasedEpochGenerator().generate()
    val byteBuffer = ByteBuffer.allocate(16) // 16 bytes for 128-bit UUID
    byteBuffer.putLong(uuidv7.getMostSignificantBits)
    byteBuffer.putLong(uuidv7.getLeastSignificantBits)
    Base64.getEncoder.encodeToString(byteBuffer.array())

  /**
   * Convert a Base64-encoded UUIDv7 string to a java.util.UUID
   *
   * @return UUID
   */
  def base64IdToUUID(base64Id: String): java.util.UUID = 
    val bytes = Base64.getDecoder.decode(base64Id)
    val byteBuffer = ByteBuffer.wrap(bytes)
    val mostSigBits = byteBuffer.getLong
    val leastSigBits = byteBuffer.getLong
    new java.util.UUID(mostSigBits, leastSigBits)