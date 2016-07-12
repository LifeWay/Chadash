package utils

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClient}
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancing, AmazonElasticLoadBalancingClient}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}

trait AmazonAutoScalingService {
  def autoScalingClient(credentials: AWSCredentialsProvider): AmazonAutoScaling = new AmazonAutoScalingClient(credentials)
}

trait AmazonCloudFormationService {
  def cloudFormationClient(credentials: AWSCredentialsProvider): AmazonCloudFormation = new AmazonCloudFormationClient(credentials)
}

trait AmazonElasticLoadBalancingService {
  def elasticLoadBalancingClient(credentials: AWSCredentialsProvider): AmazonElasticLoadBalancing = new AmazonElasticLoadBalancingClient(credentials)
}

trait AmazonS3Service {
  def s3Client(credentials: AWSCredentialsProvider): AmazonS3 = new AmazonS3Client(credentials)
}
