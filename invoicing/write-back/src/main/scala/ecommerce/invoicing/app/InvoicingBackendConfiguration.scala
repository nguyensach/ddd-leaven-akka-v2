package ecommerce.invoicing.app

import akka.actor._
import akka.kernel.Bootable
import ecommerce.invoicing.Invoice
import pl.newicom.dddd.actor.PassivationConfig
import pl.newicom.dddd.aggregate.{AggregateRootActorFactory, DefaultConfig}

trait InvoicingBackendConfiguration {
  this: Bootable =>

  implicit object InvoiceARFactory extends AggregateRootActorFactory[Invoice] {
    override def props(pc: PassivationConfig) = Props(new Invoice(DefaultConfig(pc, replyWithEvents = false)))
  }

}
