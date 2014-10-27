import actors.ChadashSystem
import play.api.mvc.WithFilters
import play.api.{Application, Logger}
import play.filters.gzip.GzipFilter
import play.filters.headers.SecurityHeadersFilter

object Global extends WithFilters(new GzipFilter(), SecurityHeadersFilter()) {

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    ChadashSystem.system.shutdown()
  }
}
