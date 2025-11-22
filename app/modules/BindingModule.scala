package modules

import play.api.inject._
import outbox.OutboxPoller
import play.api.{Configuration, Environment}

/**
 * Guice module that registers application-wide bindings.
 * Ensures OutboxPoller starts automatically when the app boots.
 */
class BindingModule extends Module {
  /**
   * Binds OutboxPoller as an eager singleton so the polling
   * scheduler starts immediately on application startup.
   */
  override def bindings(env: Environment, conf: Configuration) = Seq(
    bind[OutboxPoller].toSelf.eagerly()
  )
}
