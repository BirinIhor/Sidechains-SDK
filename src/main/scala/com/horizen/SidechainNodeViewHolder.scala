package com.horizen


import akka.actor.{ActorRef, ActorSystem, Props}
import com.horizen.block.SidechainBlock
import com.horizen.node.SidechainNodeView
import com.horizen.params.NetworkParams
import com.horizen.state.ApplicationState
import com.horizen.storage.{SidechainHistoryStorage, SidechainSecretStorage, SidechainStateStorage, SidechainWalletBoxStorage}
import com.horizen.wallet.ApplicationWallet
import scorex.core.settings.ScorexSettings
import scorex.core.utils.NetworkTimeProvider
import scorex.util.ScorexLogging

class SidechainNodeViewHolder(sidechainSettings: SidechainSettings,
                              historyStorage: SidechainHistoryStorage,
                              stateStorage: SidechainStateStorage,
                              walletSeed: Array[Byte],
                              walletBoxStorage: SidechainWalletBoxStorage,
                              secretStorage: SidechainSecretStorage,
                              params: NetworkParams,
                              timeProvider: NetworkTimeProvider,
                              applicationWallet: ApplicationWallet,
                              applicationState: ApplicationState,
                              genesisBlock: SidechainBlock)
  extends scorex.core.NodeViewHolder[SidechainTypes#SCBT, SidechainBlock]
  with ScorexLogging
  with SidechainTypes
{
  override type SI = SidechainSyncInfo
  override type HIS = SidechainHistory
  override type MS = SidechainState
  override type VL = SidechainWallet
  override type MP = SidechainMemoryPool

  override val scorexSettings: ScorexSettings = sidechainSettings.scorexSettings

  override def restoreState(): Option[(HIS, MS, VL, MP)] = for {
    history <- SidechainHistory.restoreHistory(historyStorage, params)
    state <- SidechainState.restoreState(stateStorage, applicationState)
    wallet <- SidechainWallet.restoreWallet(walletSeed, walletBoxStorage, secretStorage, applicationWallet)
    pool <- Some(SidechainMemoryPool.emptyPool)
  } yield (history, state, wallet, pool)

  override protected def genesisState: (HIS, MS, VL, MP) = {
    val result = for {
      history <- SidechainHistory.genesisHistory(historyStorage, params, genesisBlock) match {
        case h: Some[SidechainHistory] => h
        case None => throw new RuntimeException("History storage is not empty!")
      }
      state <- SidechainState.genesisState(stateStorage, applicationState, genesisBlock) match {
        case s: Some[SidechainState] => s
        case None => throw new RuntimeException("State storage is not empty!")
      }
      wallet <- SidechainWallet.genesisWallet(walletSeed, walletBoxStorage, secretStorage, applicationWallet, genesisBlock) match {
        case w: Some[SidechainWallet] => w
        case None => throw new RuntimeException("WalletBox storage is not empty!")
      }
      pool <- Some(SidechainMemoryPool.emptyPool)
    } yield (history, state, wallet, pool)

    result.get
  }

  // TO DO: Put it into NodeViewSynchronizerRef::modifierSerializers. Also put here map of custom sidechain transactions
  /*val customTransactionSerializers: Map[scorex.core.ModifierTypeId, TransactionSerializer[_ <: Transaction]] = ???

  override val modifierSerializers: Map[Byte, Serializer[_ <: NodeViewModifier]] =
    Map(new RegularTransaction().modifierTypeId() -> new SidechainTransactionsCompanion(customTransactionSerializers))
  */
  protected def getCurrentSidechainNodeViewInfo: Receive = {
    case SidechainNodeViewHolder.ReceivableMessages
      .GetDataFromCurrentSidechainNodeView(f) => sender() ! f(new SidechainNodeView(history(), minimalState(), vault(), memoryPool()))
  }

  override def receive: Receive = getCurrentSidechainNodeViewInfo orElse super.receive
}

object SidechainNodeViewHolder /*extends ScorexLogging with ScorexEncoding*/ {
  object ReceivableMessages{
    case class GetDataFromCurrentSidechainNodeView[HIS, MS, VL, MP, A](f: SidechainNodeView => A)
  }
}

object SidechainNodeViewHolderRef {
  def props(sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            stateStorage: SidechainStateStorage,
            walletSeed: Array[Byte],
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock): Props =
    Props(new SidechainNodeViewHolder(sidechainSettings, historyStorage, stateStorage, walletSeed, walletBoxStorage, secretStorage,
      params, timeProvider, applicationWallet, applicationState, genesisBlock))

  def apply(sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            stateStorage: SidechainStateStorage,
            walletSeed: Array[Byte],
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock)
           (implicit system: ActorSystem): ActorRef =
    system.actorOf(props(sidechainSettings, historyStorage, stateStorage, walletSeed, walletBoxStorage, secretStorage,
      params, timeProvider, applicationWallet, applicationState, genesisBlock))

  def apply(name: String,
            sidechainSettings: SidechainSettings,
            historyStorage: SidechainHistoryStorage,
            stateStorage: SidechainStateStorage,
            walletSeed: Array[Byte],
            walletBoxStorage: SidechainWalletBoxStorage,
            secretStorage: SidechainSecretStorage,
            params: NetworkParams,
            timeProvider: NetworkTimeProvider,
            applicationWallet: ApplicationWallet,
            applicationState: ApplicationState,
            genesisBlock: SidechainBlock)
           (implicit system: ActorSystem): ActorRef =
    system.actorOf(props(sidechainSettings, historyStorage, stateStorage, walletSeed, walletBoxStorage, secretStorage,
      params, timeProvider, applicationWallet, applicationState, genesisBlock), name)
}
