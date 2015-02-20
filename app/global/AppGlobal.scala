package global

import actors.DeploymentActor
import com.google.inject.{AbstractModule, Guice, Injector}

object AppGlobal extends AppGlobalSettings {
  override def createInjector(): Option[Injector] = {
    return Some(Guice.createInjector(
      new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[DeploymentActor]).toInstance(DeploymentActor)
        }
      }
    ))
  }
}
