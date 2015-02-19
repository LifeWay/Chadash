package utils

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}
import com.amazonaws.services.cloudformation.{AmazonCloudFormationClient, AmazonCloudFormation}

trait AmazonAutoScalingService {
  def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = new AmazonAutoScalingClient(credentials)
}

trait AmazonCloudFormationService {
  def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = new AmazonCloudFormationClient(credentials)
}
