package co.blocke.bedrock

import zio.Scope  
import zio.http.*

  type MyClient = ZClient[Any, Scope, Body, Throwable, Response]
