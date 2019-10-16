package com.horizen.fixtures

import java.lang.{Byte => JByte}
import java.util.{HashMap => JHashMap}

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.stream.ActorMaterializer
import com.horizen.api.http.{SidechainApiErrorHandler, SidechainTransactionActorRef, SidechainTransactionApiRoute}
import com.horizen.box.BoxSerializer
import com.horizen.companion.{SidechainBoxesCompanion, SidechainSecretsCompanion, SidechainTransactionsCompanion}
import com.horizen.params.{MainNetParams, NetworkParams, RegTestParams}
import com.horizen.customtypes.{DefaultApplicationState, DefaultApplicationWallet}
import com.horizen.secret.SecretSerializer
import com.horizen.state.ApplicationState
import com.horizen.storage.{IODBStoreAdapter, SidechainHistoryStorage, SidechainSecretStorage, SidechainStateStorage, SidechainWalletBoxStorage, SidechainWalletTransactionStorage, Storage}
import com.horizen.transaction.TransactionSerializer
import com.horizen.utils.BytesUtils
import com.horizen.validation.{MainchainPoWValidator, SidechainBlockValidator}
import com.horizen.validation.SidechainBlockValidator
import com.horizen.wallet.ApplicationWallet
import com.horizen.{SidechainNodeViewHolderRef, SidechainSettings, SidechainSettingsReader, SidechainTypes}
import scorex.core.api.http.ApiRejectionHandler
import scorex.core.utils.NetworkTimeProvider

import scala.concurrent.ExecutionContext

trait SidechainNodeViewHolderFixture
  extends IODBStoreFixture
{

  val sidechainSettings = SidechainSettingsReader.read("src/test/resources/sc_node_holder_fixter_settings.conf", None)

  implicit def exceptionHandler: ExceptionHandler = SidechainApiErrorHandler.exceptionHandler
  implicit def rejectionHandler: RejectionHandler = ApiRejectionHandler.rejectionHandler

  implicit val actorSystem: ActorSystem = ActorSystem(sidechainSettings.scorexSettings.network.agentName)
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("scorex.executionContext")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val timeProvider = new NetworkTimeProvider(sidechainSettings.scorexSettings.ntp)

  val sidechainBoxesCompanion: SidechainBoxesCompanion =  SidechainBoxesCompanion(new JHashMap[JByte, BoxSerializer[SidechainTypes#SCB]]())
  val sidechainSecretsCompanion: SidechainSecretsCompanion = SidechainSecretsCompanion(new JHashMap[JByte, SecretSerializer[SidechainTypes#SCS]]())
  val sidechainTransactionsCompanion: SidechainTransactionsCompanion = SidechainTransactionsCompanion(new JHashMap[JByte, TransactionSerializer[SidechainTypes#SCBT]]())
  val defaultApplicationWallet: ApplicationWallet = new DefaultApplicationWallet()
  val defaultApplicationState: ApplicationState = new DefaultApplicationState()


  val params: NetworkParams = sidechainSettings.genesisData.mcNetwork match {
    case "regtest" => RegTestParams(
      BytesUtils.fromHexString(sidechainSettings.genesisData.scId),
      sidechainSettings.genesisBlock.get.id,
      sidechainSettings.genesisBlock.get.mainchainBlocks.head.hash,
      sidechainSettings.genesisPowData,
      sidechainSettings.genesisData.mcBlockHeight
    )
    case "mainnet" | "testnet" => MainNetParams(
      BytesUtils.fromHexString(sidechainSettings.genesisData.scId),
      sidechainSettings.genesisBlock.get.id,
      sidechainSettings.genesisBlock.get.mainchainBlocks.head.hash,
      sidechainSettings.genesisPowData,
      sidechainSettings.genesisData.mcBlockHeight
    )
    case _ => throw new IllegalArgumentException("Configuration file scorex.genesis.mcNetwork parameter contains inconsistent value.")
  }

  val sidechainSecretStorage = new SidechainSecretStorage(
    getStorage(),
    sidechainSecretsCompanion)
  val sidechainWalletBoxStorage = new SidechainWalletBoxStorage(
    getStorage(),
    sidechainBoxesCompanion)
  val sidechainStateStorage = new SidechainStateStorage(
    getStorage(),
    sidechainBoxesCompanion)
  val sidechainHistoryStorage = new SidechainHistoryStorage(
    getStorage(),
    sidechainTransactionsCompanion, params)
  val sidechainWalletTransactionStorage = new SidechainWalletTransactionStorage(
    getStorage(),
    sidechainTransactionsCompanion)

  sidechainSecretStorage.add(sidechainSettings.targetSecretKey1)
  sidechainSecretStorage.add(sidechainSettings.targetSecretKey2)

  val nodeViewHolderRef: ActorRef = SidechainNodeViewHolderRef(sidechainSettings, sidechainHistoryStorage,
    sidechainStateStorage,
    "test seed %s".format(sidechainSettings.scorexSettings.network.nodeName).getBytes(), // To Do: add Wallet group to config file => wallet.seed
    sidechainWalletBoxStorage, sidechainSecretStorage, sidechainWalletTransactionStorage, params, timeProvider,
    defaultApplicationWallet, defaultApplicationState, sidechainSettings.genesisBlock.get,
    Seq(new SidechainBlockValidator(params), new MainchainPoWValidator(sidechainHistoryStorage, params)))

  val sidechainTransactionActorRef : ActorRef = SidechainTransactionActorRef(nodeViewHolderRef)

  def getSidechainNodeViewHolderRef : ActorRef = {
    nodeViewHolderRef
  }

  def getSidechainTransactionApiRoute : SidechainTransactionApiRoute = {
    SidechainTransactionApiRoute(sidechainSettings.scorexSettings.restApi, nodeViewHolderRef, sidechainTransactionActorRef)
  }

}