package com.horizen.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import scorex.core.api.http.ApiResponse
import scorex.core.settings.RESTApiSettings

import scala.concurrent.ExecutionContext

case class SidechainNodeApiRoute(override val settings: RESTApiSettings, sidechainNodeViewHolderRef: ActorRef)
                                (implicit val context: ActorRefFactory, override val ec : ExecutionContext) extends SidechainApiRoute {

  override val route : Route = (pathPrefix("node"))
            {connect ~ getAllPeersInfo}

  def connect : Route = (post & path("connect"))
  {
    ApiResponse.OK
  }

  def getAllPeersInfo : Route = (post & path("getAllPeer sInfo"))
  {
    ApiResponse.OK
  }

}