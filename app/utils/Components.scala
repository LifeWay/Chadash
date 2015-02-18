package utils

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}

trait AmazonAutoScalingService {
  def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = new AmazonAutoScalingClient(credentials)
}