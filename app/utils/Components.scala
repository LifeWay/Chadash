package utils

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClientBuilder}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancing, AmazonElasticLoadBalancingClientBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}

trait AmazonAutoScalingService {
  def autoScalingClient(credentials: AWSCredentialsProvider): AmazonAutoScaling = AmazonAutoScalingClientBuilder.standard().withCredentials(credentials).build()
}

trait AmazonCloudFormationService {
  def cloudFormationClient(credentials: AWSCredentialsProvider): AmazonCloudFormation = AmazonCloudFormationClientBuilder.standard().withCredentials(credentials).build()
}

trait AmazonElasticLoadBalancingService {
  def elasticLoadBalancingClient(credentials: AWSCredentialsProvider): AmazonElasticLoadBalancing = AmazonElasticLoadBalancingClientBuilder.standard().withCredentials(credentials).build()
}

trait AmazonS3Service {
  def s3Client(credentials: AWSCredentialsProvider): AmazonS3 = AmazonS3ClientBuilder.standard().withCredentials(credentials).build()
}
