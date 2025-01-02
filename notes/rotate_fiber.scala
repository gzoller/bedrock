// How to mock up a fiber in Authentication to rotate (relode) secret keys from SecretsManager

final case class LiveAuthentication(
    secretKeyManager: SecretKeyManager,
    @volatile private var currentSecretKey: Key,
    @volatile private var previousSecretKey: Option[Key]
) extends Authentication {

  def login(username: String, password: String): ZIO[Any, Throwable, String] =
    ZIO.succeed(jwtEncode(username, currentSecretKey.value))

  def bearerAuthWithContext(secret: String): HandlerAspect[Any, String] = {
    // Replace with your existing implementation
    HandlerAspect.identity[Any, String]
  }

  private def jwtEncode(username: String, key: String): String =
    Jwt.encode(JwtClaim(subject = Some(username)).issuedNow.expiresIn(300), key, JwtAlgorithm.HS512)

  def listenForEvents: ZIO[Any, Throwable, Unit] =
    ZStream
      .fromEffect(ZIO.logInfo("Listening for events...")) // Replace with actual event stream
      .flatMap { _ =>
        // Simulated stream of events triggering key refresh
        ZStream.tick(10.seconds).as("REFRESH_KEYS")
      }
      .mapZIO { event =>
        ZIO.logInfo(s"Event received: $event") *>
          secretKeyManager.getSecretKey.flatMap {
            case (newCurrent, newPrevious) =>
              ZIO.attempt {
                currentSecretKey = newCurrent
                previousSecretKey = newPrevious
              }
          }
      }
      .runDrain
}
