package com.lifeway.chadash.services

import scala.concurrent.Future

trait AutoScalingGroupService {

  def createASG(name: String, cooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int, loadBalancerName: String, launchConfigName: String, minSize: Int, maxSize: Int, vpcZoneIdentifiers: String): Future[String]

  def createLaunchConfig(imageId: String, detailedMonitoring: Boolean, instanceType: String, keyName: String, launchConfigName: String, securityGroups: Seq[String]): Future[String]

}
