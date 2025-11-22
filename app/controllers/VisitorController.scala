package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import services.VisitorService
import scala.concurrent.ExecutionContext

@Singleton
class VisitorController @Inject()(
                                   cc: ControllerComponents,
                                   service: VisitorService
                                 )(implicit ec: ExecutionContext)
  extends AbstractController(cc) {

  /**
   * Handles visitor check-in.
   * Passes JSON body to VisitorService and returns Created or BadRequest.
   */
  def checkin = Action(parse.json).async { req =>
    service.checkin(req.body).map(Created(_)).recover { ex =>
      BadRequest(Json.obj("error" -> ex.getMessage))
    }
  }

  /**
   * Handles visitor checkout by visitId.
   * Returns OK if checkout succeeded, 404 if visit not found.
   */
  def checkout(id: Long) = Action.async {
    service.checkout(id).map {
      case Some(ok) => Ok(ok)
      case None => NotFound(Json.obj("error" -> "Visit not found"))
    }.recover { ex =>
      InternalServerError(Json.obj("error" -> ex.getMessage))
    }
  }

  /**
   * Fetches full visitor profile including all visits.
   * Returns JSON or 404 if visitor doesn't exist.
   */
  def getVisitor(id: Long) = Action.async {
    service.getVisitorDetails(id).map {
      case Some(json) => Ok(json)
      case None => NotFound(Json.obj("error" -> "Visitor not found"))
    }
  }

  /**
   * Returns all currently active visits (CHECKED_IN only).
   */
  def activeVisits = Action.async {
    service.getActiveVisits.map(Ok(_))
  }
}
