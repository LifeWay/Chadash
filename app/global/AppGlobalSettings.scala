package global

import actors.ChadashSystem
import play.api.mvc.{EssentialAction, Filters}
import play.api.{Application, GlobalSettings, Logger}
import play.filters.gzip.GzipFilter
import play.filters.headers.SecurityHeadersFilter

trait AppGlobalSettings extends GlobalSettings {

  private var INJECTOR: Option[com.google.inject.Injector] = None

  def createInjector(): Option[com.google.inject.Injector]

  override def onStart(app: Application) {
    INJECTOR = createInjector();
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    ChadashSystem.system.shutdown()
  }

  override def doFilter(next: EssentialAction): EssentialAction = {
    Filters(super.doFilter(next), new GzipFilter(), SecurityHeadersFilter())
  }

  override def getControllerInstance[A](controllerClass: Class[A]): A = {
    INJECTOR match {
      case Some(x) => x.getInstance(controllerClass)
      case None => throw new UnsupportedOperationException("The DI framework has not been setup yet!")
    }
  }
}
