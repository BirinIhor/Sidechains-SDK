package com.horizen.validation

import java.time.Instant
import java.util.{HashMap => JHashMap}

import com.horizen.SidechainHistory
import com.horizen.block.{MainchainBlockReference, SidechainBlock}
import com.horizen.box.NoncedBox
import com.horizen.chain.SidechainBlockInfo
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.fixtures.{ForgerBoxFixture, MainchainBlockReferenceFixture, TransactionFixture, VrfGenerator}
import com.horizen.params.{NetworkParams, RegTestParams}
import com.horizen.proposition.Proposition
import com.horizen.storage.SidechainHistoryStorage
import com.horizen.transaction.SidechainTransaction
import com.horizen.utils.{ByteArrayWrapper, BytesUtils, MerkleTreeFixture, WithdrawalEpochInfo}
import org.junit.Assert.assertTrue
import org.junit.{Before, Test}
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.junit.JUnitSuite
import org.scalatest.mockito.MockitoSugar
import scorex.core.consensus.ModifierSemanticValidity
import scorex.util.{ModifierId, bytesToId}

import scala.io.Source

class WithdrawalEpochValidatorTest extends JUnitSuite with MockitoSugar with MainchainBlockReferenceFixture with TransactionFixture {

  val sidechainTransactionsCompanion = SidechainTransactionsCompanion(new JHashMap())

  val params: NetworkParams = mock[NetworkParams]
  val historyStorage: SidechainHistoryStorage = mock[SidechainHistoryStorage]
  val history: SidechainHistory = mock[SidechainHistory]


  @Before
  def setUp(): Unit = {
    Mockito.when(history.storage).thenReturn(historyStorage)
  }

