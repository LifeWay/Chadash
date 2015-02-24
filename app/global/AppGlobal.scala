package global

import actors.DeploymentActor
import com.google.inject.{AbstractModule, Module}

object AppGlobal extends AppGlobalSettings {
  override def injectorModules(): Seq[Module] = {
    Seq(new AbstractModule {
      override def configure() = bind(classOf[DeploymentActor]).toInstance(DeploymentActor)
    })
  }
}
