package utils

import com.typesafe.config.Config

object ConfigHelpers {
  implicit class RichConfig(val underlying: Config) extends AnyVal {
    def getOptLong(path: String): Option[Long] = if (underlying.hasPath(path)) Some(underlying.getLong(path)) else None

    def getOptConfig(path: String): Option[Config] = if (underlying.hasPath(path)) Some(underlying.getConfig(path)) else None
  }
}