  @Test
  def genesisBlockValidation(): Unit = {
    val validator = new WithdrawalEpochValidator(params)

    // Test 1: invalid genesis block - no MCBlockReferences
    val (forgerBox1, ownerKey1) = ForgerBoxFixture.generateForgerBox(32)
    var block: SidechainBlock = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(),
      Seq(),
      ownerKey1,
      forgerBox1,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(params.sidechainGenesisBlockId).thenReturn(block.id)
    assertTrue("Sidechain genesis block with no MC block references expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 2: invalid genesis block - multiple MCBlockReferences
    val (forgerBox2, ownerKey2) = ForgerBoxFixture.generateForgerBox(322)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference(), generateMainchainBlockReference()),
      Seq(),
      ownerKey2,
      forgerBox2,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(params.sidechainGenesisBlockId).thenReturn(block.id)
    assertTrue("Sidechain genesis block with multiple MC block references expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 3: invalid genesis block - 1 MCBlockRef without sc creation tx
    val (forgerBox3, ownerKey3) = ForgerBoxFixture.generateForgerBox(32)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference()),
      Seq(),
      ownerKey3,
      forgerBox3,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(params.sidechainGenesisBlockId).thenReturn(block.id)
    assertTrue("Sidechain genesis block with 1 MC block references without sc creation inside expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: NoSuchElementException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 4: valid genesis block with 1 MCBlockRef with sc creation tx with INVALID withdrawalEpochLength (different to the one specified in params)
    val scIdHex = "0000000000000000000000000000000000000000000000000000000000000001"
    val scId = new ByteArrayWrapper(BytesUtils.fromHexString(scIdHex))
    val mcBlockRefRegTestParams = RegTestParams(scId.data)
    // parse MC block with tx version -4 with 1 sc creation output and 3 forward transfer.
    // sc creation output withdrawal epoch = 100
    val mcBlockHex = Source.fromResource("mcblock_sc_support_regtest_sc_creation").getLines().next()
    val mcBlockBytes = BytesUtils.fromHexString(mcBlockHex)
    val mcBlockRef = MainchainBlockReference.create(mcBlockBytes, mcBlockRefRegTestParams).get

    val (forgerBox4, ownerKey4) = ForgerBoxFixture.generateForgerBox(324)
    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(mcBlockRef), //
      Seq(),
      ownerKey4,
      forgerBox4,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      mcBlockRefRegTestParams
    ).get

    Mockito.when(params.sidechainGenesisBlockId).thenReturn(block.id)
    Mockito.when(params.withdrawalEpochLength).thenReturn(123)
    assertTrue("Sidechain genesis block with 1 MC block references with sc creation inside with incorrect withdrawalEpochLength expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 5: the same as above but with valid withdrawalEpochLength specified in params / sc creation
    Mockito.when(params.withdrawalEpochLength).thenReturn(100)
    assertTrue("Sidechain genesis block with 1 MC block references with sc creation with correct withdrawalEpochLength inside expected to be valid.", validator.validate(block, history).isSuccess)
  }

  @Test
  def blockValidation(): Unit = {
    val validator = new WithdrawalEpochValidator(params)
    val withdrawalEpochLength = 100
    Mockito.when(params.sidechainGenesisBlockId).thenReturn(bytesToId(new Array[Byte](32)))
    Mockito.when(params.withdrawalEpochLength).thenReturn(withdrawalEpochLength)


    // Test 1: invalid block - no MC block references, parent is missed
    val (forgerBox1, ownerKey1) = ForgerBoxFixture.generateForgerBox(1)

    var block: SidechainBlock = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(),
      Seq(),
      ownerKey1,
      forgerBox1,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn(None)
    assertTrue("Sidechain block with missed parent expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }

    // Test 2: valid block - no MC block references, parent is the last block of previous epoch
    val (forgerBox2, ownerKey2) = ForgerBoxFixture.generateForgerBox(22)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(),
      Seq(),
      ownerKey2,
      forgerBox2,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength)
      ))
    })
    assertTrue("Sidechain block with no MCBlock references expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 3: valid block - no MC block references, parent is in the middle of the epoch
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength / 2)
      ))
    })
    assertTrue("Sidechain block with no MCBlock references expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 4: valid block - no MC block references, parent is at the beginning of the epoch
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, 0)
      ))
    })
    assertTrue("Sidechain block with no MCBlock references expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 5: valid block - with MC block references, that are in the middle of the epoch
    val (forgerBox5, ownerKey5) = ForgerBoxFixture.generateForgerBox(3524)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference(), generateMainchainBlockReference()), // 2 MC block refs
      Seq(),
      ownerKey5,
      forgerBox5,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 3) // lead to the middle index -> no epoch switch
      ))
    })
    assertTrue("Sidechain block with MCBlock references that are in the middle of the epoch expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 6: valid block - without SC transactions and with MC block references, that lead to the end of the epoch
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 2) // lead to the last epoch index -> no epoch switch
      ))
    })
    assertTrue("Sidechain block with MCBlock references that lead to the finish of the epoch expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 7: invalid block - without SC transactions and with MC block references, that lead to switching the epoch
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 1) // lead to the switching of the epoch
      ))
    })
    assertTrue("Sidechain block with MCBlock references that lead to epoch switching expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 8: valid block - with SC transactions and MC block references, that are in the middle of the epoch
    val (forgerBox8, ownerKey8) = ForgerBoxFixture.generateForgerBox(324)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference(), generateMainchainBlockReference()), // 2 MC block refs
      Seq(getTransaction().asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]]), // 1 SC Transaction
      ownerKey8,
      forgerBox8,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      null
    ).get

    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 3) // lead to the middle index -> no epoch switch
      ))
    })
    assertTrue("Sidechain block with SC transactions and MCBlock references that are in the middle of the epoch expected to be valid.", validator.validate(block, history).isSuccess)


    // Test 9: invalid block - with SC transactions and MC block references, that lead to the end of the epoch (no sc tx allowed)
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 2) // lead to the last epoch index -> no epoch switch
      ))
    })
    assertTrue("Sidechain block with SC transactions and MCBlock references that lead to the finish of the epoch expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 10: invalid block - with SC transactions and MC block references, that lead to switching the epoch (no sc tx and no switch allowed)
    Mockito.when(historyStorage.blockInfoOptionById(ArgumentMatchers.any[ModifierId]())).thenReturn({
      Some(SidechainBlockInfo(0, 0, null, 0, ModifierSemanticValidity.Valid, Seq(),
        WithdrawalEpochInfo(1, withdrawalEpochLength - 1) // lead to the switching of the epoch
      ))
    })
    assertTrue("Sidechain block with SC transactions and MCBlock references that lead to the epoch switching expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 11: invalid block - with 1 MCBlockRef with sc creation tx with declared
    val scIdHex = "0000000000000000000000000000000000000000000000000000000000000001"
    val scId = new ByteArrayWrapper(BytesUtils.fromHexString(scIdHex))
    val mcBlockRefRegTestParams = RegTestParams(scId.data)
    // parse MC block with tx version -4 with 1 sc creation output and 3 forward transfer.
    val mcBlockHex = Source.fromResource("mcblock_sc_support_regtest_sc_creation").getLines().next()
    val mcBlockBytes = BytesUtils.fromHexString(mcBlockHex)
    val mcBlockRef = MainchainBlockReference.create(mcBlockBytes, mcBlockRefRegTestParams).get

    val (forgerBox11, ownerKey11) = ForgerBoxFixture.generateForgerBox(32114)
    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(mcBlockRef),
      Seq(),
      ownerKey11,
      forgerBox11,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      mcBlockRefRegTestParams
    ).get

    assertTrue("Sidechain non-genesis block with 1 MC block references with sc creation inside expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 12: invalid block - with 2 MCBlockRef, the second one is with sc creation tx
    val (forgerBox12, ownerKey12) = ForgerBoxFixture.generateForgerBox(31224)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference(blockHash = Some(mcBlockRef.header.hashPrevBlock)), mcBlockRef),
      Seq(),
      ownerKey12,
      forgerBox12,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      mcBlockRefRegTestParams
    ).get

    assertTrue("Sidechain non-genesis block with 2 MC block references, the second one with sc creation inside expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }


    // Test 13: invalid block - with 3 MCBlockRef, the second one is with sc creation tx
    val (forgerBox13, ownerKey13) = ForgerBoxFixture.generateForgerBox(32413)

    block = SidechainBlock.create(
      bytesToId(new Array[Byte](32)),
      Instant.now.getEpochSecond - 10000,
      Seq(generateMainchainBlockReference(blockHash = Some(mcBlockRef.header.hashPrevBlock)), mcBlockRef, generateMainchainBlockReference(parentOpt = Some(new ByteArrayWrapper(mcBlockRef.hash)))),
      Seq(),
      ownerKey13,
      forgerBox13,
      VrfGenerator.generateProof(456L),
      MerkleTreeFixture.generateRandomMerklePath(456L),
      sidechainTransactionsCompanion,
      mcBlockRefRegTestParams
    ).get

    assertTrue("Sidechain non-genesis block with 3 MC block references, the second one with sc creation inside expected to be invalid.", validator.validate(block, history).isFailure)
    validator.validate(block, history).failed.get match {
      case _: IllegalArgumentException =>
      case e => assertTrue("Different exception type expected, got %s".format(e.getClass.getName), false)
    }
  }
}