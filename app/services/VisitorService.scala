package services

import play.api.libs.json.Json.toJsFieldJsValueWrapper

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json._
import models._
import repos._
import utils.{HashUtils, ValidationUtils}
import play.api.Configuration

import java.time.LocalDate
import slick.jdbc.JdbcProfile
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

@Singleton
class VisitorService @Inject()(
                                visitorRepo: VisitorRepo,
                                visitRepo: VisitRepo,
                                idProofRepo: IdProofRepo,
                                outboxRepo: OutboxRepo,
                                val dbConfigProvider: DatabaseConfigProvider,
                                config: Configuration,
                                employeeRepo: EmployeeRepo
                              )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  private val checkinTopic  = config.get[String]("kafka.topic.checkin")
  private val checkoutTopic = config.get[String]("kafka.topic.checkout")


  /**
   * Performs visitor check-in.
   * Validates host + visitor, writes DB transaction, adds outbox_event table.
   */
  def checkin(json: JsValue): Future[JsValue] = {
    val visitorJs = (json \ "visitor").as[JsObject]

    val visitor = Visitor(
      None,
      (visitorJs \ "firstName").as[String],
      (visitorJs \ "lastName").as[String],
      (visitorJs \ "email").asOpt[String],
      (visitorJs \ "phone").asOpt[String]
    )

    val aadhaar = (json \ "idProof" \ "aadhaarNumber").as[String]
    val hostId  = (json \ "hostEmployeeId").as[Long]
    val purpose = (json \ "purpose").as[String]

    ValidationUtils.requireNonEmpty("firstName", visitor.firstName)
    ValidationUtils.requireNonEmpty("lastName", visitor.lastName)
    ValidationUtils.requireNonEmpty("purpose", purpose)
    ValidationUtils.requireAadhaar(aadhaar)

    val idProofHash = HashUtils.sha256(aadhaar)
    val checkinDate = LocalDate.now()
    val correlation = java.util.UUID.randomUUID().toString

    
    // 1) VALIDATE HOST - THIS IS A FUTURE
    employeeRepo.findById(hostId).flatMap {
      case Some(host) if host.role == "HOST" && host.status == "ACTIVE" =>
        // 2) BUILD THE TRANSACTION (DBIO INSIDE FOR)
        import profile.api._
        val tx: DBIO[JsValue] = for {
          visitorId <- visitorRepo.upsert(visitor)
          visitId <- visitRepo.insert(
            Visit(
              None,
              visitorId,
              hostId,
              purpose,
              Some(checkinDate),
              None,
              "CHECKED_IN",
              None,
              Some(host.name),
              Some(host.email)
            )
          )
          _ <- idProofRepo.insert(IdProof(None, visitId, idProofHash))

          payload = Json.obj(
            "eventType"      -> "visitor.checkin",
            "visitId"        -> visitId,
            "visitorId"      -> visitorId,
            "hostEmployeeId" -> hostId,
            "hostName"       -> host.name,
            "hostEmail"      -> host.email,
            "purpose"        -> purpose,
            "visitor"        -> visitorJs,
            "idProofHash"    -> idProofHash,
            "checkinDate"    -> checkinDate.toString,
            "correlationId"  -> correlation
          )

          // Inserting into outbox
          _ <- outboxRepo.insert(
            OutboxEvent(None, visitId, "visitor.checkin", Json.stringify(payload), false)
          )

        } yield Json.obj(
          "visitId"       -> visitId,
          "status"        -> "CHECKED_IN",
          "checkinDate"   -> checkinDate.toString,
          "correlationId" -> correlation
        )

        
        // 3) RUN TX (returns Future[JsValue])
        
        db.run(tx.transactionally)

      case Some(_) =>
        Future.failed(new IllegalArgumentException("Employee is not authorized as HOST"))

      case None =>
        Future.failed(new IllegalArgumentException("Invalid host employee ID"))
    }
  }



  /**
   * Performs visitor checkout.
   * Updates visit status + adds checkout event to outbox_event table.
   */
  def checkout(visitId: Long): Future[Option[JsValue]] = {

    val checkoutDate = LocalDate.now()
    val correlation  = java.util.UUID.randomUUID().toString

    val payloadJson = Json.obj(
      "eventType"     -> "visitor.checkout",
      "visitId"       -> visitId,
      "checkoutDate"  -> checkoutDate.toString,
      "correlationId" -> correlation
    )

    val tx = for {
      updated <- visitRepo.checkout(visitId, checkoutDate)
      _       <- if (updated > 0)
        outboxRepo.insert(
          OutboxEvent(None, visitId, "visitor.checkout", Json.stringify(payloadJson), published = false)
        )
      else DBIO.successful(0)
    } yield updated

    db.run(tx.transactionally).map {
      case 0 => None
      case _ =>
        Some(
          Json.obj(
            "visitId"      -> visitId,
            "status"       -> "CHECKED_OUT",
            "checkoutDate" -> checkoutDate.toString
          )
        )
    }
  }
  
  // GET /visitors/:id
  /**
   * Fetches full visitor profile.
   * Includes visitor + all visits associated with that visitor.
   */
  def getVisitorDetails(visitorId: Long): Future[Option[JsValue]] = {
    for {
      visitorOpt <- visitorRepo.findById(visitorId)
      visits     <- visitRepo.findByVisitorId(visitorId)
    } yield {
      visitorOpt.map { v =>

        val visitsJson: Seq[JsObject] = visits.map { vs =>
          JsObject(Seq(
            "visitId"        -> JsNumber(BigDecimal(vs.id.getOrElse(0L))),
            "hostEmployeeId" -> JsNumber(BigDecimal(vs.hostEmployeeId)),
            "purpose"        -> JsString(vs.purpose),
            "checkinTime"    -> JsString(vs.checkinTime.toString),
            "checkoutTime"   -> vs.checkoutTime.map(dt => JsString(dt.toString)).getOrElse(JsNull),
            "status"         -> JsString(vs.status)
          ))
        }

        JsObject(Seq(
          "visitorId"  -> JsNumber(BigDecimal(visitorId)),
          "firstName"  -> JsString(v.firstName),
          "lastName"   -> JsString(v.lastName),
          "email"      -> v.email.map(JsString).getOrElse(JsNull),
          "phone"      -> v.phone.map(JsString).getOrElse(JsNull),
          "visits"     -> JsArray(visitsJson)
        ))
      }
    }
  }


  /**
   * Returns all active visits (CHECKED_IN only).
   */
  def getActiveVisits: Future[JsValue] = {
    visitRepo.findActiveVisits().map { visits =>

      val visitsJson: Seq[JsObject] = visits.map { v =>
        JsObject(Seq(
          "visitId"        -> JsNumber(BigDecimal(v.id.getOrElse(0L))),
          "visitorId"      -> JsNumber(BigDecimal(v.visitorId)),
          "hostEmployeeId" -> JsNumber(BigDecimal(v.hostEmployeeId)),
          "purpose"        -> JsString(v.purpose),
          "checkinTime"    -> JsString(v.checkinTime.toString)
        ))
      }

      JsArray(visitsJson)
    }
  }


}
