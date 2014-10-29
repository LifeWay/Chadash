package com.lifeway.chadash.services.impl

import com.amazonaws.auth.InstanceProfileCredentialsProvider
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest
import com.lifeway.chadash.services.AutoScalingGroupService

import scala.concurrent.Future

class AutoScalingGroupServiceImpl extends AutoScalingGroupService {
  override def createASG(name: String, cooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int, loadBalancerName: String, launchConfigName: String, minSize: Int, maxSize: Int, vpcZoneIdentifiers: String): Future[String] = ???

  override def createLaunchConfig(launchConfig: CreateLaunchConfigurationRequest): Unit = {
    val client = new AmazonAutoScalingClient(new InstanceProfileCredentialsProvider());
    client.createLaunchConfiguration(launchConfig);
  }
}
