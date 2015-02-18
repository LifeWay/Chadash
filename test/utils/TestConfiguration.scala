package utils

import com.typesafe.config.ConfigFactory

object TestConfiguration {
  val testConfig = ConfigFactory.load("akka-test")
}
