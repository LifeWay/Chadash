package com.lifeway.chadash.services.impl

import com.lifeway.chadash.services.AutoScalingGroupService

import scala.concurrent.Future

class AutoScalingGroupServiceImpl extends AutoScalingGroupService {
  override def createASG(name: String, cooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int, loadBalancerName: String, launchConfigName: String, minSize: Int, maxSize: Int, vpcZoneIdentifiers: String): Future[String] = ???

  override def createLaunchConfig(imageId: String, detailedMonitoring: Boolean, instanceType: String, keyName: String, launchConfigName: String, securityGroups: Seq[String]): Future[String] = ???
}
