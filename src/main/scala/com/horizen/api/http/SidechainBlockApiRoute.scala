package com.horizen.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import scorex.core.settings.RESTApiSettings
import scorex.util.ModifierId
import akka.pattern.ask
import com.horizen.api.http.SidechainBlockActor.ReceivableMessages.{GenerateSidechainBlocks, SubmitSidechainBlock}
import com.horizen.forge.Forger.ReceivableMessages.TryGetBlockTemplate
import com.horizen.block.SidechainBlock
import com.horizen.utils.BytesUtils

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import JacksonSupport._
import com.horizen.api.http.schema.BlockApiGroupErrorCodes
import com.horizen.api.http.schema.BlockRestScheme._

case class SidechainBlockApiRoute (override val settings: RESTApiSettings, sidechainNodeViewHolderRef: ActorRef, sidechainBlockActorRef: ActorRef, forgerRef: ActorRef)
                                  (implicit val context: ActorRefFactory, override val ec : ExecutionContext)
      extends SidechainApiRoute {

  override val route : Route = pathPrefix("block")
            {findById ~ findLastIds ~ findIdByHeight ~ getBestBlockInfo ~ getBlockTemplate ~ submitBlock  ~ generateBlocks}

  /**
    * The sidechain block by its id.
    */
  def findById : Route = (post & path("findById")) {

    entity(as[ReqFindByIdPost]) { body =>
      withNodeView{ sidechainNodeView =>
         var optionSidechainBlock = sidechainNodeView.getNodeHistory.getBlockById(body.blockId)

            if(optionSidechainBlock.isPresent) {
              var sblock = optionSidechainBlock.get()
              var sblock_serialized = sblock.serializer.toBytes(sblock)
              SidechainApiResponse(
                serialize(
                  RespFindByIdPost(BytesUtils.toHexString(sblock_serialized), sblock)
              ))
            }
            else
              SidechainApiResponse(
                serializeError(
                    BlockApiGroupErrorCodes.INVALID_ID, s"Invalid id: ${body.blockId}")
                )
      }
    }
  }

  /**
    * Returns an array of number last sidechain block ids
    */
  def findLastIds : Route = (post & path("findLastIds")) {

    entity(as[ReqLastIdsPost]) { body =>
      withNodeView{ sidechainNodeView =>
            var sidechainHistory = sidechainNodeView.getNodeHistory
            var blockIds = sidechainHistory.getLastBlockIds(sidechainHistory.getBestBlock.id, body.number)
        SidechainApiResponse(
          serialize(
            RespLastIdsPost(blockIds.asScala)))
      }
    }
  }

  /**
    * Return a sidechain block Id by its height in a blockchain
    */
  def findIdByHeight : Route = (post & path("findIdByHeight")) {

    entity(as[ReqFindIdByHeightPost]) { body =>
      withNodeView{ sidechainNodeView =>
            var sidechainHistory = sidechainNodeView.getNodeHistory
            val blockIdOptional = sidechainHistory.getBlockIdByHeight(body.height)
            if(blockIdOptional.isPresent)
              SidechainApiResponse(
                serialize(
                  RespFindIdByHeightPost(blockIdOptional.get())
                )
              )
            else
                SidechainApiResponse(
                  serializeError(
                      BlockApiGroupErrorCodes.INVALID_HEIGHT, s"Invalid height: ${body.height}")
                  )
      }
    }
  }

  /**
    * Return here best sidechain block id and height in active chain
    */
  def getBestBlockInfo : Route = (post & path("best")) {
    withNodeView{
      sidechainNodeView =>
        var sidechainHistory = sidechainNodeView.getNodeHistory
        val height = sidechainHistory.getCurrentHeight
        if(height > 0)
          SidechainApiResponse(
            serialize(
              RespBestPost(sidechainHistory.getBestBlock, height)
            )
          )
        else
          SidechainApiResponse(
            serializeError(
                BlockApiGroupErrorCodes.INVALID_HEIGHT, s"Invalid height: ${height}")
            )
    }
  }

  /**
    * Return Sidechain block candidate for being next tip, already signed by Forger
    * Note: see todos, think about returning an unsigned block
    */
  def getBlockTemplate : Route = (post & path("template")) {
    val future = forgerRef ? TryGetBlockTemplate
    val blockTemplate = Await.result(future, timeout.duration).asInstanceOf[Try[SidechainBlock]]
    // TO DO: replace with ApiResponse(blockTemplate.toJson) in future
    blockTemplate match {
      case Success(block) =>
        SidechainApiResponse(
          serialize(
            RespTemplatePost(block, BytesUtils.toHexString(block.bytes))
          )
        )
      case Failure(e) =>
        SidechainApiResponse(
          serializeError(
              BlockApiGroupErrorCodes.TEMPLATE_FAILURE, s"Failed to get block template: ${e.getMessage}")
          )
    }
  }
  
  def submitBlock : Route = (post & path("submit"))
  {

    entity(as[ReqSubmitPost]) { body =>
      withNodeView{ sidechainNodeView =>
            var blockBytes: Array[Byte] = null
            Try {
              blockBytes = BytesUtils.fromHexString(body.blockHex)
            } match {
              case Success(_) =>
                val future = sidechainBlockActorRef ? SubmitSidechainBlock(blockBytes)
                val submitResultFuture = Await.result(future, timeout.duration).asInstanceOf[Future[Try[ModifierId]]]
                Await.result(submitResultFuture, timeout.duration) match {
                  case Success(id) =>
                    SidechainApiResponse(
                      serialize(
                        RespSubmitPost(id)
                      )
                    )
                  case Failure(e) =>
                    SidechainApiResponse(
                      serializeError(
                          BlockApiGroupErrorCodes.NOT_ACCEPTED, s"Block was not accepted: ${e.getMessage}")
                      )
                }
              case Failure(e) =>
                SidechainApiResponse(
                  serializeError(
                      BlockApiGroupErrorCodes.NOT_ACCEPTED, s"Block was not accepted: ${e.getMessage}")
                  )
            }
      }
    }
  }

  /**
    * Returns ids of generated sidechain blocks.
    * It should automatically asks MC nodes for new blocks in order to be referenced inside the generated blocks, and assigns them automatically to
    * the newly generated blocks.
    */
  def generateBlocks : Route = (post & path("generate"))
  {

    entity(as[ReqGeneratePost]) { body =>
      withNodeView{ sidechainNodeView =>
            val future = sidechainBlockActorRef ? GenerateSidechainBlocks(body.number)
            val submitResultFuture = Await.result(future, timeout.duration).asInstanceOf[Future[Try[Seq[ModifierId]]]]
            Await.result(submitResultFuture, timeout.duration) match {
              case Success(ids) =>
                SidechainApiResponse(
                  serialize(
                    RespGeneratePost(ids.map(id => id.asInstanceOf[String]))
                  )
                )
              case Failure(e) =>
                SidechainApiResponse(
                  serializeError(
                      BlockApiGroupErrorCodes.NOT_CREATED, s"Block was not created: ${e.getMessage}")
                  )
            }
      }
    }
  }

}
