package utils

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}

trait AmazonAutoScalingComponent {
  def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling
}

trait AmazonAutoScalingClientImpl extends AmazonAutoScalingComponent {
  def autoScalingClient(credentials: AWSCredentials) = new AmazonAutoScalingClient(credentials)
}
