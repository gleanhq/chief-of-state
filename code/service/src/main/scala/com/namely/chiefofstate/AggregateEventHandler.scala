package com.namely.chiefofstate

import com.google.protobuf.any.Any
import com.namely.chiefofstate.config.HandlerSetting
import com.namely.chiefofstate.grpc.client.WriteSideHandlerServiceClient
import com.namely.protobuf.chiefofstate.v1.writeside.{HandleEventRequest, HandleEventResponse}
import io.superflat.lagompb.EventHandler
import io.superflat.lagompb.protobuf.v1.core.MetaData
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

/**
 * ChiefOfStateEventHandler
 *
 * @param writeSideHandlerServiceClient the gRpcClient used to connect to the actual event handler
 * @param handlerSetting                the event handler setting
 */
class AggregateEventHandler(
  writeSideHandlerServiceClient: WriteSideHandlerServiceClient,
  handlerSetting: HandlerSetting
) extends EventHandler {

  final val log: Logger = LoggerFactory.getLogger(getClass)

  override def handle(event: Any, priorState: Any, eventMeta: MetaData): Any = {
    Try(
      writeSideHandlerServiceClient.handleEvent(
        HandleEventRequest()
          .withEvent(event)
          .withPriorState(priorState)
          .withEventMeta(Util.toCosMetaData(eventMeta))
      )
    ) match {

      case Failure(e) =>
        throw e

      case Success(eventualEventResponse: Future[HandleEventResponse]) =>
        Try {
          Await.result(eventualEventResponse, Duration.Inf)
        } match {
          case Failure(exception) =>
            log.error(s"event handler failed", exception)
            throw exception

          case Success(handleEventResponse: HandleEventResponse) =>
            val stateFQN: String = Util.getProtoFullyQualifiedName(handleEventResponse.getResultingState)
            log.debug(s"[ChiefOfState]: received event handler state $stateFQN")

            // if enabled, validate the state type url returned by event handler
            if (handlerSetting.enableProtoValidations && !handlerSetting.stateFQNs.contains(stateFQN)) {
              log.error(
                s"[ChiefOfState]: command handler state to persist $stateFQN is not configured. Failing request"
              )
              throw new Exception(s"received unknown state $stateFQN")
            }

            // pass through state returned by event handler
            handleEventResponse.getResultingState
        }
    }
  }
}
