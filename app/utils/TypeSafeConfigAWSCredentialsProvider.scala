package utils

import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider}
import com.typesafe.config.Config

class TypeSafeConfigAWSCredentialsProvider(config: Config) extends AWSCredentialsProvider {
  override def getCredentials: AWSCredentials = {
    new AWSCredentials {
      override def getAWSAccessKeyId: String = config.getString("aws.accessKey")

      override def getAWSSecretKey: String = config.getString("aws.secretKey")
    }
  }

  override def refresh(): Unit = () //nothing to do here.
}
