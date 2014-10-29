package com.lifeway.chadash.services

import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest

import scala.concurrent.Future

trait AutoScalingGroupService {

  def createASG(name: String, cooldown: Int, desiredCapacity: Int, healthCheckGracePeriod: Int, loadBalancerName: String, launchConfigName: String, minSize: Int, maxSize: Int, vpcZoneIdentifiers: String): Future[String]

  def createLaunchConfig(launchConfig: CreateLaunchConfigurationRequest): Unit
}
