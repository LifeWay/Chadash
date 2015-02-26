package controllers

import actors.DeploymentSupervisor.WorkflowInProgress
import actors.workflow.WorkflowManager.WorkflowStarted
import actors.{DeploymentActor, DeploymentSupervisor}
import akka.actor._
import akka.testkit.TestKit
import com.google.inject.{AbstractModule, Module}
import global.AppGlobalSettings
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatestplus.play.{OneServerPerSuite, WsScalaTestClient}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{WS, WSAuthScheme}
import play.api.test.FakeApplication
import utils.TestConfiguration

import scala.concurrent.Await
import scala.concurrent.duration._

class ApplicationSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                             with Matchers with WsScalaTestClient with OneServerPerSuite {

  implicit override lazy val app: FakeApplication = FakeApplication(withGlobal = Some(TestGlobal))

  "A Deployment API" should "start a workflow" in {
    val testURL = s"http://localhost:$port/api/deploy/workflow-started"
    val f = WS.url(testURL).withAuth("test", "password", WSAuthScheme.BASIC).post(Json.obj("version" -> "1.01", "ami_id" -> "some-id"))
    val response = Await.result(f, 5.seconds)
    response.status shouldBe 200
    response.body shouldBe "WorkflowStarted"
  }

  it should "return a forbidden if a workflow is in progress when attempting to deploy a new stack" in {
    val testURL = s"http://localhost:$port/api/deploy/in-progress"
    val f = WS.url(testURL).withAuth("test", "password", WSAuthScheme.BASIC).post(Json.obj("version" -> "1.01", "ami_id" -> "some-id"))
    val response = Await.result(f, 5.seconds)
    response.status shouldBe 403
    response.body shouldBe "WorkflowInProgress"
  }

  it should "delete a stack" in {
    val testURL = s"http://localhost:$port/api/delete/delete-success"
    val f = WS.url(testURL).withAuth("test", "password", WSAuthScheme.BASIC).post(Json.obj("version" -> "1.01"))
    val response = Await.result(f, 5.seconds)
    response.status shouldBe 200
    response.body shouldBe "WorkflowStarted"
  }

  it should "return a forbidden if a workflow is in progress when attempting to delete a stack" in {
    val testURL = s"http://localhost:$port/api/delete/delete-in-progress"
    val f = WS.url(testURL).withAuth("test", "password", WSAuthScheme.BASIC).post(Json.obj("version" -> "1.01"))
    val response = Await.result(f, 5.seconds)
    response.status shouldBe 403
    response.body shouldBe "WorkflowInProgress"
  }

  it should "check for authentication" in {
    val testURL = s"http://localhost:$port/api/delete/tryit/stackname"
    val f = WS.url(testURL).post(Json.obj("version" -> "1.01"))
    val response = Await.result(f, 5.seconds)
    response.json shouldBe Json.obj("status" -> 401, "api-message" -> "Not Authorized").asInstanceOf[JsValue]

  }

  object TestGlobal extends AppGlobalSettings {
    override def injectorModules(): Seq[Module] = {
      Seq(new AbstractModule {
        override def configure() = bind(classOf[DeploymentActor]).toInstance(TestDeploymentActor)
      })
    }
  }

  object TestDeploymentActor extends DeploymentActor {
    val actor = system.actorOf(Props(new Actor {
      def receive = {
        case DeploymentSupervisor.DeployRequest("in-progress", _, _, _) => sender ! WorkflowInProgress
        case DeploymentSupervisor.DeployRequest("workflow-started", _, _, _) => sender ! WorkflowStarted
        case DeploymentSupervisor.DeleteStack("delete-success", _) => sender ! WorkflowStarted
        case DeploymentSupervisor.DeleteStack("delete-in-progress", _) => sender ! WorkflowInProgress
      }
    }))
  }
}
