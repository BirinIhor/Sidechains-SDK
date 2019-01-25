package com.horizen.companion

import com.horizen.box.{Box, BoxSerializer, RegularBox, RegularBoxSerializer}
import com.horizen.proposition.Proposition
import scorex.core.ModifierTypeId
import scorex.core.serialization.Serializer

import scala.util.Try

case class SidechainBoxesCompanion(customBoxSerializers: Map[scorex.core.ModifierTypeId.Raw, BoxSerializer[_ <: Box[_ <: Proposition]]])
    extends Serializer[Box[_ <: Proposition]] {

  val coreBoxSerializers: Map[scorex.core.ModifierTypeId.Raw, _ <: BoxSerializer[_]] =
    Map(new RegularBox(null, 0, 0).boxTypeId() -> RegularBoxSerializer.getSerializer())

  val customBoxId = ModifierTypeId @@ Byte.MaxValue // TO DO: think about proper value

  // TO DO: do like in SidechainTransactionsCompanion
  override def toBytes(obj: Box[_ <: Proposition]): Array[Byte] = ???

  // TO DO: do like in SidechainTransactionsCompanion
  override def parseBytes(bytes: Array[Byte]): Try[Box[_ <: Proposition]] = ???
}

