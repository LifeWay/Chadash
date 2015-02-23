package utils

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClient}
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancing, AmazonElasticLoadBalancingClient}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}

trait AmazonAutoScalingService {
  def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = new AmazonAutoScalingClient(credentials)
}

trait AmazonCloudFormationService {
  def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = new AmazonCloudFormationClient(credentials)
}

trait AmazonElasticLoadBalancingService {
  def elasticLoadBalancingClient(credentials: AWSCredentials): AmazonElasticLoadBalancing = new AmazonElasticLoadBalancingClient(credentials)
}

trait AmazonS3Service {
  def s3Client(credentials: AWSCredentials): AmazonS3 = new AmazonS3Client(credentials)
}
